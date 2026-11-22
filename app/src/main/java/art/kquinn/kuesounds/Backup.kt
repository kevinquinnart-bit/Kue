package art.kquinn.kuesounds

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A copy of KueSounds' settings and what it has worked out, kept in the music Drive as "KueSounds backup.zip":
 * the settings, the shows followed and where each episode got to, favourites, and the music's details as read
 * and looked up (hours of work on a big collection). Not the Google sign-in, the songs or the covers. Made by
 * itself once a day when the app is open, or when asked; brought back from Settings on a new phone or after a reset.
 */
object Backup {
    const val FILE_NAME = "KueSounds backup.zip"
    private const val DAY_MS = 24 * 60 * 60_000L

    /** The files kept in the backup, by name in the app's own folder. */
    private val FILES = listOf("library.json", "changes.json", "podcast-shows.json")
    /** The settings kept in the backup. Not "google" (the sign-in) or "sync" (which Drive file is used). */
    private val SETTINGS = listOf("podcasts", "podcast-positions", "notices")

    var busy by mutableStateOf("")
        private set
    var problem by mutableStateOf("")
        private set
    var lastAt by mutableStateOf(0L)
        private set

    private lateinit var app: Context
    private val gson = Gson()
    private fun prefs() = app.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        lastAt = prefs().getLong("lastAt", 0L)
    }

    /** Backs up when it hasn't been done today. Off the main thread. */
    suspend fun ifDue() {
        if (System.currentTimeMillis() - lastAt < DAY_MS || !Net.online(app)) return
        runCatching { now() }
    }

    /** Makes the backup and puts it in the Drive, in place of the last one. Off the main thread. */
    suspend fun now() {
        if (busy.isNotEmpty()) return
        busy = "Backing up…"
        problem = ""
        try {
            val token = Google.token() ?: throw IOException("not signed in to Google")
            val zip = File(app.cacheDir, "backup.zip")
            make(zip)
            val id = find(token)
            if (id == null) create(zip, token) else replace(id, zip, token)
            zip.delete()
            lastAt = System.currentTimeMillis()
            prefs().edit().putLong("lastAt", lastAt).apply()
        } catch (e: Exception) {
            problem = "The backup didn't finish: " + (e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            busy = ""
        }
    }

    private fun make(zip: File) {
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in FILES) {
                val f = File(app.filesDir, name)
                if (!f.exists()) continue
                out.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
            // Each setting with its kind, so it comes back the same.
            val settings = JsonObject()
            for (group in SETTINGS) {
                val g = JsonObject()
                for ((k, v) in app.getSharedPreferences(group, Context.MODE_PRIVATE).all) {
                    val one = JsonObject()
                    when (v) {
                        is Boolean -> { one.addProperty("t", "b"); one.addProperty("v", v) }
                        is Int -> { one.addProperty("t", "i"); one.addProperty("v", v) }
                        is Long -> { one.addProperty("t", "l"); one.addProperty("v", v) }
                        is Float -> { one.addProperty("t", "f"); one.addProperty("v", v) }
                        is String -> { one.addProperty("t", "s"); one.addProperty("v", v) }
                        else -> continue
                    }
                    g.add(k, one)
                }
                settings.add(group, g)
            }
            out.putNextEntry(ZipEntry("settings.json"))
            out.write(gson.toJson(settings).toByteArray())
            out.closeEntry()
        }
    }

    /** Brings the Drive's backup back over what is here, then starts KueSounds again. Off the main thread. */
    suspend fun restore(): Boolean {
        busy = "Bringing back the backup…"
        problem = ""
        try {
            val token = Google.token() ?: throw IOException("not signed in to Google")
            val id = find(token) ?: run { problem = "There is no backup in your Drive yet."; return false }
            val zip = File(app.cacheDir, "restore.zip")
            Drive.http.newCall(Request.Builder().url(Drive.mediaUrl(id)).header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code)
                zip.outputStream().use { r.body.byteStream().copyTo(it) }
            }
            ZipInputStream(zip.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "settings.json" -> putSettings(input.readBytes().toString(Charsets.UTF_8))
                        name in FILES -> File(app.filesDir, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            zip.delete()
            return true
        } catch (e: Exception) {
            problem = "The backup couldn't be brought back: " + (e.message ?: e.javaClass.simpleName)
            return false
        } finally {
            busy = ""
        }
    }

    private fun putSettings(json: String) {
        val all = JsonParser.parseString(json).asJsonObject
        for (group in all.keySet()) {
            if (group !in SETTINGS) continue
            val edit = app.getSharedPreferences(group, Context.MODE_PRIVATE).edit().clear()
            val g = all.getAsJsonObject(group)
            for (k in g.keySet()) {
                val one = g.getAsJsonObject(k)
                val v = one.get("v")
                when (one.get("t").asString) {
                    "b" -> edit.putBoolean(k, v.asBoolean)
                    "i" -> edit.putInt(k, v.asInt)
                    "l" -> edit.putLong(k, v.asLong)
                    "f" -> edit.putFloat(k, v.asFloat)
                    "s" -> edit.putString(k, v.asString)
                }
            }
            edit.commit()
        }
    }

    /** Starts KueSounds afresh, so everything brought back is read in. */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private fun find(token: String): String? {
        val url = Drive.API + "?q=" + java.net.URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8") + "&fields=files(id)&spaces=drive"
        Drive.http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + " to finding the backup")
            return Drive.parsePage(r.body.string()).files.firstOrNull()?.id
        }
    }

    private fun create(zip: File, token: String) {
        val meta = "{\"name\":\"$FILE_NAME\",\"mimeType\":\"application/zip\"}".toRequestBody("application/json; charset=UTF-8".toMediaType())
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta).addPart(zip.asRequestBody("application/zip".toMediaType())).build()
        val request = Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $token").post(body).build()
        Drive.http.newCall(request).execute().use { r -> if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + " to saving the backup") }
    }

    private fun replace(id: String, zip: File, token: String) {
        val request = Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
            .header("Authorization", "Bearer $token").patch(zip.asRequestBody("application/zip".toMediaType())).build()
        Drive.http.newCall(request).execute().use { r -> if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + " to saving the backup") }
    }
}
