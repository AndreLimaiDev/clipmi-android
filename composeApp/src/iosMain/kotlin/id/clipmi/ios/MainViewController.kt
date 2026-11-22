package id.clipmi.ios

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Dipanggil dari Swift (iosApp): `MainViewControllerKt.MainViewController(engine:documents:)`. */
fun MainViewController(engine: MediaEngine, documents: String): UIViewController =
    ComposeUIViewController { App(engine, documents) }
