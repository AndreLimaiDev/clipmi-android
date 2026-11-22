package id.clipmi.ios

/** Isi file teks, atau null bila tidak ada. */
expect fun readText(path: String): String?

/** Isi file, atau null bila tidak ada. */
expect fun readBytes(path: String): ByteArray?

expect fun writeText(path: String, text: String)

expect fun createDirectory(path: String)

expect fun nowMillis(): Long
