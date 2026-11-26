package art.kquinn.kuesounds

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Writing the right details into a music file on the phone: every picture inside it is taken out, the
 * catalogue's cover is put in as the front cover, and the album name, album artist and year are set.
 * The song itself (the sound) is not touched. Works on MP3, M4A, FLAC, Ogg, WMA, AIFF and WAV files.
 */
object Tagger {
    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** The kinds of file whose details can be written. */
    val fixable = setOf("mp3", "m4a", "m4b", "mp4", "flac", "ogg", "oga", "opus", "wma", "aif", "aiff", "wav", "dsf")

    fun canFix(fileName: String): Boolean = Library.ending(fileName) in fixable

    data class Details(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val year: Int,
        val trackNo: Int,
        val discNo: Int,
        val cover: File?,
    )

    /** What a file holds, read back after a change to check it. */
    data class Inside(val title: String, val album: String, val albumArtist: String, val year: String, val pictures: Int, val firstPicture: ByteArray?, val lengthSeconds: Int)

    fun read(file: File): Inside {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val pics = tag?.artworkList.orEmpty()
        return Inside(
            title = tag?.getFirst(FieldKey.TITLE).orEmpty(),
            album = tag?.getFirst(FieldKey.ALBUM).orEmpty(),
            albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST).orEmpty(),
            year = tag?.getFirst(FieldKey.YEAR).orEmpty(),
            pictures = pics.size,
            firstPicture = pics.firstOrNull()?.binaryData,
            lengthSeconds = audio.audioHeader?.trackLength ?: 0,
        )
    }

    /**
     * Fixes [file] in place, in this order: the song's own name and artist are settled, everything else
     * written inside the file is taken out (old details, comments and every picture), then the song's name,
     * artist, album, album artist, year, track and disc number and the cover are written in fresh. The file
     * is read back to check it: the details must be there, there must be exactly one picture (or none when no
     * cover was given), and the song must be as long as before. Throws when the check fails, so a damaged
     * file is never sent back to the Drive.
     */
    fun fix(file: File, d: Details, knownSeconds: Int = 0, rebuild: ((File, File) -> Boolean)? = null) {
        val mp4 = Library.ending(file.name) in setOf("m4a", "m4b", "mp4")
        if (!mp4 || rebuild == null) return write(file, d, knownSeconds)
        // An M4A the tag library can't write is rebuilt first (see Fixer.rebuildMp4), from the untouched copy.
        val original = File(file.parentFile, file.name.substringBeforeLast('.') + ".original." + Library.ending(file.name))
        file.copyTo(original, overwrite = true)
        try {
            write(file, d, knownSeconds)
        } catch (first: Exception) {
            val rebuilt = File(file.parentFile, file.name.substringBeforeLast('.') + ".rebuilt." + Library.ending(file.name))
            if (!rebuild(original, rebuilt)) throw first
            write(rebuilt, d, knownSeconds)
            rebuilt.copyTo(file, overwrite = true)
        } finally {
            original.delete()
        }
    }

    private fun write(file: File, d: Details, knownSeconds: Int) {
        val length = runCatching { AudioFileIO.read(file).audioHeader?.trackLength ?: 0 }.getOrDefault(0)
        val soundBefore = if (Library.ending(file.name) == "mp3") runCatching { mp3Sound(file) }.getOrNull() else null
        // Everything written inside the file goes: every kind of details block it holds, and its pictures.
        AudioFileIO.delete(AudioFileIO.read(file))
        val clean = AudioFileIO.read(file)
        val tag = clean.tagOrCreateAndSetDefault
        if (d.title.isNotBlank()) tag.setField(FieldKey.TITLE, d.title)
        if (d.artist.isNotBlank()) tag.setField(FieldKey.ARTIST, d.artist)
        tag.setField(FieldKey.ALBUM, d.album)
        if (d.albumArtist.isNotBlank()) tag.setField(FieldKey.ALBUM_ARTIST, d.albumArtist)
        if (d.year > 0) tag.setField(FieldKey.YEAR, d.year.toString())
        if (d.trackNo > 0) tag.setField(FieldKey.TRACK, d.trackNo.toString())
        if (d.discNo > 0) tag.setField(FieldKey.DISC_NO, d.discNo.toString())
        if (d.cover != null) {
            val art = CoverArt().apply { setFromFile(d.cover) }
            art.pictureType = PictureTypes.DEFAULT_ID
            art.description = ""
            tag.setField(art)
        }
        AudioFileIO.write(clean)

        val after = read(file)
        check(after.album == d.album) { "the album name didn't stick" }
        check(d.title.isBlank() || after.title == d.title) { "the song name didn't stick" }
        check(after.pictures == (if (d.cover != null) 1 else 0)) { "the file holds " + after.pictures + " pictures after the change" }
        // As long as before, or as long as the phone measured it when the music was read: the tag library's own reading of
        // an MP3's length can be thrown off by what was inside it before (Kevin's "Walk Like An Egyptian", 24-25 Nov 2026).
        // An MP3 whose sound is byte for byte as it was is right, whatever length the tag library reads (26 November 2026:
        // "Haunted" and "Sunday Game Theme Tune" were left unfixed on a misread length, with nothing wrong with them).
        val soundSame = soundBefore != null && runCatching { mp3Sound(file) }.getOrNull()?.contentEquals(soundBefore) == true
        check(soundSame || kotlin.math.abs(after.lengthSeconds - length) <= 1 || (knownSeconds > 0 && kotlin.math.abs(after.lengthSeconds - knownSeconds) <= 2)) { "the song's length changed" }
    }

    /**
     * A fingerprint of an MP3's sound alone: every byte from its first sound frame to its end, with the details blocks
     * around it (ID3v2 at the front; ID3v1, APE and Lyrics3 at the back) left out. Two files with the same answer
     * hold exactly the same sound.
     */
    fun mp3Sound(file: File): ByteArray {
        val b = file.readBytes()
        var start = 0
        while (start + 10 <= b.size && b[start] == 'I'.code.toByte() && b[start + 1] == 'D'.code.toByte() && b[start + 2] == '3'.code.toByte()) {
            val size = ((b[start + 6].toInt() and 0x7F) shl 21) or ((b[start + 7].toInt() and 0x7F) shl 14) or
                ((b[start + 8].toInt() and 0x7F) shl 7) or (b[start + 9].toInt() and 0x7F)
            val footer = if (b[start + 5].toInt() and 0x10 != 0) 10 else 0
            start += 10 + size + footer
        }
        while (start + 1 < b.size && !(b[start] == 0xFF.toByte() && (b[start + 1].toInt() and 0xE0) == 0xE0)) start++
        var end = b.size
        fun at(i: Int, text: String) = i >= 0 && i + text.length <= b.size && text.indices.all { b[i + it] == text[it].code.toByte() }
        var changed = true
        while (changed) {
            changed = false
            if (end >= 128 && at(end - 128, "TAG")) { end -= 128; changed = true }
            if (end >= 32 && at(end - 32, "APETAGEX")) {
                val p = end - 32
                val size = (b[p + 12].toInt() and 0xFF) or ((b[p + 13].toInt() and 0xFF) shl 8) or ((b[p + 14].toInt() and 0xFF) shl 16) or ((b[p + 15].toInt() and 0xFF) shl 24)
                val header = (b[p + 23].toInt() and 0x80) != 0
                end -= size + (if (header) 32 else 0)
                changed = true
            }
            if (end >= 15 && at(end - 9, "LYRICS200")) {
                val size = runCatching { String(b, end - 15, 6).toInt() }.getOrNull()
                if (size != null) { end -= size + 15; changed = true }
            }
        }
        if (end <= start) return ByteArray(0)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(b, start, end - start)
        return md.digest()
    }

    /**
     * A cover picture for writing into a file. FLAC and Ogg files store the picture's size beside it;
     * the phone-side picture in the tag library can't measure it, so it is read from the JPEG itself.
     */
    private class CoverArt : AndroidArtwork() {
        override fun setImageFromData(): Boolean {
            val (w, h) = jpegSize(binaryData) ?: return true
            width = w
            height = h
            return true
        }
    }

    /** The width and height written in a JPEG picture's header. Null when it isn't a JPEG. */
    fun jpegSize(b: ByteArray?): Pair<Int, Int>? {
        if (b == null || b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        return null
    }
}
