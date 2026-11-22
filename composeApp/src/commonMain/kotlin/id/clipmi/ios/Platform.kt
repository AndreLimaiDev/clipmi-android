package id.clipmi.ios

/** Isi file teks, atau null bila tidak ada. */
expect fun readText(path: String): String?

/** Isi file, atau null bila tidak ada. */
expect fun readBytes(path: String): ByteArray?

expect fun writeText(path: String, text: String)

expect fun createDirectory(path: String)

expect fun nowMillis(): Long

/** Ukuran file dalam byte, 0 bila tidak ada. */
expect fun fileSize(path: String): Long

/** Menghapus file atau folder (beserta isinya); diam bila tidak ada. */
expect fun removeItem(path: String)

/** Menyalin file (tujuan lama diganti). */
expect fun copyItem(from: String, to: String)

/** Memindahkan file (tujuan lama diganti). */
expect fun moveItem(from: String, to: String)
