package art.kquinn.kuevault

import android.content.Context
import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * One place's files, whatever kind of place it is. Every call blocks: run them off the main thread.
 * Each place keeps KueVault's files in a KueVault folder with a folder per section inside, and KueVault
 * touches nothing outside it.
 */
interface Store {
    /** A file in a section: which place it is in, its name and size, and when it last changed. */
    data class Item(val id: String, val name: String, val size: Long, val modifiedMs: Long, val mime: String, val placeId: String)

    /** How full the place is, or null when it can't say. */
    fun space(): Quota?
    /** What is in [section]'s folder here, newest first; nothing when there is no folder yet. */
    fun list(section: Section): List<Item>
    fun upload(context: Context, section: Section, uri: Uri, name: String, mime: String, size: Long, progress: (Int) -> Unit): Item
    fun download(item: Item, target: File, progress: (Int) -> Unit)
    /** Puts a file where it can still be got back: Google's bin, or KueVault › Bin on a home computer. */
    fun bin(item: Item)
}

/** A Google account, through the pass Google gave for it. */
class GoogleStore(private val place: Place, private val token: String) : Store {
    override fun space(): Quota = Drive.about(token).second
    override fun list(section: Section) = Drive.list(token, place.email, place.id, section)
    override fun upload(context: Context, section: Section, uri: Uri, name: String, mime: String, size: Long, progress: (Int) -> Unit) =
        Drive.upload(context, token, place.email, place.id, section, uri, name, mime, size, progress)
    override fun download(item: Store.Item, target: File, progress: (Int) -> Unit) = Drive.download(token, item, target, progress)
    override fun bin(item: Store.Item) = Drive.bin(token, item)
}

/**
 * A shared folder on the person's own computer, reached over the home Wi-Fi the way Windows shares folders.
 * It works while the phone is on the same network as the computer and the computer is on.
 */
class HomeStore(private val place: Place, private val password: String) : Store {
    /** The computer couldn't be reached, or turned the name and password down: said in plain words. */
    class Unreachable(message: String) : IOException(message)

    internal fun <T> onShare(work: (DiskShare) -> T): T {
        // Bouncy Castle's own code for the sign-in sums Android lacks (MD4), packed with the app.
        val config = SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS)
            .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider()).build()
        SMBClient(config).use { client ->
            val connection = try { client.connect(place.host) } catch (e: Exception) {
                throw Unreachable("Couldn't reach " + place.host + ". Is the computer on, and the phone on the same Wi-Fi?")
            }
            connection.use {
                val (domain, user) = if (place.user.contains('\\')) place.user.substringBefore('\\') to place.user.substringAfter('\\') else null to place.user
                val session = try { it.authenticate(AuthenticationContext(user, password.toCharArray(), domain)) } catch (e: Exception) {
                    throw Unreachable(place.host + " turned the name and password down.")
                }
                session.use { s ->
                    val share = try { s.connectShare(place.share) as DiskShare } catch (e: Exception) {
                        throw Unreachable(place.host + " has no shared folder called " + place.share + ".")
                    }
                    return share.use(work)
                }
            }
        }
    }

    private fun folder(section: Section) = "KueVault\\" + section.title

    private fun ensure(share: DiskShare, path: String) {
        var at = ""
        for (part in path.split('\\')) {
            at = if (at.isEmpty()) part else "$at\\$part"
            if (!share.folderExists(at)) share.mkdir(at)
        }
    }

    /** Connects once, makes the KueVault folder, and reads the space: proof the details are right. */
    fun check(): Quota? = onShare { share -> ensure(share, "KueVault"); spaceOf(share) }

    private fun spaceOf(share: DiskShare): Quota? = runCatching {
        val info = share.shareInformation
        Quota(info.totalSpace - info.freeSpace, info.totalSpace)
    }.getOrNull()

    override fun space(): Quota? = onShare { spaceOf(it) }

    override fun list(section: Section): List<Store.Item> = onShare { share ->
        val path = folder(section)
        if (!share.folderExists(path)) return@onShare emptyList()
        share.list(path).filter { f ->
            f.fileName != "." && f.fileName != ".." && (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L
        }.map { f ->
            Store.Item(path + "\\" + f.fileName, f.fileName, f.endOfFile, f.lastWriteTime.toEpochMillis(), mimeOf(f.fileName), place.id)
        }.sortedByDescending { it.modifiedMs }
    }

    override fun upload(context: Context, section: Section, uri: Uri, name: String, mime: String, size: Long, progress: (Int) -> Unit): Store.Item = onShare { share ->
        val dir = folder(section)
        ensure(share, dir)
        val path = free(share, dir, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file couldn't be opened")
        input.use { copyUp(share, path, it, size, progress) }
        val written = share.getFileInformation(path).standardInformation.endOfFile
        Store.Item(path, path.substringAfterLast('\\'), written, System.currentTimeMillis(), mime, place.id)
    }

    /** Writes [input] to [path], replacing nothing: a file that is cut short is taken away again. */
    fun copyUp(share: DiskShare, path: String, input: InputStream, size: Long, progress: (Int) -> Unit) {
        val file = share.openFile(
            path, setOf(AccessMask.GENERIC_WRITE, AccessMask.DELETE), setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            setOf(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_CREATE, setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        var ok = false
        try {
            file.outputStream.use { out ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (size > 0) { val p = (done * 100 / size).toInt(); if (p != shown) { shown = p; progress(p) } }
                }
            }
            ok = true
        } finally {
            if (!ok) runCatching { file.deleteOnClose() }
            file.close()
        }
    }

    /** [name] in [dir], or "name (2).ext" and on, so nothing already there is written over. */
    private fun free(share: DiskShare, dir: String, name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var path = "$dir\\$clean"
        var n = 2
        while (share.fileExists(path)) {
            val dot = clean.lastIndexOf('.')
            path = dir + "\\" + (if (dot > 0) clean.substring(0, dot) + " ($n)" + clean.substring(dot) else "$clean ($n)")
            n++
        }
        return path
    }

    override fun download(item: Store.Item, target: File, progress: (Int) -> Unit) = onShare { share ->
        val part = File(target.path + ".part")
        val file = share.openFile(
            item.id, setOf(AccessMask.GENERIC_READ), null, setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        file.use { f ->
            f.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (item.size > 0) { val p = (done * 100 / item.size).toInt(); if (p != shown) { shown = p; progress(p) } }
                    }
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("the downloaded file couldn't be kept")
    }

    override fun bin(item: Store.Item) = onShare { share ->
        ensure(share, "KueVault\\Bin")
        val to = free(share, "KueVault\\Bin", item.name)
        val file = share.openFile(
            item.id, setOf(AccessMask.DELETE, AccessMask.GENERIC_READ), null, setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        file.use { it.rename(to) }
    }

    companion object {
        /** The kind of file, from its name, for the phone's "open with". */
        fun mimeOf(name: String): String = runCatching {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        }.getOrNull() ?: "application/octet-stream"
    }
}
