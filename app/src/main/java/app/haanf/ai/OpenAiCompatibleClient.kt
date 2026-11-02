package app.haanf.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Chat Completions over HTTP, which is how OpenRouter is called. */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = defaultHttp,
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmReply = runInterruptible(Dispatchers.IO) {
        val call = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Haanf")
            .post(body(request).toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(call).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failureFor(response.code, text)
                parse(text)
            }
        } catch (e: IOException) {
            throw LlmException(LlmFailure.NETWORK, cause = e)
        }
    }

    private fun body(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        putJsonArray("messages") {
            add(message("system", request.system))
            request.items.forEach { item ->
                when (item) {
                    is LlmItem.User -> add(message("user", item.text))
                    is LlmItem.Assistant -> add(message("assistant", item.text))
                    is LlmItem.ToolTurn -> {
                        add(assistantToolMessage(item.reply))
                        item.outcomes.forEach { add(toolMessage(it)) }
                    }
                }
            }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") { request.tools.forEach { add(toolDefinition(it)) } }
        }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun assistantToolMessage(reply: LlmReply) = buildJsonObject {
        put("role", "assistant")
        put("content", reply.text?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("tool_calls") {
            reply.toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.input.toString())
                        }
                    },
                )
            }
        }
    }

    private fun toolMessage(outcome: ToolOutcome) = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", outcome.callId)
        put("content", outcome.content)
    }

    private fun toolDefinition(tool: ToolSpec) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            putJsonObject("parameters") {
                put("type", "object")
                put("properties", tool.properties)
                put("required", buildJsonArray { tool.required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            }
        }
    }

    private fun parse(text: String): LlmReply {
        try {
            val root = Json.parseToJsonElement(text).jsonObject
            (root["error"] as? JsonObject)?.let { throw failureFor(errorCode(it), it.toString()) }
            val choice = root.getValue("choices").jsonArray.first().jsonObject
            val message = choice.getValue("message").jsonObject
            val usage = root["usage"] as? JsonObject
            return LlmReply(
                text = (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().map(::toolCall),
                refused = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "content_filter",
                inputTokens = usage.long("prompt_tokens"),
                outputTokens = usage.long("completion_tokens"),
            )
        } catch (e: IllegalArgumentException) {
            throw LlmException(LlmFailure.BAD_RESPONSE, cause = e)
        } catch (e: NoSuchElementException) {
            throw LlmException(LlmFailure.BAD_RESPONSE, cause = e)
        }
    }

    private fun toolCall(element: JsonElement): ToolCall {
        val call = element.jsonObject
        val function = call.getValue("function").jsonObject
        return ToolCall(
            id = call.getValue("id").jsonPrimitive.content,
            name = function.getValue("name").jsonPrimitive.content,
            input = Json.parseToJsonElement(function.getValue("arguments").jsonPrimitive.content).jsonObject,
        )
    }

    private fun JsonObject?.long(key: String): Long = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0

    private fun errorCode(error: JsonObject): Int =
        (error["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0

    private fun failureFor(code: Int, detail: String): LlmException = LlmException(
        failure = when (code) {
            401, 403 -> LlmFailure.BAD_KEY
            402 -> LlmFailure.NO_CREDIT
            429 -> LlmFailure.RATE_LIMITED
            in 500..599 -> LlmFailure.SERVER
            else -> LlmFailure.BAD_RESPONSE
        },
        detail = detail.take(MAX_ERROR_CHARS),
    )

    private companion object {
        const val MAX_ERROR_CHARS = 300
        val JSON_TYPE = "application/json".toMediaType()
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}
