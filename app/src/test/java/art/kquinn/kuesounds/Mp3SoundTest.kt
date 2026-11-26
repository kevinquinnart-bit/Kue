package art.kquinn.kuesounds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The MP3 sound check (26 November 2026): "Haunted" and "Sunday Game Theme Tune" were left unfixed because the tag
 * library read a different length after their details changed. Now the sound bytes themselves are compared, with the
 * details blocks round them left out.
 */
class Mp3SoundTest {
    private val frames = ByteArray(4000) { i -> if (i % 400 == 0) 0xFF.toByte() else if (i % 400 == 1) 0xFB.toByte() else (i * 7).toByte() }

    private fun id3v2(size: Int): ByteArray {
        val h = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte())
        return h + ByteArray(size) { 0x41 }
    }

    private fun id3v1() = "TAG".toByteArray() + ByteArray(125) { 0x20 }

    private fun file(bytes: ByteArray) = File.createTempFile("snd", ".mp3").apply { writeBytes(bytes); deleteOnExit() }

    @Test fun sameSoundWhateverTheDetails() {
        val a = file(id3v2(300) + frames + id3v1())
        val b = file(id3v2(2048) + ByteArray(37) + frames)
        assertArrayEquals(Tagger.mp3Sound(a), Tagger.mp3Sound(b))
    }

    @Test fun aChangedSoundIsSeen() {
        val changed = frames.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        assertFalse(Tagger.mp3Sound(file(id3v2(300) + frames)).contentEquals(Tagger.mp3Sound(file(id3v2(300) + changed))))
    }
}
