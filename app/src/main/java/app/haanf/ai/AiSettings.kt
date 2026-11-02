package app.haanf.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only hosts the app ever calls. Each one is chosen by the user holding its key. */
enum class AiProvider(
    val label: String,
    val keyHint: String,
    val defaultModel: String,
    val baseUrl: String? = null,
) {
    OPENROUTER("OpenRouter", "sk-or-…", "anthropic/claude-opus-5.5", "https://openrouter.ai/api/v1"),
    CLAUDE("Claude", "sk-ant-…", "claude-opus-5-5"),
    HINOKI("Hinoki (testing)", "Hinoki key", "hinoki-offensive", "https://inference.hinoki.security/v1"),
}

enum class CoachTone(val label: String, val description: String, val instruction: String) {
    WARM(
        "Warm and encouraging",
        "Friendly and short, like a good running partner.",
        "Be warm and encouraging, like a good running partner.",
    ),
    CALM(
        "Calm and factual",
        "Numbers and instructions, no pep talk.",
        "Be calm and factual. Give numbers and instructions, and no pep talk.",
    ),
    TOUGH(
        "Tough and pushy",
        "Drill-sergeant energy, within the safety rules.",
        "Be tough and demanding, like a drill sergeant, but never push past the safety rules.",
    ),
}

data class AiSettings(
    val provider: AiProvider,
    val model: String,
    val tone: CoachTone,
)

/** Saved settings. The key itself lives in [KeyVault]. */
class AiSettingsStore(context: Context) : LlmProvider {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val vault = KeyVault(context)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AiSettings?> = _settings.asStateFlow()

    private var cached: Pair<AiSettings, LlmClient>? = null

    private fun load(): AiSettings? {
        val provider = prefs.getString(KEY_PROVIDER, null)?.let(AiProvider::valueOf) ?: return null
        if (vault.read() == null) return null
        return AiSettings(
            provider = provider,
            model = prefs.getString(KEY_MODEL, null) ?: provider.defaultModel,
            tone = prefs.getString(KEY_TONE, null)?.let(CoachTone::valueOf) ?: CoachTone.WARM,
        )
    }

    /** Called after the user has seen what is sent, and agreed. */
    fun save(settings: AiSettings, apiKey: String) {
        vault.save(apiKey)
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_TONE, settings.tone.name)
            .apply()
        cached = null
        _settings.value = settings
    }

    /**
     * Takes a key placed in private storage over adb, so a tester's key never has to be built
     * into the APK. The file holds the provider, the model and the key, one per line, and is
     * deleted whatever happens. Only someone with adb access to the phone can place it.
     */
    fun importDevKey(file: File) {
        if (!file.exists()) return
        try {
            val lines = file.readLines().map { it.trim() }
            val provider = AiProvider.valueOf(lines[0])
            val tone = _settings.value?.tone ?: CoachTone.WARM
            save(AiSettings(provider, lines[1], tone), lines[2])
            Log.i(TAG, "Imported a key for ${provider.name}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Ignored a key file with an unknown provider")
        } catch (e: IndexOutOfBoundsException) {
            Log.w(TAG, "Ignored an incomplete key file")
        } finally {
            file.delete()
        }
    }

    fun updateTone(tone: CoachTone) {
        val current = _settings.value ?: return
        prefs.edit().putString(KEY_TONE, tone.name).apply()
        _settings.value = current.copy(tone = tone)
    }

    fun clear() {
        vault.clear()
        prefs.edit().clear().apply()
        cached = null
        _settings.value = null
    }

    @Synchronized
    override fun client(): LlmClient? {
        val current = _settings.value ?: return null
        cached?.takeIf { it.first.provider == current.provider && it.first.model == current.model }
            ?.let { return it.second }
        val key = vault.read() ?: return null
        return build(current, key).also { cached = current to it }
    }

    companion object {
        const val DEV_KEY_FILE = "dev_ai_key"
        private const val TAG = "HaanfAi"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_TONE = "tone"

        fun build(settings: AiSettings, apiKey: String): LlmClient {
            val baseUrl = settings.provider.baseUrl
            return if (baseUrl != null) {
                OpenAiCompatibleClient(baseUrl, apiKey, settings.model)
            } else {
                ClaudeClient(apiKey, settings.model)
            }
        }
    }
}

/** Holds the API key encrypted with a key that stays inside the Android Keystore. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("ai_key", Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray())
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "haanf_ai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
    }
}
