package art.kquinn.kuetv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The channel player apps: the UK's and Ireland's free ones, and the main ones of other countries. Their programmes only play inside their own apps, so KueTV opens
 * the app when it is on this TV, and otherwise opens the TV's app store on a search for it.
 */
object TvAppList {
    /**
     * One app. It counts as installed when an app on the TV has a name containing one of [names],
     * a name exactly one of [exactNames], or a package containing one of [packages] (all lower case).
     */
    class TvApp(
        val title: String,
        val names: List<String>,
        val exactNames: List<String>,
        val packages: List<String>,
        val storeSearch: String = title,
        val country: String = UK,
    )

    const val UK = "UK"
    const val IRELAND = "Ireland"
    const val USA = "USA"
    const val CANADA = "Canada"
    const val AUSTRALIA = "Australia"
    const val NEW_ZEALAND = "New Zealand"
    const val GERMANY = "Germany"
    const val FRANCE = "France"
    const val ITALY = "Italy"
    const val SPAIN = "Spain"
    const val NETHERLANDS = "Netherlands"
    const val NORDICS = "Nordic countries"

    /** The countries, in the order Settings shows them. UK and Ireland open; the others folded away. */
    val countries = listOf(UK, IRELAND, USA, CANADA, AUSTRALIA, NEW_ZEALAND, GERMANY, FRANCE, ITALY, SPAIN, NETHERLANDS, NORDICS)

    /** The country of KueTV's players for a two-letter country code, or null when it has none for it. */
    fun countryOf(code: String): String? = when (code.uppercase()) {
        "GB", "IM", "JE", "GG" -> UK
        "IE" -> IRELAND
        "US" -> USA
        "CA" -> CANADA
        "AU" -> AUSTRALIA
        "NZ" -> NEW_ZEALAND
        "DE", "AT" -> GERMANY
        "FR", "MC", "BE" -> FRANCE
        "IT", "SM" -> ITALY
        "ES" -> SPAIN
        "NL" -> NETHERLANDS
        "SE", "NO", "DK", "FI" -> NORDICS
        else -> null
    }

    /**
     * The free and main channel players of each country (Kevin, 26 November 2026: every player there is, the UK's
     * and the main ones elsewhere, seen by KueTV whether or not they are on this TV). Recognised by their name as the
     * TV shows it, or their package. Names are compared in lower case.
     */
    val all = listOf(
        TvApp("BBC iPlayer", listOf("iplayer"), emptyList(), listOf("iplayer")),
        TvApp("ITVX", listOf("itvx", "itv hub"), emptyList(), listOf("com.itv.", "air.itv")),
        TvApp("Channel 4", listOf("channel 4", "all 4"), emptyList(), listOf("channel4")),
        TvApp("5 (Channel 5)", listOf("my5", "channel 5"), listOf("5"), listOf("channel5", "my5", "demand5"), storeSearch = "Channel 5"),
        TvApp("RTÉ Player", listOf("rté player", "rte player"), emptyList(), listOf("ie.rte", "tvbridge.rte"), country = IRELAND),
        TvApp("Virgin Media Play", listOf("virgin media play", "virgin media player"), emptyList(), listOf("virginmedia"), country = IRELAND),
        TvApp("STV Player", listOf("stv player"), listOf("stv"), listOf("stvplayer")),
        TvApp("U (UKTV)", listOf("uktv"), listOf("u"), listOf("uktv"), storeSearch = "UKTV Play"),
        TvApp("TG4 Player", listOf("tg4"), emptyList(), listOf("tg4"), country = IRELAND),
        TvApp("S4C Clic", listOf("s4c clic"), listOf("s4c", "clic"), listOf("s4c")),
        TvApp("Freely", listOf("freely"), emptyList(), listOf("uk.co.freely")),
        TvApp("Pluto TV", listOf("pluto tv"), emptyList(), listOf("tv.pluto")),
        TvApp("Sky News", listOf("sky news"), emptyList(), listOf("com.bskyb.skynews")),
        TvApp("GB News", listOf("gb news"), emptyList(), listOf("gbnews")),
        TvApp("Great! Player", listOf("great! player", "great player"), emptyList(), listOf("greatplayer")),
        // USA
        TvApp("Peacock", listOf("peacock"), emptyList(), listOf("peacock"), country = USA),
        TvApp("Paramount+", listOf("paramount+", "paramount plus"), emptyList(), listOf("com.cbs.ott", "com.cbs.app"), country = USA),
        TvApp("NBC", listOf("nbc app"), listOf("nbc"), listOf("com.nbcuni.nbc"), country = USA),
        TvApp("ABC (USA)", listOf("abc app"), listOf("abc"), listOf("com.disney.datg.videoplatforms"), storeSearch = "ABC", country = USA),
        TvApp("FOX", listOf("fox now", "fox one"), listOf("fox"), listOf("com.fox.now"), country = USA),
        TvApp("PBS", listOf("pbs video", "pbs app"), listOf("pbs"), listOf("org.pbs"), country = USA),
        TvApp("The CW", listOf("the cw"), emptyList(), listOf("com.cw.fullepisodes"), country = USA),
        TvApp("Hulu", listOf("hulu"), emptyList(), listOf("com.hulu"), country = USA),
        TvApp("YouTube TV", listOf("youtube tv"), emptyList(), listOf("tvunplugged"), country = USA),
        TvApp("Sling TV", listOf("sling tv"), listOf("sling"), listOf("com.sling"), country = USA),
        TvApp("Fubo", listOf("fubo"), emptyList(), listOf("fubo"), country = USA),
        TvApp("Philo", listOf("philo tv"), listOf("philo"), listOf("com.philo"), country = USA),
        TvApp("Tubi", listOf("tubi"), emptyList(), listOf("com.tubitv"), country = USA),
        TvApp("The Roku Channel", listOf("roku channel"), emptyList(), listOf("com.roku.trc"), country = USA),
        TvApp("Plex", emptyList(), listOf("plex"), listOf("com.plexapp"), country = USA),
        TvApp("Xumo Play", listOf("xumo"), emptyList(), listOf("xumo"), country = USA),
        TvApp("CBS News", listOf("cbs news"), emptyList(), listOf("cbsnews"), country = USA),
        TvApp("NBC News NOW", listOf("nbc news"), emptyList(), listOf("nbcnews"), country = USA),
        TvApp("ESPN", listOf("espn"), emptyList(), listOf("com.espn"), country = USA),
        // Canada
        TvApp("CBC Gem", listOf("cbc gem"), emptyList(), listOf("ca.cbc"), country = CANADA),
        TvApp("CTV", listOf("ctv app"), listOf("ctv"), listOf("ca.ctv", "ctvdigital"), country = CANADA),
        TvApp("Global TV", listOf("global tv", "stacktv"), emptyList(), listOf("com.globaltv", "corusent"), country = CANADA),
        TvApp("Crave", listOf("crave"), emptyList(), listOf("ca.bellmedia.crave"), country = CANADA),
        TvApp("ICI Tou.tv", listOf("tou.tv"), emptyList(), listOf("toutv"), country = CANADA),
        // Australia
        TvApp("ABC iview", listOf("iview"), emptyList(), listOf("au.net.abc"), country = AUSTRALIA),
        TvApp("SBS On Demand", listOf("sbs on demand"), emptyList(), listOf("com.sbs.ondemand"), country = AUSTRALIA),
        TvApp("7plus", listOf("7plus"), emptyList(), listOf("au.com.seven"), country = AUSTRALIA),
        TvApp("9Now", listOf("9now"), emptyList(), listOf("au.com.ninemsn", "au.com.nine"), country = AUSTRALIA),
        TvApp("10 Play", listOf("10 play", "10play"), listOf("10"), listOf("au.com.networkten"), country = AUSTRALIA),
        // New Zealand
        TvApp("TVNZ+", listOf("tvnz"), emptyList(), listOf("nz.co.tvnz"), country = NEW_ZEALAND),
        TvApp("ThreeNow", listOf("threenow"), emptyList(), listOf("threenow"), country = NEW_ZEALAND),
        TvApp("Whakaata Māori", listOf("whakaata", "maori television"), emptyList(), listOf("maoritelevision"), country = NEW_ZEALAND),
        // Germany
        TvApp("ARD Mediathek", listOf("ard mediathek", "ardmediathek"), emptyList(), listOf("ardmediathek"), country = GERMANY),
        TvApp("ZDF", listOf("zdfmediathek", "zdf mediathek"), listOf("zdf"), listOf("com.zdf"), country = GERMANY),
        TvApp("Joyn", listOf("joyn"), emptyList(), listOf("joyn"), country = GERMANY),
        TvApp("RTL+", listOf("rtl+"), emptyList(), listOf("rtlnow", "rtlplus"), country = GERMANY),
        TvApp("Zattoo", listOf("zattoo"), emptyList(), listOf("zattoo"), country = GERMANY),
        // France
        TvApp("france.tv", listOf("france.tv", "france tv"), emptyList(), listOf("fr.francetv"), country = FRANCE),
        TvApp("TF1+", listOf("tf1+", "tf1 +"), listOf("tf1"), listOf("fr.tf1"), country = FRANCE),
        TvApp("M6+", listOf("m6+", "6play"), emptyList(), listOf("fr.m6"), country = FRANCE),
        TvApp("Arte", listOf("arte tv"), listOf("arte"), listOf("tv.arte"), country = FRANCE),
        TvApp("Molotov", listOf("molotov"), emptyList(), listOf("molotov"), country = FRANCE),
        // Italy
        TvApp("RaiPlay", listOf("raiplay", "rai play"), emptyList(), listOf("it.rai"), country = ITALY),
        TvApp("Mediaset Infinity", listOf("mediaset infinity", "mediaset play"), emptyList(), listOf("it.mediaset"), country = ITALY),
        TvApp("La7", listOf("la7"), emptyList(), listOf("it.la7"), country = ITALY),
        // Spain
        TvApp("RTVE Play", listOf("rtve"), emptyList(), listOf("rtve"), country = SPAIN),
        TvApp("Atresplayer", listOf("atresplayer"), emptyList(), listOf("atresmedia", "atresplayer"), country = SPAIN),
        TvApp("Mitele", listOf("mitele"), emptyList(), listOf("mitele"), country = SPAIN),
        // Netherlands
        TvApp("NPO Start", listOf("npo start", "npo plus"), emptyList(), listOf("nl.uitzendinggemist", "nl.npo"), country = NETHERLANDS),
        TvApp("Videoland", listOf("videoland"), emptyList(), listOf("videoland"), country = NETHERLANDS),
        // Nordic countries
        TvApp("SVT Play", listOf("svt play"), emptyList(), listOf("se.svt"), country = NORDICS),
        TvApp("NRK TV", listOf("nrk tv"), emptyList(), listOf("no.nrk"), country = NORDICS),
        TvApp("DRTV", listOf("drtv", "dr tv"), emptyList(), listOf("dk.dr."), country = NORDICS),
        TvApp("Yle Areena", listOf("yle areena", "areena"), emptyList(), listOf("fi.yle"), country = NORDICS),
    )

    /**
     * For each of [all], in order, the package of the app on the TV that is it, or null.
     * [onTv] is every app on the TV: package to its name as the TV shows it.
     */
    fun match(onTv: Map<String, String>): List<String?> = all.map { app ->
        onTv.entries.firstOrNull { (pkg, name) ->
            val n = name.trim().lowercase()
            val p = pkg.lowercase()
            app.names.any { n.contains(it) } || app.exactNames.any { n == it } || app.packages.any { p.contains(it) }
        }?.key
    }
}

/** The parts that need the TV itself: finding the apps, opening one, opening the app store. */
object TvAppsOnDevice {
    class Found(val packageName: String, val icon: Bitmap?)

    /** For each of [TvAppList.all], in order, what is on this TV, or null. */
    fun find(context: Context): List<Found?> {
        val pm = context.packageManager
        val onTv = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val list = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }.getOrDefault(emptyList())
            for (info in list) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in onTv) continue
                onTv[pkg] = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            }
        }
        return TvAppList.match(onTv).map { pkg -> pkg?.let { Found(it, iconOf(context, it)) } }
    }

    private fun iconOf(context: Context, pkg: String): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    /** Opens the app. False if the TV would not open it. */
    fun open(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Opens the TV's app store on a search for [search]: Amazon's on a Fire TV, Google's elsewhere. */
    fun openStore(context: Context, search: String): Boolean {
        val amazonFirst = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        val stores = listOf("amzn://apps/android?s=", "market://search?q=").let { if (amazonFirst) it else it.reversed() }
        return stores.any { prefix ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefix + Uri.encode(search))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

/**
 * Settings › This TV › Channel player apps: adding a country's players one after another from the TV's app
 * store, and taking one off. Installing and removing are always the TV's own: the store has its Get button,
 * and the TV asks before it removes an app.
 */
object PlayerApps {
    /** Players still to add: the next opens in the store each time KueTV comes back to the front. */
    var queue by mutableStateOf<List<TvAppList.TvApp>>(emptyList())

    /** Opens the store on [app]. The Home watcher stands aside, as the store opens through Amazon's home screen. */
    fun openStore(context: Context, app: TvAppList.TvApp): Boolean {
        HomeGuard.pause()
        val ok = TvAppsOnDevice.openStore(context, app.storeSearch)
        if (!ok) HomeGuard.pausedUntil = 0
        Report.note("Player apps", "store opened on " + app.title + if (ok) "" else ": it wouldn't open")
        return ok
    }

    /** Starts adding the [missing] players: the first opens now, the rest one at a time as KueTV comes back. */
    fun addAll(context: Context, missing: List<TvAppList.TvApp>) {
        if (missing.isEmpty()) return
        queue = missing.drop(1)
        if (!openStore(context, missing.first())) queue = emptyList()
    }

    /** Back in KueTV: the next player still not on the TV opens in the store. [onTv] are the titles on the TV. */
    fun next(context: Context, onTv: Set<String>) {
        val rest = queue.filter { it.title !in onTv }
        if (rest.isEmpty()) { queue = emptyList(); return }
        queue = rest.drop(1)
        if (!openStore(context, rest.first())) queue = emptyList()
    }

    /** Asks the TV to remove the app; the TV shows its own question first. */
    fun remove(context: Context, pkg: String, title: String): Boolean {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        Report.note("Player apps", "remove " + title + if (ok) ": the TV asked" else ": the TV wouldn't ask")
        return ok
    }
}
