package id.clipmi.core.youtube

import id.clipmi.core.testing.assertEquals
import id.clipmi.core.testing.assertNull
import kotlin.test.Test

class YtDlpArgsTest {
    @Test
    fun escapesPercentInDirectory() {
        assertEquals("/data/100%%/a.%(ext)s", YtDlpArgs.outputTemplate("/data/100%/", "a.%(ext)s"))
    }

    @Test
    fun parsesProgressLine() {
        assertEquals(42.5f, YtDlpArgs.parseProgress("clipmi-progress  42.5%"))
        assertEquals(100f, YtDlpArgs.parseProgress("clipmi-progress 100.0%"))
        assertNull(YtDlpArgs.parseProgress("[download] Destination: x"))
    }

    @Test
    fun downloadsVideoAndAudioSeparately() {
        val args = YtDlpArgs.media("/cache", "/data/p1")
        assertEquals("bv*[height<=1080][vcodec^=avc1],ba[ext=m4a]", args[args.indexOf("-f") + 1])
        assertEquals("/data/p1/source.stream.%(ext)s", args.last())
    }
}
