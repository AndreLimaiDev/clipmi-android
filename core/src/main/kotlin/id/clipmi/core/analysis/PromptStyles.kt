package id.clipmi.core.analysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gaya prompt analisis (khusus Android): kriteria memilih momen sesuai jenis video. Bagian format (ID segmen, JSON,
 * score, keywords) sama untuk semua gaya, jadi balasan tetap dibaca [ClipImporter] yang sama. Gaya [KAJIAN] tanpa opsi
 * menghasilkan [PromptBuilder.DEFAULT_TEMPLATE] persis (prompt ClipMI desktop).
 *
 * Teks kriteria memakai `{durasi}` (rentang durasi), `{maks}` (batas atas durasi), dan `{jumlah}` (rentang jumlah klip).
 */
@Serializable
data class PromptStyle(
    val id: String,
    val name: String,
    val description: String = "",
    val role: String = GENERIC_ROLE,
    val criteria: List<String>,
    @SerialName("min_seconds") val minSeconds: Int = 30,
    @SerialName("max_seconds") val maxSeconds: Int = 90,
    @SerialName("min_clips") val minClips: Int = 5,
    @SerialName("max_clips") val maxClips: Int = 15,
    val custom: Boolean = false,
) {
    companion object {
        const val GENERIC_ROLE =
            "Kamu adalah editor konten dakwah yang memilih momen terbaik dari video untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts)."
    }
}

/** Pilihan tambahan per project; null = ikut gaya. */
@Serializable
data class PromptOptions(
    @SerialName("min_seconds") val minSeconds: Int? = null,
    @SerialName("max_seconds") val maxSeconds: Int? = null,
    @SerialName("min_clips") val minClips: Int? = null,
    @SerialName("max_clips") val maxClips: Int? = null,
    val focus: String? = null,
) {
    fun isEmpty() = minSeconds == null && maxSeconds == null && minClips == null && maxClips == null && focus.isNullOrBlank()
}

object PromptStyles {
    private const val SENTENCES = "Tidak memotong ayat, hadits, atau kalimat di tengah. Mulai di awal kalimat dan akhiri di akhir kalimat."
    private const val SERIES = "Kisah atau penjelasan yang lebih panjang dari {maks} detik boleh dipecah menjadi seri: beri judul seri yang sama di \"series\" dan nomor urut di \"part\" (1, 2, 3, …). Klip yang bukan seri memakai null untuk keduanya."
    private const val STANDALONE = "Berdiri sendiri: penonton paham tanpa menonton video lengkapnya."

    val KAJIAN = PromptStyle(
        id = "kajian",
        name = "Kajian monolog",
        description = "Ceramah atau kajian satu penceramah.",
        role = "Kamu adalah editor konten dakwah yang memilih momen terbaik dari kajian untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).",
        criteria = listOf(
            "Durasi ideal {durasi} detik.",
            STANDALONE,
            "Dibuka dengan kalimat yang langsung menarik perhatian di 3 detik pertama.",
            SENTENCES,
            "Lewati salam pembuka, pengumuman, dan bagian yang konteksnya bisa menyesatkan jika dipotong.",
            "Pilih {jumlah} klip sesuai panjang dan isi kajian, urutkan dari yang paling kuat.",
            SERIES,
        ),
    )

    val PODCAST = PromptStyle(
        id = "podcast",
        name = "Podcast / obrolan",
        description = "Dua orang atau lebih berbincang: host dan narasumber.",
        role = "Kamu adalah editor konten dakwah yang memilih momen terbaik dari podcast atau obrolan dua orang atau lebih untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).",
        minSeconds = 30, maxSeconds = 75,
        criteria = listOf(
            "Durasi ideal {durasi} detik.",
            STANDALONE,
            "Utamakan pertanyaan yang dijawab tuntas, cerita pribadi, momen lucu atau mengejutkan, dan perbedaan pendapat yang menarik.",
            "Bila momennya tanya-jawab, mulai dari pertanyaannya (walau diucapkan pembicara lain), bukan dari tengah jawaban.",
            "Dibuka dengan kalimat yang langsung menarik perhatian di 3 detik pertama; hook boleh berupa kutipan kalimat paling kuat di klip.",
            SENTENCES,
            "Lewati basa-basi, perkenalan, iklan atau sponsor, dan obrolan saling potong yang tidak berisi.",
            "Pilih {jumlah} klip sesuai panjang dan isi obrolan, urutkan dari yang paling kuat.",
            SERIES,
        ),
    )

    val QNA = PromptStyle(
        id = "tanya_jawab",
        name = "Tanya-jawab jamaah",
        description = "Sesi pertanyaan jamaah dan jawaban ustadz.",
        role = "Kamu adalah editor konten dakwah yang memilih momen terbaik dari sesi tanya-jawab kajian untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).",
        minSeconds = 30, maxSeconds = 90,
        criteria = listOf(
            "Satu klip = satu pertanyaan beserta jawabannya sampai tuntas.",
            "Durasi ideal {durasi} detik; pertanyaan yang panjang boleh dimulai dari inti pertanyaannya.",
            "Tulis pertanyaannya secara ringkas sebagai hook.",
            "Jangan menyebut nama atau identitas penanya di judul, hook, maupun caption.",
            "Lewati pertanyaan yang tidak jelas terdengar atau jawabannya hanya merujuk ke penjelasan sebelumnya.",
            SENTENCES,
            "Pilih {jumlah} klip, urutkan dari pertanyaan yang paling banyak dialami orang.",
            SERIES,
        ),
    )

    val STORY = PromptStyle(
        id = "kisah",
        name = "Kisah / sirah",
        description = "Cerita nabi, sahabat, ulama, atau pengalaman.",
        role = "Kamu adalah editor konten dakwah yang memilih kisah terbaik dari video untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).",
        minSeconds = 45, maxSeconds = 90,
        minClips = 3, maxClips = 10,
        criteria = listOf(
            "Durasi ideal {durasi} detik.",
            "Alur kisah utuh: pembuka, kejadian penting, dan pelajarannya. Jangan berhenti sebelum pelajaran kisahnya.",
            "Hook berupa bagian paling mengejutkan atau pertanyaan yang membuat penasaran tanpa membocorkan akhir kisah.",
            SENTENCES,
            "Lewati pengantar panjang sebelum kisah dimulai.",
            "Pilih {jumlah} klip, urutkan dari kisah yang paling kuat.",
            "Kisah yang lebih panjang dari {maks} detik dipecah menjadi seri: beri judul seri yang sama di \"series\" dan nomor urut di \"part\" (1, 2, 3, …), setiap bagian diakhiri bagian yang membuat penasaran. Klip yang bukan seri memakai null untuk keduanya.",
        ),
    )

    val ADVICE = PromptStyle(
        id = "nasihat",
        name = "Nasihat singkat",
        description = "Kalimat kuat yang mudah dibagikan.",
        role = "Kamu adalah editor konten dakwah yang memilih nasihat paling kuat dari video untuk dijadikan klip pendek vertikal (TikTok, Instagram Reels, YouTube Shorts).",
        minSeconds = 15, maxSeconds = 45,
        minClips = 8, maxClips = 20,
        criteria = listOf(
            "Durasi ideal {durasi} detik.",
            "Satu pesan yang kuat dan jelas, bisa dipahami tanpa konteks apa pun.",
            "Utamakan kalimat yang mudah dikutip dan dibagikan; hook berupa kutipan kalimat terkuatnya.",
            SENTENCES,
            "Lewati penjelasan teknis, perbedaan pendapat, dan bagian yang butuh penjelasan panjang.",
            "Pilih {jumlah} klip, urutkan dari yang paling kuat.",
            SERIES,
        ),
    )

    val BUILT_IN: List<PromptStyle> = listOf(KAJIAN, PODCAST, QNA, STORY, ADVICE)

    /** Gaya buatan pengguna: kriteria pengguna lalu aturan dasar yang selalu berlaku. */
    fun custom(id: String, name: String, criteria: List<String>, minSeconds: Int = 30, maxSeconds: Int = 90) = PromptStyle(
        id = id,
        name = name,
        criteria = criteria.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() } + listOf(
            "Durasi ideal {durasi} detik.",
            STANDALONE,
            SENTENCES,
            "Pilih {jumlah} klip, urutkan dari yang paling kuat.",
            SERIES,
        ),
        minSeconds = minSeconds,
        maxSeconds = maxSeconds,
        custom = true,
    )

    /**
     * Template prompt (bagian sebelum transkrip) untuk [style] dan [options]. [override] = template yang diubah sendiri di
     * Pengaturan; hanya berlaku untuk gaya [KAJIAN], dan opsi ditambahkan di akhirnya.
     */
    fun template(style: PromptStyle, options: PromptOptions? = null, override: String? = null): String {
        val opt = options ?: PromptOptions()
        val minS = opt.minSeconds ?: style.minSeconds
        val maxS = opt.maxSeconds ?: style.maxSeconds
        val minC = opt.minClips ?: style.minClips
        val maxC = opt.maxClips ?: style.maxClips
        val focus = opt.focus?.trim()?.takeIf { it.isNotEmpty() }
        val focusLine = focus?.let { "Utamakan momen yang membahas: $it. Bila tidak ada, pilih momen terbaik lainnya." }

        if (style.id == KAJIAN.id && override != null) {
            val extra = listOfNotNull(
                opt.minSeconds?.let { "Durasi ideal $minS–$maxS detik." },
                opt.minClips?.let { "Pilih $minC–$maxC klip." },
                focusLine,
            )
            return if (extra.isEmpty()) override else override + "\n\nTambahan kriteria:\n" + extra.joinToString("\n") { "- $it" }
        }

        fun fill(text: String) = text.replace("{durasi}", "$minS–$maxS").replace("{maks}", "$maxS").replace("{jumlah}", "$minC–$maxC")
        val criteria = style.criteria.map(::fill) + listOfNotNull(focusLine)
        return style.role + "\n\nVideo: {{judul}} (durasi {{durasi}})\n\nKriteria klip:\n" +
            criteria.joinToString("\n") { "- $it" } + "\n\n" + FORMAT
    }

    /** Bagian format, sama dengan akhir [PromptBuilder.DEFAULT_TEMPLATE]. */
    private val FORMAT: String = PromptBuilder.DEFAULT_TEMPLATE.substring(PromptBuilder.DEFAULT_TEMPLATE.indexOf("Transkrip di bawah berformat"))
}
