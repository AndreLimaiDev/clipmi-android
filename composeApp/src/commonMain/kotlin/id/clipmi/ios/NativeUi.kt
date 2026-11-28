package id.clipmi.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Butir menu asli iOS (UIMenu atau lembar aksi). [symbol] = nama SF Symbol, misalnya "trash" atau "calendar".
 * Butir [destructive] tampil merah; [checked] diberi tanda centang.
 */
data class NativeMenuItem(
    val title: String,
    val symbol: String? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

internal fun List<NativeMenuItem>.toJson(): String = buildJsonArray {
    forEach { item ->
        add(buildJsonObject {
            put("title", item.title)
            item.symbol?.let { put("symbol", it) }
            put("destructive", item.destructive)
            put("enabled", item.enabled)
            put("checked", item.checked)
        })
    }
}.toString()

/** Warna Compose sebagai "#AARRGGBB" untuk UIKit. */
internal fun Color.toHex(): String = "#" + toArgb().toUInt().toString(16).padStart(8, '0').uppercase()

/** MediaEngine untuk komponen asli iOS yang dipanggil dari dalam layar (menu, lembar aksi, pemilih, haptik). */
val LocalMediaEngine = staticCompositionLocalOf<MediaEngine> { error("MediaEngine belum dipasang di akar aplikasi") }

/**
 * Tombol (ikon SF Symbol [symbol], bawaan ⋯) yang membuka menu asli iOS berisi [items]. [background] harus sama dengan
 * latar di sekitarnya (view UIKit persegi tampil di lubang lapisan Compose); [fill] = isi tombol bersudut membulat.
 */
@Composable
expect fun NativeMenuButton(
    items: List<NativeMenuItem>,
    background: Color,
    modifier: Modifier = Modifier,
    fill: Color = background,
    symbol: String = "ellipsis",
    tint: Color = Color.Gray,
    size: Dp = 36.dp,
)

/** Daftarkan dialog dan lembar asli iOS untuk komponen bersama (ClipMiSheet, ConfirmDialog) di modul :ui. */
expect fun installNativeUi(engine: MediaEngine)

private fun choice(items: List<NativeMenuItem>) = object : ChoiceListener {
    override fun chosen(index: Int) {
        items.getOrNull(index)?.takeIf { it.enabled }?.onClick?.invoke()
    }
}

/** Lembar aksi asli iOS (misalnya saat kartu ditekan lama). */
fun MediaEngine.actionSheet(title: String?, items: List<NativeMenuItem>) = showActionSheet(title, items.toJson(), choice(items))

/** Pemilih tanggal asli iOS. */
fun MediaEngine.pickDate(date: LocalDate, onPick: (LocalDate) -> Unit) =
    showDatePicker("date", date.year, date.month.number, date.day, 12, 0, object : DateListener {
        override fun picked(year: Int, month: Int, day: Int, hour: Int, minute: Int) = onPick(LocalDate(year, month, day))
    })

/** Pemilih jam 24 jam asli iOS. */
fun MediaEngine.pickTime(hour: Int, minute: Int, onPick: (Int, Int) -> Unit) =
    showDatePicker("time", 2000, 1, 1, hour, minute, object : DateListener {
        override fun picked(year: Int, month: Int, day: Int, hour: Int, minute: Int) = onPick(hour, minute)
    })
