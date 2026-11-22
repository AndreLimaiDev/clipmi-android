package id.clipmi.core.analysis

/**
 * Merapikan balasan yang disalin dari aplikasi chat AI. Tombol salin ChatGPT kadang menyalin JSON sebagai teks Markdown
 * yang di-escape, bukan JSON mentah:
 * - web (desktop): tiap baris diakhiri `\` (ganti baris Markdown) dan spasi di awal baris menjadi `&#x20;`;
 * - aplikasi HP: kurung siku menjadi `\[` dan `\]`.
 *
 * Hanya dipakai bila [ClipImporter] gagal membaca balasan apa adanya (pembacaan utama tetap sama dengan desktop).
 */
object ChatPaste {
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|nbsp|quot|amp|lt|gt|apos);")
    private val ESCAPE = Regex("""\\([\s\S])""")
    private const val JSON_ESCAPES = "\"\\/bfnrtu"

    fun clean(text: String): String {
        val decoded = ENTITY.replace(text) { m ->
            when (val name = m.groupValues[1]) {
                "nbsp" -> " "
                "quot" -> "\""
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "apos" -> "'"
                else -> {
                    val code = if (name.startsWith("#x")) name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
                    code?.takeIf { id.clipmi.core.php.CodePoints.isValid(it) }?.let { id.clipmi.core.php.CodePoints.toText(it) } ?: m.value
                }
            }
        }
        // Escape Markdown (`\[`, `\_`, `\` di akhir baris) dibuang; escape JSON yang sah (`\"`, `\\`, `\n`, `\u…`) tetap.
        val unescaped = ESCAPE.replace(decoded) { m -> if (m.groupValues[1][0] in JSON_ESCAPES) m.value else m.groupValues[1] }
        // Spasi tak terputus dan spasi lebar nol bukan spasi JSON.
        return unescaped.replace('\u00A0', ' ').replace("\u200B", "").replace("\uFEFF", "")
    }
}
