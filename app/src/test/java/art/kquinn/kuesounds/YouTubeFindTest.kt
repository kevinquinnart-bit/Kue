package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Picking a song's own video from a YouTube search page (25 Nov 2026). The page here is MADE UP in the shape YouTube's
 * search page had when this was written; it was not fetched from YouTube (YouTube can't be reached from where the
 * checks run), so this checks the reading and the order, not that YouTube still sends that shape.
 */
class YouTubeFindTest {
    private fun video(id: String, title: String, channel: String, artistTick: Boolean = false) =
        """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"$channel"}]}""" +
            (if (artistTick) ""","ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED_ARTIST"}}]""" else "") + "}}"

    private val page = "<html><script>var ytInitialData = {\"contents\":{\"twoColumnSearchResultsRenderer\":{\"primaryContents\":{\"sectionListRenderer\":" +
        "{\"contents\":[{\"itemSectionRenderer\":{\"contents\":[" +
        listOf(
            video("aaa", "Stay Another Day (karaoke version)", "Sing King"),
            video("bbb", "East 17 - Stay Another Day (cover)", "Some Singer"),
            video("ccc", "East 17 - Stay Another Day (Official Video)", "East 17", artistTick = true),
            video("ddd", "Stay Another Day", "East 17 - Topic"),
        ).joinToString(",") +
        "]}}]}}}}};</script><script>more()</script></html>"

    @Test
    fun readsEveryVideoOnThePage() {
        assertEquals(listOf("aaa", "bbb", "ccc", "ddd"), YouTube.videosIn(page).map { it.id })
        assertEquals("East 17", YouTube.videosIn(page)[2].channel)
    }

    @Test
    fun theArtistsOwnVideoComesFirstAndKaraokeLast() {
        val order = YouTube.rank(YouTube.videosIn(page), "East 17", "Stay Another Day").map { it.id }
        assertEquals("ccc", order.first())
        assertEquals("ddd", order[1])
        assertEquals(setOf("aaa", "bbb"), order.takeLast(2).toSet())
    }

    @Test
    fun aPageWithNoVideosGivesNone() {
        assertEquals(0, YouTube.videosIn("<html>consent page</html>").size)
    }
}
