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
        val bytesBefore = if (Library.ending(file.name) == "mp3") runCatching { file.readBytes() }.getOrNull() else null
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
        // 27 November 2026: they were still left, the byte-for-byte check starting at the wrong place in files with stray
        // bytes before the sound; now the sound is matched from its end (soundKept).
        val soundSame = bytesBefore != null && runCatching { soundKept(bytesBefore, file.readBytes()) }.getOrDefault(false)
        check(soundSame || kotlin.math.abs(after.lengthSeconds - length) <= 1 || (knownSeconds > 0 && kotlin.math.abs(after.lengthSeconds - knownSeconds) <= 2)) { "the song's length changed" }
    }

    /**
     * Everything written in a file, for Fix it by hand (Kevin, 27 November 2026: "I need full access to the meta data in
     * the file"). [known] are the details every kind of file can hold, by name; [other] are any other text details the
     * file holds, by the file's own code for them; [picture] is the first picture inside.
     */
    data class All(val known: Map<FieldKey, String>, val other: List<Pair<String, String>>, val picture: ByteArray?, val lengthSeconds: Int)

    /** The details shown by name, in this order, with the words used for them. */
    val named: List<Pair<FieldKey, String>> = listOf(
        FieldKey.TITLE to "Song name", FieldKey.ARTIST to "Artist", FieldKey.ALBUM to "Album", FieldKey.ALBUM_ARTIST to "Album artist",
        FieldKey.YEAR to "Year", FieldKey.TRACK to "Track number", FieldKey.TRACK_TOTAL to "Tracks on the album", FieldKey.DISC_NO to "Disc",
        FieldKey.DISC_TOTAL to "Discs", FieldKey.GENRE to "Kind of music", FieldKey.COMPOSER to "Written by", FieldKey.LYRICIST to "Words by",
        FieldKey.CONDUCTOR to "Conductor", FieldKey.RECORD_LABEL to "Record label", FieldKey.CATALOG_NO to "Catalogue number",
        FieldKey.ORIGINAL_YEAR to "First out", FieldKey.COMMENT to "Comment", FieldKey.LYRICS to "Lyrics", FieldKey.BPM to "Beats a minute",
        FieldKey.ISRC to "ISRC code", FieldKey.GROUPING to "Grouping", FieldKey.LANGUAGE to "Language", FieldKey.MOOD to "Mood",
    )

    fun readAll(file: File): All {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val known = LinkedHashMap<FieldKey, String>()
        val knownIds = HashSet<String>()
        for ((k, _) in named) {
            val v = runCatching { tag?.getFirst(k) }.getOrNull().orEmpty()
            known[k] = v
            runCatching { tag?.getFields(k).orEmpty().forEach { knownIds += it.id } }
        }
        val other = ArrayList<Pair<String, String>>()
        tag?.fields?.forEach { f ->
            if (f.isBinary || f.id in knownIds) return@forEach
            val text = (f as? org.jaudiotagger.tag.TagTextField)?.content ?: f.toString()
            if (text.isNotBlank() && text.length < 2000) other += f.id to text
        }
        return All(known, other, runCatching { tag?.firstArtwork?.binaryData }.getOrNull(), audio.audioHeader?.trackLength ?: 0)
    }

    /**
     * Changes the details in [file] as given by hand, leaving everything else in it as it is: [set] details are written
     * (a blank one is taken out), [remove] are other details taken out by their code, and [cover] (when given) becomes
     * the one picture. An MP3's old second block of details (ID3v1, which some players read first) is taken out so it
     * can't show the old names. Checked the same way as [fix]; throws when the check fails. The answer is the details
     * this kind of file can't hold, by name (none, mostly).
     */
    private fun nfc(v: String) = java.text.Normalizer.normalize(v.trim(), java.text.Normalizer.Form.NFC)

    fun edit(file: File, set: Map<FieldKey, String>, remove: Set<String>, cover: File?, knownSeconds: Int = 0): List<String> {
        val length = runCatching { AudioFileIO.read(file).audioHeader?.trackLength ?: 0 }.getOrDefault(0)
        val bytesBefore = if (Library.ending(file.name) == "mp3") runCatching { file.readBytes() }.getOrNull() else null
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        val failed = HashSet<FieldKey>()
        for ((k, v) in set) {
            // Letters with accents (Sinéad's fada) are kept in their one-letter form, as every player expects.
            runCatching { if (v.isBlank()) tag.deleteField(k) else tag.setField(k, nfc(v)) }.onFailure { failed += k }
        }
        for (id in remove) runCatching { tag.deleteField(id) }
        if (cover != null) {
            runCatching { tag.deleteArtworkField() }
            val art = CoverArt().apply { setFromFile(cover) }
            art.pictureType = PictureTypes.DEFAULT_ID
            art.description = ""
            tag.setField(art)
        }
        if (audio is org.jaudiotagger.audio.mp3.MP3File && audio.hasID3v1Tag()) audio.setID3v1Tag(null as org.jaudiotagger.tag.id3.ID3v1Tag?)
        AudioFileIO.write(audio)

        val back = AudioFileIO.read(file)
        for (k in listOf(FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM)) {
            val want = set[k]?.let { nfc(it) } ?: continue
            if (k in failed) continue
            check(runCatching { back.tag?.getFirst(k) }.getOrNull().orEmpty() == want) { (named.first { it.first == k }.second) + " didn't stick" }
        }
        if (cover != null) check(back.tag?.artworkList.orEmpty().size == 1) { "the cover didn't stick" }
        val after = back.audioHeader?.trackLength ?: 0
        val soundSame = bytesBefore != null && runCatching { soundKept(bytesBefore, file.readBytes()) }.getOrDefault(false)
        check(soundSame || kotlin.math.abs(after - length) <= 1 || (knownSeconds > 0 && kotlin.math.abs(after - knownSeconds) <= 2)) { "the song's length changed" }
        return failed.map { k -> named.firstOrNull { it.first == k }?.second ?: k.name }
    }

    /** Where an MP3's sound starts and ends: from its first frame to the end, with the details blocks round it left out. */
    fun mp3Span(b: ByteArray): Pair<Int, Int> {
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
        return start to maxOf(start, end)
    }

    /** A fingerprint of an MP3's sound alone. Two files with the same answer hold exactly the same sound. */
    fun mp3Sound(file: File): ByteArray {
        val b = file.readBytes()
        val (start, end) = mp3Span(b)
        if (end <= start) return ByteArray(0)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(b, start, end - start)
        return md.digest()
    }

    /**
     * True when the MP3 [after] still holds all the sound of [before] (27 November 2026). The sound must be the same
     * bytes through to the end; the tag library may only have cleared away bytes in front that are not sound: stray
     * bytes between the details and the first frame, or the end of a picture whose size was written wrong. Proved on
     * test files of both kinds made with the tag library itself; a file with real frames cut off is caught.
     */
    fun soundKept(before: ByteArray, after: ByteArray): Boolean {
        val (os, oe) = mp3Span(before)
        val (ns, ne) = mp3Span(after)
        val oLen = oe - os
        val nLen = ne - ns
        if (nLen <= 0 || nLen > oLen) return false
        val drop = oLen - nLen
        if (java.nio.ByteBuffer.wrap(before, os + drop, nLen) != java.nio.ByteBuffer.wrap(after, ns, nLen)) return false
        return drop == 0 || !hasFrames(before, os, os + drop)
    }

    /** Three MPEG sound frames in a row starting between [from] and [to], each where the one before ends: real sound. */
    private fun hasFrames(b: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) {
            val (len, kind) = frame(b, i)
            if (len <= 0) continue
            var j = i + len
            var run = 1
            while (run < 3) {
                val (n, k) = frame(b, j)
                if (n <= 0 || k != kind) break
                j += n
                run++
            }
            if (run >= 3) return true
        }
        return false
    }

    private val rates1 = intArrayOf(44100, 48000, 32000)
    private val rates2 = intArrayOf(22050, 24000, 16000)
    private val rates25 = intArrayOf(11025, 12000, 8000)
    private val kbps = mapOf(
        "1-1" to intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        "1-2" to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        "1-3" to intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        "2-1" to intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        "2-2" to intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )

    /** The length of the MPEG frame starting at [i] (0 when none) and its kind (version, layer, sample rate). */
    private fun frame(b: ByteArray, i: Int): Pair<Int, Int> {
        if (i < 0 || i + 4 > b.size) return 0 to 0
        val h = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        if ((h ushr 21) and 0x7FF != 0x7FF) return 0 to 0
        val ver = (h ushr 19) and 3
        val layerBits = (h ushr 17) and 3
        val br = (h ushr 12) and 0xF
        val sr = (h ushr 10) and 3
        val pad = (h ushr 9) and 1
        if (ver == 1 || layerBits == 0 || br == 0 || br == 15 || sr == 3) return 0 to 0
        val layer = 4 - layerBits
        val table = if (ver == 3) "1-$layer" else if (layer == 1) "2-1" else "2-2"
        val bitrate = kbps.getValue(table)[br] * 1000
        val rate = (if (ver == 3) rates1 else if (ver == 2) rates2 else rates25)[sr]
        val len = when {
            layer == 1 -> (12 * bitrate / rate + pad) * 4
            layer == 3 && ver != 3 -> 72 * bitrate / rate + pad
            else -> 144 * bitrate / rate + pad
        }
        return len to (h and 0xFFFE0C00.toInt())
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
