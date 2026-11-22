package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastsTest {
    @Test
    fun episodeLengthsAreReadEveryWayFeedsWriteThem() {
        assertEquals(3723L, Podcasts.duration("1:02:03"))
        assertEquals(3723L, Podcasts.duration("62:03"))
        assertEquals(3723L, Podcasts.duration("3723"))
        assertEquals(0L, Podcasts.duration("about an hour"))
    }

    @Test
    fun minutesAreSaidPlainly() {
        assertEquals("45 min", Podcasts.minutes(45 * 60_000L))
        assertEquals("1 hr 5 min", Podcasts.minutes(65 * 60_000L))
        assertEquals("2 hr ", Podcasts.minutes(120 * 60_000L))
    }
}
