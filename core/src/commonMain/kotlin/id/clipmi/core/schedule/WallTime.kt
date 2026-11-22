package id.clipmi.core.schedule

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

// Aritmetika jam dinding (tanpa zona waktu), sama dengan java.time.LocalDateTime yang dipakai sebelum core
// multiplatform: menit dan hari dihitung langsung di tanggal dan jam, tanpa lompatan jam musim panas.

/** [minutes] menit sesudahnya (negatif = sebelumnya), seperti `LocalDateTime.plusMinutes`. */
fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime {
    val total = hour * 60L + minute + minutes
    val days = total.floorDiv(MINUTES_PER_DAY)
    val rest = total.mod(MINUTES_PER_DAY)
    return LocalDateTime(date.plus(days, DateTimeUnit.DAY), LocalTime((rest / 60).toInt(), (rest % 60).toInt(), second, nanosecond))
}

/** [days] hari sesudahnya di jam yang sama, seperti `LocalDateTime.plusDays`. */
fun LocalDateTime.plusDays(days: Long): LocalDateTime = LocalDateTime(date.plus(days, DateTimeUnit.DAY), time)

/** Awal hari (00.00), seperti `truncatedTo(ChronoUnit.DAYS)`. */
fun LocalDateTime.startOfDay(): LocalDateTime = LocalDateTime(date, LocalTime(0, 0))

/** Waktu dipotong ke menit, sama dengan kunci 'Y-m-d H:i' desktop. */
fun LocalDateTime.minute(): LocalDateTime = LocalDateTime(date, LocalTime(hour, minute))

/** Milidetik jam dinding sejak 1970 (tanpa zona), untuk selisih waktu seperti `Duration.between`. */
fun LocalDateTime.wallMillis(): Long = date.toEpochDays().toLong() * 86_400_000L + time.toMillisecondOfDay()

private const val MINUTES_PER_DAY = 24 * 60L

/** Dua digit, misalnya 7 → "07" (pengganti `"%02d".format` yang hanya ada di JVM). */
internal fun pad2(value: Int): String = value.toString().padStart(2, '0')
