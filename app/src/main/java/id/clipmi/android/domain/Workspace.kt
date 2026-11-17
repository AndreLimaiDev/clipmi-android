package id.clipmi.android.domain

import android.content.Context
import java.io.File

/**
 * Folder kerja aplikasi (padanan disk "workspace" desktop): `files/workspace/projects/{id}/...`. Path di database
 * relatif terhadap folder ini, supaya cadangan bisa dipulihkan di HP lain. Penyimpanan internal: tidak terlihat
 * aplikasi lain dan ikut terhapus saat aplikasi dicopot; hasil render nanti ditulis ke Movies/ClipMI.
 */
class Workspace(context: Context) {
    val root: File = File(context.filesDir, "workspace").apply { mkdirs() }

    fun projectDir(projectId: Long): File = File(root, "projects/$projectId").apply { mkdirs() }

    /** Folder hasil yt-dlp (info.json, subtitle, thumbnail); dikosongkan setiap kali metadata diambil ulang. */
    fun youtubeDir(projectId: Long): File = File(projectDir(projectId), "youtube")

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    fun file(relativePath: String?): File? = relativePath?.let { File(root, it) }

    fun projectSize(projectId: Long): Long =
        File(root, "projects/$projectId").walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteProject(projectId: Long) {
        File(root, "projects/$projectId").deleteRecursively()
    }

    /** Folder hasil per potongan transkripsi API; tetap ada sampai selesai supaya bisa dilanjutkan. */
    fun transcribeDir(projectId: Long): File = File(root, "tmp/transcribe-$projectId")
}
