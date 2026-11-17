package id.clipmi.android.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.clipmi.android.data.TranscriptStatus
import id.clipmi.android.data.VideoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Gambar dari file (thumbnail), didekode di latar dan diperkecil agar hemat memori. */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, maxSize: Int = 480) {
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
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Label status kecil berwarna, dipakai untuk status video dan transkrip. */
@Composable
fun StatusChip(label: String, tone: Tone) {
    val (bg, fg) = when (tone) {
        Tone.Good -> Color(0xFF15803D).copy(alpha = 0.15f) to Color(0xFF15803D)
        Tone.Busy -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) to MaterialTheme.colorScheme.primary
        Tone.Warn -> Color(0xFFB45309).copy(alpha = 0.15f) to Color(0xFFB45309)
        Tone.Bad -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f) to MaterialTheme.colorScheme.error
        Tone.Idle -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

enum class Tone { Good, Busy, Warn, Bad, Idle }

fun VideoStatus.tone() = when (this) {
    VideoStatus.Ready -> Tone.Good
    VideoStatus.Downloading, VideoStatus.Processing -> Tone.Busy
    VideoStatus.Failed -> Tone.Bad
    VideoStatus.Deleted -> Tone.Warn
    VideoStatus.Pending -> Tone.Idle
}

fun TranscriptStatus.tone() = when (this) {
    TranscriptStatus.Ready -> Tone.Good
    TranscriptStatus.Fetching -> Tone.Busy
    TranscriptStatus.NeedsTranscript -> Tone.Warn
    TranscriptStatus.Failed -> Tone.Bad
    TranscriptStatus.Pending -> Tone.Idle
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
}
