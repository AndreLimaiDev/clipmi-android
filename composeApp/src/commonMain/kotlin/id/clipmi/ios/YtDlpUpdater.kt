package id.clipmi.ios

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Pembaruan yt-dlp iOS (padanan tombol Perbarui Android): versi terbaru dari PyPI disimpan di Documents/python-updates
 * dan langsung dipakai tanpa memasang ulang aplikasi (`clipmi_youtube.update`). Versi bawaan yang lebih baru menang.
 */
class YtDlpUpdater(private val engine: MediaEngine) {
    data class Info(val version: String?, val updated: Boolean)

    sealed interface Result {
        data class Updated(val version: String) : Result
        data class UpToDate(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun info(): Info {
        val o = call("version") ?: return Info(null, false)
        return Info(o["version"]?.jsonPrimitive?.contentOrNull, o["updated"]?.jsonPrimitive?.booleanOrNull == true)
    }

    suspend fun update(): Result {
        val o = call("update") ?: return Result.Failed("Python tidak menjawab.")
        if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return Result.Failed(o["error"]?.jsonPrimitive?.contentOrNull ?: "sebab tidak diketahui")
        val version = o["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (o["status"]?.jsonPrimitive?.contentOrNull == "up_to_date") Result.UpToDate(version) else Result.Updated(version)
    }

    /** Hapus unduhan; kembali ke yt-dlp bawaan aplikasi. */
    suspend fun reset(): String? = call("reset")?.get("version")?.jsonPrimitive?.contentOrNull

    private suspend fun call(function: String): JsonObject? = suspendCancellableCoroutine<String> { cont ->
        engine.pythonCall("clipmi_youtube", function, emptyList(), object : ResultListener {
            override fun finished(resultJson: String) {
                if (cont.isActive) cont.resume(resultJson)
            }
        })
    }.let { text -> runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() }
}
