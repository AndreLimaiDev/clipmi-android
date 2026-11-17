package id.clipmi.android.lab

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan uji Fase 0 di folder yang bisa diambil dari PC tanpa root:
 *   adb pull /sdcard/Android/data/id.clipmi.android/files/lab
 * lab.log = log baca-manusia, results.jsonl = satu baris JSON per hasil uji.
 */
class LabLog(context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "lab").apply { mkdirs() }
    private val log = File(dir, "lab.log")
    private val results = File(dir, "results.jsonl")
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun line(message: String) {
        val text = "${clock.format(Date())} $message"
        android.util.Log.i("ClipMiLab", text)
        log.appendText(text + "\n")
    }

    @Synchronized
    fun result(test: String, values: Map<String, Any?>) {
        val json = JsonObject(
            mapOf("test" to JsonPrimitive(test), "at" to JsonPrimitive(System.currentTimeMillis())) +
                values.mapValues { (_, v) ->
                    when (v) {
                        null -> JsonPrimitive(null as String?)
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                },
        )
        results.appendText(json.toString() + "\n")
        line("HASIL $test: " + values.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    fun tail(lines: Int = 200): String = if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else ""

    fun clear() {
        log.delete()
    }
}
