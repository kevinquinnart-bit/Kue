package art.kquinn.kuetv

import android.content.Context
import java.io.File

/**
 * What KueTV keeps on the TV, part by part, and clearing what can safely go. A TV short of storage
 * stutters in every app (Fire TV says so), so KueTV keeps its share small and shows it in
 * Settings › This TV and in the Report.
 */
object Storage {
    class Part(val name: String, val bytes: Long, val clearable: Boolean)

    private fun size(f: File?): Long = if (f == null || !f.exists()) 0L else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun folders(context: Context): List<Triple<String, File, Boolean>> = listOf(
        Triple("Pause and rewind copies", ShiftBuffer.folder(context), true),
        Triple("Downloaded updates", File(context.cacheDir, "update"), true),
        Triple("Pictures (posters, logos)", File(context.cacheDir, "image_cache"), true),
        Triple("Torrents", Torrents.folder(context), true),
        Triple("Recordings on this TV", Recordings.folder(context), false),
    )

    /** Each part and its size, then the rest of KueTV's own files. Call off the main thread. */
    fun parts(context: Context): List<Part> {
        val named = folders(context).map { (name, dir, clear) -> Part(name, size(dir), clear) }
        val cacheRest = size(context.cacheDir) - named.filter { it.name != "Torrents" && it.name != "Recordings on this TV" }.sumOf { it.bytes }
        return named + Part("Other short-term files", cacheRest.coerceAtLeast(0), true) + Part("Lists, guides and settings", size(context.filesDir), false)
    }

    /** Free storage on the TV. */
    fun free(context: Context): Long = context.cacheDir.usableSpace

    /** Clears what can go: copies, downloaded updates, pictures, torrents, other short-term files. Not while something plays. */
    fun clear(context: Context) {
        // The pictures through their own keeper, which has its files open; everything else in the short-term folder directly.
        runCatching { coil3.SingletonImageLoader.get(context).diskCache?.clear() }
        runCatching { context.cacheDir.listFiles()?.filter { it.name != "image_cache" }?.forEach { it.deleteRecursively() } }
        runCatching { Torrents.folder(context).deleteRecursively() }
        Report.note("Storage", "cleared; free now " + mb(free(context)))
    }

    fun mb(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(java.util.Locale.UK, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else (bytes / (1024 * 1024)).toString() + " MB"

    /** One line for the Report. */
    fun line(context: Context): String = "free on the TV " + mb(free(context)) + "; KueTV: " + parts(context).filter { it.bytes > 0 }.joinToString(", ") { it.name + " " + mb(it.bytes) }

    /** An app on the TV and the room it takes: its own files, plus its data and cache when the TV lets KueTV see them. */
    class App(val packageName: String, val name: String, val bytes: Long, val withData: Boolean, val amazon: Boolean)

    /**
     * The apps on the TV that can be opened, biggest first. Android only tells KueTV each app's data and cache
     * when it has been allowed to see app use; without that, each app's own files are counted. Call off the main thread.
     */
    fun apps(context: Context): List<App> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, String>()
        for (category in listOf(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER, android.content.Intent.CATEGORY_LAUNCHER)) {
            val list = runCatching { pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category), 0) }.getOrDefault(emptyList())
            for (info in list) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg in seen) continue
                seen[pkg] = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            }
        }
        val stats = if (android.os.Build.VERSION.SDK_INT >= 26) context.getSystemService(android.app.usage.StorageStatsManager::class.java) else null
        return seen.mapNotNull { (pkg, name) ->
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val full = if (stats != null) runCatching {
                val st = stats.queryStatsForPackage(ai.storageUuid, pkg, android.os.Process.myUserHandle())
                st.appBytes + st.dataBytes + st.cacheBytes
            }.getOrNull() else null
            val files = full ?: (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
            val system = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            App(pkg, name, files, full != null, system || pkg.startsWith("com.amazon."))
        }.sortedByDescending { it.bytes }
    }

    /** Opens the TV's own page for an app, where its cache can be cleared or it can be uninstalled. */
    fun openAppPage(context: Context, pkg: String): Boolean {
        HomeGuard.pause()
        val ok = runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) HomeGuard.pausedUntil = 0
        return ok
    }
}
