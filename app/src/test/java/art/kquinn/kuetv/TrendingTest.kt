package art.kquinn.kuetv

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The trending banner on Home: JustWatch's answer read correctly, and the banner shown on a TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class TrendingTest {
    @get:Rule val rule = createComposeRule()

    // Shaped as JustWatch answered on 18 November 2026 for Channel 4's trending series.
    private val answer = """{"data":{"popularTitles":{"totalCount":1088,"edges":[
        {"node":{"objectType":"SHOW","content":{"title":"Hunting Alice Bell","shortDescription":"When Fran is wrongly accused online.","externalIds":{"imdbId":"tt37253101"},
        "posterUrl":"/poster/357173269/{profile}/hunting-alice-bell.{format}","backdrops":[{"backdropUrl":"/backdrop/357173271/{profile}/hunting-alice-bell.{format}"}]}}},
        {"node":{"objectType":"MOVIE","content":{"title":"No Picture","shortDescription":null,"posterUrl":"/poster/1/{profile}/x.{format}","backdrops":[]}}}]}}}"""

    @Test fun justWatchsAnswerIsRead() {
        val slides = Trending.parse("Channel 4", answer)
        assertEquals("a title with no wide picture is left out", 1, slides.size)
        val s = slides[0]
        assertEquals("Hunting Alice Bell", s.title)
        assertEquals("Channel 4", s.service)
        assertTrue(s.show)
        assertEquals("https://images.justwatch.com/backdrop/357173271/s1440/hunting-alice-bell.jpg", s.picture)
        assertEquals("https://images.justwatch.com/poster/357173269/s332/hunting-alice-bell.jpg", s.poster)
    }

    @Test fun theBannerIsAcrossTheTopOfHome() {
        Trending.setForChecks(listOf(Trending.Slide("Netflix", "A Trending Show", "What it is about.", "", "", true)))
        val library = Library(listOf(Category("1", "UK")), listOf(Channel("10", "BBC One", "", "1", "http://tv/10.ts", 0)), emptyList(), emptyList())
        rule.setContent { HomeForChecks(library, Favourites(RuntimeEnvironment.getApplication())) }
        rule.waitForIdle()
        val shown: (String) -> Boolean = { rule.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(shown("A Trending Show") && shown("Trending · Netflix") && shown("What it is about."))
        Trending.setForChecks(emptyList())
    }

    @Test fun mostPopularShowsComeInBetweenTheTrendingOnes() {
        fun slide(t: String, popular: Boolean = false) = Trending.Slide("Netflix", t, "", "", "", true, popular)
        val mixed = Trending.mix(listOf(slide("T1"), slide("T2"), slide("T3")), listOf(slide("P1", true), slide("P2", true)))
        assertEquals(listOf("T1", "P1", "T2", "P2", "T3"), mixed.map { it.title })
        assertEquals(listOf(false, true, false, true, false), mixed.map { it.popular })
    }
}
