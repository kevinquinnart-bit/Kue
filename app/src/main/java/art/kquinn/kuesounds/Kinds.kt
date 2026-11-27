package art.kquinn.kuesounds

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * The kind of music written into the files (Kevin, 27 November 2026: "the genre should be written into the file so can
 * be read on file info"). Every file KueSounds writes from now on gets it with the rest; this puts it into files already
 * written, one at a time, on Wi-Fi only, with the screens closed. Only the kind changes: everything else in the file
 * stays as it is. Each song is done once.
 */
object Kinds {
    private val waiting = LinkedHashSet<String>()
    var status by mutableStateOf("")
        private set
    var lastResult by mutableStateOf("")
        private set

    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() }

    /** Adds songs to write their kind into. Needs the edit permission; starts the work. */
    fun queue(context: Context, tracks: List<Track>) {
        val app = context.applicationContext
        val go = {
            synchronized(waiting) { tracks.forEach { waiting += it.id } }
            WorkService.start(app)
        }
        if (Google.canEdit) go() else Fixer.askPermission { go() }
    }

    private fun next(): String? = synchronized(waiting) { waiting.firstOrNull()?.also { waiting.remove(it) } }

    suspend fun run(context: Context, show: (String) -> Unit) {
        val work = File(context.cacheDir, "kinds").apply { mkdirs() }
        var done = 0
        var failed = 0
        try {
            while (true) {
                if (!Net.unmetered(context)) {
                    status = "Waiting for Wi-Fi"
                    show("Kinds of music: waiting for Wi-Fi")
                    Scanner.note("kinds of music into files: stopped, not on Wi-Fi; carries on at the next look")
                    synchronized(waiting) { waiting.clear() }
                    break
                }
                val id = next() ?: break
                val t = Scanner.data.tracks.firstOrNull { it.id == id } ?: continue
                val kind = Library.genreOf(t, Scanner.data)
                if (kind.isBlank() || !Tagger.canFix(Library.realName(t))) continue
                val left = synchronized(waiting) { waiting.size }
                status = "Kind of music into " + t.title + if (left > 0) " · $left more" else ""
                show(status)
                val file = File(work, t.id + "." + Library.ending(Library.realName(t)))
                try {
                    val token = Google.editToken() ?: throw Drive.SignedOut()
                    Drive.downloadChecked(t.id, file, token, t.size)
                    val inFile = runCatching { Tagger.readAll(file).known[org.jaudiotagger.tag.FieldKey.GENRE].orEmpty() }.getOrDefault("")
                    if (inFile.equals(kind, ignoreCase = true)) {
                        Scanner.markKind(t.id, written = true)
                        continue
                    }
                    Tagger.edit(file, mapOf(org.jaudiotagger.tag.FieldKey.GENRE to kind), emptySet(), null, (t.durationMs / 1000).toInt())
                    val (size, modified) = Drive.uploadNewVersion(t.id, file, t.mime, Google.editToken() ?: token)
                    Scanner.changeTracks(setOf(t.id)) { it.copy(size = size, modified = modified.ifEmpty { it.modified }, genre = kind) }
                    Scanner.markKind(t.id, written = true)
                    done++
                } catch (x: Drive.SignedOut) {
                    synchronized(waiting) { waiting.clear() }
                    Scanner.note("kinds of music into files: Google needs the edit permission again")
                    break
                } catch (x: Exception) {
                    failed++
                    Scanner.note("kind of music not written: " + t.fileName + ": " + x.javaClass.simpleName + " " + x.message)
                } finally {
                    file.delete()
                }
            }
        } finally {
            if (done > 0 || failed > 0) {
                lastResult = "Kind of music written into " + done + (if (done == 1) " file" else " files") + (if (failed > 0) ", $failed couldn't be" else "") + "."
                Scanner.note("kinds of music into files: $done written, $failed not")
            }
            status = ""
        }
    }
}
