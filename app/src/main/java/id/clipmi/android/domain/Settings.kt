package id.clipmi.android.domain

import id.clipmi.android.data.SettingDao
import id.clipmi.android.security.SecretStore

/** Penyedia transkripsi API (format audio/transcriptions bergaya OpenAI), sama dengan desktop. */
enum class TranscriptionProvider(val value: String, val label: String, val baseUrl: String?, val defaultModel: String?, val keyPlaceholder: String) {
    Groq("groq", "Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "gsk_…"),
    OpenAI("openai", "OpenAI", "https://api.openai.com/v1", "whisper-1", "sk-…"),
    Custom("custom", "Kompatibel OpenAI (server Whisper lain)", null, null, "Kosongkan jika tidak perlu");

    val requiresKey: Boolean get() = this != Custom

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

data class TranscriptionConfig(
    val provider: TranscriptionProvider,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
) {
    fun endpoint(): String = baseUrl.trimEnd('/')
}

/**
 * Pengaturan aplikasi di tabel app_settings (kunci sama dengan desktop). API key tidak pernah di sini: disimpan
 * terenkripsi di [SecretStore].
 */
class Settings(private val dao: SettingDao, private val secrets: SecretStore) {
    suspend fun transcription(): TranscriptionConfig? {
        val provider = TranscriptionProvider.from(dao.get(KEY_PROVIDER)) ?: return null
        val baseUrl = dao.get(KEY_BASE_URL)?.takeIf { it.isNotBlank() } ?: provider.baseUrl ?: return null
        val model = dao.get(KEY_MODEL)?.takeIf { it.isNotBlank() } ?: provider.defaultModel ?: return null
        val key = secrets.get(secretName(provider))
        if (provider.requiresKey && key.isNullOrBlank()) return null
        return TranscriptionConfig(provider, baseUrl, model, key)
    }

    suspend fun rawTranscription(): Triple<TranscriptionProvider?, String?, String?> =
        Triple(TranscriptionProvider.from(dao.get(KEY_PROVIDER)), dao.get(KEY_BASE_URL), dao.get(KEY_MODEL))

    suspend fun saveTranscription(provider: TranscriptionProvider, baseUrl: String?, model: String?, apiKey: String?) {
        dao.put(KEY_PROVIDER, provider.value)
        dao.put(KEY_BASE_URL, baseUrl?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_MODEL, model?.trim()?.takeIf { it.isNotEmpty() })
        if (!apiKey.isNullOrBlank()) secrets.put(secretName(provider), apiKey.trim())
    }

    fun hasKey(provider: TranscriptionProvider) = secrets.has(secretName(provider))

    fun removeKey(provider: TranscriptionProvider) = secrets.remove(secretName(provider))

    suspend fun autoTranscribe(): Boolean = dao.get(KEY_AUTO) == "1"

    suspend fun setAutoTranscribe(enabled: Boolean) = dao.put(KEY_AUTO, if (enabled) "1" else "0")

    private fun secretName(provider: TranscriptionProvider) = "ai.transcription.${provider.value}.api_key"

    companion object {
        const val KEY_PROVIDER = "ai.transcription.provider"
        const val KEY_BASE_URL = "ai.transcription.base_url"
        const val KEY_MODEL = "ai.transcription.model"
        /** "Transkripsi otomatis untuk video tanpa subtitle", sama dengan `TranscribeWithApi::AUTO_SETTING`. */
        const val KEY_AUTO = "ai.transcription.auto"
    }
}
