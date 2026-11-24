package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Catch-up in the channel's own app: which app a channel's past programmes are in, and the programme links known. */
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

    @Test fun channel4ProgrammesGoToTheirOwnPage() {
        assertEquals(
            "https://www.channel4.com/programmes/the-great-british-bake-off" to "com.channel4.ondemand",
            CatchUpApps.programmeLink("Channel 4 HD", "The Great British Bake Off"),
        )
        assertEquals("https://www.channel4.com/programmes/hollyoaks", CatchUpApps.programmeLink("E4", "Hollyoaks")?.first)
    }

    @Test fun appsWithNoProgrammeLinkGiveNone() {
        assertNull(CatchUpApps.programmeLink("ITV1", "Coronation Street"))
        assertNull(CatchUpApps.programmeLink("BBC One", "EastEnders"))
        assertNull(CatchUpApps.programmeLink("Sky One", "Anything"))
    }
}
