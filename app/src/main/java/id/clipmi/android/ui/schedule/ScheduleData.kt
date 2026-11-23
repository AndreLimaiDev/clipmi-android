package id.clipmi.android.ui.schedule

import id.clipmi.core.schedule.isOverdue

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.data.ProjectEntity
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.data.ScheduleEntity
import id.clipmi.android.data.SocialAccountEntity
import id.clipmi.android.domain.SlotCodec
import id.clipmi.android.domain.Wall
import id.clipmi.android.ui.screens.copy
import id.clipmi.android.ui.screens.exists
import id.clipmi.android.ui.screens.videoChooser
import id.clipmi.android.ui.screens.videoSend
import id.clipmi.android.ui.styles.hexColor
import id.clipmi.core.analysis.ClipText
import id.clipmi.core.schedule.ScheduleState
import id.clipmi.core.schedule.Schedules
import id.clipmi.core.schedule.Slot
import id.clipmi.core.schedule.StatNumbers
import id.clipmi.core.schedule.StatPoint
import id.clipmi.core.schedule.TargetStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDateTime

/** Akun tujuan satu jadwal beserta akunnya. */
data class TargetView(val row: ScheduleAccountEntity, val account: SocialAccountEntity) {
    val status: String get() = row.status
}

/** Jadwal lengkap untuk layar: klip, project, akun tujuan, dan waktunya sebagai jam dinding HP. */
data class ScheduleView(val entity: ScheduleEntity, val clip: ClipEntity, val project: ProjectEntity?, val targets: List<TargetView>) {
    val id: Long get() = entity.id
    val at: LocalDateTime = Wall.of(entity.scheduledAt)
    val title: String get() = ClipText.displayTitle(clip.title, clip.partNumber)
    val state: ScheduleState get() = Schedules.state(targets.map { it.status })
    val pending: List<TargetView> get() = targets.filter { it.status == TargetStatus.PENDING }

    fun isOverdue(now: LocalDateTime) = Schedules.isOverdue(at, targets.map { it.status }, now)
}

/** Isi halaman Jadwal dan Statistik, diperbarui otomatis dari database. */
data class ScheduleData(
    val accounts: List<SocialAccountEntity>,
    val schedules: List<ScheduleView>,
    val clips: List<ClipEntity>,
    val projects: Map<Long, ProjectEntity>,
    val stats: Map<Long, List<PostStatEntity>>,
) {
    val accountSlots: Map<Long, List<Slot>> = accounts.associate { it.id to SlotCodec.decode(it.slots) }
    val hasSlots: Boolean get() = accountSlots.values.any { it.isNotEmpty() }
    val scheduledClipIds: Set<Long> = schedules.map { it.clip.id }.toSet()
    /** Status jadwal per klip (terbit, sebagian terbit, atau masih dijadwalkan); jadwal terbaik yang dipakai. */
    val clipStates: Map<Long, ScheduleState> = schedules.groupBy { it.clip.id }.mapValues { (_, list) ->
        val states = list.map { it.state }
        when {
            ScheduleState.Published in states -> ScheduleState.Published
            ScheduleState.Partial in states -> ScheduleState.Partial
            else -> ScheduleState.Scheduled
        }
    }

    fun schedule(id: Long) = schedules.firstOrNull { it.id == id }
}

fun scheduleFlow(container: AppContainer): Flow<ScheduleData> {
    val dao = container.database.schedules()
    val base = combine(
        dao.observeAccounts(), dao.observeSchedules(), dao.observeTargets(), container.database.clips().observeSchedulable(), container.database.projects().observeAll(),
    ) { accounts, schedules, targets, clips, projects -> Base(accounts, schedules, targets, clips, projects) }
    return combine(base, dao.observeStats()) { b, stats ->
        val accountById = b.accounts.associateBy { it.id }
        val clipById = b.clips.associateBy { it.id }
        val projectById = b.projects.associateBy { it.id }
        val targetsBySchedule = b.targets.groupBy { it.scheduleId }
        ScheduleData(
            accounts = b.accounts,
            schedules = b.schedules.mapNotNull { s ->
                val clip = clipById[s.clipId] ?: return@mapNotNull null
                ScheduleView(s, clip, projectById[clip.projectId], targetsBySchedule[s.id].orEmpty().mapNotNull { row -> accountById[row.socialAccountId]?.let { TargetView(row, it) } })
            },
            clips = b.clips,
            projects = projectById,
            stats = stats.groupBy { it.scheduleAccountId },
        )
    }
}

private data class Base(
    val accounts: List<SocialAccountEntity>,
    val schedules: List<ScheduleEntity>,
    val targets: List<ScheduleAccountEntity>,
    val clips: List<ClipEntity>,
    val projects: List<ProjectEntity>,
)

@Composable
fun rememberScheduleData(container: AppContainer): State<ScheduleData?> {
    val flow = remember(container) { scheduleFlow(container) }
    return flow.collectAsState(initial = null)
}

fun PostStatEntity.numbers() = StatNumbers(views, likes, comments, shares)

fun List<PostStatEntity>.points() = map { StatPoint(Wall.of(it.recordedAt), it.numbers()) }

/** Titik warna akun (kalender, daftar). */
@Composable
fun AccountDot(color: String, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(hexColor(color)))
}

/** Aplikasi platform yang dibuka tombol Bagikan; pertama yang terpasang dipakai. */
val PLATFORM_APPS: Map<String, List<String>> = mapOf(
    "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
    "instagram" to listOf("com.instagram.android"),
    "youtube" to listOf("com.google.android.youtube"),
    "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
)

fun installedApp(context: Context, platform: String): String? =
    PLATFORM_APPS[platform]?.firstOrNull { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }

/**
 * Bagikan video ke aplikasi platform akun (atau pemilih aplikasi), setelah teks unggahan disalin. Mengembalikan pesan
 * untuk pengguna.
 */
fun shareTo(context: Context, clip: ClipEntity, text: String, platform: String?): String {
    copy(context, text)
    val uri = clip.outputPath?.let(Uri::parse)
    if (uri == null || !exists(context, uri)) return "File hasil tidak ditemukan. Render ulang klip ini."
    val send = videoSend(uri, clip.title)
    val pkg = platform?.let { installedApp(context, it) }
    val ok = runCatching { context.startActivity(if (pkg != null) send.setPackage(pkg) else videoChooser(context, send)) }.isSuccess
    return if (ok) "Caption & hashtag disalin. Tempel di aplikasi tujuan." else "Aplikasi tujuan tidak bisa dibuka."
}
