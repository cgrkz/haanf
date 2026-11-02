package app.haanf.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Replays scripted replies, and repeats the last one when the script runs out. */
class FakeLlm(private val script: MutableList<(LlmRequest) -> LlmReply> = mutableListOf()) : LlmClient, LlmProvider {
    val requests = mutableListOf<LlmRequest>()

    fun then(reply: (LlmRequest) -> LlmReply): FakeLlm {
        script += reply
        return this
    }

    override fun client(): LlmClient = this

    override suspend fun send(request: LlmRequest): LlmReply {
        requests += request
        val next = if (requests.size <= script.size) script[requests.size - 1] else script.last()
        return next(request)
    }

    companion object {
        fun call(name: String, input: JsonObject) =
            LlmReply(text = null, toolCalls = listOf(ToolCall("id-$name", name, input)))

        fun speak(message: String, nextMinutes: Int = 5, askTalkTest: Boolean = false) = call(
            "speak",
            buildJsonObject {
                put("message", message)
                put("next_check_in_minutes", nextMinutes)
                put("ask_talk_test", askTalkTest)
            },
        )

        fun adjust(change: PlanChange, minutes: Int = 0) = call(
            "adjust_workout",
            buildJsonObject {
                put("change", JsonPrimitive(change.name))
                put("minutes", minutes)
            },
        )

        fun text(text: String) = LlmReply(text = text, toolCalls = emptyList())
    }
}
