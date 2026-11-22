package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.android.data.AiUsageEntity
import id.clipmi.android.data.AnalysisStatus
import id.clipmi.android.data.ProjectEntity
import id.clipmi.core.analysis.AnalysisResultException
import id.clipmi.core.analysis.PromptBuilder
import id.clipmi.core.analysis.PromptOptions
import id.clipmi.core.analysis.PromptStyle
import id.clipmi.core.analysis.PromptStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.resume
import kotlin.math.roundToLong

// Mode API di iPhone, padanan AiClient/Settings/Analysis/AnalyzeWorker Android: penyedia, permintaan dan pembacaan
// jawaban yang sama (HTTP lewat URLSession di Swift), API key di Keychain, kunci pengaturan sama dengan Android
// (ikut cadangan, kecuali API key).

/** Penyedia AI untuk analisis Mode API (sama dengan Android dan desktop). */
enum class AiProvider(val value: String, val label: String, val baseUrl: String?, val defaultModel: String?, val keyPlaceholder: String) {
    Anthropic("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-haiku-5-5", "sk-ant-…"),
    OpenAI("openai", "OpenAI", "https://api.openai.com/v1", null, "sk-…"),
    Gemini("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", null, "AIza…"),
    Groq("groq", "Groq", "https://api.groq.com/openai/v1", null, "gsk_…"),
    Custom("custom", "Kompatibel OpenAI (OpenRouter, Ollama, dll.)", null, null, "Kosongkan jika tidak perlu");

    /** Endpoint lokal seperti Ollama tidak memakai API key. */
    val requiresKey: Boolean get() = this != Custom

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

data class AiConfig(
    val provider: AiProvider,
    val model: String,
    val baseUrl: String?,
    val apiKey: String?,
    /** USD per 1 juta token masuk/keluar; null = harga bawaan model Claude yang dikenal. */
    val priceInput: Double? = null,
    val priceOutput: Double? = null,
) {
    fun endpoint(): String = (if (provider == AiProvider.Custom) baseUrl.orEmpty() else provider.baseUrl.orEmpty()).trimEnd('/')

    fun tokenPrices(): Pair<Double, Double>? = when {
        priceInput != null -> priceInput to (priceOutput ?: 0.0)
        provider == AiProvider.Anthropic -> KNOWN_PRICES[model]
        else -> null
    }

    fun cost(inputTokens: Int, outputTokens: Int): Double? =
        tokenPrices()?.let { (input, output) -> (inputTokens * input + outputTokens * output) / 1_000_000 }

    companion object {
        /** Harga per 1 juta token (USD) model Claude yang dikenal (sama dengan desktop dan Android). */
        val KNOWN_PRICES = mapOf(
            "claude-haiku-5-5" to (0.10 to 0.50),
            "claude-sonnet-5-5" to (2.00 to 10.00),
            "claude-opus-5-5" to (4.00 to 20.00),
        )
    }
}

data class AiReply(val text: String, val inputTokens: Int, val outputTokens: Int)

class AiException(message: String, val transient: Boolean = false) : Exception(message)

/** Jawaban HTTP dari Swift. */
data class HttpResult(val status: Int, val body: String, val retryAfter: String?, val error: String?)

suspend fun MediaEngine.http(method: String, url: String, headers: Map<String, String>, body: String?, timeoutSeconds: Int): HttpResult =
    suspendCancellableCoroutine { cont ->
        val headersJson = JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString()
        httpRequest(method, url, headersJson, body, timeoutSeconds, object : HttpListener {
            override fun finished(status: Int, body: String, retryAfter: String?, error: String?) {
                if (cont.isActive) cont.resume(HttpResult(status, body, retryAfter, error))
            }
        })
    }

/**
 * Mengirim satu prompt ke penyedia AI dan mengembalikan teks balasannya (sama dengan AiClient Android): bentuk
 * permintaan per penyedia, percobaan ulang sekali untuk 429/5xx, dan pesan kesalahan berbahasa Indonesia.
 */
class IosAiClient(private val engine: MediaEngine) {
    suspend fun complete(config: AiConfig, prompt: String): AiReply = when (config.provider) {
        AiProvider.Anthropic -> anthropic(config, prompt)
        AiProvider.Gemini -> gemini(config, prompt)
        else -> openAi(config, prompt)
    }

    /** Daftar model untuk "Uji koneksi": tidak memakai token, tetapi memastikan alamat dan key diterima. */
    suspend fun models(config: AiConfig): List<String> {
        val url = config.endpoint() + "/models?" + (if (config.provider == AiProvider.Gemini) "pageSize" else "limit") + "=1000"
        val body = send(config, "GET", url, null, timeoutSeconds = 15)
        return when (config.provider) {
            AiProvider.Gemini -> (body["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name")?.removePrefix("models/") }
            else -> (body["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
        }
    }

    private suspend fun anthropic(config: AiConfig, prompt: String): AiReply {
        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
        }
        val body = send(config, "POST", config.endpoint() + "/messages", payload.toString())
        when (body.string("stop_reason")) {
            "refusal" -> throw AiException("Claude menolak memproses transkrip ini. Coba model lain atau mode manual.")
            "max_tokens" -> throw AiException(TRUNCATED)
        }
        val text = (body["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() }
        val usage = body["usage"] as? JsonObject
        return AiReply(text, usage.int("input_tokens"), usage.int("output_tokens"))
    }

    private suspend fun gemini(config: AiConfig, prompt: String): AiReply {
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) }) })
            })
            put("generationConfig", buildJsonObject { put("responseMimeType", "application/json") })
        }
        val url = config.endpoint() + "/models/" + encodePath(config.model) + ":generateContent"
        val body = send(config, "POST", url, payload.toString())
        val candidate = (body["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            val blocked = (body["promptFeedback"] as? JsonObject)?.string("blockReason")
            throw AiException("Gemini tidak memberi balasan" + if (blocked != null) " (diblokir: $blocked)." else ".")
        }
        if (candidate.string("finishReason") == "MAX_TOKENS") throw AiException(TRUNCATED)
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.filterIsInstance<JsonObject>().mapNotNull { it.string("text") }.filter { it.isNotEmpty() }.joinToString("")
        val usage = body["usageMetadata"] as? JsonObject
        return AiReply(text, usage.int("promptTokenCount"), usage.int("candidatesTokenCount"))
    }

    /** OpenAI, Groq, dan semua endpoint kompatibel OpenAI. Batas token keluar tidak dikirim (namanya beda-beda). */
    private suspend fun openAi(config: AiConfig, prompt: String): AiReply {
        val payload = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
        }
        val body = send(config, "POST", config.endpoint() + "/chat/completions", payload.toString())
        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice?.string("finish_reason") == "length") throw AiException(TRUNCATED)
        val usage = body["usage"] as? JsonObject
        return AiReply((choice?.get("message") as? JsonObject)?.string("content").orEmpty(), usage.int("prompt_tokens"), usage.int("completion_tokens"))
    }

    private fun headers(config: AiConfig): Map<String, String> {
        val key = config.apiKey
        val base = mapOf("Accept" to "application/json")
        return when (config.provider) {
            AiProvider.Anthropic -> base + mapOf("x-api-key" to key.orEmpty(), "anthropic-version" to "2023-06-01")
            AiProvider.Gemini -> base + ("x-goog-api-key" to key.orEmpty())
            else -> if (key != null) base + ("Authorization" to "Bearer $key") else base
        }
    }

    private suspend fun send(config: AiConfig, method: String, url: String, body: String?, timeoutSeconds: Int = REQUEST_TIMEOUT): JsonObject {
        var attempt = 1
        while (true) {
            val response = engine.http(method, url, headers(config), body, timeoutSeconds)
            if (response.error != null) {
                throw AiException(
                    if (response.error == "timeout") {
                        "${config.provider.label} tidak membalas dalam ${timeoutSeconds / 60} menit, jadi permintaan dihentikan. " +
                            "Server penyedia mungkin sedang sibuk; coba lagi beberapa saat lagi atau pakai model yang lebih cepat."
                    } else {
                        "Tidak bisa terhubung ke ${config.provider.label}. Periksa koneksi internet atau alamat API."
                    },
                )
            }
            if (response.status in 200..299) {
                return runCatching { Json.parseToJsonElement(response.body) as JsonObject }.getOrElse {
                    throw AiException("Balasan ${config.provider.label} bukan JSON yang dikenali.")
                }
            }
            if (attempt >= 2 || !(response.status == 429 || response.status >= 500)) throw errorFrom(response.status, response.body, config.provider)
            delay(minOf(30, maxOf(2, response.retryAfter?.toIntOrNull() ?: 0)) * 1000L)
            attempt++
        }
    }

    private fun errorFrom(code: Int, body: String, provider: AiProvider): AiException {
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val detailText = (json?.get("error") as? JsonObject)?.string("message") ?: json?.string("message") ?: body.trim().take(200)
        val detail = if (detailText.isNotEmpty()) " ($detailText)" else ""
        val message = when {
            code == 403 && detailText.contains("model", ignoreCase = true) -> "Model ini tidak diizinkan untuk akun/project ${provider.label}. Pilih model lain atau izinkan model itu di konsol penyedia."
            code == 401 || code == 403 -> "API key ditolak oleh ${provider.label}. Periksa key di Pengaturan."
            code == 404 -> "Model atau alamat API tidak ditemukan. Periksa nama model di Pengaturan."
            code == 413 -> "Data yang dikirim terlalu besar untuk penyedia ini."
            code == 429 -> "Batas pemakaian atau kuota ${provider.label} tercapai. Coba lagi nanti atau periksa saldo akun."
            code >= 500 -> "Server ${provider.label} sedang bermasalah (HTTP $code). Coba lagi beberapa saat lagi."
            else -> "Permintaan ditolak oleh ${provider.label} (HTTP $code)."
        }
        return AiException(message + detail, transient = code >= 500)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    /** Bagian path URL (pengganti URLEncoder, spasi = %20). */
    private fun encodePath(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") append(c) else append("%" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 16000
        const val REQUEST_TIMEOUT = 300
        private const val TRUNCATED = "Balasan AI terpotong karena terlalu panjang. Kurangi batas panjang prompt di Pengaturan supaya transkrip dibagi lebih kecil."
    }
}

/** Pengaturan Mode API dan prompt (kunci sama dengan Settings Android; API key di Keychain). */
class IosSettings(private val repo: Repository, private val engine: MediaEngine) {
    private val dao get() = repo.db.settings()

    data class RawAnalysis(val provider: AiProvider?, val model: String?, val baseUrl: String?, val priceInput: String?, val priceOutput: String?)

    /** Pengaturan lengkap dan siap dipakai: key tersedia bila penyedia membutuhkannya. */
    suspend fun analysis(): AiConfig? {
        val provider = AiProvider.from(dao.get(KEY_AI_PROVIDER)) ?: return null
        val model = dao.get(KEY_AI_MODEL)?.takeIf { it.isNotBlank() } ?: provider.defaultModel ?: return null
        val baseUrl = dao.get(KEY_AI_BASE_URL)?.takeIf { it.isNotBlank() }
        if (provider == AiProvider.Custom && baseUrl == null) return null
        val key = analysisKey(provider)
        if (provider.requiresKey && key == null) return null
        return AiConfig(provider, model, baseUrl, key, dao.get(KEY_AI_PRICE_INPUT)?.toDoubleOrNull(), dao.get(KEY_AI_PRICE_OUTPUT)?.toDoubleOrNull())
    }

    suspend fun rawAnalysis() = RawAnalysis(
        AiProvider.from(dao.get(KEY_AI_PROVIDER)), dao.get(KEY_AI_MODEL), dao.get(KEY_AI_BASE_URL), dao.get(KEY_AI_PRICE_INPUT), dao.get(KEY_AI_PRICE_OUTPUT),
    )

    suspend fun saveAnalysis(provider: AiProvider, model: String?, baseUrl: String?, apiKey: String?, priceInput: String?, priceOutput: String?) {
        dao.put(KEY_AI_PROVIDER, provider.value)
        dao.put(KEY_AI_MODEL, model?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_AI_BASE_URL, baseUrl?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_AI_PRICE_INPUT, priceInput?.trim()?.replace(',', '.')?.toDoubleOrNull()?.toString())
        dao.put(KEY_AI_PRICE_OUTPUT, priceOutput?.trim()?.replace(',', '.')?.toDoubleOrNull()?.toString())
        if (!apiKey.isNullOrBlank()) engine.secretPut(analysisSecret(provider), apiKey.trim())
    }

    fun hasAnalysisKey(provider: AiProvider) = analysisKey(provider) != null

    /**
     * Key analisis; untuk Groq/OpenAI yang belum punya key analisis sendiri, key Transkripsi API penyedia yang sama
     * dipakai (sama dengan Android), supaya key yang sama tidak perlu diisi dua kali.
     */
    private fun analysisKey(provider: AiProvider): String? =
        engine.secretGet(analysisSecret(provider))?.takeIf { it.isNotBlank() }
            ?: transcriptionSecretFor(provider)?.let { engine.secretGet(it) }?.takeIf { it.isNotBlank() }

    private fun transcriptionSecretFor(provider: AiProvider): String? = when (provider) {
        AiProvider.Groq -> transcriptionSecret(TranscriptionProvider.Groq)
        AiProvider.OpenAI -> transcriptionSecret(TranscriptionProvider.OpenAI)
        else -> null
    }

    // --- Transkripsi API ----------------------------------------------------------------------------------------

    suspend fun transcription(): TranscriptionConfig? {
        val provider = TranscriptionProvider.from(dao.get(KEY_TR_PROVIDER)) ?: return null
        val baseUrl = dao.get(KEY_TR_BASE_URL)?.takeIf { it.isNotBlank() } ?: provider.baseUrl ?: return null
        val model = dao.get(KEY_TR_MODEL)?.takeIf { it.isNotBlank() } ?: provider.defaultModel ?: return null
        val key = engine.secretGet(transcriptionSecret(provider))?.takeIf { it.isNotBlank() }
        if (provider.requiresKey && key == null) return null
        return TranscriptionConfig(provider, baseUrl, model, key)
    }

    suspend fun rawTranscription(): Triple<TranscriptionProvider?, String?, String?> =
        Triple(TranscriptionProvider.from(dao.get(KEY_TR_PROVIDER)), dao.get(KEY_TR_BASE_URL), dao.get(KEY_TR_MODEL))

    suspend fun saveTranscription(provider: TranscriptionProvider, baseUrl: String?, model: String?, apiKey: String?) {
        dao.put(KEY_TR_PROVIDER, provider.value)
        dao.put(KEY_TR_BASE_URL, baseUrl?.trim()?.takeIf { it.isNotEmpty() })
        dao.put(KEY_TR_MODEL, model?.trim()?.takeIf { it.isNotEmpty() })
        if (!apiKey.isNullOrBlank()) engine.secretPut(transcriptionSecret(provider), apiKey.trim())
    }

    fun hasTranscriptionKey(provider: TranscriptionProvider) = engine.secretGet(transcriptionSecret(provider)) != null

    fun removeTranscriptionKey(provider: TranscriptionProvider) = engine.secretRemove(transcriptionSecret(provider))

    suspend fun autoTranscribe(): Boolean = dao.get(KEY_TR_AUTO) == "1"

    suspend fun setAutoTranscribe(enabled: Boolean) = dao.put(KEY_TR_AUTO, if (enabled) "1" else "0")

    private fun transcriptionSecret(provider: TranscriptionProvider) = "ai.transcription.${provider.value}.api_key"

    fun removeAnalysisKey(provider: AiProvider) = engine.secretRemove(analysisSecret(provider))

    suspend fun autoAnalyze(): Boolean = dao.get(KEY_AI_AUTO) == "1"

    suspend fun setAutoAnalyze(enabled: Boolean) = dao.put(KEY_AI_AUTO, if (enabled) "1" else "0")

    suspend fun promptTemplate(): String? = dao.get(KEY_PROMPT_TEMPLATE)?.takeIf { it.isNotBlank() }

    suspend fun promptMaxTokens(): Int? = dao.get(KEY_PROMPT_MAX_TOKENS)?.toIntOrNull()

    suspend fun savePrompt(template: String?, maxTokens: Int?) {
        dao.put(KEY_PROMPT_TEMPLATE, template?.takeIf { it.isNotBlank() })
        dao.put(KEY_PROMPT_MAX_TOKENS, maxTokens?.toString())
    }

    /** Semua gaya prompt: bawaan lalu buatan sendiri (dari cadangan Android). */
    suspend fun promptStyles(): List<PromptStyle> = PromptStyles.BUILT_IN + customPromptStyles()

    private suspend fun customPromptStyles(): List<PromptStyle> =
        dao.get(KEY_PROMPT_STYLES)?.let { runCatching { styleJson.decodeFromString(ListSerializer(PromptStyle.serializer()), it) }.getOrNull() }.orEmpty()

    suspend fun saveCustomPromptStyle(style: PromptStyle) {
        val list = customPromptStyles().filterNot { it.id == style.id } + style.copy(custom = true)
        dao.put(KEY_PROMPT_STYLES, styleJson.encodeToString(ListSerializer(PromptStyle.serializer()), list))
    }

    suspend fun deleteCustomPromptStyle(id: String) {
        dao.put(KEY_PROMPT_STYLES, styleJson.encodeToString(ListSerializer(PromptStyle.serializer()), customPromptStyles().filterNot { it.id == id }))
    }

    private val styleJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- Statistik ----------------------------------------------------------------------------------------------

    /** API key YouTube Data API (opsional): statistik YouTube resmi tanpa login. */
    fun youtubeKey(): String? = engine.secretGet(YOUTUBE_KEY)?.takeIf { it.isNotBlank() }

    fun saveYoutubeKey(key: String) = engine.secretPut(YOUTUBE_KEY, key.trim())

    fun removeYoutubeKey() = engine.secretRemove(YOUTUBE_KEY)

    private fun analysisSecret(provider: AiProvider) = "ai.analysis.${provider.value}.api_key"

    companion object {
        const val YOUTUBE_KEY = "stats.youtube_key"
        const val KEY_AI_PROVIDER = "ai.analysis.provider"
        const val KEY_AI_MODEL = "ai.analysis.model"
        const val KEY_AI_BASE_URL = "ai.analysis.base_url"
        const val KEY_AI_PRICE_INPUT = "ai.analysis.price_input"
        const val KEY_AI_PRICE_OUTPUT = "ai.analysis.price_output"
        const val KEY_AI_AUTO = "ai.analysis.auto"
        const val KEY_PROMPT_TEMPLATE = "prompt_template"
        const val KEY_PROMPT_STYLES = "prompt_styles"
        const val KEY_PROMPT_MAX_TOKENS = "prompt_max_tokens"
        const val KEY_TR_PROVIDER = "ai.transcription.provider"
        const val KEY_TR_BASE_URL = "ai.transcription.base_url"
        const val KEY_TR_MODEL = "ai.transcription.model"
        const val KEY_TR_AUTO = "ai.transcription.auto"
    }
}

/** Perkiraan token dan biaya Mode API. */
data class Estimate(val tokens: Int, val cost: Double?)

/**
 * Analisis Mode API di iPhone (padanan Analysis.start + AnalyzeWorker Android): prompt per bagian dikirim berurutan,
 * balasan disimpan sebagai kandidat, bagian yang selesai dicatat (bisa dilanjutkan), dan pemakaian token dicatat.
 * Berjalan selama aplikasi terbuka; satu project pada satu waktu.
 */
class IosAnalysis(private val repo: Repository, val settings: IosSettings, private val client: IosAiClient, private val scope: CoroutineScope) {
    private val projects get() = repo.db.projects()
    var active by mutableStateOf<Long?>(null)
        private set
    private var job: Job? = null

    /** Pengaturan Mode API yang siap dipakai, atau null bila belum lengkap. */
    suspend fun config(): AiConfig? = settings.analysis()

    /** Prompt project dengan template, batas panjang, dan gaya dari Pengaturan (sama dengan Android). */
    suspend fun prompts(project: ProjectEntity): List<String> = repo.prompts(project.id, settings)

    /** Gaya prompt project dan opsinya (sama dengan Android). */
    suspend fun promptStyle(project: ProjectEntity): Pair<PromptStyle, PromptOptions?> {
        val style = settings.promptStyles().firstOrNull { it.id == project.promptStyle } ?: PromptStyles.KAJIAN
        val options = project.promptOptions?.let { runCatching { optionsJson.decodeFromString(PromptOptions.serializer(), it) }.getOrNull() }
        return style to options
    }

    /** Menyimpan gaya prompt dan opsi untuk project (dipakai analisis berikutnya, otomatis maupun lewat chat AI). */
    suspend fun setPromptStyle(projectId: Long, styleId: String, options: PromptOptions?) {
        projects.modify(projectId) {
            it.copy(
                promptStyle = styleId.takeIf { id -> id != PromptStyles.KAJIAN.id },
                promptOptions = options?.takeIf { o -> !o.isEmpty() }?.let { o -> optionsJson.encodeToString(PromptOptions.serializer(), o) },
                updatedAt = nowMillis(),
            )
        }
    }

    private val optionsJson = Json { ignoreUnknownKeys = true }

    fun estimate(prompts: List<String>, config: AiConfig?, onlyParts: List<Int>? = null): Estimate {
        val counted = onlyParts?.mapNotNull { prompts.getOrNull(it) } ?: prompts
        val tokens = counted.sumOf { PromptBuilder.estimateTokens(it) }
        return Estimate(tokens, config?.cost(tokens, 3000 * counted.size))
    }

    fun start(projectId: Long, resume: Boolean = false) {
        if (active != null) return
        active = projectId
        job = scope.launch {
            try {
                projects.modify(projectId) { it.copy(analysisStatus = AnalysisStatus.QUEUED, analysisMessage = "Menyiapkan…") }
                run(projectId, resume)
            } finally {
                active = null
                job = null
            }
        }
    }

    /** Permintaan yang sedang dikirim tidak ditarik; analisis berhenti setelah balasannya datang. */
    suspend fun cancel(projectId: Long) {
        projects.modify(projectId) {
            if (AnalysisStatus.isActive(it.analysisStatus)) it.copy(analysisStatus = AnalysisStatus.CANCELLED, analysisMessage = "Analisis dibatalkan.") else it
        }
    }

    suspend fun dismiss(projectId: Long) {
        projects.modify(projectId) { if (AnalysisStatus.isActive(it.analysisStatus)) it else it.copy(analysisStatus = null, analysisMessage = null) }
    }

    suspend fun completedParts(projectId: Long, prompts: List<String>): List<Int> {
        val parts = projects.find(projectId)?.analysisParts ?: return emptyList()
        val data = runCatching { Json.parseToJsonElement(parts) as JsonObject }.getOrNull() ?: return emptyList()
        if (data["key"]?.jsonPrimitive?.content != fingerprint(prompts)) return emptyList()
        return (data["done"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.int }.getOrNull() }
    }

    private suspend fun saveParts(projectId: Long, prompts: List<String>, done: List<Int>) {
        val json = buildJsonObject {
            put("key", fingerprint(prompts))
            put("total", prompts.size)
            putJsonArray("done") { done.sorted().forEach { add(JsonPrimitive(it)) } }
        }.toString()
        projects.modify(projectId) { it.copy(analysisParts = json) }
    }

    /** Sidik prompt (SHA-1, sama dengan Android): transkrip, template, atau batas panjang yang berubah = bagian berbeda. */
    fun fingerprint(prompts: List<String>): String = sha1Hex(prompts.joinToString("\u0000").encodeToByteArray())

    private suspend fun isCancelled(projectId: Long) = projects.find(projectId)?.analysisStatus == AnalysisStatus.CANCELLED

    private suspend fun status(projectId: Long, status: String, message: String) {
        projects.modify(projectId) { it.copy(analysisStatus = status, analysisMessage = message, analysisHeartbeatAt = nowMillis()) }
    }

    private suspend fun run(projectId: Long, resume: Boolean) {
        val project = projects.find(projectId) ?: return
        val config = settings.analysis() ?: return status(projectId, AnalysisStatus.FAILED, "Mode API belum diatur. Pilih penyedia dan isi API key di Pengaturan.")
        val prompts = prompts(project)
        if (prompts.isEmpty()) return status(projectId, AnalysisStatus.FAILED, "Transkrip belum siap, jadi belum ada yang bisa dianalisis.")
        val total = prompts.size
        val done = (if (resume) completedParts(projectId, prompts) else emptyList()).toMutableList()
        var created = 0
        var skipped = 0
        var inputTokens = 0
        var outputTokens = 0
        val problems = mutableListOf<String>()
        fun label(i: Int) = if (total > 1) "Bagian ${i + 1} dari $total" else null

        try {
            for ((i, prompt) in prompts.withIndex()) {
                if (i in done) continue
                if (isCancelled(projectId)) break
                status(projectId, AnalysisStatus.RUNNING, "Menunggu balasan ${config.provider.label}" + (label(i)?.let { " (${it.replaceFirstChar(Char::lowercase)})" } ?: "") + "…")
                val reply = try {
                    client.complete(config, prompt)
                } catch (e: AiException) {
                    saveParts(projectId, prompts, done)
                    if (isCancelled(projectId)) return
                    return status(
                        projectId, AnalysisStatus.FAILED,
                        listOfNotNull(
                            (label(i)?.let { "$it: " } ?: "") + e.message,
                            if (done.isNotEmpty()) "${done.size} dari $total bagian sudah selesai dan tidak perlu diulang." else null,
                            if (created > 0) "$created kandidat baru sudah tersimpan." else null,
                        ).joinToString(" "),
                    )
                }
                inputTokens += reply.inputTokens
                outputTokens += reply.outputTokens
                try {
                    val result = repo.importReply(projectId, reply.text)
                    created += result.created
                    skipped += result.skipped
                    problems += result.errors
                    done += i
                } catch (e: AnalysisResultException) {
                    problems += (label(i)?.let { "$it: " } ?: "") + e.message
                }
                saveParts(projectId, prompts, done)
            }

            val remaining = total - done.size
            if (isCancelled(projectId)) {
                return status(
                    projectId, AnalysisStatus.CANCELLED,
                    "Analisis dibatalkan." +
                        (if (created > 0) " $created kandidat dari balasan yang sudah diterima tetap disimpan." else "") +
                        (if (done.isNotEmpty() && remaining > 0) " ${done.size} dari $total bagian sudah selesai; sisanya bisa dilanjutkan." else ""),
                )
            }
            val cost = config.cost(inputTokens, outputTokens)
            status(
                projectId, if (remaining == 0) AnalysisStatus.DONE else AnalysisStatus.FAILED,
                listOfNotNull(
                    "$created kandidat klip ditambahkan" + (if (skipped > 0) ", $skipped dilewati karena sudah ada" else "") + ".",
                    if (problems.isNotEmpty()) "${problems.size} ditolak: ${problems.take(3).joinToString("; ")}${if (problems.size > 3) "; …" else ""}." else null,
                    if (remaining > 0) "$remaining dari $total bagian belum berhasil; ketuk Lanjutkan untuk mengulang bagian itu saja." else null,
                    "${groupThousands(inputTokens)} token masuk, ${groupThousands(outputTokens)} token keluar" + (cost?.let { " (sekitar $" + dollars(it) + ")" } ?: "") + ".",
                ).joinToString(" "),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status(projectId, AnalysisStatus.FAILED, "Analisis gagal: ${e.message ?: "kesalahan tidak dikenal"}")
        } finally {
            if (inputTokens > 0 || outputTokens > 0) {
                repo.db.aiUsages().insert(
                    AiUsageEntity(
                        purpose = "analysis", projectId = project.id, projectTitle = project.title, provider = config.provider.value, model = config.model,
                        inputTokens = inputTokens, outputTokens = outputTokens, cost = config.cost(inputTokens, outputTokens),
                    ),
                )
            }
        }
    }

    /**
     * Setelah transkrip baru siap: dimulai sendiri hanya bila diaktifkan, Mode API sudah diatur, dan project belum punya
     * kandidat klip (sama dengan Android).
     */
    suspend fun startAutomatically(projectId: Long): Boolean {
        val project = projects.find(projectId) ?: return false
        if (!settings.autoAnalyze() || settings.analysis() == null || AnalysisStatus.isActive(project.analysisStatus) ||
            repo.db.clips().count(projectId) > 0 || repo.db.segments().count(projectId) == 0
        ) return false
        start(projectId)
        return true
    }

    companion object {
        /** Angka dengan titik ribuan, misalnya 12.345. */
        fun groupThousands(value: Int): String = value.toString().reversed().chunked(3).joinToString(".").reversed()

        /** Dolar 4 desimal di bawah 0,1, selain itu 2 desimal (titik desimal, sama dengan Android). */
        fun dollars(value: Double): String {
            val decimals = if (value < 0.1) 4 else 2
            var factor = 1L
            repeat(decimals) { factor *= 10 }
            val scaled = (value * factor).roundToLong()
            return "${scaled / factor}." + (scaled % factor).toString().padStart(decimals, '0')
        }
    }
}

/** SHA-1 (RFC 3174) dalam heksadesimal kecil; dipakai sidik prompt agar sama dengan Android (MessageDigest). */
fun sha1Hex(input: ByteArray): String {
    val h = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476, -0x3c2d1e10)
    val ml = input.size.toLong() * 8
    val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (ml ushr (8 * i)).toByte()
    val w = IntArray(80)
    for (chunk in padded.indices step 64) {
        for (i in 0 until 16) {
            w[i] = ((padded[chunk + 4 * i].toInt() and 0xFF) shl 24) or ((padded[chunk + 4 * i + 1].toInt() and 0xFF) shl 16) or
                ((padded[chunk + 4 * i + 2].toInt() and 0xFF) shl 8) or (padded[chunk + 4 * i + 3].toInt() and 0xFF)
        }
        for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        for (i in 0 until 80) {
            val (f, k) = when {
                i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                i < 60 -> ((b and c) or (b and d) or (c and d)) to -0x70e44324
                else -> (b xor c xor d) to -0x359d3e2a
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}
