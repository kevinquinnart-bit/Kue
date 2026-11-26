package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics ran half a minute ahead on 10cc's "Dreadlock Holiday" (Kevin, 26 November 2026): his file is 5:01 and the
 * first version LRCLIB gave was 4:25. LRCLIB's real answer (saved 26 Nov, its words replaced by "line N") has a
 * 4:58 version; that one's timings must be taken, and a far-off one's never.
 */
class LyricsChooseTest {
    private val list = javaClass.getResourceAsStream("/lyrics/dreadlock_search.json")!!.readBytes().decodeToString()

    @Test fun takesTheVersionOfTheSameLength() {
        val w = Lyrics.read(Lyrics.choose(list, 301)!!)
        assertEquals(19_160L, w.timed.first().at)
    }

    @Test fun neverTakesTimingsFromAnotherLength() {
        // A 3:30 song: no version that long, so the words come without timings.
        val w = Lyrics.read(Lyrics.choose(list, 210)!!)
        assertTrue(w.timed.isEmpty())
        assertTrue(w.plain.isNotBlank())
    }
}
