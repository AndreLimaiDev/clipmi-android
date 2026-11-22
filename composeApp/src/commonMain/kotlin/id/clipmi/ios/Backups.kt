package id.clipmi.ios

import androidx.compose.runtime.Composable

/** Layar Cadangkan & Pulihkan (file ZIP yang sama dengan Android). */
@Composable
expect fun BackupScreen(repo: Repository, engine: MediaEngine, onBack: () -> Unit)

/** Langkah setelah pemulihan (dipanggil saat aplikasi dibuka); pesan untuk pengguna, atau null bila tidak ada pemulihan. */
expect suspend fun finishRestore(repo: Repository): String?
