package id.clipmi.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.clipmi.android.data.PostStatEntity
import id.clipmi.android.data.ScheduleAccountEntity
import id.clipmi.android.ui.components.ActionChip
import id.clipmi.android.ui.components.ChipRow
import id.clipmi.android.ui.components.ClipMiCard
import id.clipmi.android.ui.components.EmptyState
import id.clipmi.android.ui.components.FloatingNavSpace
import id.clipmi.android.ui.components.IconCircle
import id.clipmi.android.ui.components.ProgressBar
import id.clipmi.android.ui.components.ScreenGutter
import id.clipmi.android.ui.components.bleed
import id.clipmi.android.ui.theme.ClipMi
import id.clipmi.android.ui.theme.ClipMiIcons
import id.clipmi.core.schedule.AccountSummary
import id.clipmi.core.schedule.IdDate
import id.clipmi.core.schedule.PostStats
import id.clipmi.core.schedule.StatSource
import id.clipmi.core.schedule.TargetStatus
import id.clipmi.core.schedule.wallMillis
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

// Tab Statistik iOS, padanan StatsScreen Android (tanpa Analisis performa AI, yang menyusul bersama mode API).

/** Satu postingan terbit: akun tujuan jadwal beserta jadwal dan catatan statistiknya (urut waktu). */
private data class Post(val schedule: ScheduleView, val target: TargetView, val stats: List<PostStatEntity>) {
    val row: ScheduleAccountEntity get() = target.row
    val latest: PostStatEntity? get() = stats.lastOrNull()
}

/**
 * Tab Statistik: ringkasan (total tayangan, engagement, suka/komentar/share), rincian per akun dengan batang, dan daftar
 * postingan (ketuk untuk angka, tayangan hari 1/7/30, dan aksi). Statistik yang sudah waktunya diambil saat tab dibuka.
 */
@Composable
fun StatsScreen(repo: Repository, engine: MediaEngine, scheduler: IosScheduler, downloads: Downloads, onOpenSchedule: (Long) -> Unit) {
    val data by rememberScheduleData(repo)
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<Long?>(null) }
    var fetching by remember { mutableStateOf(setOf<Long>()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var best by remember { mutableStateOf(false) }
    val c = ClipMi.colors

    fun fetch(ids: List<Long>, report: Boolean) {
        if (ids.isEmpty() || fetching.isNotEmpty()) return
        fetching = ids.toSet()
        scope.launch {
            scheduler.fetchStats(ids)
            val failed = ids.count { id -> repo.db.schedules().target(id)?.statsError != null }
            fetching = emptySet()
            if (report) banner = if (failed > 0) "$failed statistik gagal diambil; alasannya ada di kartu postingan." else null
        }
    }

    // iOS tidak mengambil statistik di latar: yang sudah waktunya diambil saat tab ini dibuka (bukan saat mengunduh video).
    LaunchedEffect(Unit) {
        if (downloads.active == null && fetching.isEmpty()) {
            fetching = setOf(-1L)
            runCatching { scheduler.refreshDueStats() }
            fetching = emptySet()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp)) {
        val d = data
        val all = d?.schedules.orEmpty()
            .flatMap { s -> s.targets.filter { it.status == TargetStatus.PUBLISHED }.map { Post(s, it, d?.stats?.get(it.row.id).orEmpty()) } }
            .sortedByDescending { it.row.publishedAt ?: 0 }
            .take(300)
        val posts = all.filter { account == null || it.target.account.id == account }

        Row(Modifier.padding(horizontal = ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Statistik", style = MaterialTheme.typography.headlineMedium, color = c.text)
                if (all.isNotEmpty()) Text("${PostStats.MAX_AGE_DAYS} hari setelah terbit · diambil saat tab dibuka", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            if (all.isNotEmpty()) {
                if (fetching.isNotEmpty()) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp) }
                else IconCircle(ClipMiIcons.Refresh, "Perbarui semua statistik", {
                    scope.launch {
                        val ids = repo.db.schedules().publishedWithLink()
                        if (ids.isEmpty()) banner = "Belum ada postingan terbit yang punya link." else fetch(ids, report = true)
                    }
                })
            }
        }
        if (d == null) return@Column
        if (all.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = FloatingNavSpace)) {
                EmptyState("Belum ada postingan terbit", "Tandai jadwal terbit dan tempel link postingannya; statistiknya diambil otomatis.", icon = ClipMiIcons.Chart)
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
            if (fetching.size > 1) {
                item(key = "progress") {
                    ClipMiCard {
                        Text("Mengambil statistik ${fetching.size} postingan…", style = MaterialTheme.typography.bodyMedium, color = c.text)
                        ProgressBar(null)
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
                        d.accounts.forEach { a -> AccountRow(a.name, a.color, summaries[a.id], totals.getValue(a.id).toFloat() / max) { account = a.id } }
                    }
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
                PostCard(repo, engine, post, fetching = post.row.id in fetching, onOpen = { onOpenSchedule(post.schedule.id) }, onRefresh = { fetch(listOf(post.row.id), report = false) })
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
        Modifier.height(38.dp).clip(shape).background(if (selected) c.text else c.card).border(1.dp, if (selected) c.text else c.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
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
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
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
private fun MiniTile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    val c = ClipMi.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (value == "0" || value == "–") c.textMuted else c.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
    }
}

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
            if (share > 0f) Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(statColor(color)))
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ClipMi.colors
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.panel).padding(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.card else Color.Transparent).clickable { onSelect(i) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) c.text else c.textMuted) }
        }
    }
}

/** Postingan: gambar, judul, akun · tanggal, angka sebaris; ketuk untuk angka lengkap, hari 1/7/30, dan aksi. */
@Composable
private fun PostCard(repo: Repository, engine: MediaEngine, post: Post, fetching: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit) {
    val c = ClipMi.colors
    val latest = post.latest
    val published = post.row.publishedAt?.let(Wall::of)
    val points = post.stats.points()
    var expanded by remember(post.row.id) { mutableStateOf(false) }
    val checkedAt = post.row.statsCheckedAt
    val failed = post.row.statsError != null && checkedAt != null && (latest == null || checkedAt > latest.recordedAt)
    ClipMiCard(onClick = { expanded = !expanded }, padding = PaddingValues(12.dp), spacing = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ClipThumbnail(repo, engine, post.schedule.clip, Modifier.width(56.dp).height(76.dp))
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
        val day = published?.let { ((Wall.now().wallMillis() - it.wallMillis()) / 86_400_000L).coerceIn(0, PostStats.MAX_AGE_DAYS) + 1 }
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
                    val due = published?.date?.plus(age, DateTimeUnit.DAY)
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
private fun InlineStat(icon: ImageVector, value: String, bold: Boolean = false) {
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
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = if (highlight) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (highlight) c.accent else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

private fun statColor(hex: String): Color =
    hex.trimStart('#').takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.Gray
