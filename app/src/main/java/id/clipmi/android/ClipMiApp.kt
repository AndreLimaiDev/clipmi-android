package id.clipmi.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import id.clipmi.android.data.AppDatabase
import id.clipmi.android.domain.AiClient
import id.clipmi.android.domain.Backup
import id.clipmi.android.domain.Analysis
import id.clipmi.android.domain.Ffmpeg
import id.clipmi.android.domain.MediaLibrary
import id.clipmi.android.domain.PerformanceReports
import id.clipmi.android.domain.Renderer
import id.clipmi.android.domain.ProjectActions
import id.clipmi.android.domain.Scheduler
import id.clipmi.android.domain.SpeakerDetector
import id.clipmi.android.domain.SpeakerTracks
import id.clipmi.android.domain.Settings
import id.clipmi.android.domain.TeamPacks
import id.clipmi.android.domain.Transcriber
import id.clipmi.android.domain.Transcripts
import id.clipmi.android.domain.Workspace
import id.clipmi.android.domain.YtDlp
import id.clipmi.android.security.SecretStore

/** Dependency injection manual (tanpa framework DI) agar build dan waktu buka aplikasi tetap ringan. */
class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.build(app) }
    val secrets: SecretStore by lazy { SecretStore(app) }
    val workspace: Workspace by lazy { Workspace(app) }
    val settings: Settings by lazy { Settings(database.settings(), secrets) }
    val transcripts: Transcripts by lazy { Transcripts(database) }
    val ffmpeg: Ffmpeg by lazy { Ffmpeg(app) }
    val ytDlp: YtDlp by lazy { YtDlp(app) }
    val transcriber: Transcriber by lazy { Transcriber(ffmpeg) }
    val actions: ProjectActions by lazy { ProjectActions(app, database, workspace, transcripts) }
    val aiClient: AiClient by lazy { AiClient() }
    val analysis: Analysis by lazy { Analysis(app, database, settings, transcripts) }
    val renderer: Renderer by lazy { Renderer(app, database, workspace, transcripts, settings, ffmpeg) }
    val speakers: SpeakerTracks by lazy { SpeakerTracks(database, workspace, SpeakerDetector(app, ffmpeg)) }
    val media: MediaLibrary by lazy { MediaLibrary(app, database, workspace, ffmpeg) }
    val scheduler: Scheduler by lazy { Scheduler(app, database, analysis) }
    val performance: PerformanceReports by lazy { PerformanceReports(app, database, renderer, analysis, settings) }
    val teamPacks: TeamPacks by lazy { TeamPacks(app, database, workspace, settings, media) }
    val backup: Backup by lazy { Backup(app, database, workspace) }
}

class ClipMiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val work = NotificationChannel(CHANNEL_WORK, getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.channel_work_desc) }
        val reminder = NotificationChannel(CHANNEL_REMINDER, getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = getString(R.string.channel_reminder_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(work, reminder))
    }

    companion object {
        const val CHANNEL_WORK = "work"
        const val CHANNEL_REMINDER = "reminder"
    }
}
