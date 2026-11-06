package app.haanf.ai

import app.haanf.ai.CoachTools.int
import app.haanf.ai.CoachTools.text
import app.haanf.core.Clock
import app.haanf.data.CoachDao
import app.haanf.data.CoachFactEntity
import app.haanf.data.CoachMessageEntity
import app.haanf.data.FactKind
import app.haanf.data.FactStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The ongoing chat. The AI can only propose what to remember: the user confirms it. */
class CoachChat(
    private val llm: LlmProvider,
    private val dao: CoachDao,
    private val data: CoachDataSource,
    private val clock: Clock,
    private val tone: () -> CoachTone,
) {
    /** Saves the message and the reply. Returns an error to show, or null if all went well. */
    suspend fun send(text: String): String? {
        val client = llm.client() ?: return "Add an API key first."
        val history = dao.recentMessages(HISTORY_MESSAGES)
        dao.insertMessage(CoachMessageEntity(atMs = clock.nowEpochMs(), fromCoach = false, text = text))
        val snapshot = data.snapshot()
        val facts = dao.activeFacts()
        val background = snapshot?.let { CoachContext.render(it) }.orEmpty()
        val factIds = facts.joinToString("\n") { "${it.id}: ${it.text}" }
        val items = buildList {
            history.forEach {
                add(if (it.fromCoach) LlmItem.Assistant(it.text) else LlmItem.User(it.text))
            }
            add(LlmItem.User("$background\n\nActive fact ids:\n$factIds\n\nThe athlete says: $text"))
        }
        val request = LlmRequest(
            system = CoachPrompts.chat(tone()),
            items = mergeUserRuns(items),
            tools = listOf(REMEMBER_FACT, RESOLVE_FACT),
            effort = LlmEffort.LOW,
        )
        return try {
            val reply = runToolLoop(client, request) { call ->
                when (call.name) {
                    REMEMBER_FACT.name -> remember(call)
                    RESOLVE_FACT.name -> resolve(call)
                    else -> ToolOutcome(call.id, "Unknown tool.", isError = true)
                }
            }
            val answer = reply.text?.trim().orEmpty().ifEmpty { "Noted." }
            dao.insertMessage(CoachMessageEntity(atMs = clock.nowEpochMs(), fromCoach = true, text = answer))
            null
        } catch (e: LlmException) {
            e.failure.userMessage
        }
    }

    /** Some providers refuse two user turns in a row, which a failed reply can leave behind. */
    private fun mergeUserRuns(items: List<LlmItem>): List<LlmItem> {
        val merged = mutableListOf<LlmItem>()
        items.forEach { item ->
            val last = merged.lastOrNull()
            if (item is LlmItem.User && last is LlmItem.User) {
                merged[merged.lastIndex] = LlmItem.User(last.text + "\n\n" + item.text)
            } else {
                merged += item
            }
        }
        return merged.dropWhile { it !is LlmItem.User }
    }

    private suspend fun remember(call: ToolCall): ToolOutcome {
        val kind = FactKind.entries.firstOrNull { it.name == call.input.text("kind") } ?: FactKind.NOTE
        val text = call.input.text("fact")?.trim().orEmpty()
        if (text.isEmpty()) return ToolOutcome(call.id, "The fact was empty.", isError = true)
        dao.insertFact(
            CoachFactEntity(
                createdAtMs = clock.nowEpochMs(),
                kind = kind.name,
                text = text,
                status = FactStatus.PROPOSED.name,
            ),
        )
        return ToolOutcome(call.id, "Proposed. The athlete will confirm it in the app.")
    }

    private suspend fun resolve(call: ToolCall): ToolOutcome {
        val id = call.input.int("fact_id")?.toLong() ?: return ToolOutcome(call.id, "No id.", isError = true)
        val fact = dao.fact(id) ?: return ToolOutcome(call.id, "Unknown fact.", isError = true)
        dao.updateFact(fact.copy(status = FactStatus.RESOLVED.name))
        return ToolOutcome(call.id, "Marked resolved.")
    }

    private companion object {
        const val HISTORY_MESSAGES = 20

        val REMEMBER_FACT get() = CoachTools.REMEMBER_FACT

        val RESOLVE_FACT = ToolSpec(
            name = "resolve_fact",
            description = "Mark an active fact as no longer true, such as a healed injury.",
            properties = buildJsonObject {
                put("fact_id", CoachTools.integer("The id from the active fact list."))
            },
        )
    }
}
