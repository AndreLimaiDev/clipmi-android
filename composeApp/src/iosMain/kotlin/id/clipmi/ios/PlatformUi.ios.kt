package id.clipmi.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.AVFoundation.seekToTime
import platform.AVFoundation.rate
import platform.AVFoundation.play
import platform.AVFoundation.currentTime
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.VideoStatus
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import org.jetbrains.skia.Image as SkiaImage

@Composable
actual fun ProjectThumbnail(repo: Repository, engine: MediaEngine, project: ProjectEntity, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, project.id, project.videoStatus, project.thumbnailPath) {
        value = withContext(Dispatchers.Default) {
            val stored = project.thumbnailPath?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 }
            val path = stored ?: repo.absolute("${repo.projectDir(project.id)}/thumb.jpg")
            if (fileSize(path) <= 0) {
                val source = project.videoPath?.takeIf { project.video == VideoStatus.Ready }?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 }
                if (source != null) {
                    createDirectory(path.substringBeforeLast('/'))
                    engine.videoFrame(source, minOf(30.0, (project.durationSeconds ?: 60.0) * 0.1), path, 360)
                }
            }
            readBytes(path)?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
        }
    }
    Box(modifier.background(ClipMi.colors.panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(ClipMiIcons.Play, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
actual fun ClipThumbnail(repo: Repository, engine: MediaEngine, clip: ClipEntity, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, clip.id, clip.outputPath, clip.renderedAt) {
        value = withContext(Dispatchers.Default) {
            val output = clip.outputPath?.takeIf { !it.contains("://") }?.let { repo.absolute(it) }?.takeIf { fileSize(it) > 0 } ?: return@withContext null
            val thumb = output.substringBeforeLast('.') + "-${clip.renderedAt ?: 0}.jpg"
            if (fileSize(thumb) <= 0) engine.videoFrame(output, 0.5, thumb, 256)
            readBytes(thumb)?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(ClipMi.colors.panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(ClipMiIcons.Film, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SourceVideo(path: String, modifier: Modifier, playback: SourcePlayback?) {
    val controller = remember(path) { AVPlayerViewController().apply { player = AVPlayer(uRL = NSURL.fileURLWithPath(path)) } }
    DisposableEffect(controller) { onDispose { controller.player?.pause() } }
    if (playback != null) {
        var stopAt by remember { mutableStateOf<Double?>(null) }
        // Putar dari luar (ketuk segmen transkrip atau "Putar" di kartu klip).
        LaunchedEffect(playback.request) {
            val request = playback.request ?: return@LaunchedEffect
            val player = controller.player ?: return@LaunchedEffect
            player.seekToTime(CMTimeMakeWithSeconds(request.start, 600), CMTimeMake(0, 1), CMTimeMake(0, 1))
            player.play()
            stopAt = request.end
            playback.consumed()
        }
        // Waktu pemutar untuk menandai segmen yang sedang diputar, dan berhenti di akhir rentang klip.
        LaunchedEffect(controller) {
            while (true) {
                val player = controller.player
                if (player != null) {
                    val now = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() }
                    playback.position = if (player.rate > 0f || playback.position != null) now else null
                    val end = stopAt
                    if (end != null && now != null && now >= end) {
                        player.pause()
                        stopAt = null
                    }
                }
                delay(250)
            }
        }
    }
    UIKitViewController(factory = { controller }, modifier = modifier)
}

actual fun dayMonth(millis: Long): String = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "id_ID")
    dateFormat = "d MMM"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))

@Composable
actual fun FileThumbnail(path: String?, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.Default) {
            path?.let(::readBytes)?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
        }
    }
    Box(modifier.background(ClipMi.colors.panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(ClipMiIcons.Film, contentDescription = null, tint = ClipMi.colors.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}
