package id.clipmi.android.lab

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/** Info HP untuk catatan uji: model, suhu baterai, status termal, encoder H.264, dan ukuran bagian APK. */
object DeviceProbe {
    fun describe(context: Context): Map<String, Any?> = mapOf(
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "soc" to if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ram_mb" to (context.getSystemService(android.app.ActivityManager::class.java)
            .let { am -> android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / 1_048_576 }),
        "h264_encoders" to h264Encoders().joinToString("; "),
    ) + thermal(context) + apkSizes(context)

    fun thermal(context: Context): Map<String, Any?> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        return mapOf(
            "battery_temp_c" to battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0),
            "battery_pct" to battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) },
            "thermal_status" to power.currentThermalStatus,
            "thermal_headroom" to if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(0).takeIf { !it.isNaN() } else null,
        )
    }

    private fun h264Encoders(): List<String> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
        .map { info ->
            val caps = info.getCapabilitiesForType("video/avc").videoCapabilities
            val hw = if (info.isHardwareAccelerated) "hw" else "sw"
            if (caps == null) "${info.name} [$hw]"
            else "${info.name} [$hw] ${caps.supportedWidths.upper}x${caps.supportedHeights.upper} " +
                "1080x1920@30=${caps.areSizeAndRateSupported(1080, 1920, 30.0)}"
        }

    fun apkSizes(context: Context): Map<String, Any?> {
        val info = context.applicationInfo
        val libs = File(info.nativeLibraryDir).listFiles().orEmpty().associate { it.name to it.length() }
        return mapOf(
            "apk_bytes" to File(info.sourceDir).length(),
            "native_libs" to libs.entries.joinToString("; ") { "${it.key}=${it.value / 1024}KB" },
        )
    }
}
