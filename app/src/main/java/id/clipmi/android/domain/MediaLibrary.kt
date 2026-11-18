package id.clipmi.android.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.data.BumperEntity
import id.clipmi.core.php.Php
import id.clipmi.core.php.Str
import id.clipmi.core.render.TemplateFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MediaRejected(message: String) : Exception(message)

/**
 * Gambar dan video dari galeri HP untuk gaya bingkai (logo, latar, template), sampul klip, dan pustaka intro/outro.
 * Semua disalin ke workspace; gambar disimpan ulang sebagai PNG (logo, template: kanal transparan tetap) atau JPEG
 * (latar, sampul), karena FFmpeg di HP tidak membawa decoder WebP/HEIC.
 */
class MediaLibrary(
    private val context: Context,
    private val database: AppDatabase,
    private val workspace: Workspace,
    private val ffmpeg: Ffmpeg,
) {
    /** Jenis gambar, sisi terpanjang maksimum, dan formatnya. */
    enum class ImageKind(val prefix: String, val maxSide: Int, val png: Boolean, val maxBytes: Long) {
        Logo("frame", 1024, true, 2L * 1024 * 1024),
        Background("background", 2160, false, 5L * 1024 * 1024),
        Template("template", 2160, true, 5L * 1024 * 1024),
        Cover("upload", 2160, false, 10L * 1024 * 1024),
    }

    data class TemplateImage(val path: String, val aspect: String, val area: id.clipmi.core.render.TemplateArea)

    /** Menyimpan gambar pilihan ke [folder] workspace; mengembalikan path relatifnya. */
    suspend fun importImage(uri: Uri, kind: ImageKind, folder: String = "branding"): String = withContext(Dispatchers.IO) {
        val size = querySize(uri)
        if (size != null && size > kind.maxBytes) throw MediaRejected("Ukuran gambar maksimal ${kind.maxBytes / 1024 / 1024} MB.")
        val bitmap = decode(uri, kind.maxSide)
        try {
            save(bitmap, kind, folder)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Template baru: area transparannya langsung dicari, dan rasio hasil mengikuti ukuran gambarnya
     * (`FrameStyleForm::updatedTemplateImage` desktop).
     */
    suspend fun importTemplate(uri: Uri): TemplateImage = withContext(Dispatchers.IO) {
        val size = querySize(uri)
        if (size != null && size > ImageKind.Template.maxBytes) throw MediaRejected("Ukuran template maksimal 5 MB.")
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == "image/jpeg") throw MediaRejected("Template harus berupa gambar PNG atau WebP berlatar transparan.")
        // Rasio dari ukuran asli; area dari kanal alpha (gambar besar diperkecil dulu, cukup untuk kisi 200×200).
        val (width, height) = bounds(uri)
        val bitmap = decode(uri, ImageKind.Template.maxSide)
        try {
            if (!bitmap.hasAlpha()) throw MediaRejected("Template ini tidak punya area transparan untuk video. Simpan desainnya sebagai PNG atau WebP dengan bagian video dikosongkan (transparan).")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) pixels[i] = pixels[i] ushr 24
            val analysis = try {
                TemplateFrame.analyze(pixels, bitmap.width, bitmap.height)
            } catch (e: TemplateFrame.Rejected) {
                throw MediaRejected(e.message.orEmpty())
            }
            TemplateImage(save(bitmap, ImageKind.Template, "branding"), TemplateFrame.aspectFor(width, height), analysis.area)
        } finally {
            bitmap.recycle()
        }
    }

    /** Salinan gambar gaya lain untuk gaya baru, supaya menghapus salah satu gaya tidak menghapus gambar yang lain. */
    fun copyImage(path: String?): String? {
        val file = workspace.file(path)?.takeIf { it.isFile } ?: return null
        val copy = File(file.parentFile, file.nameWithoutExtension.substringBeforeLast('-') + "-" + random() + "." + file.extension)
        file.copyTo(copy)
        return workspace.relative(copy)
    }

    fun delete(path: String?) {
        workspace.file(path)?.delete()
    }

    /**
     * Video baru di pustaka intro/outro (`Bumper::upload` desktop): durasinya dibaca (maksimal [BumperEntity.MAX_SECONDS]),
     * lalu gambar kecilnya dibuat dari frame di sekitar detik pertama.
     */
    suspend fun importBumper(kind: String, uri: Uri): BumperEntity = withContext(Dispatchers.IO) {
        val label = BumperEntity.KINDS.getValue(kind)
        val name = displayName(uri)
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty().ifEmpty { "mp4" }
        if (extension !in listOf("mp4", "mov", "m4v")) throw MediaRejected("$label harus berupa video MP4 atau MOV.")

        val dir = File(workspace.root, "bumpers").apply { mkdirs() }
        val file = File(dir, "$kind-${random(10)}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw MediaRejected("Video ini tidak bisa dibuka.")
        try {
            val codec = videoCodec(file)
            if (codec == null || codec !in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                throw MediaRejected("Video ini tidak bisa dibaca. Pakai MP4 (H.264) atau MOV.")
            }
            val duration = MediaInfo.durationSeconds(file)
            if (duration == null || duration <= 0) throw MediaRejected("Video ini tidak bisa dibaca. Pakai MP4 (H.264) atau MOV.")
            if (duration > BumperEntity.MAX_SECONDS) {
                throw MediaRejected("$label maksimal ${BumperEntity.MAX_SECONDS} detik; video ini ${duration.roundToInt()} detik.")
            }
            val title = Str.limit(Php.trim(name?.substringBeforeLast('.').orEmpty()).ifEmpty { label }, 60, "")
            val bumper = BumperEntity(kind = kind, name = title, path = workspace.relative(file), duration = Php.round(duration, 3))
            val id = database.bumpers().insert(bumper)
            val thumbnail = thumbnail(file, id, kind, duration)
            database.bumpers().find(id)!!.copy(thumbnailPath = thumbnail).also { database.bumpers().update(it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Menghapus dari pustaka beserta filenya; klip dan project yang memakainya kembali tanpa intro/outro. */
    suspend fun removeBumper(bumper: BumperEntity) = withContext(Dispatchers.IO) {
        delete(bumper.path)
        delete(bumper.thumbnailPath)
        database.bumpers().delete(bumper)
    }

    private suspend fun thumbnail(file: File, id: Long, kind: String, duration: Double): String? {
        val output = File(file.parentFile, "$kind-$id-thumb.jpg")
        return runCatching {
            ffmpeg.run(
                listOf(
                    "-y", "-ss", Php.numberFormat(minOf(1.0, duration / 2), 3), "-i", file.absolutePath,
                    "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "5", output.absolutePath,
                ),
            )
            output.takeIf { it.isFile && it.length() > 0 }?.let(workspace::relative)
        }.getOrNull()
    }

    private fun videoCodec(file: File): String? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.firstOrNull { it?.startsWith("video/") == true }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun save(bitmap: Bitmap, kind: ImageKind, folder: String): String {
        val dir = File(workspace.root, folder).apply { mkdirs() }
        val file = File(dir, "${kind.prefix}-${random()}.${if (kind.png) "png" else "jpg"}")
        file.outputStream().use { bitmap.compress(if (kind.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        return workspace.relative(file)
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toDouble() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
    } catch (e: Exception) {
        throw MediaRejected("Gambar ini tidak bisa dibaca. Pakai PNG, JPG, atau WebP.")
    }

    private fun bounds(uri: Uri): Pair<Int, Int> {
        var size = 0 to 0
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                size = info.size.width to info.size.height
                decoder.setTargetSize(1, 1)
            }.recycle()
        }
        return size
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun random(length: Int = 6): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
