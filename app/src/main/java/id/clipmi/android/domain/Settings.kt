package id.clipmi.android.domain

import id.clipmi.core.analysis.PromptStyle
import id.clipmi.core.analysis.PromptStyles
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

    // --- Analisis Mode API ---------------------------------------------------------------------------------------

    data class RawAnalysis(val provider: AiProvider?, val model: String?, val baseUrl: String?, val priceInput: String?, val priceOutput: String?)

    /** Pengaturan lengkap dan siap dipakai: key tersedia bila penyedia membutuhkannya. */
    suspend fun analysis(): AiConfig? {
        migrateLegacyAnthropicKey()
        val provider = AiProvider.from(dao.get(KEY_AI_PROVIDER)) ?: return null
        val model = dao.get(KEY_AI_MODEL)?.takeIf { it.isNotBlank() } ?: provider.defaultModel ?: return null
        val baseUrl = dao.get(KEY_AI_BASE_URL)?.takeIf { it.isNotBlank() }
        if (provider == AiProvider.Custom && baseUrl == null) return null
        val key = analysisKey(provider)
        if (provider.requiresKey && key.isNullOrBlank()) return null
        return AiConfig(
            provider, model, baseUrl, key?.takeIf { it.isNotBlank() },
            dao.get(KEY_AI_PRICE_INPUT)?.toDoubleOrNull(), dao.get(KEY_AI_PRICE_OUTPUT)?.toDoubleOrNull(),
        )
    }

    suspend fun rawAnalysis(): RawAnalysis {
        migrateLegacyAnthropicKey()
        return RawAnalysis(
            AiProvider.from(dao.get(KEY_AI_PROVIDER)), dao.get(KEY_AI_MODEL), dao.get(KEY_AI_BASE_URL),
            dao.get(KEY_AI_PRICE_INPUT), dao.get(KEY_AI_PRICE_OUTPUT),
        )
    }

    suspend fun saveAnalysis(provider: AiProvider, model: String?, baseUrl: String?, apiKey: String?, priceInput: String?, priceOutput: String?) {
        dao.put(KEY_AI_PROVIDER, provider.value)
        dao.put(KEY_AI_MODEL, model?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_AI_BASE_URL, baseUrl?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_AI_PRICE_INPUT, priceInput?.trim()?.replace(',', '.')?.toDoubleOrNull()?.toString())
        dao.put(KEY_AI_PRICE_OUTPUT, priceOutput?.trim()?.replace(',', '.')?.toDoubleOrNull()?.toString())
        if (!apiKey.isNullOrBlank()) secrets.put(analysisSecret(provider), apiKey.trim())
    }

    fun hasAnalysisKey(provider: AiProvider) = secrets.has(analysisSecret(provider)) || transcriptionSecretFor(provider)?.let(secrets::has) == true

    /**
     * Key analisis; untuk Groq/OpenAI yang belum punya key analisis sendiri, key Transkripsi API penyedia yang sama
     * dipakai, supaya pengguna tidak perlu mengisi key yang sama dua kali.
     */
    private fun analysisKey(provider: AiProvider): String? =
        secrets.get(analysisSecret(provider))?.takeIf { it.isNotBlank() } ?: transcriptionSecretFor(provider)?.let(secrets::get)

    private fun transcriptionSecretFor(provider: AiProvider): String? = when (provider) {
        AiProvider.Groq -> secretName(TranscriptionProvider.Groq)
        AiProvider.OpenAI -> secretName(TranscriptionProvider.OpenAI)
        else -> null
    }

    fun removeAnalysisKey(provider: AiProvider) = secrets.remove(analysisSecret(provider))

    suspend fun autoAnalyze(): Boolean = dao.get(KEY_AI_AUTO) == "1"

    /** "Rapikan audio saat render" (kunci desktop render.enhance_audio); bawaan aktif. */
    suspend fun enhanceAudio(): Boolean = dao.get(KEY_ENHANCE_AUDIO) != "0"

    suspend fun setEnhanceAudio(enabled: Boolean) = dao.put(KEY_ENHANCE_AUDIO, if (enabled) "1" else "0")

    suspend fun setAutoAnalyze(enabled: Boolean) = dao.put(KEY_AI_AUTO, if (enabled) "1" else "0")

    /** Template prompt (null = bawaan desktop) dan batas panjang prompt per bagian (token). */
    suspend fun promptTemplate(): String? = dao.get(KEY_PROMPT_TEMPLATE)?.takeIf { it.isNotBlank() }

    suspend fun promptMaxTokens(): Int? = dao.get(KEY_PROMPT_MAX_TOKENS)?.toIntOrNull()

    suspend fun savePrompt(template: String?, maxTokens: Int?) {
        dao.put(KEY_PROMPT_TEMPLATE, template?.takeIf { it.isNotBlank() })
        dao.put(KEY_PROMPT_MAX_TOKENS, maxTokens?.toString())
    }

    /** Gaya prompt buatan sendiri (JSON daftar [PromptStyle]); ikut cadangan karena tersimpan di database. */
    suspend fun customPromptStyles(): List<PromptStyle> =
        dao.get(KEY_PROMPT_STYLES)?.let { runCatching { styleJson.decodeFromString(styleList, it) }.getOrNull() }.orEmpty()

    /** Semua gaya: bawaan lalu buatan sendiri. */
    suspend fun promptStyles(): List<PromptStyle> = PromptStyles.BUILT_IN + customPromptStyles()

    /** Menyimpan (menambah atau mengganti, berdasarkan id) gaya buatan sendiri. */
    suspend fun saveCustomPromptStyle(style: PromptStyle) {
        val list = customPromptStyles().filterNot { it.id == style.id } + style.copy(custom = true)
        dao.put(KEY_PROMPT_STYLES, styleJson.encodeToString(styleList, list))
    }

    suspend fun deleteCustomPromptStyle(id: String) {
        dao.put(KEY_PROMPT_STYLES, styleJson.encodeToString(styleList, customPromptStyles().filterNot { it.id == id }))
    }

    private val styleJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val styleList = kotlinx.serialization.builtins.ListSerializer(PromptStyle.serializer())

    /** Key Anthropic dari layar kerangka Fase 0 dipindah ke tempat key analisis (tanpa ditampilkan). */
    private fun migrateLegacyAnthropicKey() {
        if (!secrets.has(SecretStore.ANTHROPIC_API_KEY)) return
        val legacy = secrets.get(SecretStore.ANTHROPIC_API_KEY)
        if (legacy != null && !secrets.has(analysisSecret(AiProvider.Anthropic))) secrets.put(analysisSecret(AiProvider.Anthropic), legacy)
        secrets.remove(SecretStore.ANTHROPIC_API_KEY)
    }

    private fun analysisSecret(provider: AiProvider) = "ai.analysis.${provider.value}.api_key"

    // --- Statistik postingan ----------------------------------------------------------------------------------

    /** API key YouTube Data API (opsional, Keystore): statistik YouTube resmi tanpa login (`PostStatsFetcher`). */
    fun youtubeKey(): String? = secrets.get(YOUTUBE_KEY)?.takeIf { it.isNotBlank() }

    fun hasYoutubeKey(): Boolean = secrets.has(YOUTUBE_KEY)

    fun saveYoutubeKey(key: String) = secrets.put(YOUTUBE_KEY, key.trim())

    fun removeYoutubeKey() = secrets.remove(YOUTUBE_KEY)

    companion object {
        const val YOUTUBE_KEY = "stats.youtube_key"
        const val KEY_AI_PROVIDER = "ai.analysis.provider"
        const val KEY_AI_MODEL = "ai.analysis.model"
        const val KEY_AI_BASE_URL = "ai.analysis.base_url"
        const val KEY_AI_PRICE_INPUT = "ai.analysis.price_input"
        const val KEY_AI_PRICE_OUTPUT = "ai.analysis.price_output"
        /** "Mulai analisis otomatis setelah transkrip siap", sama dengan `AnalyzeWithAi::AUTO_SETTING`. */
        const val KEY_AI_AUTO = "ai.analysis.auto"
        const val KEY_ENHANCE_AUDIO = "render.enhance_audio"
        const val KEY_PROMPT_TEMPLATE = "prompt_template"
        const val KEY_PROMPT_STYLES = "prompt_styles"
        const val KEY_PROMPT_MAX_TOKENS = "prompt_max_tokens"

        const val KEY_PROVIDER = "ai.transcription.provider"
        const val KEY_BASE_URL = "ai.transcription.base_url"
        const val KEY_MODEL = "ai.transcription.model"
        /** "Transkripsi otomatis untuk video tanpa subtitle", sama dengan `TranscribeWithApi::AUTO_SETTING`. */
        const val KEY_AUTO = "ai.transcription.auto"
    }
}
