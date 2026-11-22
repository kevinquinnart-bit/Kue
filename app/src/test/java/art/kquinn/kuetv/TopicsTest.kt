package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live TV's categories in topics: the provider's and the free lists' names land in the right one. */
class TopicsTest {
    @Test fun namesLandInTheRightTopic() {
        assertEquals("sport", Topics.of("UK | Sport"))
        assertEquals("sport", Topics.of("IE | SPORTS"))
        assertEquals("sport", Topics.of("Free: Football"))
        assertEquals("news", Topics.of("UK | News"))
        assertEquals("kids", Topics.of("Kids Movies"))
        assertEquals("films", Topics.of("UK | Movies"))
        assertEquals("ent", Topics.of("UK | Entertainment"))
        assertEquals("ent", Topics.of("IE | General"))
        assertEquals("docs", Topics.of("Documentary"))
        assertEquals("other", Topics.of("Adult"))
        assertEquals("sport", Topics.of("Todays Live Events"))
        assertEquals("docs", Topics.of("Free: Education (Watchio.live TV)"))
        assertEquals("ent", Topics.of("Free: United Kingdom (Watchio.live TV)"))
    }

    @Test fun sportNamesKevinFound() {
        assertEquals("sport", Topics.of("UK | FA Cup"))
        assertEquals("sport", Topics.of("IE | LOI"))
        assertEquals("sport", Topics.of("IE | GAA"))
        assertEquals("other", Topics.of("IE | Reflections"))
    }

    @Test fun aCategoryOfSportChannelsGoesUnderSport() {
        fun ch(name: String, cat: String) = Channel(name, name, "", cat, "http://x/$name", 0)
        val cats = listOf(Category("a", "UK | Free To Air"), Category("b", "IE | Channels"))
        val channels = listOf(
            ch("LOI 11 | (Events Only)", "b"), ch("LOI 12 | (Events Only)", "b"), ch("FA Cup 01 | (No Match)", "b"), ch("RTE One", "b"),
            ch("BBC One", "a"), ch("BBC Two", "a"), ch("ITV1", "a"), ch("Sky Sports Main Event", "a"),
        )
        val topics = Topics.byCategory(cats, channels)
        assertEquals("sport", topics["b"])
        assertEquals("ent", topics["a"])
    }

    @Test fun onlyTopicsWithChannelsShow() {
        val cats = listOf(Category("1", "UK | Sport"), Category("2", "UK | News"), Category("free:Sports", "Free: Sports"))
        val shown = Topics.categories(Topics.byCategory(cats), setOf("1", "free:Sports"))
        assertEquals(listOf("topic:sport"), shown.map { it.id })
    }
}

/** The TV's apps land in the right row on Home. */
class AppKindsTest {
    private fun kind(name: String, pkg: String) = AppKinds.of(DeviceApps.App(pkg, name, null))
    @org.junit.Test fun appsLandInTheRightRow() {
        org.junit.Assert.assertEquals("tv", kind("BBC iPlayer", "uk.co.bbc.iplayer"))
        org.junit.Assert.assertEquals("tv", kind("RTÉ Player", "com.twentyfouri.tvbridge.rte"))
        org.junit.Assert.assertEquals("films", kind("YouTube", "com.amazon.firetv.youtube"))
        org.junit.Assert.assertEquals("films", kind("Netflix", "com.netflix.ninja"))
        org.junit.Assert.assertEquals("music", kind("Spotify", "com.spotify.tv.android"))
        org.junit.Assert.assertEquals("other", kind("Downloader", "com.esaba.downloader"))
    }
}

/** A provider's channel names lead to the right channel in its own app. */
class LiveInAppsTest {
    @org.junit.Test fun providerNamesFindTheirApp() {
        org.junit.Assert.assertEquals("ITV1", LiveInApps.forChannel("ITV 1 HD")?.name)
        org.junit.Assert.assertEquals("ITV1", LiveInApps.forChannel("UK: ITV1")?.name)
        org.junit.Assert.assertEquals("Channel 4", LiveInApps.forChannel("UK | Channel 4 HD")?.name)
        org.junit.Assert.assertEquals("E4", LiveInApps.forChannel("E4")?.name)
        org.junit.Assert.assertEquals(null, LiveInApps.forChannel("E4 +1"))
        org.junit.Assert.assertEquals("RTÉ Live", LiveInApps.forChannel("RTE One")?.name)
        org.junit.Assert.assertEquals("RTÉ Live", LiveInApps.forChannel("IE: RTÉ 2 HD")?.name)
        org.junit.Assert.assertEquals(null, LiveInApps.forChannel("ITV Wales"))
    }
}
