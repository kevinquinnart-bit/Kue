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
        check(kotlin.math.abs(after.lengthSeconds - length) <= 1 || (knownSeconds > 0 && kotlin.math.abs(after.lengthSeconds - knownSeconds) <= 2)) { "the song's length changed" }
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
