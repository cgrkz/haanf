package app.haanf.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Duration

/** Calls Claude through the official SDK. */
class ClaudeClient(
    apiKey: String,
    private val model: String,
    baseUrl: String? = null,
) : LlmClient {

    // API keys belong on x-api-key. Setting it stops the SDK adding its own Bearer header.
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .putHeader("X-Api-Key", apiKey)
        .timeout(Duration.ofSeconds(90))
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    override suspend fun send(request: LlmRequest): LlmReply = runInterruptible(Dispatchers.IO) {
        try {
            toReply(client.beta().messages().create(params(request)))
        } catch (e: UnauthorizedException) {
            throw LlmException(LlmFailure.BAD_KEY, cause = e)
        } catch (e: PermissionDeniedException) {
            throw LlmException(LlmFailure.BAD_KEY, cause = e)
        } catch (e: RateLimitException) {
            throw LlmException(LlmFailure.RATE_LIMITED, cause = e)
        } catch (e: AnthropicServiceException) {
            val failure = if (e.statusCode() >= 500) LlmFailure.SERVER else LlmFailure.BAD_RESPONSE
            throw LlmException(failure, cause = e)
        } catch (e: AnthropicIoException) {
            throw LlmException(LlmFailure.NETWORK, cause = e)
        }
    }

    private fun params(request: LlmRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .outputConfig(BetaOutputConfig.builder().effort(effort(request.effort)).build())
        // A refused request reroutes to another model rather than simply stopping.
        if (model in MODELS_WITH_FALLBACK) {
            builder.addBeta(FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        // Keeps the conversation so far for an hour, so a workout's later check-ins reread it cheaply.
        if (request.cacheable) {
            builder.cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build())
        }
        request.tools.forEach { builder.addTool(tool(it)) }
        // Tool results and the user text after them share one user turn, so turns always alternate.
        var pending = mutableListOf<BetaContentBlockParam>()
        fun flush() {
            if (pending.isNotEmpty()) builder.addUserMessageOfContentBlockParams(pending)
            pending = mutableListOf()
        }
        request.items.forEach { item ->
            when (item) {
                is LlmItem.User -> pending += text(item.text)
                is LlmItem.Assistant -> {
                    flush()
                    builder.addAssistantMessage(item.text)
                }
                is LlmItem.ToolTurn -> {
                    flush()
                    // Sent back whole: a tool turn's thinking must return unchanged.
                    builder.addMessage(item.reply.native as BetaMessage)
                    pending += item.outcomes.map(::toolResult)
                }
                is LlmItem.Reply -> {
                    flush()
                    val native = item.reply.native as? BetaMessage
                    if (native != null) builder.addMessage(native) else builder.addAssistantMessage(item.reply.text.orEmpty())
                }
            }
        }
        flush()
        return builder.build()
    }

    private fun text(text: String): BetaContentBlockParam =
        BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(text).build())

    private fun effort(effort: LlmEffort): BetaOutputConfig.Effort = when (effort) {
        LlmEffort.LOW -> BetaOutputConfig.Effort.LOW
        LlmEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
    }

    private fun tool(spec: ToolSpec): BetaTool {
        val properties = BetaTool.InputSchema.Properties.builder()
        spec.properties.forEach { (name, schema) ->
            properties.putAdditionalProperty(name, jsonValue(schema.toString()))
        }
        return BetaTool.builder()
            .name(spec.name)
            .description(spec.description)
            .strict(true)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(properties.build())
                    .required(spec.required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    private fun jsonValue(json: String): JsonValue = JsonValue.fromJsonNode(jsonMapper().readTree(json))

    private fun toolResult(outcome: ToolOutcome): BetaContentBlockParam =
        BetaContentBlockParam.ofToolResult(
            BetaToolResultBlockParam.builder()
                .toolUseId(outcome.callId)
                .content(outcome.content)
                .isError(outcome.isError)
                .build(),
        )

    private fun toReply(message: BetaMessage): LlmReply {
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
        val calls = message.content().mapNotNull { block ->
            block.toolUse().map { use ->
                ToolCall(
                    id = use.id(),
                    name = use.name(),
                    input = Json.parseToJsonElement(jsonMapper().writeValueAsString(use._input())).jsonObject,
                )
            }.orElse(null)
        }
        return LlmReply(
            text = text.joinToString("\n").takeIf { it.isNotBlank() },
            toolCalls = calls,
            refused = message.stopReason().orElse(null) == BetaStopReason.REFUSAL,
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens(),
            native = message,
        )
    }

    private companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        val MODELS_WITH_FALLBACK = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1")
    }
}
