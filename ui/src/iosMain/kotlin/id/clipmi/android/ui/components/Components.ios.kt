package id.clipmi.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiTheme
import platform.UIKit.UIViewController
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// Padanan iOS bagian Components yang memakai API Android.

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun isWideScreen(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { size.width.toDp().value } >= 600
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun isSideBySide(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { size.width.toDp().value }
    val height = with(density) { size.height.toDp().value }
    return width >= 600 && width > height
}

/** iOS: warna ikon status bar mengikuti tema sistem (tidak diatur per layar). */
@Composable
actual fun LightStatusIcons() {}

/**
 * Dialog dan lembar asli iOS, didaftarkan aplikasi (composeApp, lewat MediaEngine di Swift). Selama belum didaftarkan,
 * [ClipMiSheet] dan [ConfirmDialog] memakai versi Material.
 */
object NativeUi {
    /** Tampilkan UIAlertController; [onAnswer] true = konfirmasi. Mengembalikan fungsi untuk menutupnya dari luar. */
    var presentAlert: ((title: String, message: String, confirm: String, destructive: Boolean, onAnswer: (Boolean) -> Unit) -> (() -> Unit))? = null

    /** Tampilkan [controller] sebagai lembar asli; [onDismissed] saat diusap tutup. Mengembalikan fungsi penutup. */
    var presentSheet: ((controller: UIViewController, onDismissed: () -> Unit) -> (() -> Unit))? = null

    /** CompositionLocal milik aplikasi (misalnya MediaEngine) untuk isi lembar, yang berjalan di jendela Compose sendiri. */
    var provide: @Composable (content: @Composable () -> Unit) -> Unit = { it() }
}

@Composable
actual fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean) {
    val present = NativeUi.presentAlert
    if (present == null) {
        MaterialConfirmDialog(title, body, confirmLabel, onConfirm, onDismiss, danger)
        return
    }
    val confirm by rememberUpdatedState(onConfirm)
    val cancel by rememberUpdatedState(onDismiss)
    DisposableEffect(title, body, confirmLabel, danger) {
        var answered = false
        val close = present(title, body, confirmLabel, danger) { ok ->
            answered = true
            if (ok) confirm() else cancel()
        }
        onDispose { if (!answered) close() }
    }
}

private class SheetContent(val title: String?, val subtitle: String?, val content: @Composable ColumnScope.() -> Unit)

/**
 * Lembar asli iOS: isi Compose di ComposeUIViewController tersendiri (tema dan Notifier dibawa dari layar asalnya),
 * ditampilkan dengan pegangan, setengah/penuh layar, dan usap ke bawah untuk menutup.
 */
@Composable
actual fun ClipMiSheet(onDismiss: () -> Unit, title: String?, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    val present = NativeUi.presentSheet
    if (present == null) {
        MaterialSheet(onDismiss, title, subtitle, content)
        return
    }
    val dark = ClipMi.colors.bg.luminance() < 0.5f
    val notifier = LocalNotifier.current
    val sheet = rememberUpdatedState(SheetContent(title, subtitle, content))
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        var closedByUser = false
        val controller = ComposeUIViewController {
            ClipMiTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalNotifier provides notifier) {
                    NativeUi.provide {
                        val current = sheet.value
                        Box(Modifier.fillMaxSize().background(ClipMi.colors.card)) {
                            SheetBody(current.title, current.subtitle, Modifier.padding(top = 16.dp).imePadding(), current.content)
                        }
                    }
                }
            }
        }
        val close = present(controller) {
            closedByUser = true
            dismiss()
        }
        onDispose { if (!closedByUser) close() }
    }
}

actual val BottomTabsAlways: Boolean = true
