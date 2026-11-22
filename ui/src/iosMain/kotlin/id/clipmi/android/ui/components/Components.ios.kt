package id.clipmi.android.ui.components

import androidx.compose.runtime.Composable
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
