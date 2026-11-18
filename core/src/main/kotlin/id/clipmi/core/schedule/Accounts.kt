package id.clipmi.core.schedule

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Akun sosmed dan slot rutinnya, padanan `App\Models\SocialAccount` desktop. Akun hanya label untuk merencanakan
 * posting: tanpa login, karena posting tetap dilakukan di aplikasi platformnya.
 *
 * Semua waktu jadwal di core adalah jam dinding HP ([LocalDateTime], presisi menit), sama dengan desktop yang menyimpan
 * jam dinding PC.
 */
object Platforms {
    val LABELS: Map<String, String> = linkedMapOf(
        "instagram" to "Instagram",
        "tiktok" to "TikTok",
        "youtube" to "YouTube",
        "facebook" to "Facebook",
        "other" to "Lainnya",
    )

    /**
     * Batas teks saat mengunggah langsung di aplikasi platform: panjang caption, jumlah hashtag, dan panjang judul
     * (YouTube). Facebook dan platform lain tidak dibatasi karena batasnya jauh di atas caption klip.
     */
    val LIMITS: Map<String, TextLimit> = mapOf(
        "instagram" to TextLimit(caption = 2200, hashtags = 30),
        "tiktok" to TextLimit(caption = 4000),
        "youtube" to TextLimit(title = 100, caption = 5000),
    )

    /** Warna pilihan untuk membedakan akun di kalender. */
    val COLORS = listOf("#E1306C", "#64748B", "#DC2626", "#2563EB", "#16A34A", "#D97706", "#7C3AED", "#0891B2")

    /** Hari dalam slot rutin (nomor ISO: Senin = 1). */
    val DAYS: Map<Int, String> = linkedMapOf(1 to "Sen", 2 to "Sel", 3 to "Rab", 4 to "Kam", 5 to "Jum", 6 to "Sab", 7 to "Min")

    fun label(platform: String): String = LABELS[platform] ?: platform

    /** Warna untuk akun baru: yang pertama belum dipakai akun lain. */
    fun nextColor(used: Collection<String>): String =
        COLORS.firstOrNull { color -> used.none { it.equals(color, ignoreCase = true) } } ?: COLORS[0]
}

data class TextLimit(val caption: Int? = null, val hashtags: Int? = null, val title: Int? = null)

/** Satu slot rutin: jam "HH:mm" pada hari-hari ISO tertentu. */
data class Slot(val time: String, val days: List<Int>)

object Slots {
    private val TIME = Regex("^(\\d{2}):(\\d{2})$")

    /** Jam dari "HH:mm", atau null bila tidak sah. */
    fun parseTime(time: String): Pair<Int, Int>? =
        TIME.find(time)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }?.takeIf { (h, m) -> h in 0..23 && m in 0..59 }

    /**
     * Waktu slot rutin setelah [after] sampai [until] (inklusif), urut dari yang terdekat, tanpa duplikat
     * (`SocialAccount::slotTimes`).
     */
    fun times(slots: List<Slot>, after: LocalDateTime, until: LocalDateTime): List<LocalDateTime> {
        val times = sortedMapOf<LocalDateTime, LocalDateTime>()
        var day = after.truncatedTo(ChronoUnit.DAYS)
        while (!day.isAfter(until)) {
            for (slot in slots) {
                val (hour, minute) = TIME.find(slot.time)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: continue
                if (day.dayOfWeek.value !in slot.days) continue
                val at = day.plusHours(hour.toLong()).plusMinutes(minute.toLong())
                if (at.isAfter(after) && !at.isAfter(until)) times[at] = at
            }
            day = day.plusDays(1)
        }
        return times.values.toList()
    }

    /** Ringkasan slot untuk daftar akun, misalnya "07.00 setiap hari · 19.00 Sen–Jum" (`slotSummary`). */
    fun summary(slots: List<Slot>): String = slots.sortedBy { it.time }.joinToString(" · ") { slot ->
        val days = slot.days.sorted()
        val label = when {
            days.size == 7 -> "setiap hari"
            days == listOf(1, 2, 3, 4, 5) -> "Sen–Jum"
            days == listOf(6, 7) -> "akhir pekan"
            else -> days.joinToString(", ") { Platforms.DAYS[it].orEmpty() }
        }
        slot.time.replace(":", ".") + " " + label
    }

    /**
     * Slot dari isian: jam yang sama digabung harinya, urut jam, hari urut; kosong menjadi null (`normalizeSlots`).
     */
    fun normalize(slots: List<Slot>): List<Slot>? {
        val merged = sortedMapOf<String, List<Int>>()
        for (slot in slots) merged[slot.time] = ((merged[slot.time] ?: emptyList()) + slot.days).distinct().sorted()
        return if (merged.isEmpty()) null else merged.map { (time, days) -> Slot(time, days) }
    }

    /** Slot baru: jam 19.00 setiap hari, atau satu jam setelah slot terakhir (`addSlot`). */
    fun next(slots: List<Slot>): Slot {
        val hour = slots.lastOrNull()?.let { minOf(23, it.time.take(2).toIntOrNull()?.plus(1) ?: DEFAULT_HOUR) } ?: DEFAULT_HOUR
        return Slot("%02d:00".format(hour), Platforms.DAYS.keys.toList())
    }

    /** Hari slot ditambah atau dibuang (`toggleSlotDay`). */
    fun toggleDay(slot: Slot, day: Int): Slot =
        slot.copy(days = (if (day in slot.days) slot.days - day else slot.days + day).sorted())

    /** Jam bawaan untuk jadwal baru tanpa jam. */
    const val DEFAULT_HOUR = 19
}
