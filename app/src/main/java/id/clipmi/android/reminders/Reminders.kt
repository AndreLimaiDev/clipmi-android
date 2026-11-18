package id.clipmi.android.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.clipmi.android.ClipMiApp
import id.clipmi.android.MainActivity
import id.clipmi.android.R
import id.clipmi.android.domain.Wall
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.DueSchedule
import id.clipmi.core.schedule.ReminderNote
import id.clipmi.core.schedule.Reminders
import id.clipmi.core.schedule.TargetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pengingat "Waktunya posting" lewat alarm sistem, sehingga tetap muncul walaupun aplikasi tertutup. Satu alarm saja
 * yang dipasang: untuk jadwal berikutnya yang belum diingatkan. Saat berbunyi, semua jadwal yang waktunya tiba
 * diingatkan sekaligus (`ScheduleReminder` desktop), lalu alarm berikutnya dipasang.
 */
object ReminderAlarm {
    private val lock = Mutex()

    /** Memasang ulang alarm, dan langsung mengingatkan jadwal yang sudah tiba (misalnya HP baru menyala). */
    suspend fun arm(context: Context) = lock.withLock {
        val app = context.applicationContext
        val dao = (app as ClipMiApp).container.database.schedules()
        sendDue(app)
        val alarms = app.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(app)
        val next = dao.nextReminderAt(System.currentTimeMillis())
        if (next == null) {
            alarms.cancel(pending)
            return@withLock
        }
        if (canExact(alarms)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, next, 5 * 60_000L, pending)
    }

    /** Alarm tepat waktu diizinkan (Android 12+ bisa dicabut pengguna; tanpa izin, pengingat bisa telat beberapa menit). */
    fun canExact(alarms: AlarmManager): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    /** Mengirim notifikasi untuk jadwal yang waktunya tiba lalu menandainya sudah diingatkan. */
    private suspend fun sendDue(app: Context) {
        val container = (app as ClipMiApp).container
        val db = container.database
        val nowMillis = System.currentTimeMillis()
        val due = db.schedules().dueReminders(nowMillis)
        if (due.isEmpty()) return
        val accounts = db.schedules().accounts().associateBy { it.id }
        val items = due.map { schedule ->
            val clip = db.clips().find(schedule.clipId)
            DueSchedule(
                id = schedule.id,
                at = Wall.of(schedule.scheduledAt),
                clipTitle = clip?.let { ClipText.displayTitle(it.title, it.partNumber) } ?: "Klip",
                pendingAccounts = db.schedules().targets(schedule.id).filter { it.status == TargetStatus.PENDING }.mapNotNull { accounts[it.socialAccountId]?.name },
            )
        }
        Reminders.notes(items, Wall.now()).forEach { ReminderNotifier.show(app, it) }
        db.schedules().markNotified(due.map { it.id }, nowMillis)
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** "Nanti 30 menit": notifikasi yang sama muncul lagi tanpa mengubah jadwalnya. */
    fun snooze(context: Context, note: ReminderNote) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZED)
            .putExtra(EXTRA_TITLE, note.title).putExtra(EXTRA_BODY, note.body).putExtra(EXTRA_SCHEDULE, note.scheduleId ?: 0L)
        val pending = PendingIntent.getBroadcast(context, ReminderNotifier.notificationId(note), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        if (canExact(alarms)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pending)
    }

    const val SNOOZE_MINUTES = 30L
    const val ACTION_ALARM = "id.clipmi.android.REMINDER"
    const val ACTION_SNOOZE = "id.clipmi.android.REMINDER_SNOOZE"
    const val ACTION_SNOOZED = "id.clipmi.android.REMINDER_SNOOZED"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_SCHEDULE = "schedule"
}

/** Notifikasi pengingat: ketuk membuka layar Siap Unggah (atau daftar Jadwal), dengan tombol Unggah dan Nanti. */
object ReminderNotifier {
    fun notificationId(note: ReminderNote): Int = note.scheduleId?.let { 20_000 + (it % 100_000).toInt() } ?: 19_999

    fun show(context: Context, note: ReminderNote) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val id = notificationId(note)
        val open = Intent(context, MainActivity::class.java)
            .setAction("id.clipmi.android.OPEN_SCHEDULE.$id")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, note.scheduleId ?: 0L)
        val content = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderAlarm.ACTION_SNOOZE)
                .putExtra(ReminderAlarm.EXTRA_TITLE, note.title).putExtra(ReminderAlarm.EXTRA_BODY, note.body).putExtra(ReminderAlarm.EXTRA_SCHEDULE, note.scheduleId ?: 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ClipMiApp.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(note.title)
            .setContentText(note.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(content)
            .addAction(0, if (note.scheduleId != null) "Unggah" else "Buka Jadwal", content)
            .addAction(0, "Nanti ${ReminderAlarm.SNOOZE_MINUTES} menit", snooze)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, note: ReminderNote) =
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(note))
}

/** Alarm pengingat, tombol Nanti, dan pengingat yang ditunda. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val note = ReminderNote(
            intent.getStringExtra(ReminderAlarm.EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(ReminderAlarm.EXTRA_BODY).orEmpty(),
            intent.getLongExtra(ReminderAlarm.EXTRA_SCHEDULE, 0L).takeIf { it != 0L },
        )
        when (intent.action) {
            ReminderAlarm.ACTION_SNOOZE -> {
                ReminderNotifier.cancel(context, note)
                ReminderAlarm.snooze(context, note)
            }
            ReminderAlarm.ACTION_SNOOZED -> ReminderNotifier.show(context, note)
            else -> rearm(context)
        }
    }
}

/** HP menyala ulang, aplikasi diperbarui, atau jam/zona waktu diubah: alarm dipasang lagi. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = rearm(context)
}

private fun BroadcastReceiver.rearm(context: Context) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            ReminderAlarm.arm(context)
        } finally {
            pending.finish()
        }
    }
}
