package id.clipmi.android.domain

import android.content.Context
import android.net.Uri
import id.clipmi.core.render.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

class FontException(message: String) : Exception(message)

/**
 * Font tambahan pengguna (TTF/OTF) untuk subtitle. Berkasnya di `workspace/fonts` (ikut Cadangkan) dan disalin ke folder
 * font libass ([id.clipmi.android.nativebridge.AssRenderer.installFonts]), jadi pratinjau dan render menemukannya lewat
 * nama keluarga di dalam berkas (sama seperti font bawaan). Hanya dikenal di HP ini: ClipMI PC dan Paket Standar Tim
 * tidak mengenalnya, jadi gaya yang memakainya diekspor dengan font bawaan.
 */
object UserFonts {
    @Serializable
    data class Entry(val family: String, val file: String)

    /** Daftar font tambahan, di workspace dan di folder font libass. */
    const val REGISTRY = "user-fonts.json"
    private const val PREFIX = "user-"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(context: Context): File = File(context.filesDir, "workspace/fonts")

    fun list(context: Context): List<Entry> = read(File(dir(context), REGISTRY))

    /** Font tambahan untuk [family] di folder font libass [fontsDir], atau null bila bukan font tambahan. */
    fun entry(fontsDir: File, family: String?): Entry? {
        if (family == null || family in Fonts.AVAILABLE || family in Fonts.COVER) return null
        return read(File(fontsDir, REGISTRY)).firstOrNull { it.family == family }
    }

    /** Berkas font untuk [family]: font tambahan bila ada, selain itu font bawaan (atau font bawaan utama). */
    fun file(fontsDir: File, family: String?): File = entry(fontsDir, family)?.let { File(fontsDir, it.file) } ?: File(fontsDir, Fonts.file(family))

    /** Menyalin font dari [uri], membaca nama keluarganya, dan mendaftarkannya. Nama yang sudah ada diganti berkasnya. */
    suspend fun add(context: Context, uri: Uri): Entry = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        val temp = File(dir, "tambah.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw FontException("File font tidak bisa dibuka.")
            input.use { stream ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw FontException("File font terlalu besar (maksimal 20 MB).")
                        out.write(buffer, 0, read)
                    }
                }
            }
            val (family, otf) = readFamily(temp)
            if (family in Fonts.AVAILABLE || family in Fonts.COVER) throw FontException("Font \"$family\" sudah ada di ClipMI.")
            val name = PREFIX + family.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "font" } + if (otf) ".otf" else ".ttf"
            val entries = list(context).filterNot { it.family == family }
            entries.filter { it.file == name }.forEach { File(dir, it.file).delete() }
            temp.copyTo(File(dir, name), overwrite = true)
            val entry = Entry(family, name)
            write(dir, entries + entry)
            entry
        } finally {
            temp.delete()
        }
    }

    /** Menghapus font tambahan [family]; gaya yang memakainya jatuh ke font bawaan saat dirender. */
    suspend fun remove(context: Context, family: String) = withContext(Dispatchers.IO) {
        val dir = dir(context)
        val entries = list(context)
        entries.filter { it.family == family }.forEach { File(dir, it.file).delete() }
        write(dir, entries.filterNot { it.family == family })
    }

    /** Menyamakan font tambahan di folder font libass [fontsDir] dengan workspace (tambah, ganti, hapus). */
    fun sync(context: Context, fontsDir: File) {
        val dir = dir(context)
        val entries = list(context)
        val wanted = entries.map { it.file }.toSet()
        fontsDir.listFiles().orEmpty().filter { it.name.startsWith(PREFIX) && it.name !in wanted }.forEach { it.delete() }
        entries.forEach { entry ->
            val source = File(dir, entry.file)
            val target = File(fontsDir, entry.file)
            if (source.isFile && (!target.isFile || target.length() != source.length() || target.lastModified() < source.lastModified())) source.copyTo(target, overwrite = true)
        }
        val registry = File(fontsDir, REGISTRY)
        if (entries.isEmpty()) registry.delete() else registry.writeText(json.encodeToString(ListSerializer, entries))
    }

    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Entry.serializer())

    private fun read(file: File): List<Entry> =
        if (!file.isFile) emptyList() else runCatching { json.decodeFromString(ListSerializer, file.readText()) }.getOrDefault(emptyList())

    private fun write(dir: File, entries: List<Entry>) {
        File(dir, REGISTRY).writeText(json.encodeToString(ListSerializer, entries))
    }

    /**
     * Nama keluarga font (nameID 1 tabel `name`, yang dicocokkan libass) dan apakah berkasnya OpenType CFF. Kumpulan
     * font (TTC) dan berkas yang bukan font ditolak.
     */
    private fun readFamily(file: File): Pair<String, Boolean> = RandomAccessFile(file, "r").use { f ->
        fun u16(at: Long): Int { f.seek(at); return f.readUnsignedShort() }
        fun u32(at: Long): Long { f.seek(at); return f.readInt().toLong() and 0xFFFFFFFFL }
        if (f.length() < 12) throw FontException("File ini bukan font.")
        val tag = u32(0)
        val otf = tag == 0x4F54544FL // "OTTO"
        if (tag == 0x74746366L) throw FontException("Kumpulan font (TTC) belum didukung. Pilih satu file TTF atau OTF.")
        if (tag != 0x00010000L && tag != 0x74727565L && !otf) throw FontException("File ini bukan font TTF atau OTF.")
        var nameTable = -1L
        for (i in 0 until u16(4)) {
            val record = 12L + 16 * i
            f.seek(record)
            val name = ByteArray(4).also(f::readFully).toString(Charsets.ISO_8859_1)
            if (name == "name") nameTable = u32(record + 8)
        }
        if (nameTable < 0) throw FontException("Font ini tidak punya nama keluarga.")
        val count = u16(nameTable + 2)
        val strings = nameTable + u16(nameTable + 4)
        // (prioritas, nama): Windows Inggris, Windows lain, Unicode, Mac.
        val found = ArrayList<Pair<Int, String>>()
        for (i in 0 until count) {
            val r = nameTable + 6 + 12L * i
            val platform = u16(r)
            val language = u16(r + 4)
            if (u16(r + 6) != 1) continue
            val length = u16(r + 8)
            f.seek(strings + u16(r + 10))
            val bytes = ByteArray(length).also(f::readFully)
            val (priority, text) = when (platform) {
                3 -> (if (language == 0x409) 0 else 1) to bytes.toString(Charsets.UTF_16BE)
                0 -> 2 to bytes.toString(Charsets.UTF_16BE)
                1 -> 3 to bytes.toString(Charsets.ISO_8859_1)
                else -> continue
            }
            if (text.isNotBlank()) found += priority to text.trim()
        }
        val family = found.minByOrNull { it.first }?.second ?: throw FontException("Font ini tidak punya nama keluarga.")
        family to otf
    }
}
