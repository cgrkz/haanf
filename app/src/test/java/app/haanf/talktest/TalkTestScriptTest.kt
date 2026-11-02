package app.haanf.talktest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TalkTestScriptTest {
    @Test
    fun `the passage is 30 to 40 words`() {
        val words = TalkTestScript.PASSAGE.split(Regex("\\s+")).size
        assertTrue("Passage has $words words", words in 30..40)
    }

    @Test
    fun `file names carry the labels`() {
        val request = TalkTestRequest(
            TalkTestType.PASSAGE,
            Effort.HARD,
            TrainingActivity.TREADMILL,
            AudioInput.BLUETOOTH_EARBUDS,
        )
        assertEquals(
            "talk_19700101-000001_passage_hard_treadmill_bluetooth_earbuds.wav",
            TalkTestScript.fileName(1_000, request, ZoneOffset.UTC),
        )
    }
}
