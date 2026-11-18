package id.clipmi.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ikon garis 24×24 dari mockup (gaya Lucide: garis 2, ujung bulat). Warna mengikuti `tint` Icon.
 * Dibuat sendiri agar tidak perlu material-icons-extended (besar).
 */
object ClipMiIcons {
    val Users by lazy { stroke(circle(9f, 8f, 3.5f), "M2.5 20a6.5 6.5 0 0 1 13 0M16 4.6a3.5 3.5 0 0 1 0 6.8M18 14.5a6.5 6.5 0 0 1 3.5 5.5") }
    val Clock by lazy { stroke(circle(12f, 12f, 9f), "M12 7v5l3 2") }
    val Bell by lazy { stroke("M6 9a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8", "M10.3 21a1.9 1.9 0 0 0 3.4 0") }
    val Upload by lazy { stroke("M12 15V3M7 8l5-5 5 5", "M5 15v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4") }
    val Home by lazy { stroke("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Calendar by lazy { stroke("M6 5h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M16 3v4M8 3v4M3 10h18") }
    val Chart by lazy { stroke("M5 20V11M12 20V5M19 20v-6") }
    val Sliders by lazy {
        stroke("M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1", circle(15f, 6f, 2f), circle(9f, 12f, 2f), circle(17f, 18f, 2f))
    }
    val Search by lazy { stroke(circle(11f, 11f, 7f), "m20 20-3.5-3.5") }
    val Plus by lazy { stroke("M12 5v14M5 12h14", width = 2.4f) }
    val Back by lazy { stroke("m15 18-6-6 6-6", width = 2.2f) }
    val Chevron by lazy { stroke("m9 18 6-6-6-6") }
    val More by lazy { fill(circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f)) }
    val Close by lazy { stroke("M6 6l12 12M18 6 6 18", width = 2.2f) }
    val Check by lazy { stroke("m5 12 5 5L20 7", width = 2.6f) }
    val Play by lazy { fill("M8 5v14l11-7z") }
    val Pause by lazy { fill("M7 5h4v14H7zM13 5h4v14h-4z") }
    val Undo by lazy { stroke("M9 14 4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-3") }
    val Redo by lazy { stroke("m15 14 5-5-5-5", "M20 9H9a5 5 0 0 0 0 10h3") }
    val Heart by lazy { stroke("M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z") }
    val Comment by lazy { stroke("M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.8-.9L3 21l1.9-5.2A8.4 8.4 0 1 1 21 11.5z") }
    val Send by lazy { stroke("m22 2-7 20-4-9-9-4z", "M22 2 11 13") }
    /** Lingkaran berisi centang: status siap. */
    val CircleCheck by lazy { stroke(circle(12f, 12f, 9.5f), "m8 12.5 2.7 2.7L16.5 9.5") }
    val Eye by lazy { stroke("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z") }
    val Magnet by lazy { stroke("M6 15V9a6 6 0 0 1 12 0v6", "M6 15a6 6 0 0 0 12 0", "M6 11h4M14 11h4") }
    val Link by lazy { stroke("M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1", "M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1") }
    val Image by lazy { stroke("M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z", circle(9f, 9f, 2f), "m21 15-5-5L5 21") }
    val Sparkle by lazy {
        stroke("M12 3l1.8 4.7 4.7 1.8-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z", "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z")
    }
    val Alert by lazy { stroke("M12 8v5M12 16.5v.5", circle(12f, 12f, 9f), width = 2.2f) }
    val Share by lazy { stroke("M12 3v12M7 8l5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6") }
    val Download by lazy { stroke("M12 3v12m-5-5 5 5 5-5M5 21h14") }
    val Trash by lazy { stroke("M4 7h16M10 11v6M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3") }
    val Copy by lazy {
        stroke("M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
    }
    val Edit by lazy { stroke("M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Book by lazy { stroke("M4 5a2 2 0 0 1 2-2h14v16H6a2 2 0 0 0-2 2z", "M4 19V5M8 7h8") }
    val Mic by lazy { stroke("M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0M12 18v3") }
    val Refresh by lazy { stroke("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7") }
    val Flask by lazy { stroke("M9 3h6M10 3v6l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3", "M7.5 14h9") }
    val Film by lazy { stroke("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4") }
    /** Sampul: kartu potret dengan judul. */
    val Cover by lazy { stroke("M7 2h10a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3z", "M8 15h8M8 18h5", "m4 12 4-4 4 4 3-3 5 5") }
    val Scissors by lazy { stroke(circle(6f, 6f, 3f), circle(6f, 18f, 3f), "M8.1 8.1 20 20M20 4 8.1 15.9") }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun stroke(vararg paths: String, width: Float = 2f) = build(paths, width, filled = false)

    private fun fill(vararg paths: String) = build(paths, 0f, filled = true)

    private fun build(paths: Array<out String>, width: Float, filled: Boolean): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { d ->
                if (filled) {
                    addPath(addPathNodes(d), fill = SolidColor(Color.Black))
                } else {
                    addPath(
                        addPathNodes(d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()
}
