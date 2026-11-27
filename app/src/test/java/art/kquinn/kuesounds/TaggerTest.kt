package art.kquinn.kuesounds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks on writing the right cover and details into real music files: short tone files made for the
 * test (MP3, M4A, FLAC with a wrong red cover inside; Ogg with none). No song of Kevin's is used.
 * The fixed copies are left in build/tagger-out so they can be played back to check the sound.
 */
class TaggerTest {
    private fun copyOf(name: String): File {
        val out = File("build/tagger-out").apply { mkdirs() }
        val f = File(out, name)
        javaClass.getResourceAsStream("/tagger/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private val right: File by lazy { copyOf("right.jpg") }

    private fun fixes(name: String, hadPicture: Boolean) {
        val f = copyOf(name)
        val before = Tagger.read(f)
        assertEquals("Wrong Album", before.album)
        assertEquals(hadPicture, before.pictures > 0)
        val length = before.lengthSeconds
        // An old comment inside the file: fixing must take it out with everything else.
        org.jaudiotagger.audio.AudioFileIO.read(f).let { a ->
            a.tagOrCreateAndSetDefault.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "Ripped by somebody")
            org.jaudiotagger.audio.AudioFileIO.write(a)
        }

        Tagger.fix(f, Tagger.Details("Right Song", "Right Singer", "Right Album", "Right Artist", 1995, 3, 1, right))

        val after = Tagger.read(f)
        val tag = org.jaudiotagger.audio.AudioFileIO.read(f).tag
        assertEquals("Right Song", after.title)
        assertEquals("Right Singer", tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST))
        assertEquals("", tag.getFirst(org.jaudiotagger.tag.FieldKey.COMMENT))
        assertTrue(tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK).startsWith("3"))
        assertEquals("Right Album", after.album)
        assertEquals("Right Artist", after.albumArtist)
        assertTrue(after.year.startsWith("1995"))
        assertEquals(1, after.pictures)
        assertArrayEquals(right.readBytes(), after.firstPicture)
        assertEquals(length, after.lengthSeconds)
    }

    @Test fun mp3() = fixes("song.mp3", hadPicture = true)

    @Test fun m4a() = fixes("song.m4a", hadPicture = true)

    @Test fun flac() = fixes("song.flac", hadPicture = true)

    @Test fun ogg() = fixes("song.ogg", hadPicture = false)

    @Test
    fun onlyKnownKindsAreTouched() {
        assertTrue(Tagger.canFix("a.MP3"))
        assertTrue(!Tagger.canFix("a.3ga"))
        assertTrue(!Tagger.canFix("a.aac"))
    }

    // 25 November 2026: 3 of Kevin's M4A files could never be written ("incorrect offsets", "identifier is invalid").
    // Such a file is now rebuilt from an untouched copy and written again; here the rebuild is a plain good M4A.
    @Test
    fun anM4aThatCantBeWrittenIsRebuiltFromAnUntouchedCopyAndWritten() {
        val broken = File("build/tagger-out", "broken.m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4096) { 7 }) }
        var askedWith: ByteArray? = null
        Tagger.fix(broken, Tagger.Details("Right Song", "Right Singer", "Right Album", "Right Artist", 1995, 3, 1, right)) { from, to ->
            askedWith = from.readBytes()
            copyOf("song.m4a").copyTo(to, overwrite = true)
            true
        }
        assertTrue("the rebuild was given the file as it came, untouched", askedWith?.all { it == 7.toByte() } == true)
        assertEquals("Right Album", Tagger.read(broken).album)
        assertEquals(1, Tagger.read(broken).pictures)
    }

    @Test
    fun aLengthMisreadBeforeIsCheckedAgainstThePhonesOwnMeasure() {
        // The file's length as the phone measured it when the music was read stands in when the tag library's own
        // first reading was thrown off: a right length after fixing then passes.
        val f = copyOf("song.mp3")
        val real = Tagger.read(f).lengthSeconds
        Tagger.fix(f, Tagger.Details("Right Song", "Right Singer", "Right Album", "Right Artist", 1995, 3, 1, right), knownSeconds = real)
        assertEquals("Right Album", Tagger.read(f).album)
    }

    // Fix it by hand (27 November 2026): only what is changed changes; everything else in the file stays.
    private fun editsByHand(name: String) {
        val f = File("build/tagger-out", "hand-" + name).also { copyOf(name).copyTo(it, overwrite = true) }
        org.jaudiotagger.audio.AudioFileIO.read(f).let { a ->
            a.tagOrCreateAndSetDefault.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "Keep this")
            org.jaudiotagger.audio.AudioFileIO.write(a)
        }
        val before = Tagger.readAll(f)
        assertEquals("Keep this", before.known[org.jaudiotagger.tag.FieldKey.COMMENT])
        val cannot = Tagger.edit(
            f,
            mapOf(org.jaudiotagger.tag.FieldKey.TITLE to "By Hand", org.jaudiotagger.tag.FieldKey.GENRE to "Folk", org.jaudiotagger.tag.FieldKey.ALBUM to "Hand Album"),
            emptySet(), right,
        )
        assertTrue(cannot.isEmpty())
        val after = Tagger.readAll(f)
        assertEquals("By Hand", after.known[org.jaudiotagger.tag.FieldKey.TITLE])
        assertEquals("Folk", after.known[org.jaudiotagger.tag.FieldKey.GENRE])
        assertEquals("Hand Album", after.known[org.jaudiotagger.tag.FieldKey.ALBUM])
        assertEquals("Keep this", after.known[org.jaudiotagger.tag.FieldKey.COMMENT])
        assertArrayEquals(right.readBytes(), after.picture)
        assertEquals(before.lengthSeconds, after.lengthSeconds)
    }

    @Test fun mp3ByHand() = editsByHand("song.mp3")

    @Test fun m4aByHand() = editsByHand("song.m4a")

    @Test fun flacByHand() = editsByHand("song.flac")
}
