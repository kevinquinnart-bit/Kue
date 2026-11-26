package art.kquinn.kuesounds

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Name that song (Kevin, 26 November 2026): the sound "signature" Shazam's song finder reads, made by KueSounds itself.
 * Written here from the method that SongRec and others worked out and published; none of their code is copied in.
 *
 * Steps: the sound at 16,000 a second, one channel → every 128 samples, the last 2,048 through a Hann window and a
 * Fourier transform → the loudest points in time and pitch picked out → those points, in four pitch bands, packed
 * into Shazam's own layout with its check number on the front.
 *
 * Shazam's song finder is not a published service: it is used here without its say-so and could stop answering at
 * any time. Fine for Kevin's own phone; not for an app on sale (noted in his private notes).
 */
object ShazamSig {
    const val RATE = 16_000
    /** How much sound is sent: 12 seconds, as Shazam's own app does. */
    const val SECONDS = 12

    private const val FFT = 2048
    private const val BINS = FFT / 2 + 1
    private val hann = FloatArray(FFT) { i -> (0.5 * (1 - cos(2 * PI * (i + 1) / 2049))).toFloat() }
    private val neighbours = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val others = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    /** The signature of [length] samples at 16,000 a second, one channel. */
    fun of(samples: ShortArray, length: Int = samples.size): ByteArray {
        val ring = ShortArray(FFT)
        var ringAt = 0
        val ffts = Array(256) { FloatArray(BINS) }
        var fftAt = 0
        val spread = Array(256) { FloatArray(BINS) }
        var spreadAt = 0
        var spreadDone = 0
        val bands = Array(4) { ArrayList<Peak>() }
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val chunks = length / 128
        for (c in 0 until chunks) {
            System.arraycopy(samples, c * 128, ring, ringAt, 128)
            ringAt = (ringAt + 128) and (FFT - 1)
            for (i in 0 until FFT) {
                re[i] = (ring[(i + ringAt) and (FFT - 1)].toFloat() * hann[i]).toDouble()
                im[i] = 0.0
            }
            fft(re, im)
            val out = ffts[fftAt]
            for (i in 0 until BINS) out[i] = maxOf(((re[i] * re[i] + im[i] * im[i]) / 131072.0).toFloat(), 1e-10f)
            fftAt = (fftAt + 1) and 255

            // Spread each peak a little over nearby pitches, then over the slices just before.
            val s = spread[spreadAt]
            System.arraycopy(out, 0, s, 0, BINS)
            for (p in 0..BINS - 3) s[p] = maxOf(s[p], s[p + 1], s[p + 2])
            for (k in intArrayOf(1, 3, 6)) {
                val f = spread[(spreadAt - k) and 255]
                for (p in 0 until BINS) if (s[p] > f[p]) f[p] = s[p]
            }
            spreadAt = (spreadAt + 1) and 255
            spreadDone++
            if (spreadDone < 46) continue

            // A point is kept when it is louder than everything around it, before and after.
            val m46 = ffts[(fftAt - 46) and 255]
            val m49 = spread[(spreadAt - 49) and 255]
            for (b in 10 until 1015) {
                val v = m46[b]
                if (v < 1f / 64 || v < m49[b - 1]) continue
                var most = 0f
                for (o in neighbours) most = maxOf(most, m49[b + o])
                if (v <= most) continue
                for (o in others) most = maxOf(most, spread[(spreadAt + o) and 255][b - 1])
                if (v <= most) continue
                val mag = ln(maxOf(1.0 / 64, v.toDouble())) * 1477.3 + 6144
                val before = ln(maxOf(1.0 / 64, m46[b - 1].toDouble())) * 1477.3 + 6144
                val after = ln(maxOf(1.0 / 64, m46[b + 1].toDouble())) * 1477.3 + 6144
                val v1 = mag * 2 - before - after
                val v2 = (after - before) * 32 / v1
                val bin = (b * 64 + v2.toInt()) and 0xFFFF
                val hz = bin * (16000.0 / 2 / 1024 / 64)
                val band = when {
                    hz >= 250 && hz < 520 -> 0
                    hz >= 520 && hz < 1450 -> 1
                    hz >= 1450 && hz < 3500 -> 2
                    hz >= 3500 && hz <= 5500 -> 3
                    else -> -1
                }
                if (band >= 0) bands[band] += Peak(spreadDone - 46, mag.toInt() and 0xFFFF, bin)
            }
        }
        return pack(bands, length)
    }

    /** Shazam's layout: a 48-byte front, then each band's points with the gap in time since the one before. */
    private fun pack(bands: Array<ArrayList<Peak>>, samples: Int): ByteArray {
        val body = Bytes()
        for ((index, peaks) in bands.withIndex()) {
            if (peaks.isEmpty()) continue
            val pb = Bytes()
            var last = 0
            for (p in peaks) {
                if (p.pass - last >= 255) { pb.u8(0xff); pb.u32(p.pass); last = p.pass }
                pb.u8(p.pass - last); pb.u16(p.magnitude); pb.u16(p.bin)
                last = p.pass
            }
            body.u32(0x60030040 + index); body.u32(pb.size())
            body.all(pb.toArray())
            repeat((4 - pb.size() % 4) % 4) { body.u8(0) }
        }
        val size = 8 + body.size()
        val all = Bytes()
        all.u32(0xcafe2580.toInt()); all.u32(0); all.u32(size); all.u32(0x94119c00.toInt())
        all.u32(0); all.u32(0); all.u32(0); all.u32(3 shl 27); all.u32(0); all.u32(0)
        all.u32(samples + (RATE * 0.24).toInt()); all.u32((15 shl 19) + 0x40000)
        all.u32(0x40000000); all.u32(size)
        all.all(body.toArray())
        val out = all.toArray()
        val crc = java.util.zip.CRC32().apply { update(out, 8, out.size - 8) }.value.toInt()
        out[4] = crc.toByte(); out[5] = (crc shr 8).toByte(); out[6] = (crc shr 16).toByte(); out[7] = (crc shr 24).toByte()
        return out
    }

    /** The signature written as Shazam takes it. */
    fun uri(sig: ByteArray) = "data:audio/vnd.shazam.sig;base64," + java.util.Base64.getEncoder().encodeToString(sig)

    private class Bytes : java.io.ByteArrayOutputStream() {
        fun u8(v: Int) = write(v and 255)
        fun u16(v: Int) { write(v and 255); write((v shr 8) and 255) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        fun all(b: ByteArray) = write(b, 0, b.size)
        fun toArray(): ByteArray = toByteArray()
    }

    /** A plain fast Fourier transform, in place, of a length that is a power of two. */
    private val cosT = DoubleArray(FFT / 2) { cos(-2 * PI * it / FFT) }
    private val sinT = DoubleArray(FFT / 2) { sin(-2 * PI * it / FFT) }
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val cr = cosT[k * step]; val ci = sinT[k * step]
                    val a = i + k + len / 2
                    val ar = re[a] * cr - im[a] * ci
                    val ai = re[a] * ci + im[a] * cr
                    re[a] = re[i + k] - ar; im[a] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                }
                i += len
            }
            len = len shl 1
        }
    }
}
