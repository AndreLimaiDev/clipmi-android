package id.clipmi.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
