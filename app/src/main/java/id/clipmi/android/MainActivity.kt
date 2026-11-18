package id.clipmi.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import id.clipmi.android.lab.LabTests
import id.clipmi.android.ui.ClipMiNavigation
import id.clipmi.android.ui.SharedResult
import id.clipmi.android.ui.theme.ClipMiTheme
import id.clipmi.core.youtube.YouTubeUrl
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_SCHEDULE = "open_schedule"
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var shared by mutableStateOf<SharedResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val container = (application as ClipMiApp).container
        setContent {
            ClipMiTheme { ClipMiNavigation(container, shared, onSharedHandled = { shared = null }) }
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
            lifecycleScope.launch {
                // Baru dipulihkan dari cadangan: video hasil didaftarkan lagi ke galeri sebelum pekerjaan lain jalan.
                container.backup.finishRestore()?.let { shared = SharedResult(message = it) }
                container.actions.resumeInterrupted()
                id.clipmi.android.work.RenderQueue.resumeInterrupted(this@MainActivity)
                id.clipmi.android.reminders.ReminderAlarm.arm(this@MainActivity)
                id.clipmi.android.work.StatsQueue.schedulePeriodic(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            @Suppress("DEPRECATION")
            // Izin baca dari aplikasi galeri melekat pada ClipData/data intent; EXTRA_STREAM berisi URI yang sama.
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?: intent.data
            if (uri != null) {
                val container = (application as ClipMiApp).container
                lifecycleScope.launch {
                    shared = runCatching { SharedResult(openProject = container.actions.createFromLocal(uri)) }
                        .getOrElse { SharedResult(message = "Video tidak bisa dibuka: ${it.message}") }
                }
            }
            intent.action = null
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            intent.action = null
            return
        }
        // Notifikasi pengingat: jadwal tertentu (layar Siap Unggah) atau daftar Jadwal (0).
        if (intent.hasExtra(EXTRA_OPEN_SCHEDULE)) {
            val id = intent.getLongExtra(EXTRA_OPEN_SCHEDULE, 0)
            shared = if (id > 0) SharedResult(openSchedule = id) else SharedResult(openScheduleList = true)
            intent.removeExtra(EXTRA_OPEN_SCHEDULE)
            return
        }
        // Uji dari PC: adb shell am start -n id.clipmi.android/.MainActivity --el editor_clip <id>
        if (intent.hasExtra("editor_clip")) {
            shared = SharedResult(openEditor = intent.getLongExtra("editor_clip", 0))
            intent.removeExtra("editor_clip")
            return
        }
        handleLabIntent(intent)
    }

    /** Video dibagikan dari aplikasi YouTube ("Judul https://youtu.be/…"): project baru langsung dibuat. */
    private fun handleSharedText(text: String) {
        val container = (application as ClipMiApp).container
        lifecycleScope.launch {
            val link = YouTubeUrl.firstUrlIn(text) ?: text
            val result = container.actions.createFromYouTube(link)
            shared = when {
                result.created.size == 1 -> SharedResult(openProject = result.created[0])
                result.created.size > 1 -> SharedResult(message = "${result.created.size} project dibuat.")
                else -> SharedResult(message = result.skipped.joinToString("\n").ifEmpty { "Teks yang dibagikan tidak berisi link video YouTube." })
            }
        }
    }

    /** Uji Fase 0 bisa dipicu dari PC: `adb shell am start -n id.clipmi.android/.MainActivity --es lab <uji> ...`. */
    private fun handleLabIntent(intent: Intent) {
        val test = intent.getStringExtra("lab") ?: return
        if (test == "transcribe") {
            // Sama dengan tombol "Transkripsi API" di detail project: --es project <id>
            val projectId = intent.getStringExtra("project")?.toLongOrNull()
            if (projectId != null) {
                val container = (application as ClipMiApp).container
                lifecycleScope.launch { container.actions.startTranscription(projectId) }
            }
            intent.removeExtra("lab")
            return
        }
        if (test == "cancel") {
            // Uji yang terputus dijalankan ulang otomatis oleh WorkManager; "cancel" mengosongkan antrean uji.
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork(id.clipmi.android.work.WorkQueue.LAB.uniqueName)
            intent.removeExtra("lab")
            return
        }
        val params = intent.extras?.keySet().orEmpty()
            .filter { it != "lab" }
            .associateWith { intent.extras?.getString(it).orEmpty() }
        LabTests.enqueue(this, test, params)
        intent.removeExtra("lab")
    }
}
