package id.clipmi.ios

import androidx.compose.runtime.Composable

/** Editor klip (iosMain: pratinjau AVPlayer + subtitle libass). */
@Composable
expect fun ClipEditorScreen(repo: Repository, renders: Renders, clipId: Long, onBack: () -> Unit, onEditStyle: (caption: Boolean, id: Long) -> Unit)
