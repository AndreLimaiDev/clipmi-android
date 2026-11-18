package id.clipmi.android.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.clipmi.android.ClipMiApp
import id.clipmi.android.data.ClipEntity
import id.clipmi.android.data.RenderStatus
import id.clipmi.android.domain.FfmpegException
import id.clipmi.android.domain.RenderException
import id.clipmi.core.analysis.ClipText
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Render satu klip di antrean render (padanan RenderClip desktop): klip-klip dirender berurutan, tetap jalan saat
 * aplikasi ditutup, dengan progres per klip di notifikasi.
 */
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as ClipMiApp).container
    private val clips = container.database.clips()
    private val clipId = inputData.getLong(KEY_CLIP, 0)
    private var lastProgress = -10

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, WorkQueue.RENDER, "Merender klip", "Menyiapkan…", null)

    override suspend fun doWork(): Result {
        val clip = clips.find(clipId) ?: return Result.success()
        // Dibatalkan pengguna selagi antre.
        if (!clip.render.isBusy) return Result.success()
        // HP terlalu panas: tunggu dingin dulu (dicoba lagi tiap menit); antrean di belakangnya ikut menunggu.
        RenderSafety.thermalReason(applicationContext)?.let { reason ->
            clips.setRender(clipId, RenderStatus.Queued.value, null, reason)
            return Result.retry()
        }
        val title = ClipText.displayTitle(clip.title, clip.partNumber)

        clips.setRender(clipId, RenderStatus.Rendering.value, 0, null)
        notify(title, 0)
        return try {
            val uri = container.renderer.render(clip) { progress ->
                if (progress - lastProgress >= 2) {
                    lastProgress = progress
                    kotlinx.coroutines.runBlocking { clips.setRender(clipId, RenderStatus.Rendering.value, progress, null) }
                    setForegroundAsync(foregroundInfo(applicationContext, WorkQueue.RENDER, "Merender klip", "$title · $progress%", progress))
                }
            }
            clips.renderDone(clipId, uri.toString())
            Result.success()
        } catch (e: CancellationException) {
            clips.setRender(clipId, RenderStatus.NotRendered.value, null, null)
            throw e
        } catch (e: Throwable) {
            val message = when (e) {
                is RenderException -> e.message
                is FfmpegException -> "FFmpeg gagal: ${e.message}"
                else -> e.message ?: e.javaClass.simpleName
            }
            clips.setRender(clipId, RenderStatus.Failed.value, null, message)
            Result.success()
        }
    }

    private suspend fun notify(title: String, progress: Int?) {
        runCatching { setForeground(foregroundInfo(applicationContext, WorkQueue.RENDER, "Merender klip", title, progress)) }
    }

    companion object {
        const val KEY_CLIP = "clip"

        fun tag(clipId: Long) = "render-$clipId"
    }
}

/** Antrean render: masuk antre, batal, dan pemulihan setelah proses dimatikan sistem. */
object RenderQueue {
    suspend fun render(context: Context, clip: ClipEntity) {
        val container = (context.applicationContext as ClipMiApp).container
        container.database.clips().setRender(clip.id, RenderStatus.Queued.value, null, null)
        enqueue(context, clip.id)
    }

    /**
     * Aman baterai: render baru mulai saat baterai tidak lemah (sistem: di atas ±15% atau sedang dicas); saat HP terlalu
     * panas, render dicoba lagi tiap menit (lihat [RenderSafety]).
     */
    private fun enqueue(context: Context, clipId: Long) = WorkQueue.RENDER.enqueue(
        context,
        OneTimeWorkRequestBuilder<RenderWorker>()
            .setInputData(workDataOf(RenderWorker.KEY_CLIP to clipId))
            .addTag(RenderWorker.tag(clipId))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .build(),
    )

    suspend fun cancel(context: Context, clip: ClipEntity) {
        WorkManager.getInstance(context).cancelAllWorkByTag(RenderWorker.tag(clip.id))
        val container = (context.applicationContext as ClipMiApp).container
        // Hasil render sebelumnya (bila ada) tetap dipakai.
        val previous = if (clip.outputPath != null && clip.renderedAt != null) RenderStatus.Done else RenderStatus.NotRendered
        container.database.clips().setRender(clip.id, previous.value, if (previous == RenderStatus.Done) 100 else null, null)
    }

    /** Klip yang masih "antre/merender" tanpa pekerjaan WorkManager yang hidup dimasukkan lagi ke antrean. */
    suspend fun resumeInterrupted(context: Context) {
        val container = (context.applicationContext as ClipMiApp).container
        val manager = WorkManager.getInstance(context)
        for (clip in container.database.clips().busyRenders()) {
            val alive = runCatching { manager.getWorkInfosByTag(RenderWorker.tag(clip.id)).get() }.getOrNull().orEmpty().any { !it.state.isFinished }
            if (!alive) {
                container.database.clips().setRender(clip.id, RenderStatus.Queued.value, null, null)
                enqueue(context, clip.id)
            }
        }
    }

}

/** Render massal aman baterai dan suhu: alasan menunda render, untuk antrean dan untuk ditampilkan di kartu klip. */
object RenderSafety {
    /** Batas baterai yang dianggap lemah oleh WorkManager (`requiresBatteryNotLow`) kira-kira 15%. */
    private const val LOW_BATTERY = 15

    /** HP terlalu panas (status termal parah atau lebih): render ditunda. */
    fun thermalReason(context: Context): String? {
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return null
        return if (power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) "Dijeda: HP terlalu panas. Render lanjut otomatis setelah dingin." else null
    }

    /** Baterai lemah dan tidak dicas: render menunggu (lanjut otomatis saat dicas atau baterai cukup). */
    fun batteryReason(context: Context): String? {
        val battery = context.getSystemService(android.os.BatteryManager::class.java) ?: return null
        val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..LOW_BATTERY && !battery.isCharging) "Dijeda: baterai $level%. Render lanjut otomatis saat HP dicas." else null
    }

    fun reason(context: Context): String? = batteryReason(context) ?: thermalReason(context)
}
