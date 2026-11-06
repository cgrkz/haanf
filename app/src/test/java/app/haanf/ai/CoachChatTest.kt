package app.haanf.ai

import app.haanf.core.Clock
import app.haanf.data.CoachDao
import app.haanf.data.CoachFactEntity
import app.haanf.data.CoachMessageEntity
import app.haanf.data.TalkCheckEntity
import app.haanf.data.WorkoutEntity
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import app.haanf.workout.TimelineEntry
import app.haanf.workout.TimelineKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CoachChatTest {
    private val start = LocalDate.of(2026, 11, 5).atTime(7, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val ride = WorkoutEntity(
        id = 5, startedAtMs = start, endedAtMs = start + 1_800_000, title = "Morning ride", activity = "CYCLING",
        origin = "FREE", zoneMaxBpm = 193, avgBpm = 149, maxBpm = 185, distanceMetres = 9_000.0, movingSeconds = 1_700,
    )
    private val older = ride.copy(id = 2, startedAtMs = start - 20 * 86_400_000L, endedAtMs = start - 20 * 86_400_000L + 1_500_000, title = "October ride")

    private val dao = FakeCoachDao()
    private val clock = object : Clock {
        override fun nowEpochMs() = start + 86_400_000
        override fun elapsedMs() = 0L
    }
    private val data = object : CoachDataSource {
        override suspend fun snapshot() = CoachSnapshot(
            LocalDate.of(2026, 11, 6), 22, Sex.MALE, 61.0, HeartRateZones(193), false,
            emptyList(), emptyList(), listOf(ride), emptyList(), null, null,
        )
        override suspend fun ruleContext(minutesAvailable: Int, feeling: Int) =
            RuleContext(LocalDate.of(2026, 11, 6), minutesAvailable, feeling, 2, emptyList(), emptyList(), false)
        override suspend fun lastBenchmarkDay(): LocalDate? = null
    }
    private val lookup = WorkoutLookup(
        between = { from, to -> listOf(ride, older).filter { it.startedAtMs in from until to } },
        workout = { id -> listOf(ride, older).firstOrNull { it.id == id } },
        benchmarks = { emptyList() },
        detail = WorkoutDetail(
            samples = { emptyList() },
            timeline = { id ->
                if (id == 5L) listOf(TimelineEntry(420, TimelineKind.ATHLETE, "Legs feel heavy today.", 151)) else emptyList()
            },
            splits = { emptyList() },
            zone = ZoneOffset.UTC,
        ),
        zone = ZoneOffset.UTC,
    )

    private fun lastResult(request: LlmRequest): String =
        (request.items.last() as LlmItem.ToolTurn).outcomes.single().content

    @Test
    fun `chat finds an older workout, opens two together and answers from them`() = runBlocking {
        val llm = FakeLlm()
            .then {
                FakeLlm.call(
                    "find_workouts",
                    buildJsonObject {
                        put("activity", "ride")
                        put("kind", "any")
                        put("from_date", "2026-10-01")
                        put("to_date", "2026-10-31")
                    },
                )
            }
            .then {
                FakeLlm.call("open_workouts", buildJsonObject { put("workout_ids", JsonArray(listOf(JsonPrimitive(5), JsonPrimitive(2)))) })
            }
            .then { FakeLlm.text("You were quicker today, though your legs felt heavy at 7 minutes.") }
        val chat = CoachChat(llm, dao, data, clock, { CoachTone.WARM }, lookup)

        assertNull(chat.send("How did today's ride compare with my October one?"))

        val first = llm.requests.first()
        assertEquals(
            listOf("remember_fact", "resolve_fact", "find_workouts", "open_workouts"),
            first.tools.map { it.name },
        )
        val asked = (first.items.single() as LlmItem.User).text
        assertTrue(asked, asked.contains("- #5 2026-11-05: Morning ride (free), ride"))
        assertTrue(lastResult(llm.requests[1]), lastResult(llm.requests[1]).contains("#2 2026-10-16: October ride"))
        val opened = lastResult(llm.requests[2])
        assertTrue(opened, opened.contains("Workout #5: Morning ride"))
        assertTrue(opened, opened.contains("[7:00] Athlete: Legs feel heavy today. (151 bpm)"))
        assertTrue(opened, opened.contains("Workout #2: October ride"))
        assertEquals("You were quicker today, though your legs felt heavy at 7 minutes.", dao.messages.last().text)
        assertTrue(dao.messages.last().fromCoach)
    }

    @Test
    fun `without workout look-up the chat lists workouts without ids or tools for them`() = runBlocking {
        val llm = FakeLlm().then { FakeLlm.text("Nice work.") }
        CoachChat(llm, dao, data, clock, { CoachTone.WARM }).send("Hi")
        val request = llm.requests.single()
        assertEquals(listOf("remember_fact", "resolve_fact"), request.tools.map { it.name })
        val asked = (request.items.single() as LlmItem.User).text
        assertTrue(asked, asked.contains("- 2026-11-05: Morning ride"))
        assertFalse(asked, asked.contains("#5"))
    }
}

/** Keeps the chat's messages and facts in memory. */
class FakeCoachDao : CoachDao {
    val messages = mutableListOf<CoachMessageEntity>()
    val facts = mutableListOf<CoachFactEntity>()

    override suspend fun insertMessage(message: CoachMessageEntity): Long {
        val saved = message.copy(id = messages.size + 1L)
        messages += saved
        return saved.id
    }

    override fun observeMessages(): Flow<List<CoachMessageEntity>> = flowOf(messages.toList())

    override suspend fun recentMessages(limit: Int): List<CoachMessageEntity> = messages.takeLast(limit)

    override suspend fun insertFact(fact: CoachFactEntity): Long {
        val saved = fact.copy(id = facts.size + 1L)
        facts += saved
        return saved.id
    }

    override suspend fun updateFact(fact: CoachFactEntity) {
        facts.replaceAll { if (it.id == fact.id) fact else it }
    }

    override suspend fun deleteFact(id: Long) {
        facts.removeAll { it.id == id }
    }

    override fun observeFacts(): Flow<List<CoachFactEntity>> = flowOf(facts.toList())

    override suspend fun activeFacts(): List<CoachFactEntity> = facts.filter { it.status == "ACTIVE" }

    override suspend fun fact(id: Long): CoachFactEntity? = facts.firstOrNull { it.id == id }

    override suspend fun insertTalkCheck(check: TalkCheckEntity) = Unit

    override fun observeTalkChecks(workoutId: Long): Flow<List<TalkCheckEntity>> = flowOf(emptyList())

    override suspend fun recentTalkChecks(limit: Int): List<TalkCheckEntity> = emptyList()
}
