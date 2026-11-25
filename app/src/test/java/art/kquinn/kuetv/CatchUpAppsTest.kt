package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Catch-up in the channel's own app: which app a channel's past programmes are in, and the links tried on Kevin's TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatchUpAppsTest {
    @Test fun channelsFindTheirApp() {
        assertEquals("BBC iPlayer", CatchUpApps.appFor("UK: BBC One HD")?.title)
        assertEquals("ITVX", CatchUpApps.appFor("UTV")?.title)
        assertEquals("Channel 4", CatchUpApps.appFor("4seven")?.title)
        assertEquals("5 (Channel 5)", CatchUpApps.appFor("Channel 5 FHD")?.title)
        assertEquals("RTÉ Player", CatchUpApps.appFor("IRE | RTÉ One")?.title)
        assertEquals("Virgin Media Play", CatchUpApps.appFor("Virgin Media One")?.title)
        assertEquals("U (UKTV)", CatchUpApps.appFor("U&Dave")?.title)
        assertNull("a +1 channel has no app of its own", CatchUpApps.appFor("ITV1 +1"))
        assertNull(CatchUpApps.appFor("Sky Sports Main Event"))
    }

    /** channel4://…/programmes/<name> opened Hollyoaks' page on the TV on 25 Nov 2026; the https:// address wasn't taken. */
    @Test fun channel4ProgrammesGoToTheirOwnPage() {
        assertEquals(
            "channel4://www.channel4.com/programmes/the-great-british-bake-off" to "com.channel4.ondemand",
            CatchUpApps.knownLink("Channel 4 HD", "The Great British Bake Off"),
        )
        assertEquals("channel4://www.channel4.com/programmes/hollyoaks", CatchUpApps.knownLink("E4", "Hollyoaks")?.first)
    }

    /** ITV's search answer, cut down from a real one (Coronation Street, 25 Nov 2026). */
    @Test fun itvSearchGivesTheShowAndItsPicture() {
        val json = """{"results":[{"entityType":"programme","data":{"programmeCCId":"bq8xd8w","programmeTitle":"Coronation Street",
            "latestAvailableEpisode":{"episodeCCId":"0ngj9xd","imageHref":"https://ovp.itv.com/images/programme/bq8xd8w/itv_hub/04/16x9?w={width}&h={height}&q={quality}&blur={blur}&bg={bg}"}}}]}"""
        val show = CatchUpApps.itvFrom(json, "Coronation Street")!!
        assertEquals("bq8xd8w", show.id)
        assertEquals("https://ovp.itv.com/images/programme/bq8xd8w/itv_hub/04/16x9?w=640&h=360&q=80&blur=0&bg=false", show.picture)
        assertNull("another show's name doesn't match", CatchUpApps.itvFrom(json, "Emmerdale"))
        assertEquals("coronation-street", CatchUpApps.slug("Coronation Street"))
    }

    @Test fun appsWithNoProgrammeLinkGiveNone() {
        assertNull(CatchUpApps.knownLink("BBC One", "EastEnders"))
        assertNull(CatchUpApps.knownLink("Channel 5", "Neighbours"))
        assertNull(CatchUpApps.knownLink("Sky One", "Anything"))
    }
}
