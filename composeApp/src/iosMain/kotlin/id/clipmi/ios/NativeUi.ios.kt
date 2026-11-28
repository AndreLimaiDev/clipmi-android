package id.clipmi.ios

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import id.clipmi.android.ui.components.NativeUi
import platform.UIKit.UIView

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun NativeMenuButton(items: List<NativeMenuItem>, background: Color, modifier: Modifier, fill: Color, symbol: String, tint: Color, size: Dp) {
    val engine = LocalMediaEngine.current
    val latest by rememberUpdatedState(items)
    // Satu pendengar per tombol; memanggil daftar butir terbaru, jadi aksi selalu memakai keadaan layar sekarang.
    val listener = remember {
        object : ChoiceListener {
            override fun chosen(index: Int) {
                latest.getOrNull(index)?.takeIf { it.enabled }?.onClick?.invoke()
            }
        }
    }
    val json = items.toJson()
    val tintHex = tint.toHex()
    val backgroundHex = background.toHex()
    val fillHex = fill.toHex()
    UIKitView(
        factory = { engine.makeMenuButton(symbol, tintHex, backgroundHex, fillHex, json, listener) as UIView },
        modifier = modifier.size(size),
        update = { engine.updateMenuButton(it, symbol, tintHex, backgroundHex, fillHex, json, listener) },
        // Ketukan langsung ke tombol UIKit (tanpa jeda 150 ms); daftar tetap bisa digulir dari luar tombol.
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true),
    )
}

actual fun installNativeUi(engine: MediaEngine) {
    NativeUi.presentAlert = { title, message, confirm, destructive, onAnswer ->
        val token = engine.showAlert(title, message, confirm, destructive, object : ChoiceListener {
            override fun chosen(index: Int) = onAnswer(index == 1)
        })
        val close: () -> Unit = { engine.dismissPresented(token) }
        close
    }
    NativeUi.presentSheet = { controller, onDismissed ->
        val token = engine.presentSheet(controller, object : ChoiceListener {
            override fun chosen(index: Int) = onDismissed()
        })
        val close: () -> Unit = { engine.dismissPresented(token) }
        close
    }
    NativeUi.provide = { content -> CompositionLocalProvider(LocalMediaEngine provides engine) { content() } }
}
