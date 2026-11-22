package id.clipmi.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.ProjectEntity

/** Gambar kecil project: frame video (dibuat sekali, projects/<id>/thumb.jpg) atau thumbnail dari cadangan. */
@Composable
expect fun ProjectThumbnail(repo: Repository, engine: MediaEngine, project: ProjectEntity, modifier: Modifier)

/** Gambar kecil video hasil render (frame dibuat sekali, renders/<id>.jpg); kosong bila belum dirender. */
@Composable
expect fun ClipThumbnail(repo: Repository, engine: MediaEngine, clip: ClipEntity, modifier: Modifier)

/** Pemutar video sumber dengan kontrol bawaan iOS. */
@Composable
expect fun SourceVideo(path: String, modifier: Modifier)

/** Tanggal pendek bahasa Indonesia, misalnya "22 Nov". */
expect fun dayMonth(millis: Long): String

/** Gambar dari file JPEG/PNG [path] (absolut), diisi penuh; ikon film bila tidak ada. */
@Composable
expect fun FileThumbnail(path: String?, modifier: Modifier)

/** Font bawaan render (Fonts.file di [fontsDir]) sebagai FontFamily Compose untuk contoh huruf; bawaan sistem bila gagal. */
expect fun captionFontFamily(fontsDir: String, family: String): FontFamily

/** Contoh frame video (JPEG/PNG) untuk pratinjau gaya: gambar kecil project terbaru, atau gradasi buatan. */
@Composable
expect fun rememberSampleFrame(repo: Repository): String?

/** Pratinjau gaya subtitle: kalimat contoh dari AssWriter yang sama dengan render, digambar libass berulang. */
@Composable
expect fun CaptionStylePreview(renderer: IosRenderer, style: id.clipmi.core.render.CaptionStyle, sample: String?, modifier: Modifier)

/** Pratinjau gaya bingkai: satu frame dirender FFmpeg dengan filtergraph render, dihitung ulang setelah isian berhenti berubah. */
@Composable
expect fun FrameStylePreview(media: StyleMedia, frame: id.clipmi.core.render.FrameStyle, caption: id.clipmi.core.render.CaptionStyle, sample: String?, modifier: Modifier)
