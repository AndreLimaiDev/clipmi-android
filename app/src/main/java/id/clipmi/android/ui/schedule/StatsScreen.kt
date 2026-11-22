package id.clipmi.android.ui.schedule

import id.clipmi.core.schedule.statAt

import id.clipmi.core.schedule.dayMonth
import id.clipmi.core.schedule.dayMonthTime

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import id.clipmi.android.ui.components.bleed
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import id.clipmi.android.ui.components.ActionChip
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
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.schedule.AccountSummary
import id.clipmi.core.schedule.IdDate
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
 * Tab Statistik: ringkasan (total tayangan besar, engagement, suka/komentar/share), rincian per akun dengan batang,
 * analisis performa, dan daftar postingan (ketuk untuk angka, tayangan hari 1/7/30, dan aksi). Filter satu akun atau
 * semua.
 */
@Composable
fun StatsScreen(container: AppContainer, onOpenSchedule: (Long) -> Unit, onOpenPerformance: () -> Unit = {}) {
    val data by rememberScheduleData(container)
    val scope = rememberCoroutineScope()
    // null = semua akun.
    var account by rememberSaveable { mutableStateOf<Long?>(null) }
    var since by rememberSaveable { mutableStateOf(0L) }
    var rows by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var banner by rememberSaveable { mutableStateOf<String?>(null) }
    var best by rememberSaveable { mutableStateOf(false) }
    val c = ClipMi.colors

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp)) {
        val d = data
        val all = d?.schedules.orEmpty()
            .flatMap { s -> s.targets.filter { it.status == TargetStatus.PUBLISHED }.map { Post(s, it, d?.stats?.get(it.row.id).orEmpty()) } }
            .sortedByDescending { it.row.publishedAt ?: 0 }
            .take(300)
        val posts = all.filter { account == null || it.target.account.id == account }
        val fetchingRows = rows.filter { id -> all.firstOrNull { it.row.id == id }?.row?.statsCheckedAt?.let { it >= since } != true }

        // Progres "Perbarui semua": selesai begitu tiap baris dicek sesudah waktu mulai, atau berhenti memantau setelah 3 menit.
        LaunchedEffect(since, rows, d) {
            if (rows.isEmpty() || d == null) return@LaunchedEffect
            val checked = rows.mapNotNull { id -> d.schedules.flatMap { it.targets }.firstOrNull { it.row.id == id }?.row }
            val done = checked.filter { (it.statsCheckedAt ?: 0) >= since }
            val failed = done.count { it.statsError != null }
            if (done.size == rows.size) {
                banner = if (failed > 0) "$failed statistik gagal diambil; alasannya ada di kartu postingan." else null
                rows = emptyList()
            } else {
                delay((since + 180_000 - System.currentTimeMillis()).coerceAtLeast(0))
                banner = "Baru ${done.size} dari ${rows.size} selesai. Sisanya diambil di latar."
                rows = emptyList()
            }
        }

        Row(Modifier.padding(horizontal = ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Statistik", style = MaterialTheme.typography.headlineMedium, color = c.text)
                if (all.isNotEmpty()) Text("${PostStats.MAX_AGE_DAYS} hari setelah terbit · diambil otomatis", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            if (all.isNotEmpty()) {
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
        if (all.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = FloatingNavSpace)) {
                EmptyState(
                    "Belum ada postingan terbit",
                    "Tandai jadwal terbit dan tempel link postingannya; statistiknya diambil otomatis.",
                    icon = ClipMiIcons.Chart,
                )
            }
            return@Column
        }
        val summaries = all.groupBy { it.target.account.id }.mapValues { (_, list) -> PostStats.summary(list.map { it.latest?.views }) }
        val shown = if (best) posts.sortedByDescending { it.latest?.views ?: -1 } else posts

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 16.dp, bottom = FloatingNavSpace),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (rows.isNotEmpty()) {
                item(key = "progress") {
                    val done = rows.size - fetchingRows.size
                    ClipMiCard {
                        Text(if (rows.size == 1) "Mengambil statistik…" else "Mengambil statistik $done dari ${rows.size}", style = MaterialTheme.typography.bodyMedium, color = c.text)
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
            // Filter satu akun: Semua lalu tiap akun (titik warna).
            if (d.accounts.size > 1) {
                item(key = "filter") {
                    ChipRow(Modifier.bleed()) {
                        AccountFilter("Semua", null, account == null) { account = null }
                        d.accounts.forEach { a -> AccountFilter(a.name, a.color, account == a.id) { account = a.id } }
                    }
                }
            }
            item(key = "hero") { HeroCard(posts) }
            if (account == null && d.accounts.size > 1) {
                item(key = "accounts") {
                    val totals = d.accounts.associate { a -> a.id to (summaries[a.id]?.total ?: 0L) }
                    val max = totals.values.maxOrNull()?.takeIf { it > 0 } ?: 1L
                    ClipMiCard(spacing = 14.dp) {
                        Text("Per akun", style = MaterialTheme.typography.titleSmall, color = c.text)
                        d.accounts.forEach { a ->
                            val summary = summaries[a.id]
                            AccountRow(a.name, a.color, summary, (totals.getValue(a.id).toFloat() / max)) { account = a.id }
                        }
                    }
                }
            }
            item(key = "performance") {
                // Kartu gelap: aksi utama halaman ini.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF0E1512)).clickable(onClick = onOpenPerformance).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F7A5A)), contentAlignment = Alignment.Center) {
                        Icon(ClipMiIcons.Sparkle, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Analisis performa", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text("Saran AI dari postingan yang sudah terbit", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9C4BF))
                    }
                    Icon(ClipMiIcons.Chevron, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            item(key = "sort") {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Postingan", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                    Segmented(listOf("Terbaru", "Terbaik"), if (best) 1 else 0) { best = it == 1 }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text("Belum ada postingan terbit di akun ini.", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            items(shown, key = { "post-${it.row.id}" }) { post ->
                PostCard(post, fetching = post.row.id in fetchingRows, onOpen = { onOpenSchedule(post.schedule.id) }, onRefresh = {
                    scope.launch {
                        val start = System.currentTimeMillis() / 1000 * 1000
                        if (container.scheduler.refreshStats(post.row.id)) { since = start; rows = listOf(post.row.id); banner = null }
                    }
                })
            }
        }
    }
}

/** Chip filter akun: terpilih = gelap (titik putih), lainnya bergaris dengan titik warna akun. */
@Composable
private fun AccountFilter(label: String, color: String?, selected: Boolean, onClick: () -> Unit) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(38.dp).clip(shape)
            .background(if (selected) c.text else c.card)
            .border(1.dp, if (selected) c.text else c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        color?.let { AccountDot(if (selected) "#FFFFFF" else it, 8.dp) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.bg else c.text, maxLines = 1)
    }
}

/** Ringkasan: total tayangan besar, engagement di kanan atas, lalu suka/komentar/share. */
@Composable
private fun HeroCard(posts: List<Post>) {
    val c = ClipMi.colors
    val latest = posts.mapNotNull { it.latest }
    fun total(value: (PostStatEntity) -> Long?) = latest.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()
    val views = total { it.views }
    val likes = total { it.likes }
    val comments = total { it.comments }
    val shares = total { it.shares }
    val engagement = if (views != null && views > 0) ((likes ?: 0) + (comments ?: 0) + (shares ?: 0)).toDouble() / views else null
    ClipMiCard(spacing = 16.dp, padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Total tayangan", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Text(PostStats.number(views), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp), color = c.text, maxLines = 1)
            }
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("Engagement", style = MaterialTheme.typography.labelSmall, color = c.accent)
                Text(PostStats.rate(engagement), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = c.accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniTile(ClipMiIcons.Heart, PostStats.number(likes), "Suka", Modifier.weight(1f))
            MiniTile(ClipMiIcons.Comment, PostStats.number(comments), "Komentar", Modifier.weight(1f))
            MiniTile(ClipMiIcons.Send, PostStats.number(shares), "Share", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier) {
    val c = ClipMi.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (value == "0" || value == "–") c.textMuted else c.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
    }
}

/** Satu akun di ringkasan: nama, jumlah postingan dan median/terbaik bila berbeda, total, dan batang pembanding. */
@Composable
private fun AccountRow(name: String, color: String, summary: AccountSummary?, share: Float, onClick: () -> Unit) {
    val c = ClipMi.colors
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountDot(color, 10.dp)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        summary == null || summary.count == 0 -> "Belum ada data"
                        summary.count == 1 -> "1 postingan"
                        else -> "${summary.count} postingan · median ${PostStats.number(summary.median)} · terbaik ${PostStats.number(summary.best)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary != null && summary.count > 0) Text(PostStats.number(summary.total), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = c.text)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.panel)) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(parseColor(color)))
        }
    }
}

/** Pilihan bersegmen kecil (latar abu, pilihan terpilih putih). */
@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ClipMi.colors
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.panel).padding(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.card else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) c.text else c.textMuted) }
        }
    }
}

/** Postingan: gambar, judul, akun · tanggal, angka sebaris; ketuk untuk angka lengkap, hari 1/7/30, dan aksi. */
@Composable
private fun PostCard(post: Post, fetching: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit) {
    val c = ClipMi.colors
    val latest = post.latest
    val published = post.row.publishedAt?.let(Wall::of)
    val points = post.stats.points()
    var expanded by rememberSaveable(post.row.id) { mutableStateOf(false) }
    val checkedAt = post.row.statsCheckedAt
    val failed = post.row.statsError != null && checkedAt != null && (latest == null || checkedAt > latest.recordedAt)
    ClipMiCard(onClick = { expanded = !expanded }, padding = PaddingValues(12.dp), spacing = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ClipThumb(post.schedule.clip, Modifier.width(56.dp).height(76.dp))
            Column(Modifier.weight(1f).height(76.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(post.schedule.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountDot(post.target.account.color, 6.dp)
                        Text(
                            listOfNotNull(post.target.account.name, published?.let { IdDate.dayMonth(it) }).joinToString(" · ") +
                                when {
                                    fetching -> " · mengambil…"
                                    failed -> " · gagal diambil"
                                    latest == null && post.row.url == null -> " · belum ada link"
                                    else -> ""
                                },
                            style = MaterialTheme.typography.bodySmall, color = if (failed) c.warn else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    InlineStat(ClipMiIcons.Eye, PostStats.number(latest?.views), bold = true)
                    InlineStat(ClipMiIcons.Heart, PostStats.number(latest?.likes))
                    InlineStat(ClipMiIcons.Comment, PostStats.number(latest?.comments))
                }
            }
            Icon(ClipMiIcons.Chevron, null, tint = c.textMuted, modifier = Modifier.align(Alignment.CenterVertically).size(16.dp).rotate(if (expanded) -90f else 90f))
        }
        if (!expanded) return@ClipMiCard
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetailTile(PostStats.number(latest?.likes), "Suka", Modifier.weight(1f))
            DetailTile(PostStats.number(latest?.comments), "Komentar", Modifier.weight(1f))
            DetailTile(PostStats.number(latest?.shares), "Share", Modifier.weight(1f))
            DetailTile(PostStats.rate(latest?.numbers()?.engagementRate), "Engagement", Modifier.weight(1.1f), highlight = true)
        }
        // Tayangan per periode: hari ke-berapa dari 31, lalu hari 1/7/30 (bergaris putus bila belum tersedia).
        val day = published?.let { java.time.Duration.between(it, Wall.now()).toDays().coerceIn(0, PostStats.MAX_AGE_DAYS) + 1 }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tayangan per periode", style = MaterialTheme.typography.labelLarge, color = c.text, modifier = Modifier.weight(1f))
                day?.let { Text(if (it > PostStats.MAX_AGE_DAYS) "Selesai dipantau" else "Hari $it dari ${PostStats.MAX_AGE_DAYS}", style = MaterialTheme.typography.labelSmall, color = c.textMuted) }
            }
            if (day != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(c.panel)) {
                    Box(Modifier.fillMaxWidth((day.toFloat() / PostStats.MAX_AGE_DAYS).coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(c.accent))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PostStats.AGES.forEach { age ->
                    val value = PostStats.statAt(published, points, age)?.numbers?.views
                    val due = published?.toLocalDate()?.plusDays(age.toLong())
                    AgeTile("Hari $age", value, due?.let { "Tersedia ${IdDate.dayMonth(it)}" }, Modifier.weight(1f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(ClipMiIcons.Clock, null, tint = if (failed) c.warn else c.textMuted, modifier = Modifier.size(14.dp))
            Text(
                when {
                    fetching -> "Mengambil…"
                    failed -> "Gagal diambil: ${post.row.statsError}"
                    latest != null -> "Diperbarui ${IdDate.dayMonthTime(Wall.of(latest.recordedAt))} · via ${StatSource.LABELS[latest.source] ?: latest.source}"
                    post.row.url != null -> "Menunggu"
                    else -> "Belum ada link"
                },
                style = MaterialTheme.typography.bodySmall, color = if (failed) c.warn else c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Buka jadwal", onOpen, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Calendar)
            if (post.row.url != null && !fetching) ActionChip("Ambil ulang", onRefresh, Modifier.weight(1f), height = 44.dp, icon = ClipMiIcons.Refresh, primary = true)
        }
    }
}

@Composable
private fun InlineStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, bold: Boolean = false) {
    val c = ClipMi.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
        Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium), color = if (bold) c.text else c.textMuted)
    }
}

@Composable
private fun DetailTile(value: String, label: String, modifier: Modifier, highlight: Boolean = false) {
    val c = ClipMi.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (highlight) c.accentSoft else c.panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, color = if (highlight) c.accent else c.text)
        androidx.compose.foundation.text.BasicText(
            value, style = style, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
        val labelStyle = MaterialTheme.typography.labelSmall.copy(color = if (highlight) c.accent else c.textMuted)
        androidx.compose.foundation.text.BasicText(
            label, style = labelStyle, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
        )
    }
}

/** Tayangan pada hari ke-n: kotak abu bila ada angkanya, bergaris putus dengan tanggal tersedia bila belum. */
@Composable
private fun AgeTile(label: String, views: Long?, pending: String?, modifier: Modifier) {
    val c = ClipMi.colors
    val shape = RoundedCornerShape(14.dp)
    val box = if (views != null) modifier.clip(shape).background(c.panel) else modifier.clip(shape).dashedBorder(c.lineStrong, 14.dp)
    Column(box.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = c.textMuted)
        Text(PostStats.number(views), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = if (views != null) c.text else c.textMuted)
        Text(if (views != null) "tayangan" else pending ?: "–", style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

private fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
