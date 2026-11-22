package id.clipmi.ios

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Project sederhana tahap 2 (sebelum Room): video sumber dari YouTube di Documents/projects/<id>/source.mp4. */
@Serializable
data class IosProject(
    val id: Long,
    val title: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("video_id") val videoId: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("video_mb") val videoMb: Double? = null,
    val status: String = STATUS_DOWNLOADING,
    val error: String? = null,
    @SerialName("created_at") val createdAt: Long,
) {
    companion object {
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
    }
}

/** Daftar project disimpan sebagai JSON di Documents/projects.json. */
class ProjectStore(private val documents: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(IosProject.serializer())
    private val file get() = "$documents/projects.json"

    fun directory(id: Long): String = "$documents/projects/$id"

    fun load(): List<IosProject> =
        readText(file)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun save(projects: List<IosProject>) {
        writeText(file, json.encodeToString(serializer, projects))
    }
}

/** Isi file teks, atau null bila tidak ada. */
expect fun readText(path: String): String?

expect fun writeText(path: String, text: String)

expect fun createDirectory(path: String)

expect fun nowMillis(): Long
