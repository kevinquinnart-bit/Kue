package art.kquinn.kuesounds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // 27 November 2026: the two songs were still left after the check above. Files of two kinds the check misjudged,
    // made here as 3-second tones and changed by the tag library itself (before = as made, after = as it wrote them).
    private fun res(name: String) = javaClass.getResourceAsStream("/mp3/$name")!!.readBytes()

    @Test fun strayBytesBeforeTheSoundAreNotSound() = assertTrue(Tagger.soundKept(res("junk_before.mp3"), res("junk_after.mp3")))

    @Test fun aPictureWithAWrongSizeIsNotSound() = assertTrue(Tagger.soundKept(res("badsize_before.mp3"), res("badsize_after.mp3")))

    @Test fun soundCutOffAtTheStartIsCaught() = assertFalse(Tagger.soundKept(res("base_before.mp3"), res("cut_after.mp3")))

    @Test fun fixingSuchFilesWorks() {
        for (name in listOf("junk_before.mp3", "badsize_before.mp3")) {
            val f = file(res(name))
            Tagger.fix(f, Tagger.Details("Haunted", "Shane MacGowan", "Test", "Shane MacGowan", 1995, 1, 1, null))
            assertTrue(Tagger.soundKept(res(name), f.readBytes()))
        }
    }
}
