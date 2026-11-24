package art.kquinn.kuevault

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The phone's own photos and videos: backed up into the Backups section's place, and brought back onto a new
 * phone. Only into the person's own drive (or later OneDrive or Dropbox): Google's Drive rules need its written
 * permission for apps that back up into Drive (checked 24 November 2026).
 */
object PhoneMedia {
    data class Media(val uri: Uri, val name: String, val size: Long, val mime: String)

    /** The permissions to ask for before reading the phone's photos and videos. */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 33 && BuildConfig.STORE) emptyArray()
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        else if (Build.VERSION.SDK_INT >= 29) arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /**
     * The Play store version on Android 13 and later: the person picks the photos and videos to back up, as
     * Google Play allows seeing all of them only to some apps. The direct version backs up every one.
     */
    val picking: Boolean get() = BuildConfig.STORE && Build.VERSION.SDK_INT >= 33

    /** How many can be picked at once. */
    const val PICK_MOST = 100

    /** The picked photos and videos, with their names and sizes. */
    suspend fun describe(context: Context, uris: List<Uri>): List<Media> = withContext(Dispatchers.IO) {
        uris.mapNotNull { u ->
            runCatching {
                context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else Media(u, c.getString(0) ?: "Photo", c.getLong(1), context.contentResolver.getType(u) ?: "image/jpeg")
                }
            }.getOrNull()
        }
    }

    /** Every photo and video on the phone. Off the main thread. */
    fun list(context: Context): List<Media> {
        val out = ArrayList<Media>()
        val kinds = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image/jpeg",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/mp4",
        )
        for ((where, fallback) in kinds) {
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
            runCatching {
                context.contentResolver.query(where, columns, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        out += Media(Uri.withAppendedPath(where, c.getLong(0).toString()), name, c.getLong(2), c.getString(3) ?: fallback)
                    }
                }
            }.onFailure { Report.note("reading the phone's photos failed: " + it.message) }
        }
        return out
    }

    /** Why the phone can't be backed up to Backups' place, or null when it can. */
    fun blocked(): String? {
        val place = Vault.homeOf(Section.BACKUPS) ?: return "Choose a place for Backups first: your own drive (home computer or network drive)."
        if (place.kind == PlaceKind.GOOGLE) return "Phone backups go to your own drive, not a Google account (Google's rules). Choose your drive for Backups."
        return null
    }

    /** Copies every photo and video not yet there into Backups' place. The answer is what to tell the person. */
    suspend fun backUp(context: Context, picked: List<Media>? = null): String {
        blocked()?.let { return it }
        val place = Vault.homeOf(Section.BACKUPS)!!
        val store = Work.store(context, place) ?: return Work.unreachable[place.id] ?: (place.label + " couldn't be reached.")
        val there = withContext(Dispatchers.IO) { runCatching { store.list(Section.BACKUPS) }.getOrNull() }?.toMutableList()
            ?: return Work.unreachable[place.id] ?: (place.label + " couldn't be read.")
        val mine = picked ?: withContext(Dispatchers.IO) { list(context) }
        val missing = mine.filter { m -> there.none { it.name == m.name && it.size == m.size } }
        var done = 0
        for ((i, m) in missing.withIndex()) {
            if (Vault.room(Section.BACKUPS, m.size) == Room.FULL) { Work.doing = ""; return place.label + " is full after $done of " + missing.size + "." }
            Work.doing = "Backing up this phone · " + (i + 1) + " of " + missing.size + " · " + m.name
            val made = withContext(Dispatchers.IO) {
                runCatching { store.upload(context, Section.BACKUPS, m.uri, m.name, m.mime, m.size) {} }.onFailure { Report.note("phone backup failed at " + m.name + ": " + it.message) }.getOrNull()
            } ?: break
            there += made
            Library.add(made)
            done++
        }
        Work.doing = ""
        Report.note("phone backup: " + done + " of " + missing.size + " new photos and videos to " + Vault.describe(place))
        return when {
            missing.isEmpty() -> "All " + mine.size + " photos and videos " + (if (picked != null) "picked" else "on this phone") + " are already backed up in " + place.label + "."
            done == missing.size -> "Backed up " + done + " photos and videos to " + place.label + "."
            else -> "Backed up " + done + " of " + missing.size + ". " + (Work.unreachable[place.id] ?: "Try again to carry on.")
        }
    }

    /** Puts the backed-up photos and videos that aren't on this phone back on it, in Pictures › KueVault and Movies › KueVault. */
    suspend fun bringBack(context: Context): String {
        val place = Vault.homeOf(Section.BACKUPS) ?: return "Backups has no place, so there is nothing to bring back."
        val store = Work.store(context, place) ?: return Work.unreachable[place.id] ?: (place.label + " couldn't be reached.")
        val there = withContext(Dispatchers.IO) { runCatching { store.list(Section.BACKUPS) }.getOrNull() }
            ?: return Work.unreachable[place.id] ?: (place.label + " couldn't be read.")
        val mine = withContext(Dispatchers.IO) { list(context) }
        val wanted = there.filter { b -> (isPicture(b) || isVideo(b)) && mine.none { it.name == b.name && it.size == b.size } }
        var done = 0
        val work = File(context.cacheDir, "bringing").apply { mkdirs() }
        for ((i, item) in wanted.withIndex()) {
            Work.doing = "Bringing back · " + (i + 1) + " of " + wanted.size + " · " + item.name
            val ok = withContext(Dispatchers.IO) {
                val file = File(work, "f" + System.nanoTime())
                runCatching {
                    store.download(item, file) {}
                    putOnPhone(context, file, item)
                }.onFailure { Report.note("bringing back failed at " + item.name + ": " + it.message) }.also { file.delete() }.isSuccess
            }
            if (!ok) break
            done++
        }
        Work.doing = ""
        return when {
            wanted.isEmpty() -> "Everything in Backups is already on this phone."
            done == wanted.size -> "Brought back " + done + " photos and videos. They're in Pictures and Movies, in KueVault folders."
            else -> "Brought back " + done + " of " + wanted.size + ". " + (Work.unreachable[place.id] ?: "Try again to carry on.")
        }
    }

    private fun isPicture(i: Store.Item) = i.mime.startsWith("image/") || i.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng")
    private fun isVideo(i: Store.Item) = i.mime.startsWith("video/") || i.name.substringAfterLast('.').lowercase() in setOf("mp4", "mov", "3gp", "mkv", "webm")

    private fun putOnPhone(context: Context, file: File, item: Store.Item) {
        val video = isVideo(item)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime.ifBlank { if (video) "video/mp4" else "image/jpeg" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/KueVault")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val where = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(where, values) ?: throw java.io.IOException("the phone wouldn't make room for it")
            try {
                context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "KueVault").apply { mkdirs() }
            val target = File(dir, item.name)
            file.copyTo(target, overwrite = false)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        }
    }
}
