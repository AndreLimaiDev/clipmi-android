package id.clipmi.android.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Bagian Components yang memakai API Android (dipindah dari Components.kt saat tampilan dibagi dengan iOS).

@Composable
actual fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
actual fun isSideBySide(): Boolean = LocalConfiguration.current.let { it.screenWidthDp >= 600 && it.screenWidthDp > it.screenHeightDp }

@Composable
actual fun LightStatusIcons() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

/** Gambar dari file (thumbnail), didekode di latar dan diperkecil agar hemat memori. */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, maxSize: Int = 480, placeholder: ImageVector? = ClipMiIcons.Play) {
    var image by remember(file?.path, file?.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.path, file?.lastModified()) {
        image = withContext(Dispatchers.IO) {
            if (file == null || !file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    val c = ClipMi.colors
    Box(modifier.background(c.panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (placeholder != null) {
            Icon(placeholder, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.forLanguageTag("id"), bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
}
