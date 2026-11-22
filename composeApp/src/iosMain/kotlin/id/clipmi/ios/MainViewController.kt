package id.clipmi.ios

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeUIViewController
import id.clipmi.android.ui.theme.ClipMiFonts
import id.clipmi.android.data.openDatabase
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
    installFonts(engine)
    // Pemulihan cadangan yang sudah disiapkan diterapkan sebelum database dibuka.
    runCatching { IosBackup.applyPending(documents) }
    // Database Room yang sama dengan Android (modul shared), di Documents/clipmi.db.
    val repository = Repository(openDatabase(documents), documents)
    return ComposeUIViewController { App(repository, engine) }
}

/** Plus Jakarta Sans (res/font Android, di bundle sebagai font/) untuk tema bersama; gagal = font sistem. */
private fun installFonts(engine: MediaEngine) {
    val path = engine.fontsDirectory().substringBeforeLast('/') + "/font/plus_jakarta_sans.ttf"
    val bytes = readBytes(path) ?: return
    ClipMiFonts.install(
        FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
                Font("PlusJakartaSans-${weight.weight}", bytes, weight)
            },
        ),
    )
}
