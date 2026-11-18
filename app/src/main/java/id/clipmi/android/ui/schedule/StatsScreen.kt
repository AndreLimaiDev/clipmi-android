package id.clipmi.android.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.AppContainer
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.domain.Wall
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FilterPill
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.ScreenHeader
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.schedule.AccountSummary
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.Platforms
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.TargetStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Satu postingan terbit: akun tujuan jadwal beserta jadwal dan catatan statistiknya (urut waktu). */
private data class Post(val schedule: ScheduleView, val target: TargetView, val stats: List<PostStatEntity>) {
    val row: ScheduleAccountEntity get() = target.row
    val latest: PostStatEntity? get() = stats.lastOrNull()
}

/**
 * Tab Statistik (tampilan Statistik di halaman Jadwal desktop): ringkasan per akun (total, median, terbaik) dan tiap
 * postingan dengan tayangan dibanding median akunnya, engagement, serta tayangan hari ke-1/7/30.
 */
@Composable
fun StatsScreen(container: AppContainer, onOpenSchedule: (Long) -> Unit) {
    val data by rememberScheduleData(container)
    val scope = rememberCoroutineScope()
    var hidden by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var since by rememberSaveable { mutableStateOf(0L) }
    var rows by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var banner by rememberSaveable { mutableStateOf<String?>(null) }
    val c = ClipMi.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 20.dp)) {
        val d = data
        val posts = d?.schedules.orEmpty()
            .flatMap { s -> s.targets.filter { it.status == TargetStatus.PUBLISHED }.map { Post(s, it, d?.stats?.get(it.row.id).orEmpty()) } }
            .filter { it.target.account.id !in hidden }
            .sortedByDescending { it.row.publishedAt ?: 0 }
            .take(300)
        val fetchingRows = rows.filter { id -> posts.firstOrNull { it.row.id == id }?.row?.statsCheckedAt?.let { it >= since } != true }

        // Progres "Perbarui semua": selesai begitu tiap baris dicek sesudah waktu mulai, atau berhenti memantau setelah 3 menit.
        LaunchedEffect(since, rows, d) {
            if (rows.isEmpty() || d == null) return@LaunchedEffect
            val checked = rows.mapNotNull { id -> d.schedules.flatMap { it.targets }.firstOrNull { it.row.id == id }?.row }
            val done = checked.filter { (it.statsCheckedAt ?: 0) >= since }
            val failed = done.count { it.statsError != null }
            if (done.size == rows.size) {
                banner = if (rows.size == 1) (if (failed > 0) "Statistik gagal diambil; alasannya ada di kartu postingan." else "Statistik diperbarui.")
                else "Statistik ${rows.size} postingan diperbarui." + if (failed > 0) " $failed gagal; alasannya ada di kartu postingan." else ""
                rows = emptyList()
            } else {
                delay((since + 180_000 - System.currentTimeMillis()).coerceAtLeast(0))
                banner = "Baru ${done.size} dari ${rows.size} statistik selesai diambil. Sisanya tetap diambil di latar begitu ada internet."
                rows = emptyList()
            }
        }

        ScreenHeader("Statistik", Modifier.padding(horizontal = ScreenGutter), eyebrow = "ClipMI") {
            if (posts.isNotEmpty()) {
                if (rows.isNotEmpty()) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp) }
                else IconCircle(ClipMiIcons.Refresh, "Perbarui semua statistik", {
                    scope.launch {
                        val start = System.currentTimeMillis() / 1000 * 1000
                        val ids = container.scheduler.refreshAllStats()
                        if (ids.isEmpty()) banner = "Belum ada postingan terbit yang punya link."
                        else { since = start; rows = ids; banner = null }
                    }
                })
            }
        }
        if (d == null) return@Column
        if (posts.isEmpty() && hidden.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = FloatingNavSpace)) {
                EmptyState(
                    "Belum ada postingan terbit",
                    "Buka jadwal, tandai terbit, dan tempel link postingannya. Statistiknya diambil otomatis di latar sampai ${PostStats.MAX_AGE_DAYS} hari setelah terbit.",
                    icon = ClipMiIcons.Chart,
                )
            }
            return@Column
        }
        val byAccount = posts.groupBy { it.target.account.id }
        val summaries = byAccount.mapValues { (_, list) -> PostStats.summary(list.map { it.latest?.views }) }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 16.dp, bottom = FloatingNavSpace),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rows.isNotEmpty()) {
                item(key = "progress") {
                    val done = rows.size - fetchingRows.size
                    ClipMiCard {
                        Text(if (rows.size == 1) "Mengambil statistik postingan…" else "Mengambil statistik $done dari ${rows.size} postingan", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        ProgressBar(if (rows.size == 1) null else done.toFloat() / rows.size)
                    }
                }
            }
            banner?.let { text ->
                item(key = "banner") {
                    ClipMiCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                            IconCircle(ClipMiIcons.Close, "Tutup pesan", { banner = null }, size = 32.dp, background = Color.Transparent, border = false)
                        }
                    }
                }
            }
            if (d.accounts.size > 1) {
                item(key = "filter") {
                    ChipRow(gutter = 0.dp) {
                        d.accounts.forEach { account ->
                            FilterPill(account.name, selected = account.id !in hidden, onClick = { hidden = if (account.id in hidden) hidden - account.id else hidden + account.id })
                        }
                    }
                }
            }
            items(byAccount.keys.toList(), key = { "sum-$it" }) { accountId ->
                val account = byAccount.getValue(accountId).first().target.account
                SummaryCard(account.name, account.color, Platforms.label(account.platform), summaries.getValue(accountId))
            }
            items(posts, key = { "post-${it.row.id}" }) { post ->
                PostCard(post, summaries.getValue(post.target.account.id), fetching = post.row.id in fetchingRows, onOpen = { onOpenSchedule(post.schedule.id) }, onRefresh = {
                    scope.launch {
                        val start = System.currentTimeMillis() / 1000 * 1000
                        if (container.scheduler.refreshStats(post.row.id)) { since = start; rows = listOf(post.row.id); banner = null }
                    }
                })
            }
            item(key = "note") {
                Text(
                    "Statistik diambil otomatis di latar tiap beberapa jam (makin muda postingan, makin sering), sampai ${PostStats.MAX_AGE_DAYS} hari setelah terbit. " +
                        "Angka \"×\" membandingkan tayangan dengan median akunnya (mulai ${PostStats.MIN_MEASURED} postingan terukur). Hari 1/7/30 kosong bila statistik belum diambil di sekitar umur itu. " +
                        "Instagram dan Facebook biasanya harus diisi manual.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(name: String, color: String, platform: String, summary: AccountSummary) {
    val c = ClipMi.colors
    ClipMiCard(spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountDot(color, 10.dp)
            Text(name, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text("· $platform", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(PostStats.number(summary.total), style = MaterialTheme.typography.headlineSmall, color = c.text)
            Text("total tayangan", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            "${summary.count} postingan" + (if (summary.measured < summary.count) " (${summary.measured} terukur)" else "") +
                " · median ${PostStats.number(summary.median)} · terbaik ${PostStats.number(summary.best)}",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
        )
    }
}

@Composable
private fun PostCard(post: Post, summary: AccountSummary, fetching: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit) {
    val c = ClipMi.colors
    val latest = post.latest
    val published = post.row.publishedAt?.let(Wall::of)
    val points = post.stats.points()
    val ratio = PostStats.ratio(latest?.views, summary)
    ClipMiCard(onClick = onOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClipThumb(post.schedule.clip, Modifier.width(40.dp).height(70.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(post.schedule.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountDot(post.target.account.color, 8.dp)
                    Text(
                        listOfNotNull(post.target.account.name, published?.let { IdDate.full(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (post.row.url != null) {
                if (fetching) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp) }
                else IconCircle(ClipMiIcons.Refresh, "Ambil ulang statistik ${post.schedule.title} di ${post.target.account.name}", onRefresh, size = 36.dp, background = Color.Transparent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Tayangan", PostStats.number(latest?.views), ratio?.let { PostStats.decimal(it) + "×" }, ratioColor(ratio), Modifier.weight(1.3f))
            Metric("Suka", PostStats.number(latest?.likes), modifier = Modifier.weight(1f))
            Metric("Komentar", PostStats.number(latest?.comments), modifier = Modifier.weight(1f))
            Metric("Share", PostStats.number(latest?.shares), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Engagement", PostStats.rate(latest?.numbers()?.engagementRate), modifier = Modifier.weight(1.3f))
            PostStats.AGES.forEach { age ->
                Metric("Hari $age", PostStats.number(PostStats.statAt(published, points, age)?.numbers?.views), modifier = Modifier.weight(1f))
            }
        }
        val checkedAt = post.row.statsCheckedAt
        val failed = post.row.statsError != null && checkedAt != null && (latest == null || checkedAt > latest.recordedAt)
        Text(
            when {
                fetching -> "Mengambil…"
                failed -> "Gagal diambil: ${post.row.statsError}"
                latest != null -> "Diperbarui ${IdDate.dayMonthTime(Wall.of(latest.recordedAt))} · ${StatSource.LABELS[latest.source] ?: latest.source}"
                post.row.url != null -> "Menunggu"
                else -> "Belum ada link"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) c.warn else c.textMuted,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ratioColor(ratio: Double?): Color {
    val c = ClipMi.colors
    return when {
        ratio == null -> c.textMuted
        ratio >= 1.5 -> c.accent
        ratio < 0.5 -> c.bad
        else -> c.textMuted
    }
}

@Composable
private fun Metric(label: String, value: String, extra: String? = null, extraColor: Color = ClipMi.colors.textMuted, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // Angka besar (jutaan) mengecil sendiri supaya tidak terpotong di kolom yang sempit.
            val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, color = c.text)
            androidx.compose.foundation.text.BasicText(
                value,
                modifier = Modifier.weight(1f, fill = false),
                style = style,
                maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            )
            extra?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = extraColor, maxLines = 1) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
    }
}
