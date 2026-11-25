package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics with timings (25 Nov 2026). The words here are made up; no real song's words are kept in the code. */
class LyricsTest {
    private val lrc = "[00:01.50] First line here\n[00:04.20]Second line\n[00:04.20][00:12.00] Said twice\nno time on this one\n[01:02.345] Last"

    @Test
    fun linesAreReadWithTheirTimes() {
        val lines = Lyrics.parse(lrc)
        assertEquals(listOf(1_500L, 4_200L, 4_200L, 12_000L, 62_345L), lines.map { it.at })
        assertEquals("First line here", lines[0].words)
        assertEquals("Last", lines.last().words)
    }

    @Test
    fun theLineBeingSungFollowsTheSong() {
        val lines = Lyrics.parse(lrc)
        assertEquals(-1, Lyrics.lineAt(lines, 1_000))
        assertEquals(0, Lyrics.lineAt(lines, 2_000))
        assertEquals(4, Lyrics.lineAt(lines, 70_000))
    }

    @Test
    fun anAnswerWithOnlyPlainWordsIsKept() {
        val w = Lyrics.read("""{"plainLyrics":"just words","syncedLyrics":null,"instrumental":false}""")
        assertTrue(w.timed.isEmpty())
        assertEquals("just words", w.plain)
    }
}
