package id.clipmi.ios

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/** Dipanggil dari Swift (iosApp): `MainViewControllerKt.MainViewController(engine:documents:)`. */
@OptIn(ExperimentalNativeApi::class)
fun MainViewController(engine: MediaEngine, documents: String): UIViewController {
    // Tanpa Xcode tidak ada log crash: error Kotlin yang tidak tertangani ditulis ke Documents/crash.txt
    // (terlihat di aplikasi Files) sebelum aplikasi berhenti.
    setUnhandledExceptionHook { error ->
        writeText("$documents/crash.txt", "ClipMI iOS crash ${nowMillis()}\n${error.stackTraceToString()}")
    }
    return ComposeUIViewController { App(engine, documents) }
}
