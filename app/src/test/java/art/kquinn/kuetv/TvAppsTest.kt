package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which apps on a pretend TV count as which of the free UK and Irish players. */
class TvAppsTest {
    @Test fun findsEachAppByNameOrPackage() {
        val onTv = linkedMapOf(
            "com.netflix.ninja" to "Netflix",
            "uk.co.bbc.iplayer" to "BBC iPlayer",
            "air.ITVMobilePlayer" to "ITVX",
            "com.channel4.ondemand" to "Channel 4",
            "com.something.five" to " 5 ",
            "ie.rte.player" to "RTÉ Player",
            "com.virginmedia.play" to "Virgin Media Play",
            "com.stv.android" to "STV Player",
            "uk.co.uktv.dave" to "U",
            "ie.tg4.player" to "TG4 Player",
        )
        assertEquals(
            listOf(
                "uk.co.bbc.iplayer", "air.ITVMobilePlayer", "com.channel4.ondemand", "com.something.five", "ie.rte.player", "com.virginmedia.play",
                "com.stv.android", "uk.co.uktv.dave", "ie.tg4.player",
            ),
            TvAppList.match(onTv).take(9),
        )
    }

    @Test fun missingAppsAreNullAndLookalikesDoNotCount() {
        val onTv = linkedMapOf(
            "com.netflix.ninja" to "Netflix",
            "com.example.five" to "5 Minute Crafts",
            "com.example.radio" to "Radio 5 Live",
            "com.example.youtube" to "YouTube",
            "com.example.stvp" to "STV News Extra",
            "uk.co.bbc.iplayer" to "BBC iPlayer",
        )
        assertEquals(listOf("uk.co.bbc.iplayer") + List(TvAppList.all.size - 1) { null }, TvAppList.match(onTv))
    }

    @Test fun nothingOnTheTvGivesAllMissing() {
        assertEquals(List(TvAppList.all.size) { null }, TvAppList.match(emptyMap()))
    }

    @Test fun rtePlayerOnFireTvCounts() {
        val onTv = linkedMapOf("com.twentyfouri.tvbridge.rte" to "RTE Player")
        assertEquals("com.twentyfouri.tvbridge.rte", TvAppList.match(onTv)[4])
    }

    @Test fun eachCountryHasItsPlayers() {
        assertEquals(
            listOf("BBC iPlayer", "ITVX", "Channel 4", "5 (Channel 5)", "STV Player", "U (UKTV)", "S4C Clic", "Freely", "Pluto TV", "Sky News", "GB News", "Great! Player"),
            TvAppList.all.filter { it.country == TvAppList.UK }.map { it.title },
        )
        assertEquals(listOf("RTÉ Player", "Virgin Media Play", "TG4 Player"), TvAppList.all.filter { it.country == TvAppList.IRELAND }.map { it.title })
        // Every country listed has players, and every player's country is listed.
        TvAppList.countries.forEach { c -> assert(TvAppList.all.any { it.country == c }) { "no players for $c" } }
        TvAppList.all.forEach { a -> assert(a.country in TvAppList.countries) { a.title } }
    }

    /** Players from other countries are seen too, by name or package (Kevin, 26 November 2026). */
    @Test fun otherCountriesPlayersAreSeen() {
        val onTv = linkedMapOf("com.peacocktv.peacockandroid" to "Peacock TV", "au.net.abc.iview" to "ABC iview", "com.example.style" to "Style Magazine")
        val found = TvAppList.all.zip(TvAppList.match(onTv)).filter { it.second != null }.map { it.first.title }
        assertEquals(listOf("Peacock", "ABC iview"), found)
    }
}
