package app.haanf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.haanf.ai.AiProvider
import app.haanf.ai.AiSettings
import app.haanf.ai.AiSettingsStore
import app.haanf.ai.CoachTone
import app.haanf.ai.KeyVault
import app.haanf.ai.LlmException
import app.haanf.ai.LlmFailure
import app.haanf.ai.LlmItem
import app.haanf.ai.LlmRequest
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Things only a real Android runtime can prove. */
@RunWith(AndroidJUnit4::class)
class AiOnDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theKeyIsEncryptedAtRestAndComesBackIntact() {
        val vault = KeyVault(context)
        vault.save("sk-or-v1-not-a-real-key")
        assertEquals("sk-or-v1-not-a-real-key", vault.read())
        val stored = context.getSharedPreferences("ai_key", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains("not-a-real-key"))
        vault.clear()
        assertNull(vault.read())
    }

    @Test
    fun aKeyPlacedOverAdbIsEncryptedAndItsFileRemoved() {
        val store = AiSettingsStore(context)
        val file = File(context.filesDir, AiSettingsStore.DEV_KEY_FILE)
        file.writeText("HINOKI\nhinoki-offensive\nnot-a-real-key-but-long-enough\n")

        store.importDevKey(file)

        assertFalse(file.exists())
        assertEquals(AiProvider.HINOKI, store.settings.value?.provider)
        assertEquals("hinoki-offensive", store.settings.value?.model)
        assertEquals("not-a-real-key-but-long-enough", KeyVault(context).read())
        val stored = context.getSharedPreferences("ai_key", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains("not-a-real-key"))
        assertNotNull(store.client())
        store.clear()
    }

    @Test
    fun aDamagedKeyFileIsIgnoredAndStillRemoved() {
        val store = AiSettingsStore(context)
        // A phone or emulator may already hold a working key. A damaged file must leave it alone.
        val before = store.settings.value
        val file = File(context.filesDir, AiSettingsStore.DEV_KEY_FILE)
        file.writeText("NOT_A_PROVIDER\n")
        store.importDevKey(file)
        assertFalse(file.exists())
        assertEquals(before, store.settings.value)
    }

    @Test
    fun theClaudeSdkParsesARealResponseShapeOnAndroid() {
        val json = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
            "content":[{"type":"thinking","thinking":"","signature":"sig"},
            {"type":"tool_use","id":"tu_1","name":"speak","input":{"message":"Hi"}}],
            "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":2}}"""
        val message = jsonMapper().readValue(json, BetaMessage::class.java)
        assertEquals("tu_1", message.content()[1].toolUse().get().id())
        message.toParam()
    }

    /** Real calls with an obviously fake key. A rejection proves the whole path works. */
    private fun rejectsFakeKey(provider: AiProvider, key: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "1")
        val settings = AiSettings(provider, provider.defaultModel, CoachTone.WARM)
        val client = AiSettingsStore.build(settings, key)
        val error = assertThrows(LlmException::class.java) {
            runBlocking { client.send(LlmRequest("Reply OK.", listOf(LlmItem.User("OK?")))) }
        }
        assertEquals(LlmFailure.BAD_KEY, error.failure)
    }

    @Test
    fun claudeCallsReachTheRealApiAndUnderstandTheRejection() {
        rejectsFakeKey(AiProvider.CLAUDE, "sk-ant-api03-this-is-not-a-real-key")
    }

    @Test
    fun openRouterCallsReachTheRealApiAndUnderstandTheRejection() {
        rejectsFakeKey(AiProvider.OPENROUTER, "sk-or-v1-this-is-not-a-real-key")
    }
}
