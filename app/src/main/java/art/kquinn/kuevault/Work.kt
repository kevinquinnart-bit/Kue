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
    /** What is being done now, for the screen: "Uploading Report.pdf · 42%". Empty when nothing is. */
    var doing by mutableStateOf("")
    /** The last thing that went wrong, until it is closed. */
    var problem by mutableStateOf("")
    /** Google places that want signing in again before they can be used, by place id. */
    val signInAgain = mutableStateListOf<String>()
    /** Home computers that couldn't be reached last time, by place id, with why. */
    val unreachable = androidx.compose.runtime.mutableStateMapOf<String, String>()

    val documents = mutableStateListOf<Store.Item>()
    var documentsRead by mutableStateOf(false)

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

    /**
     * Reads what is in Documents: its own place first, then any other connected place that already has a
     * Documents folder (files put there before Documents moved). Nothing is made where there is none.
     */
    suspend fun readDocuments(context: Context) {
        val all = ArrayList<Store.Item>()
        val home = Vault.homeOf(Section.DOCUMENTS)
        for (place in listOfNotNull(home) + Vault.places.filter { it.id != home?.id }) {
            val store = store(context, place) ?: continue
            withContext(Dispatchers.IO) {
                runCatching { store.list(Section.DOCUMENTS) }.onFailure { failed(place, it, "reading Documents") }.getOrNull()
            }?.let { all.addAll(it) }
        }
        documents.clear()
        documents.addAll(all.sortedByDescending { it.modifiedMs })
        documentsRead = true
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

    /** Puts files from the phone into Documents' own place. When that place is full, it says so and stops. */
    suspend fun upload(context: Context, uris: List<Uri>) {
        val place = Vault.homeOf(Section.DOCUMENTS) ?: run { problem = "Documents has no place yet. Choose one in Places first."; return }
        val store = store(context, place) ?: run { problem = signInWords(place); return }
        var done = 0
        for (uri in uris) {
            val (name, size, mime) = withContext(Dispatchers.IO) { describe(context, uri) }
            if (Vault.room(Section.DOCUMENTS, size.coerceAtLeast(0)) == Room.FULL) {
                problem = place.label + " is full, so $name wasn't put in. Free some space there, or choose another place for Documents in Places."
                break
            }
            doing = "Uploading $name · 0%"
            val item = withContext(Dispatchers.IO) {
                runCatching { store.upload(context, Section.DOCUMENTS, uri, name, mime, size) { p -> doing = "Uploading $name · $p%" } }
                    .onFailure { failed(place, it, "uploading") }.getOrNull()
            }
            if (item == null) { problem = unreachable[place.id] ?: "$name didn't upload. Check the internet and try again."; break }
            documents.add(0, item)
            Vault.quotas[place.id]?.let { Vault.keepQuota(place.id, it.copy(used = it.used + item.size)) }
            Report.note("uploaded a file of " + Vault.size(item.size) + " to " + Vault.describe(place))
            done++
        }
        doing = ""
        if (done > 0) readSpace(context)
        if (Vault.room(Section.DOCUMENTS, 0) == Room.NEARLY_FULL && problem.isEmpty()) {
            problem = place.label + " is nearly full. Free some space there soon, or choose another place for Documents in Places."
        }
    }

    private fun signInWords(place: Place) = when (place.kind) {
        PlaceKind.GOOGLE -> "Sign in to " + place.email + " again, in Places."
        else -> unreachable[place.id] ?: (place.label + " couldn't be reached.")
    }

    /** Downloads a file and hands it to the app on the phone that opens that kind of file. */
    suspend fun open(context: Context, item: Store.Item) {
        val place = Vault.place(item.placeId) ?: run { problem = "That file's place isn't connected any more."; return }
        val store = store(context, place) ?: run { problem = signInWords(place); return }
        val folder = File(context.cacheDir, "open").apply { mkdirs() }
        val file = File(folder, item.id.hashCode().toUInt().toString(16) + "-" + item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (!file.exists() || file.length() != item.size) {
            doing = "Opening " + item.name + " · 0%"
            val ok = withContext(Dispatchers.IO) {
                runCatching { store.download(item, file) { p -> doing = "Opening " + item.name + " · $p%" } }
                    .onFailure { failed(place, it, "downloading") }.isSuccess
            }
            doing = ""
            if (!ok) { problem = unreachable[place.id] ?: (item.name + " didn't download. Check the internet and try again."); return }
        }
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
        if (ok) documents.remove(item) else problem = unreachable[place.id] ?: (item.name + " couldn't be moved to the bin. Try again.")
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
}
