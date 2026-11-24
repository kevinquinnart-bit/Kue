package art.kquinn.kuevault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What KueVault does with the places: how full they are, what is in Documents, putting files in, opening them
 * and binning them. Each call gives the screen plain words for anything that went wrong.
 */
object Work {
    /** Work that carries on when the person moves to another screen: putting papers in, reading manuals. */
    val scope = kotlinx.coroutines.MainScope()

    /** What is being done now, for the screen: "Uploading Report.pdf · 42%". Empty when nothing is. */
    var doing by mutableStateOf("")
    /** The last thing finished, said plainly, until it is closed. */
    var told by mutableStateOf("")
    /** The last thing that went wrong, until it is closed. */
    var problem by mutableStateOf("")
    /** Google places that want signing in again before they can be used, by place id. */
    val signInAgain = mutableStateListOf<String>()
    /** Home computers that couldn't be reached last time, by place id, with why. */
    val unreachable = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** The way into [place]'s files, or null with the reason marked on it. */
    suspend fun store(context: Context, place: Place): Store? = when (place.kind) {
        PlaceKind.GOOGLE -> {
            val token = Google.token(context, place.email)
            if (token == null) { if (place.id !in signInAgain) signInAgain += place.id } else signInAgain.remove(place.id)
            token?.let { GoogleStore(place, it) }
        }
        PlaceKind.HOME -> {
            val password = Secrets.read(context, place.id)
            if (password == null) { unreachable[place.id] = "Its password needs typing in again."; null } else HomeStore(place, password)
        }
        else -> null
    }

    private fun failed(place: Place, e: Throwable, what: String) {
        when (e) {
            is Drive.SignedOut -> { Google.drop(place.email); if (place.id !in signInAgain) signInAgain += place.id }
            is HomeStore.Unreachable -> unreachable[place.id] = e.message.orEmpty()
        }
        Report.note(what + " failed for " + Vault.describe(place) + ": " + e.javaClass.simpleName + " " + e.message)
    }

    /** Asks each place how full it is. */
    suspend fun readSpace(context: Context) {
        for (place in Vault.places.toList()) {
            val store = store(context, place) ?: continue
            val quota = withContext(Dispatchers.IO) {
                runCatching { store.space() }.onFailure { failed(place, it, "reading space") }.getOrNull()
            } ?: continue
            if (place.kind == PlaceKind.HOME) unreachable.remove(place.id)
            Vault.keepQuota(place.id, quota)
        }
    }

    /** Reading the library from the places is under way. */
    var reading by mutableStateOf(false)

    /**
     * Reads every section's folder in every place into the library. A place that can't be reached keeps what
     * the library last knew of it. Nothing is made where there is no folder.
     */
    suspend fun readLibrary(context: Context) {
        if (reading) return
        reading = true
        try {
            val read = HashMap<String, List<Store.Item>>()
            for (place in Vault.places.toList()) {
                val store = store(context, place) ?: continue
                val list = withContext(Dispatchers.IO) {
                    runCatching { Section.entries.flatMap { store.list(it) } }.onFailure { failed(place, it, "reading the library") }.getOrNull()
                } ?: continue
                if (place.kind == PlaceKind.HOME) unreachable.remove(place.id)
                read[place.id] = list
            }
            Library.replace(read)
            Library.tidy()
            Things.relink(Library.items.toList())
            Report.note("library read: " + Library.items.size + " files in " + read.size + " of " + Vault.places.size + " places")
        } finally {
            reading = false
        }
    }

    /** Name, size and kind of a file the person picked on the phone. */
    private fun describe(context: Context, uri: Uri): Triple<String, Long, String> {
        var name = "File"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Triple(name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** Puts files from the phone into [section]'s own place. When that place is full, it says so and stops. */
    suspend fun upload(context: Context, section: Section, uris: List<Uri>) {
        val place = Vault.homeOf(section) ?: run { problem = section.title + " has no place yet. Choose one in Places first."; return }
        val store = store(context, place) ?: run { problem = signInWords(place); return }
        var done = 0
        for (uri in uris) {
            val (name, size, mime) = withContext(Dispatchers.IO) { describe(context, uri) }
            if (Vault.room(section, size.coerceAtLeast(0)) == Room.FULL) {
                problem = place.label + " is full, so $name wasn't put in. Free some space there, or choose another place for " + section.title + " in Places."
                break
            }
            doing = "Uploading $name · 0%"
            val item = withContext(Dispatchers.IO) {
                runCatching { store.upload(context, section, uri, name, mime, size) { p -> doing = "Uploading $name · $p%" } }
                    .onFailure { failed(place, it, "uploading") }.getOrNull()
            }
            if (item == null) { problem = unreachable[place.id] ?: "$name didn't upload. Check the internet and try again."; break }
            Library.add(item)
            Vault.quotas[place.id]?.let { Vault.keepQuota(place.id, it.copy(used = it.used + item.size)) }
            Report.note("uploaded a file of " + Vault.size(item.size) + " to " + Vault.describe(place))
            done++
        }
        doing = ""
        if (done > 0) readSpace(context)
        if (Vault.room(section, 0) == Room.NEARLY_FULL && problem.isEmpty()) {
            problem = place.label + " is nearly full. Free some space there soon, or choose another place for " + section.title + " in Places."
        }
    }

    private fun signInWords(place: Place) = when (place.kind) {
        PlaceKind.GOOGLE -> "Sign in to " + place.email + " again, in Places."
        else -> unreachable[place.id] ?: (place.label + " couldn't be reached.")
    }

    /** Downloads a file into the phone's cache (once), or null with the reason given to the person. */
    suspend fun fetch(context: Context, item: Store.Item): File? {
        val place = Vault.place(item.placeId) ?: run { problem = "That file's place isn't connected any more."; return null }
        val store = store(context, place) ?: run { problem = signInWords(place); return null }
        val folder = File(context.cacheDir, "open").apply { mkdirs() }
        val file = File(folder, item.id.hashCode().toUInt().toString(16) + "-" + item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (!file.exists() || (item.size > 0 && file.length() != item.size)) {
            doing = "Opening " + item.name + " · 0%"
            val ok = withContext(Dispatchers.IO) {
                runCatching { store.download(item, file) { p -> doing = "Opening " + item.name + " · $p%" } }
                    .onFailure { failed(place, it, "downloading") }.isSuccess
            }
            doing = ""
            if (!ok) { file.delete(); problem = unreachable[place.id] ?: (item.name + " didn't download. Check the internet and try again."); return null }
        }
        return file
    }

    /** Downloads a file and hands it to the app on the phone that opens that kind of file. */
    suspend fun open(context: Context, item: Store.Item) {
        val file = fetch(context, item) ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(view, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { problem = "No app on this phone opens this kind of file." }
    }

    /** Moves a file to its place's bin: Google keeps it 30 days; a home computer keeps it in KueVault › Bin. */
    suspend fun bin(context: Context, item: Store.Item) {
        val place = Vault.place(item.placeId) ?: run { problem = "That file's place isn't connected any more."; return }
        val store = store(context, place) ?: run { problem = signInWords(place); return }
        val ok = withContext(Dispatchers.IO) { runCatching { store.bin(item) }.onFailure { failed(place, it, "binning") }.isSuccess }
        if (ok) Library.remove(item) else problem = unreachable[place.id] ?: (item.name + " couldn't be moved to the bin. Try again.")
    }

    /**
     * Connects a shared folder on the home computer: tries it first, and only keeps it when the computer
     * answered and the KueVault folder could be made there. The answer is what to tell the person.
     */
    suspend fun connectHome(context: Context, host: String, share: String, user: String, password: String): String {
        val h = host.trim().removePrefix("\\\\").removePrefix("smb://").trimEnd('/', '\\')
        val s = share.trim().trim('/', '\\')
        if (h.isEmpty() || s.isEmpty()) return "Type the computer's name and the shared folder's name."
        val place = Place.home(h, s, user.trim())
        doing = "Trying $s on $h…"
        val result = withContext(Dispatchers.IO) { runCatching { HomeStore(place, password).check() } }
        doing = ""
        result.exceptionOrNull()?.let { e ->
            Report.note("home computer not connected: " + e.javaClass.simpleName + " " + e.message)
            return if (e is HomeStore.Unreachable) e.message.orEmpty() else "That didn't work: " + (e.message ?: e.javaClass.simpleName)
        }
        Secrets.keep(context, place.id, password)
        Vault.connect(place)
        unreachable.remove(place.id)
        result.getOrNull()?.let { Vault.keepQuota(place.id, it) }
        return "Connected"
    }

    // ------------------------------------------------------------------ Moving, backup copies and the archive

    /** Whether the phone is on Wi-Fi (or another connection that isn't charged by the amount). */
    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The same file already in a list: same name and same size. */
    private fun sameIn(list: List<Store.Item>, item: Store.Item): Store.Item? = list.firstOrNull { it.name == item.name && it.size == item.size }

    /**
     * Copies [item] from its place into the same section's folder in [to]. Nothing is copied when the same
     * file is already there. Gives back the copy, or null with the reason noted.
     */
    private suspend fun copy(context: Context, item: Store.Item, from: Store, to: Store, toPlace: Place, there: MutableList<Store.Item>): Store.Item? {
        sameIn(there, item)?.let { return it }
        val folder = File(context.cacheDir, "moving").apply { mkdirs() }
        val file = File(folder, "f" + System.nanoTime())
        return try {
            withContext(Dispatchers.IO) {
                from.download(item, file) {}
                if (item.size > 0 && file.length() != item.size) throw java.io.IOException("the copy came back the wrong size")
                val made = to.upload(context, item.section, Uri.fromFile(file), item.name, item.mime, file.length()) {}
                if (made.size != file.length()) throw java.io.IOException("the new copy is the wrong size")
                there += made
                made
            }
        } catch (e: Exception) {
            failed(toPlace, e, "copying " + item.section.title)
            null
        } finally {
            file.delete()
        }
    }

    /**
     * Moves [section]'s files from [from] into [to], the section's new place. Each original goes to its
     * place's bin only once its copy is safely in. The answer is what to tell the person.
     */
    suspend fun moveSection(context: Context, section: Section, from: Place, to: Place): String {
        val a = store(context, from) ?: return signInWords(from)
        val b = store(context, to) ?: return signInWords(to)
        val files = withContext(Dispatchers.IO) { runCatching { a.list(section) }.getOrNull() } ?: return from.label + " couldn't be read."
        val there = withContext(Dispatchers.IO) { runCatching { b.list(section) }.getOrNull() }?.toMutableList() ?: return to.label + " couldn't be read."
        var moved = 0
        for ((i, item) in files.withIndex()) {
            doing = "Moving " + section.title + " · " + (i + 1) + " of " + files.size + " · " + item.name
            copy(context, item, a, b, to, there) ?: break
            withContext(Dispatchers.IO) { runCatching { a.bin(item) }.onFailure { failed(from, it, "binning a moved file") } }
            moved++
        }
        doing = ""
        readLibrary(context)
        readSpace(context)
        Report.note("moved " + moved + " of " + files.size + " " + section.title + " files")
        return if (moved == files.size) "Moved all " + moved + " " + section.title.lowercase() + " files to " + to.label + "."
        else "Moved " + moved + " of " + files.size + ". " + (unreachable[to.id] ?: "The rest stay where they were; try again later.")
    }

    /** Makes sure every file of [section] in its place also has a copy in its backup place. Gives back how many were copied, or null. */
    suspend fun backupNow(context: Context, section: Section): Int? {
        val home = Vault.homeOf(section) ?: return null
        val copyPlace = Vault.copyOf(section) ?: return null
        val a = store(context, home) ?: return null
        val b = store(context, copyPlace) ?: return null
        val files = withContext(Dispatchers.IO) { runCatching { a.list(section) }.onFailure { failed(home, it, "reading for the backup") }.getOrNull() } ?: return null
        val there = withContext(Dispatchers.IO) { runCatching { b.list(section) }.onFailure { failed(copyPlace, it, "reading the backup") }.getOrNull() }?.toMutableList() ?: return null
        val missing = files.filter { sameIn(there, it) == null }
        var done = 0
        for ((i, item) in missing.withIndex()) {
            doing = "Backup copy of " + section.title + " · " + (i + 1) + " of " + missing.size + " · " + item.name
            copy(context, item, a, b, copyPlace, there) ?: break
            done++
        }
        doing = ""
        if (done > 0) Report.note("backup copy: " + done + " " + section.title + " files copied to " + Vault.describe(copyPlace))
        return done
    }

    /**
     * Moves [section]'s oldest files out of its place to its archive, until the place is back to 70% full.
     * A file leaves its place only once it is safely both in the archive and in the backup copy, so it is
     * always in two places. [force]: "Archive now", from 70% full instead of 80%. The answer is what to say.
     */
    suspend fun archiveNow(context: Context, section: Section, force: Boolean): String {
        val home = Vault.homeOf(section) ?: return section.title + " has no place."
        val copyPlace = Vault.copyOf(section) ?: return "Set a backup copy first: archived files are always kept in two places."
        val archivePlace = Vault.archiveOf(section) ?: return "Choose where the archive goes first."
        val a = store(context, home) ?: return signInWords(home)
        val b = store(context, copyPlace) ?: return signInWords(copyPlace)
        val c = store(context, archivePlace) ?: return signInWords(archivePlace)
        readSpace(context)
        val files = withContext(Dispatchers.IO) { runCatching { a.list(section) }.getOrNull() } ?: return home.label + " couldn't be read."
        val plan = Vault.toArchive(files, Vault.quotas[home.id], force)
        if (plan.isEmpty()) return home.label + " has plenty of room: nothing needs archiving."
        val inCopy = withContext(Dispatchers.IO) { runCatching { b.list(section) }.getOrNull() }?.toMutableList() ?: return unreachable[copyPlace.id] ?: (copyPlace.label + " couldn't be read.")
        val inArchive = withContext(Dispatchers.IO) { runCatching { c.list(section) }.getOrNull() }?.toMutableList() ?: return unreachable[archivePlace.id] ?: (archivePlace.label + " couldn't be read.")
        var done = 0
        var freed = 0L
        for ((i, item) in plan.withIndex()) {
            doing = "Archiving " + section.title + " · " + (i + 1) + " of " + plan.size + " · " + item.name
            copy(context, item, a, b, copyPlace, inCopy) ?: break
            copy(context, item, a, c, archivePlace, inArchive) ?: break
            // Both copies are in and the right size: only now does the original go, for good, freeing its space.
            val gone = withContext(Dispatchers.IO) { runCatching { a.delete(item) }.onFailure { failed(home, it, "removing an archived file") }.isSuccess }
            if (!gone) break
            Library.remove(item)
            done++
            freed += item.size
        }
        doing = ""
        readLibrary(context)
        readSpace(context)
        Report.note("archived " + done + " of " + plan.size + " " + section.title + " files, " + Vault.size(freed) + " freed")
        return if (done == plan.size) "Archived " + done + " older " + section.title.lowercase() + " files and freed " + Vault.size(freed) + ". Each is in " + archivePlace.label + " and " + copyPlace.label + "."
        else "Archived " + done + " of " + plan.size + ". " + (unreachable[archivePlace.id] ?: unreachable[copyPlace.id] ?: "The rest stay where they are; try again later.")
    }

    /** On opening, on Wi-Fi: tops up backup copies and archives any section whose place is past 80%. */
    suspend fun keepUp(context: Context) {
        if (!onWifi(context) || doing.isNotEmpty()) return
        for (section in Section.entries) {
            if (Vault.copyOf(section) != null) backupNow(context, section)
            if (Vault.archiveOf(section) != null) {
                val home = Vault.homeOf(section) ?: continue
                val q = Vault.quotas[home.id] ?: continue
                if (q.limit != null && q.fraction >= Vault.ARCHIVE_FROM) archiveNow(context, section, force = false)
            }
        }
    }

    // ------------------------------------------------------------------ My things: papers, manuals and links

    /** A file brought in from the phone, a share or the scanner: kept in the cache, with its kind. */
    data class Brought(val file: File, val mime: String, val name: String)

    /** Copies what [uri] points at into the phone's cache, so it can be read and put in a place. */
    suspend fun bringIn(context: Context, uri: Uri, mimeGiven: String? = null): Brought? = withContext(Dispatchers.IO) {
        runCatching {
            val (name, _, mimeRead) = if (uri.scheme == "file") Triple(uri.lastPathSegment ?: "File", -1L, "") else describe(context, uri)
            val mime = (mimeGiven?.takeIf { it.isNotBlank() && it != "*/*" } ?: mimeRead.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: HomeStore.mimeOf(name))
            val folder = File(context.cacheDir, "in").apply { mkdirs() }
            val file = File(folder, "in" + System.nanoTime() + "." + extensionOf(mime, name))
            (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("couldn't be opened")).use { input -> file.outputStream().use { input.copyTo(it) } }
            Brought(file, mime, name)
        }.onFailure { Report.note("bringing a file in failed: " + it.javaClass.simpleName) }.getOrNull()
    }

    /** Words typed or shared (an email's text, say) kept as a text file, so they can go with a thing. */
    fun textFile(context: Context, text: String): Brought {
        val folder = File(context.cacheDir, "in").apply { mkdirs() }
        val file = File(folder, "in" + System.nanoTime() + ".txt")
        file.writeText(text)
        return Brought(file, "text/plain", "Text.txt")
    }

    fun extensionOf(mime: String, name: String): String = when (mime) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "text/plain" -> "txt"
        "text/html" -> "html"
        "message/rfc822" -> "eml"
        else -> name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "bin"
    }

    /**
     * Puts [brought] into My things' place as [thing]'s [kind] paper, named after the thing. A manual's words
     * are read straight away for asking. The answer is what to tell the person, or empty when it failed.
     */
    suspend fun attach(context: Context, thing: Thing, kind: Paper, brought: Brought): String {
        val place = Vault.homeOf(Section.THINGS) ?: run { problem = "Choose where My things' papers live first."; return "" }
        val store = store(context, place) ?: run { problem = signInWords(place); return "" }
        if (Vault.room(Section.THINGS, brought.file.length()) == Room.FULL) {
            problem = place.label + " is full. Free some space there, or choose another place for My things in Places."
            return ""
        }
        val name = Things.fileName(thing, kind, extensionOf(brought.mime, brought.name))
        doing = "Putting in " + name + " · 0%"
        val item = withContext(Dispatchers.IO) {
            runCatching { store.upload(context, Section.THINGS, Uri.fromFile(brought.file), name, brought.mime, brought.file.length()) { p -> doing = "Putting in $name · $p%" } }
                .onFailure { failed(place, it, "putting in a paper") }.getOrNull()
        }
        doing = ""
        if (item == null) { problem = unreachable[place.id] ?: "$name didn't go in. Check the internet and try again."; return "" }
        Library.add(item)
        val paper = Paperwork(kind, item.placeId, item.id, item.name, item.size, item.mime)
        Things.addPaper(thing.id, paper)
        Vault.quotas[place.id]?.let { Vault.keepQuota(place.id, it.copy(used = it.used + item.size)) }
        Report.note("a " + kind.one.lowercase() + " of " + Vault.size(item.size) + " put in " + Vault.describe(place))
        if (kind != Paper.MANUAL) return kind.one + " added to " + thing.title + "."
        return if (readManual(context, thing.id, paper, brought.file)) "Manual added. Ask it anything in Ask your manuals."
        else "Manual added, but it has no words KueVault could read, so it can't be asked."
    }

    /**
     * Reads a manual's words and keeps them for asking. Its own words first; a manual of scanned pages is
     * read off each page's picture instead, on the phone. [pdf]: the manual on the phone, or null to download it.
     */
    suspend fun readManual(context: Context, thingId: String, paper: Paperwork, pdf: File? = null): Boolean {
        val file = pdf ?: fetch(context, paper.item()) ?: return false
        if (paper.mime != "application/pdf" && !file.name.endsWith(".pdf", true)) {
            val words = Scan.wordsOfFile(context, file, paper.mime)
            return Manuals.keep(context, thingId, paper, listOf(words))
        }
        doing = "Reading the manual…"
        try {
            val own = withContext(Dispatchers.IO) { runCatching { Manuals.pagesOf(context, file) }.getOrDefault(emptyList()) }
            if (own.count { it.isNotBlank() } * 2 >= own.size.coerceAtLeast(1)) return Manuals.keep(context, thingId, paper, own)
            val count = withContext(Dispatchers.IO) { Manuals.pageCount(file) }.coerceAtMost(300)
            val pages = ArrayList<String>()
            for (i in 0 until count) {
                doing = "Reading the manual · page " + (i + 1) + " of " + count
                val own1 = own.getOrNull(i).orEmpty()
                pages += own1.ifBlank { withContext(Dispatchers.IO) { Manuals.pagePicture(file, i) }?.let { Scan.words(it) }.orEmpty() }
            }
            return Manuals.keep(context, thingId, paper, pages)
        } finally {
            doing = ""
        }
    }

    /** Opens one of a thing's papers, wherever it is now. */
    suspend fun openPaper(context: Context, paper: Paperwork) {
        val item = Things.find(paper, Library.items.toList()) ?: paper.item()
        open(context, item)
    }

    /** Moves one of a thing's papers to its place's bin, and takes it off the thing. */
    suspend fun binPaper(context: Context, thingId: String, paper: Paperwork) {
        val item = Things.find(paper, Library.items.toList()) ?: paper.item()
        val place = Vault.place(item.placeId)
        val store = place?.let { store(context, it) }
        if (store != null) {
            val ok = withContext(Dispatchers.IO) { runCatching { store.bin(item) }.onFailure { failed(place, it, "binning a paper") }.isSuccess }
            if (!ok) { problem = unreachable[place.id] ?: (item.name + " couldn't be moved to the bin. Try again."); return }
            Library.remove(item)
        }
        Things.removePaper(thingId, paper)
        Manuals.forget(context, thingId, paper.id)
    }

    /** Takes a thing out of My things; [alsoPapers]: its papers go to their places' bins too. */
    suspend fun removeThing(context: Context, thing: Thing, alsoPapers: Boolean) {
        if (alsoPapers) for (p in thing.papers) binPaper(context, thing.id, p)
        for (p in thing.papers) Manuals.forget(context, thing.id, p.id)
        Things.remove(thing.id)
        told = thing.title + " taken out of My things." + if (alsoPapers && thing.papers.isNotEmpty()) " Its papers are in the bin." else ""
    }

    private val web by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** Downloads a link the person shared (a maker's manual, say). Null with the reason given when it isn't a file. */
    suspend fun fetchLink(context: Context, url: String): Brought? {
        doing = "Downloading…"
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    web.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) KueVault/" + BuildConfig.VERSION_NAME).build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("the site answered " + r.code)
                        val type = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Download" }
                        val folder = File(context.cacheDir, "in").apply { mkdirs() }
                        val file = File(folder, "in" + System.nanoTime() + ".part")
                        r.body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                        val head = file.inputStream().use { val b = ByteArray(5); val n = it.read(b); if (n > 0) String(b, 0, n) else "" }
                        val mime = if (head.startsWith("%PDF")) "application/pdf" else type.ifBlank { HomeStore.mimeOf(name) }
                        if (mime.startsWith("text/html")) { file.delete(); throw java.io.IOException("that link is a web page, not a file") }
                        val done = File(folder, "in" + System.nanoTime() + "." + extensionOf(mime, name))
                        file.renameTo(done)
                        Brought(done, mime, name)
                    }
                }.onFailure { problem = "That link couldn't be downloaded: " + (it.message ?: "try again") + ". Open it, download the file, then add it from the phone." }.getOrNull()
            }
        } finally {
            doing = ""
        }
    }
}
