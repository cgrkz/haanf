package app.haanf.ai

import kotlinx.serialization.json.JsonObject

/*
 * The boundary between coaching logic and AI providers. A subscription can replace
 * bring-your-own-key later by adding a client here, with no change to anything above it.
 */

class ToolSpec(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String> = properties.keys.toList(),
)

data class ToolCall(val id: String, val name: String, val input: JsonObject)

data class ToolOutcome(val callId: String, val content: String, val isError: Boolean = false)

sealed interface LlmItem {
    data class User(val text: String) : LlmItem
    data class Assistant(val text: String) : LlmItem

    /** A reply that called tools, and their results. [reply] goes back unchanged. */
    data class ToolTurn(val reply: LlmReply, val outcomes: List<ToolOutcome>) : LlmItem
}

enum class LlmEffort { LOW, MEDIUM }

data class LlmRequest(
    val system: String,
    val items: List<LlmItem>,
    val tools: List<ToolSpec> = emptyList(),
    val effort: LlmEffort = LlmEffort.LOW,
    val maxTokens: Long = DEFAULT_MAX_TOKENS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 4_000L
    }
}

/** [native] is the provider's own message, needed to continue a tool turn correctly. */
data class LlmReply(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val refused: Boolean = false,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val native: Any? = null,
)

enum class LlmFailure(val userMessage: String) {
    BAD_KEY("The API key was rejected."),
    NO_CREDIT("The account has run out of credit."),
    RATE_LIMITED("Too many requests. Try again in a moment."),
    NETWORK("Could not reach the AI. Check your connection."),
    SERVER("The AI service had a problem."),
    BAD_RESPONSE("The AI sent a reply Haanf could not read."),
}

class LlmException(val failure: LlmFailure, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: failure.userMessage, cause)

interface LlmClient {
    suspend fun send(request: LlmRequest): LlmReply
}

/** The client for the current key and model, or null when no key is set. */
fun interface LlmProvider {
    fun client(): LlmClient?
}

/**
 * Sends [request], runs each tool call through [handle], and repeats until the model stops
 * calling tools, [isDone] is true, or [maxRounds] is reached. The last reply is returned.
 */
suspend fun runToolLoop(
    client: LlmClient,
    request: LlmRequest,
    maxRounds: Int = 4,
    isDone: () -> Boolean = { false },
    handle: suspend (ToolCall) -> ToolOutcome,
): LlmReply {
    var items = request.items
    var reply = client.send(request)
    var round = 1
    while (reply.toolCalls.isNotEmpty() && !reply.refused) {
        val outcomes = reply.toolCalls.map { handle(it) }
        if (isDone() || round >= maxRounds) return reply
        items = items + LlmItem.ToolTurn(reply, outcomes)
        reply = client.send(request.copy(items = items))
        round++
    }
    return reply
}
