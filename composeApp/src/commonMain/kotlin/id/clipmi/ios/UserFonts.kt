package id.clipmi.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.clipmi.core.render.Fonts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class FontException(message: String) : Exception(message)

/**
 * Font tambahan pengguna (TTF/OTF) untuk subtitle, padanan UserFonts Android: berkasnya di Documents/fonts (folder dan
 * daftar `user-fonts.json` yang sama dengan Android, jadi ikut cadangan), lalu digabung dengan font bawaan di satu folder
 * ([dir]) yang dipakai libass pratinjau, FFmpeg render, dan pengukur teks. Hanya dikenal di perangkat ini: Paket Standar
 * Tim dan ClipMI PC memakai font bawaan untuk gaya yang memakainya.
 */
class IosUserFonts(private val repo: Repository, private val engine: MediaEngine) {
    @Serializable
    data class Entry(val family: String, val file: String)

    /** Folder gabungan: font bawaan + font tambahan + daftarnya. */
    val dir: String get() = repo.absolute("tmp/fonts")

    private val userDir get() = repo.absolute("fonts")

    /** Daftar font tambahan (berubah setelah tambah/hapus, untuk UI). */
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    /** Menyamakan folder gabungan dengan font bawaan dan font tambahan, lalu memberi tahu libass. */
    fun sync() {
        createDirectory(dir)
        val bundle = engine.fontsDirectory()
        for (name in listDirectory(bundle)) {
            if (!name.endsWith(".ttf") && !name.endsWith(".otf")) continue
            val target = "$dir/$name"
            if (fileSize(target) != fileSize("$bundle/$name")) copyItem("$bundle/$name", target)
        }
        val list = read("$userDir/$REGISTRY")
        val wanted = list.map { it.file }.toSet()
        listDirectory(dir).filter { it.startsWith(PREFIX) && it !in wanted }.forEach { removeItem("$dir/$it") }
        for (entry in list) {
            val source = "$userDir/${entry.file}"
            val target = "$dir/${entry.file}"
            if (fileSize(source) > 0 && fileSize(target) != fileSize(source)) copyItem(source, target)
        }
        if (list.isEmpty()) removeItem("$dir/$REGISTRY") else writeText("$dir/$REGISTRY", json.encodeToString(serializer, list))
        entries = list
        engine.useFontsDirectory(dir)
    }

    /** Memilih font dari Files, membaca nama keluarganya, dan mendaftarkannya; null bila batal. */
    suspend fun pickAndAdd(): Entry? {
        createDirectory(repo.absolute("tmp"))
        val picked = suspendCancellableCoroutine<String?> { cont ->
            engine.pickFont(repo.absolute("tmp/font-pick.bin"), object : PickListener {
                override fun finished(path: String?) {
                    if (cont.isActive) cont.resume(path)
                }
            })
        } ?: return null
        try {
            if (fileSize(picked) > MAX_BYTES) throw FontException("File font terlalu besar (maksimal 20 MB).")
            val bytes = readBytes(picked) ?: throw FontException("File font tidak bisa dibuka.")
            val (family, otf) = readFamily(bytes)
            if (family in Fonts.AVAILABLE || family in Fonts.COVER) throw FontException("Font \"$family\" sudah ada di ClipMI.")
            val name = PREFIX + family.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "font" } + if (otf) ".otf" else ".ttf"
            createDirectory(userDir)
            val list = read("$userDir/$REGISTRY").filterNot { it.family == family }
            list.filter { it.file == name }.forEach { removeItem("$userDir/${it.file}") }
            copyItem(picked, "$userDir/$name")
            val entry = Entry(family, name)
            writeText("$userDir/$REGISTRY", json.encodeToString(serializer, list + entry))
            sync()
            return entry
        } finally {
            removeItem(picked)
        }
    }

    /** Menghapus font tambahan [family]; gaya yang memakainya jatuh ke font bawaan saat dirender. */
    fun remove(family: String) {
        val list = read("$userDir/$REGISTRY")
        list.filter { it.family == family }.forEach { removeItem("$userDir/${it.file}") }
        writeText("$userDir/$REGISTRY", json.encodeToString(serializer, list.filterNot { it.family == family }))
        sync()
    }

    companion object {
        const val REGISTRY = "user-fonts.json"
        private const val PREFIX = "user-"
        private const val MAX_BYTES = 20L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Entry.serializer())

        private fun read(path: String): List<Entry> =
            readText(path)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

        /** Berkas font untuk [family] di folder gabungan [fontsDir]: font tambahan bila ada, selain itu font bawaan. */
        fun file(fontsDir: String, family: String?): String {
            if (family != null && family !in Fonts.AVAILABLE && family !in Fonts.COVER) {
                read("$fontsDir/$REGISTRY").firstOrNull { it.family == family }?.let { return "$fontsDir/${it.file}" }
            }
            return "$fontsDir/${Fonts.file(family)}"
        }

        /**
         * Nama keluarga font (nameID 1 tabel `name`, yang dicocokkan libass) dan apakah berkasnya OpenType CFF (sama dengan
         * Android). Kumpulan font (TTC) dan berkas yang bukan font ditolak.
         */
        fun readFamily(b: ByteArray): Pair<String, Boolean> {
            fun u16(at: Int): Int {
                if (at < 0 || at + 2 > b.size) throw FontException("File font rusak.")
                return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            }
            fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
            if (b.size < 12) throw FontException("File ini bukan font.")
            val tag = u32(0)
            val otf = tag == 0x4F54544FL // "OTTO"
            if (tag == 0x74746366L) throw FontException("Kumpulan font (TTC) belum didukung. Pilih satu file TTF atau OTF.")
            if (tag != 0x00010000L && tag != 0x74727565L && !otf) throw FontException("File ini bukan font TTF atau OTF.")
            var nameTable = -1
            for (i in 0 until u16(4)) {
                val record = 12 + 16 * i
                if (record + 16 > b.size) break
                val name = (0 until 4).map { (b[record + it].toInt() and 0xFF).toChar() }.joinToString("")
                if (name == "name") nameTable = u32(record + 8).toInt()
            }
            if (nameTable < 0) throw FontException("Font ini tidak punya nama keluarga.")
            val count = u16(nameTable + 2)
            val strings = nameTable + u16(nameTable + 4)
            // (prioritas, nama): Windows Inggris, Windows lain, Unicode, Mac.
            val found = ArrayList<Pair<Int, String>>()
            for (i in 0 until count) {
                val r = nameTable + 6 + 12 * i
                val platform = u16(r)
                val language = u16(r + 4)
                if (u16(r + 6) != 1) continue
                val length = u16(r + 8)
                val start = strings + u16(r + 10)
                if (start < 0 || start + length > b.size) continue
                val bytes = b.copyOfRange(start, start + length)
                val (priority, text) = when (platform) {
                    3 -> (if (language == 0x409) 0 else 1) to utf16be(bytes)
                    0 -> 2 to utf16be(bytes)
                    1 -> 3 to bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
                    else -> continue
                }
                if (text.isNotBlank()) found += priority to text.trim()
            }
            val family = found.minByOrNull { it.first }?.second ?: throw FontException("Font ini tidak punya nama keluarga.")
            return family to otf
        }

        private fun utf16be(bytes: ByteArray): String = buildString {
            var i = 0
            while (i + 1 < bytes.size) {
                append((((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toChar())
                i += 2
            }
        }
    }
}
