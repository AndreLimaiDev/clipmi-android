package id.clipmi.android.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Penyedia AI untuk analisis Mode API (sama dengan `App\Ai\AiProvider` desktop). */
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
    /** Alamat API tanpa garis miring di akhir; untuk penyedia bawaan selalu alamat resminya. */
    fun endpoint(): String = (if (provider == AiProvider.Custom) baseUrl.orEmpty() else provider.baseUrl.orEmpty()).trimEnd('/')

    fun tokenPrices(): Pair<Double, Double>? = when {
        priceInput != null -> priceInput to (priceOutput ?: 0.0)
        provider == AiProvider.Anthropic -> KNOWN_PRICES[model]
        else -> null
    }

    fun cost(inputTokens: Int, outputTokens: Int): Double? =
        tokenPrices()?.let { (input, output) -> (inputTokens * input + outputTokens * output) / 1_000_000 }

    companion object {
        /** Harga per 1 juta token (USD) model Claude yang dikenal (sama dengan desktop). */
        val KNOWN_PRICES = mapOf(
            "claude-haiku-5-5" to (0.10 to 0.50),
            "claude-sonnet-5-5" to (2.00 to 10.00),
            "claude-opus-5-5" to (4.00 to 20.00),
        )
    }
}

data class AiReply(val text: String, val inputTokens: Int, val outputTokens: Int)

/**
 * Mengirim satu prompt ke penyedia AI dan mengembalikan teks balasannya (padanan `App\Ai\AiClient` desktop). Prompt
 * dan validasi JSON sama untuk semua penyedia; yang berbeda hanya bentuk permintaan HTTP-nya.
 */
class AiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT.toLong(), TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT.toLong(), TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    suspend fun complete(config: AiConfig, prompt: String): AiReply = when (config.provider) {
        AiProvider.Anthropic -> anthropic(config, prompt)
        AiProvider.Gemini -> gemini(config, prompt)
        else -> openAi(config, prompt)
    }

    /** Daftar model untuk tombol "Uji koneksi": tidak memakai token, tetapi memastikan alamat dan key diterima. */
    suspend fun models(config: AiConfig): List<String> {
        val url = (config.endpoint() + "/models").toHttpUrl().newBuilder()
            .addQueryParameter(if (config.provider == AiProvider.Gemini) "pageSize" else "limit", "1000").build()
        val body = send(config, timeoutSeconds = 15) { request(config).url(url).get().build() }
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
        val body = send(config) { request(config).url(config.endpoint() + "/messages").post(payload.toString().toRequestBody(jsonType)).build() }
        // Model Claude terbaru bisa menolak permintaan (HTTP 200) atau terpotong di batas token.
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
        val url = config.endpoint() + "/models/" + java.net.URLEncoder.encode(config.model, "UTF-8").replace("+", "%20") + ":generateContent"
        val body = send(config) { request(config).url(url).post(payload.toString().toRequestBody(jsonType)).build() }
        val candidate = (body["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            val blocked = ((body["promptFeedback"] as? JsonObject)?.string("blockReason"))
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
        val body = send(config) { request(config).url(config.endpoint() + "/chat/completions").post(payload.toString().toRequestBody(jsonType)).build() }
        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice?.string("finish_reason") == "length") throw AiException(TRUNCATED)
        val usage = body["usage"] as? JsonObject
        return AiReply((choice?.get("message") as? JsonObject)?.string("content").orEmpty(), usage.int("prompt_tokens"), usage.int("completion_tokens"))
    }

    /** Permintaan dengan header autentikasi sesuai penyedia. */
    private fun request(config: AiConfig): Request.Builder {
        val builder = Request.Builder().header("Accept", "application/json")
        val key = config.apiKey
        return when (config.provider) {
            AiProvider.Anthropic -> builder.header("x-api-key", key.orEmpty()).header("anthropic-version", "2023-06-01")
            AiProvider.Gemini -> builder.header("x-goog-api-key", key.orEmpty())
            else -> if (key != null) builder.header("Authorization", "Bearer $key") else builder
        }
    }

    /**
     * Mencoba ulang sekali untuk batas laju (429) dan gangguan server (5xx), lalu mengubah kegagalan menjadi
     * AiException berpesan Indonesia.
     */
    private suspend fun send(config: AiConfig, timeoutSeconds: Int = REQUEST_TIMEOUT, build: () -> Request): JsonObject =
        withContext(Dispatchers.IO) {
            val http = if (timeoutSeconds == REQUEST_TIMEOUT) client else client.newBuilder()
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS).build()
            var attempt = 1
            while (true) {
                val response: Response = try {
                    http.newCall(build()).execute()
                } catch (_: InterruptedIOException) {
                    throw AiException(
                        "${config.provider.label} tidak membalas dalam ${timeoutSeconds / 60} menit, jadi permintaan dihentikan. " +
                            "Server penyedia mungkin sedang sibuk; coba lagi beberapa saat lagi, pakai model yang lebih cepat, atau kecilkan batas panjang prompt di Pengaturan.",
                    )
                } catch (_: IOException) {
                    throw AiException("Tidak bisa terhubung ke ${config.provider.label}. Periksa koneksi internet atau alamat API.")
                }
                response.use {
                    val text = it.body.string()
                    if (it.isSuccessful) {
                        return@withContext runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse {
                            throw AiException("Balasan ${config.provider.label} bukan JSON yang dikenali.")
                        }
                    }
                    if (attempt >= 2 || !(it.code == 429 || it.code >= 500)) throw errorFrom(it.code, text, config.provider)
                    delay(minOf(30, maxOf(2, it.header("retry-after")?.toIntOrNull() ?: 0)) * 1000L)
                }
                attempt++
            }
            @Suppress("UNREACHABLE_CODE")
            JsonObject(emptyMap())
        }

    private fun errorFrom(code: Int, body: String, provider: AiProvider): AiException {
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val detailText = (json?.get("error") as? JsonObject)?.string("message") ?: json?.string("message") ?: body.trim().take(200)
        val detail = if (detailText.isNotEmpty()) " ($detailText)" else ""
        val message = when {
            // 403 juga dipakai penyedia untuk model yang diblokir di akun/project (misalnya pengaturan project Groq).
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

    companion object {
        /** Ruang untuk balasan JSON belasan klip plus pemikiran model yang ikut dihitung token keluar. */
        private const val MAX_OUTPUT_TOKENS = 16000

        /** Batas waktu satu permintaan analisis (detik). */
        const val REQUEST_TIMEOUT = 300

        private const val TRUNCATED = "Balasan AI terpotong karena terlalu panjang. Kurangi batas panjang prompt di Pengaturan supaya transkrip dibagi lebih kecil."
    }
}
