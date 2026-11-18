package id.clipmi.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class SpeakerAnalyzerTest {
    private val step = 0.25
    private val interval = 0.5

    private fun frames(duration: Double, faces: (Double) -> List<FaceObservation>) =
        (0 until (duration / step).toInt()).map { i -> AnalysisFrame(i * step, faces(i * step)) }

    /** Bukaan mulut: bergerak saat berbicara, hampir diam saat mendengarkan. */
    private fun mouth(t: Double, speaking: Boolean) = if (speaking) 0.25 + 0.15 * sin(t * 9) else 0.04 + 0.003 * sin(t * 5)

    @Test
    fun singleSpeakerFollowsFace() {
        val result = SpeakerAnalyzer.analyze(frames(10.0) { t -> listOf(FaceObservation(0.3 + 0.01 * sin(t), 0.12)) }, interval, 10.0, emptyList())
        assertEquals(20, result.samples.size)
        assertTrue(result.samples.all { it != null && abs(it - 0.3) < 0.02 })
        assertTrue(result.switches.isEmpty())
    }

    @Test
    fun activeSpeakerByMouthAndSnappedSwitch() {
        val result = SpeakerAnalyzer.analyze(
            frames(12.0) { t ->
                listOf(FaceObservation(0.25, 0.1, mouth(t, t < 6.0)), FaceObservation(0.75, 0.11, mouth(t, t >= 6.0)))
            },
            interval, 12.0, emptyList(), boundaries = listOf(3.1, 6.6, 9.0),
        )
        assertEquals(listOf(6.6), result.switches)
        assertEquals(listOf(6.6), result.cuts)
        // Sebelum pergantian crop di pembicara kiri, sesudahnya di kanan.
        assertEquals(0.25, result.samples[4]!!, 1e-9)
        assertEquals(0.25, result.samples[12]!!, 1e-9)
        assertEquals(0.75, result.samples[14]!!, 1e-9)
        assertEquals(0.75, result.samples[22]!!, 1e-9)
        val keyframes = SpeakerTrack.build(result.samples, interval, 12.0, result.cuts)
        assertEquals(0.25, SpeakerTrack.positionAt(keyframes, 6.5), 1e-9)
        assertEquals(0.75, SpeakerTrack.positionAt(keyframes, 6.7), 1e-9)
    }

    @Test
    fun audienceFacesAndShortGlitchesIgnored() {
        val result = SpeakerAnalyzer.analyze(
            frames(8.0) { t ->
                val speaker = if (abs(t - 4.0) < 0.1) emptyList() else listOf(FaceObservation(0.6, 0.15, mouth(t, true)))
                // Jamaah kecil yang mulutnya "bergerak" tidak dianggap pembicara.
                speaker + FaceObservation(0.1, 0.04, mouth(t, true))
            },
            interval, 8.0, emptyList(),
        )
        assertTrue(result.switches.isEmpty())
        assertTrue(result.samples.filterNotNull().all { abs(it - 0.6) < 1e-9 })
    }

    @Test
    fun cameraCutsFromBrightness() {
        val luma = (0 until 60).map { k -> IntArray(32 * 18) { if (k < 30) 40 + (k % 3) else 200 - (k % 2) } }
        val cuts = SpeakerAnalyzer.cameraCuts(luma)
        assertEquals(1, cuts.size)
        assertEquals(29.5 / 15, cuts[0], 1e-9)
        assertTrue(SpeakerAnalyzer.cameraCuts(List(40) { IntArray(10) { 90 } }).isEmpty())
    }

    @Test
    fun manualPositionAndRetarget() {
        val analysis = SpeakerTrackData.Analysis(100.0, 120.0, 0.5, List(40) { k -> if (k < 20) 0.3 else 0.7 }, listOf(110.0))
        val track = SpeakerTrackData(100.0, 120.0, analysis = analysis).rebuilt()
        assertEquals(0.3, SpeakerTrack.positionAt(track.keyframes, 5.0), 1e-9)
        assertEquals(0.7, SpeakerTrack.positionAt(track.keyframes, 15.0), 1e-9)

        val shots = track.shots()
        assertEquals(2, shots.size)
        val manual = track.withManual(shots[1], 0.55)
        assertEquals(0.55, manual.shots()[1].manual!!, 1e-9)
        assertEquals(0.55, SpeakerTrack.positionAt(manual.keyframes, 15.0), 1e-9)
        assertEquals(0.3, SpeakerTrack.positionAt(manual.keyframes, 5.0), 1e-9)

        // Klip dipendekkan: tanpa deteksi ulang, posisi manual tetap di adegannya.
        assertTrue(manual.covers(104.0, 118.0))
        val shorter = manual.retarget(104.0, 118.0)
        assertEquals(listOf(6.0), shorter.cuts())
        assertEquals(0.3, SpeakerTrack.positionAt(shorter.keyframes, 1.0), 1e-9)
        assertEquals(0.55, SpeakerTrack.positionAt(shorter.keyframes, 10.0), 1e-9)
        assertNull(shorter.withManual(shorter.shots()[1], null).shots()[1].manual)
        assertTrue(!manual.covers(95.0, 118.0))
    }

    @Test
    fun speakerSwitchGlidesButCameraCutJumps() {
        // Sampel tiap detik: 0,3 sampai detik 5, lalu 0,7. Potongan di detik 5.
        val samples = (0..10).map { if (it < 5) 0.3 else 0.7 }
        val jump = SpeakerTrack.build(samples, 1.0, 10.0, listOf(5.0))
        assertEquals(0.3, SpeakerTrack.positionAt(jump, 4.99), 1e-9)
        assertEquals(0.7, SpeakerTrack.positionAt(jump, 5.0), 1e-9)

        val glide = SpeakerTrack.build(samples, 1.0, 10.0, listOf(5.0), switches = listOf(5.0))
        val middle = SpeakerTrack.positionAt(glide, 5.0 + SpeakerTrack.SWITCH_GLIDE / 2)
        assertTrue("di tengah pergeseran: $middle", middle > 0.4 && middle < 0.6)
        assertEquals(0.3, SpeakerTrack.positionAt(glide, 5.0), 1e-9)
        assertEquals(0.7, SpeakerTrack.positionAt(glide, 5.0 + SpeakerTrack.SWITCH_GLIDE), 1e-6)
        // Awal pergeseran pelan (smoothstep): sepersepuluh waktu pertama bergeser jauh kurang dari sepersepuluh jarak.
        val early = SpeakerTrack.positionAt(glide, 5.0 + SpeakerTrack.SWITCH_GLIDE / 6)
        assertTrue("awal pelan: $early", early - 0.3 < 0.04)
    }

    @Test
    fun cameraCutsAtThirtyFpsUseSameSeconds() {
        // 3 detik pada 30 fps, gambar berganti tepat di frame 45 (detik 1,5).
        val frames = (0 until 90).map { k -> IntArray(16) { if (k < 45) 20 else 200 } }
        val cuts = SpeakerAnalyzer.cameraCuts(frames, 30.0)
        assertEquals(1, cuts.size)
        assertEquals(44.5 / 30, cuts[0], 1e-9)
    }
}
