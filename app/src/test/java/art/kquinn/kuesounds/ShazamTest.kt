package art.kquinn.kuesounds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Name that song (26 November 2026). The signature is checked byte for byte against a separate reference version that
 * Shazam's song finder did name real songs from (East 17 "Stay Another Day", also with noise mixed in, and Derek Ryan
 * "Run To The Door"). The test tune here is made-up sound, so nothing of anyone's music is kept in the project.
 */
class ShazamTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/shazam/$name")!!.readBytes()

    @Test fun theSameSignatureAsTheReference() {
        val b = bytes("tune16k.raw")
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tune = ShortArray(b.size / 2) { bb.getShort(it * 2) }
        assertArrayEquals(bytes("tune16k.sig"), ShazamSig.of(tune))
    }

    @Test fun shazamsLayoutAndCheckNumber() {
        val sig = ShazamSig.of(ShortArray(ShazamSig.RATE * 4) { (kotlin.math.sin(it * 0.3) * 9000).toInt().toShort() })
        val bb = java.nio.ByteBuffer.wrap(sig).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), bb.getInt(0))
        assertEquals(0x94119c00.toInt(), bb.getInt(12))
        assertEquals(sig.size - 48, bb.getInt(8))
        val crc = java.util.zip.CRC32().apply { update(sig, 8, sig.size - 8) }.value.toInt()
        assertEquals(crc, bb.getInt(4))
    }

    @Test fun readsShazamsAnswer() {
        val answer = """{"matches":[{"id":"1"}],"track":{"key":"42","title":"Stay Another Day","subtitle":"East 17",
            "images":{"coverart":"https://is1-ssl.mzstatic.com/image/thumb/a/b.jpg/400x400cc.jpg"},"genres":{"primary":"Pop"},
            "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Steam"},{"title":"Label","text":"London Records"},{"title":"Released","text":"1994"}]}]}}"""
        val f = Shazam.parse(answer)!!
        assertEquals("Stay Another Day", f.title)
        assertEquals("East 17", f.artist)
        assertEquals(listOf("Steam"), f.albums)
        assertEquals("1994", f.year)
        assertEquals("London Records", f.label)
        assertEquals("Pop", f.genre)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/a/b.jpg/1000x1000cc.jpg", f.cover)
        assertEquals("Shazam", f.from)
    }

    @Test fun noSongWhenShazamDoesntKnowIt() {
        assertNull(Shazam.parse("""{"matches":[],"timestamp":1}"""))
    }
}
