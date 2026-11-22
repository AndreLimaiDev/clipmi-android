package id.clipmi.ios

/** Isi file teks, atau null bila tidak ada. */
expect fun readText(path: String): String?

/** Isi file, atau null bila tidak ada. */
expect fun readBytes(path: String): ByteArray?

expect fun writeText(path: String, text: String)

expect fun writeBytes(path: String, bytes: ByteArray)

expect fun createDirectory(path: String)

expect fun nowMillis(): Long

/** Ukuran file dalam byte, 0 bila tidak ada. */
expect fun fileSize(path: String): Long

/** Menghapus file atau folder (beserta isinya); diam bila tidak ada. */
expect fun removeItem(path: String)

/** Menyalin file (tujuan lama diganti). */
expect fun copyItem(from: String, to: String)

/** Nama isi folder (tanpa path), kosong bila folder tidak ada. */
expect fun listDirectory(path: String): List<String>

/** Memindahkan file (tujuan lama diganti). */
expect fun moveItem(from: String, to: String)

/** Ukuran semua file di dalam folder [path] (rekursif), dalam byte. */
expect fun directorySize(path: String): Long

/** Sisa ruang penyimpanan di volume [path], dalam byte. */
expect fun freeSpace(path: String): Long
