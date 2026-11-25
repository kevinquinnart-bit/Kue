package art.kquinn.kuesounds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KueSounds' own sound fingerprint checked against Chromaprint's, the fingerprint AcoustID's database is made of
 * (25 November 2026). The answers were made by ffmpeg's own Chromaprint on a 20-second made-up tune (resources/
 * fingerprint): ours must be the same, number for number, and written the same way.
 */
class FingerprintTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/fingerprint/$name")!!.readBytes()

    private fun wav(name: String): Triple<ShortArray, Int, Int> {
        val b = bytes(name)
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var p = 12; var ch = 1; var rate = 0; var data = 0; var size = 0
        while (p < b.size - 8) {
            val id = String(b, p, 4); val len = bb.getInt(p + 4)
            if (id == "fmt ") { ch = bb.getShort(p + 10).toInt(); rate = bb.getInt(p + 12) }
            if (id == "data") { data = p + 8; size = minOf(len, b.size - data); break }
            p += 8 + len
        }
        return Triple(ShortArray(size / 2) { bb.getShort(data + it * 2) }, rate, ch)
    }

    private val chromaprint: IntArray by lazy {
        val b = bytes("clip_ref.raw")
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        IntArray(b.size / 4) { bb.getInt(it * 4) }
    }

    @Test fun theSameNumbersAsChromaprint() {
        val (s, rate, ch) = wav("clip11.wav")
        val ours = Fingerprint.of(Fingerprint.toRate(s, s.size, rate, ch))
        assertTrue(ours.size > 100)
        assertArrayEquals(chromaprint, ours)
    }

    @Test fun writtenTheSameWayAsChromaprint() {
        assertEquals(String(bytes("clip_ref.b64")).trim(), Fingerprint.encode(chromaprint))
    }

    @Test fun cdQualityStereoGivesTheSameFingerprint() {
        // 44,100 a second, two channels: made one channel at 11,025 by our own filter first.
        val (s, rate, ch) = wav("clip44.wav")
        val ours = Fingerprint.of(Fingerprint.toRate(s, s.size, rate, ch))
        val n = minOf(ours.size, chromaprint.size)
        var wrong = 0L
        for (i in 0 until n) wrong += Integer.bitCount(ours[i] xor chromaprint[i])
        assertTrue("bits different: $wrong of ${32 * n}", wrong.toDouble() / (32 * n) < 0.05)
    }

    @Test fun acoustIdsAnswerIsRead() {
        val answer = """{"status":"ok","results":[{"id":"x","score":0.97,"recordings":[{"id":"r1","title":"A Moment With You","artists":[{"name":"George Michael"}],
            "releasegroups":[{"id":"g1","title":"Ladies & Gentlemen: The Best of George Michael","type":"Album","secondarytypes":["Compilation"]}]}]}]}"""
        val found = SoundMatch.parse(answer)
        assertEquals("A Moment With You", found.single().title)
        assertEquals("George Michael", found.single().artist)
        assertTrue("a best of isn't taken as its album", found.single().albums.isEmpty())
        assertEquals(0.97, found.single().score, 0.001)
    }
}
