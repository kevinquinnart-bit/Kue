package art.kquinn.kuetv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Telling a live channel with no sound: the sound passes through unchanged, and only real sound counts as heard. */
class SoundLevelTest {
    private fun pcm(vararg v: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(v.size * 2).order(ByteOrder.nativeOrder())
        v.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    @Test fun peakOfSamples() {
        assertEquals(0f, SoundLevel.peak(shortArrayOf(0, 0)), 0f)
        assertEquals(0.5f, SoundLevel.peak(shortArrayOf(100, -16384, 3)), 0.0001f)
    }

    @Test fun silenceIsNotHeardAndSoundIs() {
        SoundLevel.reset()
        val p = LevelProcessor()
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        assertTrue(SoundLevel.measuring)
        p.queueInput(pcm(0, 1, -2, 3))
        assertEquals(0L, SoundLevel.lastHeardAt)
        p.getOutput()
        p.queueInput(pcm(0, 9000, -9000, 5))
        assertTrue(SoundLevel.lastHeardAt > 0)
        val out = p.getOutput()
        val back = ShortArray(4).also { a -> for (i in a.indices) a[i] = out.short }
        assertArrayEquals(shortArrayOf(0, 9000, -9000, 5), back)
    }

    @Test fun dolbyPassedOnIsNotMeasured() {
        SoundLevel.reset()
        val p = LevelProcessor()
        p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_AC3))
        assertEquals(false, SoundLevel.measuring)
    }
}
