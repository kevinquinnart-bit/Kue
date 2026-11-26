package art.kquinn.kuesounds

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Asks Shazam's song finder what a piece of sound is (Kevin, 26 November 2026), with the signature KueSounds makes
 * itself ([ShazamSig]). The same request Shazam's own Android app sends. No key, no account and nothing about Kevin
 * is sent: only the signature, the time and a made-up place.
 *
 * Not a published service: used without Shazam's say-so, so it could stop answering at any time.
 */
object Shazam {
    private var lastAsked = 0L

    /** The song in [mono] (16,000 a second, one channel, up to 12 seconds used), or null when Shazam doesn't know it. */
    fun name(mono: ShortArray, length: Int = mono.size): SoundMatch.Found? {
        val n = minOf(length, ShazamSig.RATE * ShazamSig.SECONDS)
        val start = (length - n).coerceAtLeast(0)
        val clip = if (start == 0 && n == mono.size) mono else mono.copyOfRange(start, start + n)
        return ask(ShazamSig.of(clip), clip.size)
    }

    /** One question at a time, at least 2 seconds apart, so a run through the library doesn't get KueSounds shut out. */
    @Synchronized
    private fun ask(sig: ByteArray, samples: Int): SoundMatch.Found? {
        val wait = 2000 - (System.currentTimeMillis() - lastAsked)
        if (wait > 0) Thread.sleep(wait)
        lastAsked = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val body = JsonObject().apply {
            add("geolocation", JsonObject().apply { addProperty("altitude", 300); addProperty("latitude", 45); addProperty("longitude", 2) })
            add("signature", JsonObject().apply {
                addProperty("samplems", samples / (ShazamSig.RATE / 1000))
                addProperty("timestamp", now)
                addProperty("uri", ShazamSig.uri(sig))
            })
            addProperty("timestamp", now)
            addProperty("timezone", "Europe/London")
        }
        val url = "https://amp.shazam.com/discovery/v5/en/GB/android/-/tag/" + UUID.randomUUID().toString().uppercase() + "/" + UUID.randomUUID() +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Language", "en_GB")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 15)")
            .build()
        val text = Drive.http.newCall(request).execute().use { r ->
            if (r.code == 429) throw IOException("Shazam is busy. Try again in a minute.")
            if (!r.isSuccessful) throw IOException("Shazam didn't answer (" + r.code + ").")
            r.body.string()
        }
        return parse(text)
    }

    /** Reads Shazam's answer: the song, its artist, and from the song's details its album, label and year. */
    internal fun parse(text: String): SoundMatch.Found? {
        val root = JsonParser.parseString(text).asJsonObject
        val track = root.getAsJsonObject("track") ?: return null
        val title = track.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val artist = track.get("subtitle")?.asString.orEmpty()
        var album = ""; var label = ""; var year = ""
        for (sec in track.getAsJsonArray("sections") ?: com.google.gson.JsonArray()) {
            val o = sec.asJsonObject
            if (o.get("type")?.asString != "SONG") continue
            for (m in o.getAsJsonArray("metadata") ?: continue) {
                val mo = m.asJsonObject
                val v = mo.get("text")?.asString.orEmpty()
                when (mo.get("title")?.asString) { "Album" -> album = v; "Label" -> label = v; "Released" -> year = v }
            }
        }
        val images = track.getAsJsonObject("images")
        val cover = (images?.get("coverarthq") ?: images?.get("coverart"))?.asString
            // Shazam's pictures can be asked for bigger by their size in the address.
            ?.replace(Regex("/\\d+x\\d+cc\\.jpg$"), "/1000x1000cc.jpg")
        val genre = track.getAsJsonObject("genres")?.get("primary")?.asString.orEmpty()
        fun clean(s: String) = s.replace('’', '\'')
        return SoundMatch.Found(
            clean(title), clean(artist), if (album.isNotBlank()) listOf(clean(album)) else emptyList(), null,
            1.0, track.get("key")?.asString.orEmpty(), cover, year, clean(label), genre, "Shazam",
        )
    }
}
