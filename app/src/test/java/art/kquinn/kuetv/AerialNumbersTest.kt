package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The aerial channel file in the app, and provider names matched to its numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AerialNumbersTest {
    private val plans = AerialLists.parse(File("src/main/assets/aerial-channels.json").readText())

    @Before fun freely() = AerialLists.setForChecks(plans, "GB-freely")

    @Test fun theFileOnlineIsTheOneInTheApp() {
        assertEquals(File("../channels/aerial-channels.json").readText(), File("src/main/assets/aerial-channels.json").readText())
        assertEquals(listOf("GB-freely", "GB-freeview", "IE-saorview", "FR-tnt", "IT-dtt", "AU-freeview", "NZ-freeview"), plans.map { it.id })
    }

    @Test fun namesAsProvidersWriteThem() {
        assertEquals(245, Aerial.numberFor("4 Seven"))
        assertEquals(245, Aerial.numberFor("UK: 4seven HD"))
        assertEquals(233, Aerial.numberFor("ITV 1 HD"))
        assertEquals(254, Aerial.numberFor("UK| ITV2 +1"))
        assertEquals(252, Aerial.numberFor("Channel 4 plus 1"))
        assertEquals(301, Aerial.numberFor("BBC News"))
        assertEquals(259, Aerial.numberFor("U&Dave ja vu"))
        assertEquals(1, Aerial.numberFor("UK: BBC One HD"))
        assertEquals(2, Aerial.numberFor("BBC Two"))
    }

    @Test fun notOnTheAerial() {
        assertEquals(null, Aerial.numberFor("Sky Sports Main Event"))
    }

    /** Freely's own numbers (freely.co.uk, 26 November 2026): 1, 4 and 5 opened the right channel on Kevin's TV. */
    @Test fun freelysOwnNumbers() {
        assertEquals(5, Aerial.numberFor("Channel 5"))
        assertEquals(5, Aerial.numberFor("UK: 5 HD"))
        assertEquals(19, Aerial.numberFor("Dave"))
        assertEquals(19, Aerial.numberFor("U&Dave HD"))
        assertEquals(20, Aerial.numberFor("Drama"))
        assertEquals(10, Aerial.numberFor("BBC Three"))
        assertEquals(351, Aerial.numberFor("CBBC"))
    }

    @Test fun eachCountryItsOwnNumbers() {
        AerialLists.setForChecks(plans, "IE-saorview")
        assertEquals(1, Aerial.numberFor("IE: RTÉ One HD"))
        assertEquals(4, Aerial.numberFor("TG4"))
        AerialLists.setForChecks(plans, "GB-freeview")
        assertEquals(49, Aerial.numberFor("4seven"))
        assertEquals(101, Aerial.numberFor("BBC One"))
    }

    @Test fun thePlatformPickedByItself() {
        assertEquals("GB-freely", AerialLists.pickFor(plans, "GB") { it == "com.vewd.core.opapp" }?.id)
        assertEquals("GB-freeview", AerialLists.pickFor(plans, "GB") { false }?.id)
        assertEquals("IE-saorview", AerialLists.pickFor(plans, "IE") { false }?.id)
        assertEquals("FR-tnt", AerialLists.pickFor(plans, "FR") { false }?.id)
        assertEquals("AU-freeview", AerialLists.pickFor(plans, "AU") { false }?.id)
        assertEquals(null, AerialLists.pickFor(plans, "US") { false })
    }

    @Test fun ownNumbersReadFromLines() {
        val c = AerialLists.ownChannels("101 BBC One\r\n103 - ITV1\nChannel 4: 104\n\nnonsense\n4.1 NBC")
        assertEquals(listOf(101 to "BBC One", 103 to "ITV1", 104 to "Channel 4"), c.map { it.number to it.name })
    }

    @Test fun ownNumbersReadFromAChannelList() {
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-chno=\"7\" tvg-name=\"x\",Seven\nhttp://a/b\n#EXTINF:-1,No number\nhttp://c"
        assertEquals(listOf(7 to "Seven"), AerialLists.ownChannels(m3u).map { it.number to it.name })
    }
}
