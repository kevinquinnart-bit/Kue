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
        assertEquals(listOf("GB-freely", "GB-freeview", "IE-saorview"), plans.map { it.id })
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
        assertEquals(null, Aerial.numberFor("Channel 5"))
        assertEquals(null, Aerial.numberFor("Sky Sports Main Event"))
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
        assertEquals(null, AerialLists.pickFor(plans, "FR") { false })
    }
}
