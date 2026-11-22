package id.clipmi.core.render

import id.clipmi.core.php.Php
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Tampilan teks hook per klip (padanan `App\Render\HookLayout` desktop): kapan muncul, di mana (titik tengah dalam
 * persen layar; null = posisi bawaan gaya bingkai), model kotak/teks, warna, ukuran, sudut, dan animasi. Waktu dalam
 * detik sejak awal isi klip di video hasil; nilai negatif = di atas pembuka (intro atau kartu pembuka).
 */
@Serializable
data class HookLayout(
    val start: Double,
    val end: Double,
    val x: Int?,
    val y: Int?,
    val model: String,
    val color: String,
    val background: String,
    /** Transparansi kotak dalam satuan ASS (0 = pekat); null = tanpa kotak, teks bergaris tepi. */
    val alpha: Int?,
    val size: Int,
    val radius: Int,
    val animation: String,
    val opener: Boolean,
    val custom: Boolean,
) {
    data class Model(val label: String, val color: String, val background: String, val alpha: Int?)

    companion object {
        val MODELS: Map<String, Model> = linkedMapOf(
            "dark" to Model("Kotak gelap", "#FFFFFF", "#000000", 0x50),
            "light" to Model("Kotak terang", "#111111", "#FFFFFF", 0x14),
            "accent" to Model("Kotak warna", "#111111", "#FFD400", 0x00),
            "outline" to Model("Garis tepi", "#FFFFFF", "#000000", null),
        )

        val ANIMATIONS = linkedMapOf("fade" to "Muncul pelan", "pop" to "Membesar", "none" to "Langsung")

        /** Ukuran huruf di layar 9:16 selebar 1080. */
        const val SIZE = 62
        val SIZE_RANGE = 40..110

        /** Sudut kotak (piksel di layar selebar 1080); 0 = kotak bersudut tajam. */
        val RADIUS_RANGE = 0..48

        /** Durasi tampil terpendek (detik). */
        const val MIN_SECONDS = 0.5

        /** Lama kartu pembuka (detik). */
        const val OPENER_SECONDS = 2.0

        /** Hook bawaan di atas pembuka tampil paling tidak selama ini (detik). */
        const val LEAD_SECONDS = 2.0

        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        /** Tampilan lengkap untuk klip yang isinya [length] detik, dengan pembuka sepanjang [lead] detik sebelumnya. */
        fun resolve(stored: JsonObject?, length: Double, lead: Double = 0.0): HookLayout {
            val values = stored ?: JsonObject(emptyMap())
            val modelKey = values.text("model")?.takeIf { it in MODELS } ?: "dark"
            val preset = MODELS.getValue(modelKey)
            val len = maxOf(MIN_SECONDS, length)
            val pre = maxOf(0.0, lead)
            val timed = values.present("start")
            val (defaultStart, defaultEnd) = if (pre > 0) -pre to maxOf(0.0, LEAD_SECONDS - pre) else 0.0 to AssWriter.HOOK_SECONDS
            val start = maxOf(if (pre > 0) -pre else 0.0, minOf(len - MIN_SECONDS, values.float("start") ?: defaultStart))
            val end = maxOf(start + MIN_SECONDS, minOf(len, values.float("end") ?: (if (timed) start + AssWriter.HOOK_SECONDS else defaultEnd)))
            val percent = { key: String -> values.numeric(key)?.let { clamp(Php.roundInt(it), 0, 100) } }
            val x = percent("x")
            val y = percent("y")

            return HookLayout(
                start = Php.round(start, 2),
                end = Php.round(minOf(len, end), 2),
                // Posisi hanya berlaku berpasangan; satu saja berarti posisi bawaan.
                x = if (x != null && y != null) x else null,
                y = if (x != null && y != null) y else null,
                model = modelKey,
                color = color(values["color"]) ?: preset.color,
                background = color(values["background"]) ?: preset.background,
                alpha = preset.alpha,
                size = maxOf(SIZE_RANGE.first, minOf(SIZE_RANGE.last, values.int("size") ?: SIZE)),
                radius = maxOf(RADIUS_RANGE.first, minOf(RADIUS_RANGE.last, values.int("radius") ?: 0)),
                animation = values.text("animation")?.takeIf { it in ANIMATIONS } ?: "fade",
                opener = values.bool("opener") ?: false,
                custom = values.isNotEmpty(),
            )
        }

        private val MERGEABLE = setOf("start", "end", "x", "y", "model", "color", "background", "size", "radius", "animation", "opener")

        /** Nilai tersimpan setelah diubah sebagian; warna kembali ke warna model saat model diganti. */
        fun merge(stored: JsonObject?, changes: JsonObject): JsonObject {
            val result = LinkedHashMap(stored.orEmpty())
            val newModel = changes["model"]
            if (newModel != null && newModel !is JsonNull && (newModel as? JsonPrimitive)?.content != (stored?.text("model") ?: "dark")) {
                result.remove("color")
                result.remove("background")
            }
            for ((key, value) in changes) if (key in MERGEABLE) result[key] = value
            return JsonObject(result.filterValues { it !is JsonNull })
        }

        private fun color(value: JsonElement?): String? {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return if (COLOR.containsMatchIn(text)) text.uppercase() else null
        }
    }
}

// Pembacaan nilai JSON tersimpan dengan aturan PHP: isset (bukan null), (float), (int), (bool), is_numeric.

private fun JsonObject.present(key: String) = this[key].let { it != null && it !is JsonNull }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

private fun JsonObject.numeric(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.numeric()

private fun JsonObject.float(key: String): Double? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    value.booleanOrNull?.let { return if (it) 1.0 else 0.0 }
    return value.numeric() ?: 0.0
}

private fun JsonObject.int(key: String): Int? = float(key)?.let { if (it.isNaN()) 0 else it.toLong().toInt() }

private fun JsonObject.bool(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    value.booleanOrNull?.let { return it }
    value.doubleOrNull?.let { return it != 0.0 }
    return value.content.isNotEmpty() && value.content != "0"
}
