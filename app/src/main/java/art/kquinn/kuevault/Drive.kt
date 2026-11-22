package art.kquinn.kuevault

import android.content.Context
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive, as KueVault uses it: a "KueVault" folder in each account with a folder per section inside,
 * holding only what KueVault put there. Every call blocks; run them off the main thread.
 */
object Drive {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER = "application/vnd.google-apps.folder"

    /** Google turned the pass down: the person needs asking again. */
    class SignedOut : IOException("Google wants this account signed in again")

    /** A file in a section: where it is, its name and size, and when it last changed. */
    data class Item(val id: String, val name: String, val size: Long, val modifiedMs: Long, val mime: String, val email: String)

    private fun send(request: Request): String = http.newCall(request).execute().use { r ->
        if (r.code == 401) throw SignedOut()
        if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + ": " + r.body.string().take(200))
        r.body.string()
    }

    private fun get(token: String, url: String): JsonObject =
        JsonParser.parseString(send(Request.Builder().url(url).header("Authorization", "Bearer $token").build())).asJsonObject

    /** The account's address and how full it is. */
    fun about(token: String): Pair<String, Quota> {
        val o = get(token, "$API/about?fields=user(emailAddress),storageQuota(limit,usage)")
        val q = o.getAsJsonObject("storageQuota")
        return o.getAsJsonObject("user").get("emailAddress").asString to
            Quota(q.get("usage")?.asString?.toLongOrNull() ?: 0L, q.get("limit")?.asString?.toLongOrNull())
    }

    private val folders = HashMap<String, String>()

    private fun q(text: String) = URLEncoder.encode(text, "UTF-8")

    /** The folder [name] inside [parent] ("root" for the top), made if it isn't there yet. */
    private fun folder(token: String, email: String, name: String, parent: String): String {
        val key = "$email/$parent/$name"
        synchronized(folders) { folders[key] }?.let { return it }
        val search = "mimeType='$FOLDER' and name='" + name.replace("'", "\\'") + "' and '$parent' in parents and trashed=false"
        val found = get(token, "$API/files?q=" + q(search) + "&fields=files(id)&spaces=drive").getAsJsonArray("files")
        val id = if (found.size() > 0) found[0].asJsonObject.get("id").asString else {
            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("mimeType", FOLDER)
                add("parents", com.google.gson.JsonArray().apply { add(parent) })
            }.toString().toRequestBody("application/json".toMediaType())
            JsonParser.parseString(send(Request.Builder().url("$API/files?fields=id").header("Authorization", "Bearer $token").post(body).build()))
                .asJsonObject.get("id").asString
        }
        synchronized(folders) { folders[key] = id }
        return id
    }

    /** The section's own folder in this account: KueVault › Documents, say. */
    fun sectionFolder(token: String, email: String, section: Section): String =
        folder(token, email, section.title, folder(token, email, "KueVault", "root"))

    /** What is in the section's folder in this account, newest first. */
    fun list(token: String, email: String, section: Section): List<Item> {
        val parent = sectionFolder(token, email, section)
        val out = ArrayList<Item>()
        var page: String? = null
        do {
            val url = "$API/files?q=" + q("'$parent' in parents and trashed=false") +
                "&fields=nextPageToken,files(id,name,size,modifiedTime,mimeType)&orderBy=modifiedTime desc&pageSize=200" +
                (page?.let { "&pageToken=" + q(it) } ?: "")
            val o = get(token, url)
            for (f in o.getAsJsonArray("files")) {
                val j = f.asJsonObject
                out += Item(
                    j.get("id").asString, j.get("name").asString, j.get("size")?.asString?.toLongOrNull() ?: 0L,
                    runCatching { java.time.Instant.parse(j.get("modifiedTime").asString).toEpochMilli() }.getOrDefault(0L),
                    j.get("mimeType")?.asString.orEmpty(), email,
                )
            }
            page = o.get("nextPageToken")?.asString
        } while (page != null)
        return out
    }

    /** Puts a file from the phone into the section's folder, telling [progress] how far it has got, 0 to 100. */
    fun upload(context: Context, token: String, email: String, section: Section, uri: Uri, name: String, mime: String, size: Long, progress: (Int) -> Unit): Item {
        val parent = sectionFolder(token, email, section)
        val meta = JsonObject().apply {
            addProperty("name", name)
            add("parents", com.google.gson.JsonArray().apply { add(parent) })
        }.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val start = Request.Builder().url("$UPLOAD?uploadType=resumable&fields=id,name,size,modifiedTime,mimeType")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mime)
            .apply { if (size > 0) header("X-Upload-Content-Length", size.toString()) }
            .post(meta).build()
        val where = http.newCall(start).execute().use { r ->
            if (r.code == 401) throw SignedOut()
            if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + " to the upload")
            r.header("Location") ?: throw IOException("Google Drive gave nowhere to send the file")
        }
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = if (size > 0) size else -1L
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file couldn't be opened")
                input.use {
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        done += n
                        if (size > 0) { val p = (done * 100 / size).toInt(); if (p != shown) { shown = p; progress(p) } }
                    }
                }
            }
        }
        val j = JsonParser.parseString(send(Request.Builder().url(where).put(body).build())).asJsonObject
        return Item(
            j.get("id").asString, j.get("name").asString, j.get("size")?.asString?.toLongOrNull() ?: size,
            runCatching { java.time.Instant.parse(j.get("modifiedTime").asString).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
            j.get("mimeType")?.asString ?: mime, email,
        )
    }

    /** Downloads a file into [target], telling [progress] how far it has got, 0 to 100. */
    fun download(token: String, item: Item, target: File, progress: (Int) -> Unit) {
        val part = File(target.path + ".part")
        http.newCall(Request.Builder().url("$API/files/" + item.id + "?alt=media").header("Authorization", "Bearer $token").build()).execute().use { r ->
            if (r.code == 401) throw SignedOut()
            if (!r.isSuccessful) throw IOException("Google Drive answered " + r.code + " to the download")
            val total = r.body.contentLength().takeIf { it > 0 } ?: item.size
            r.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != shown) { shown = p; progress(p) } }
                    }
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("the downloaded file couldn't be kept")
    }

    /** Moves a file to the account's bin, where Google keeps it for 30 days. */
    fun bin(token: String, item: Item) {
        val body = """{"trashed":true}""".toRequestBody("application/json".toMediaType())
        send(Request.Builder().url("$API/files/" + item.id).header("Authorization", "Bearer $token").patch(body).build())
    }
}
