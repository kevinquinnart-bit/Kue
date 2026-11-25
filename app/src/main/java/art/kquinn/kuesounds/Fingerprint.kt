package art.kquinn.kuesounds

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * KueSounds' own sound fingerprint (Kevin, 25 November 2026: "build ourselves from scratch"), written here from the
 * published method of Chromaprint, the fingerprint AcoustID's free song database is built on (Chromaprint's own code
 * is MIT licensed; none of it is copied in). The sound is turned into 12 notes over time, then into one 32-bit number
 * about every eighth of a second; songs that sound the same give nearly the same numbers.
 *
 * Steps: the sound at 11,025 a second, one channel → slices of 4,096 every 1,365 → strength of each pitch →
 * the 12 notes of the scale → smoothed over 5 slices → evened out → 16 comparisons on the notes over time, 2 bits each.
 */
object Fingerprint {
    const val RATE = 11_025
    private const val FRAME = 4096
    private const val HOP = FRAME / 3          // 4096 - (4096 - 4096 / 3)
    private const val MIN_FREQ = 28
    private const val MAX_FREQ = 3520
    private const val BANDS = 12
    /** How much sound is used: the first two minutes, as AcoustID expects. */
    const val SECONDS = 120

    private val smoothing = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

    /** One comparison: which shape of box on the notes over time, where, how big, and where its value splits into 0-3. */
    private class Test(val type: Int, val y: Int, val height: Int, val width: Int, val t0: Double, val t1: Double, val t2: Double)

    // The 16 comparisons of the method's second version, the one AcoustID uses by default.
    private val tests = arrayOf(
        Test(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Test(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Test(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Test(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Test(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Test(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Test(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Test(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Test(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Test(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Test(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Test(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Test(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Test(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Test(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Test(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )
    private val widest = tests.maxOf { it.width }

    private val window = DoubleArray(FRAME) { i -> (0.54 - 0.46 * cos(i * 2.0 * PI / (FRAME - 1))) / Short.MAX_VALUE }

    /** Which of the 12 notes each pitch slot belongs to, and the slots used (28 to 3,520 a second). */
    private val firstSlot = maxOf(1, round(FRAME.toDouble() * MIN_FREQ / RATE).toInt())
    private val lastSlot = minOf(FRAME / 2, round(FRAME.toDouble() * MAX_FREQ / RATE).toInt())
    private val noteOf = IntArray(FRAME).also { notes ->
        for (i in firstSlot until lastSlot) {
            val freq = i.toDouble() * RATE / FRAME
            val octave = ln(freq / (440.0 / 16.0)) / ln(2.0)
            notes[i] = (BANDS * (octave - floor(octave))).toInt()
        }
    }

    /** The fingerprint of sound already at 11,025 a second, one channel. */
    fun of(samples: ShortArray, length: Int = samples.size): IntArray {
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val power = DoubleArray(FRAME / 2 + 1)
        val history = Array(8) { DoubleArray(BANDS) }
        var historyAt = 0
        var historySize = 1
        // The notes over time, added up as they come (an "integral image"), so any box on them sums in four steps.
        val rows = ArrayList<DoubleArray>()
        val out = ArrayList<Int>()
        var start = 0
        while (start + FRAME <= length) {
            for (i in 0 until FRAME) { re[i] = samples[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (i in 0..FRAME / 2) power[i] = re[i] * re[i] + im[i] * im[i]
            val notes = DoubleArray(BANDS)
            for (i in firstSlot until lastSlot) notes[noteOf[i]] += power[i]
            // Smoothed over the last 5 slices.
            history[historyAt] = notes
            historyAt = (historyAt + 1) % 8
            if (historySize >= smoothing.size) {
                val offset = (historyAt + 8 - smoothing.size) % 8
                val smooth = DoubleArray(BANDS)
                for (b in 0 until BANDS) for (j in smoothing.indices) smooth[b] += history[(offset + j) % 8][b] * smoothing[j]
                // Evened out: each slice scaled to length 1 (or all 0 when nearly silent).
                var sq = 0.0
                for (v in smooth) sq += v * v
                val norm = if (sq > 0) sqrt(sq) else 0.0
                for (b in 0 until BANDS) smooth[b] = if (norm < 0.01) 0.0 else smooth[b] / norm
                // Added to the running sums.
                val row = DoubleArray(BANDS)
                var acc = 0.0
                for (b in 0 until BANDS) { acc += smooth[b]; row[b] = acc + (rows.lastOrNull()?.get(b) ?: 0.0) }
                rows += row
                if (rows.size >= widest) out += word(rows, rows.size - widest)
            } else historySize++
            start += HOP
        }
        return out.toIntArray()
    }

    /** One 32-bit number: the 16 comparisons at this point in time, 2 bits each. */
    private fun word(rows: List<DoubleArray>, x: Int): Int {
        var bits = 0
        for (t in tests) {
            val v = apply(t, rows, x)
            val q = if (v < t.t1) (if (v < t.t0) 0 else 1) else (if (v < t.t2) 2 else 3)
            bits = (bits shl 2) or intArrayOf(0, 1, 3, 2)[q]
        }
        return bits
    }

    /** The sum of the notes in a box: from slice [r1] to before [r2], note [c1] to before [c2]. */
    private fun area(rows: List<DoubleArray>, r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val b = rows[r2 - 1]
        if (r1 == 0) return if (c1 == 0) b[c2 - 1] else b[c2 - 1] - b[c1 - 1]
        val a = rows[r1 - 1]
        return if (c1 == 0) b[c2 - 1] - a[c2 - 1] else b[c2 - 1] - a[c2 - 1] - b[c1 - 1] + a[c1 - 1]
    }

    private fun apply(t: Test, rows: List<DoubleArray>, x: Int): Double {
        val y = t.y
        val w = t.width
        val h = t.height
        val cmp = { a: Double, b: Double -> ln((1.0 + a) / (1.0 + b)) }
        return when (t.type) {
            0 -> cmp(area(rows, x, y, x + w, y + h), 0.0)
            1 -> { val h2 = h / 2; cmp(area(rows, x, y + h2, x + w, y + h), area(rows, x, y, x + w, y + h2)) }
            2 -> { val w2 = w / 2; cmp(area(rows, x + w2, y, x + w, y + h), area(rows, x, y, x + w2, y + h)) }
            3 -> {
                val w2 = w / 2; val h2 = h / 2
                cmp(area(rows, x, y + h2, x + w2, y + h) + area(rows, x + w2, y, x + w, y + h2),
                    area(rows, x, y, x + w2, y + h2) + area(rows, x + w2, y + h2, x + w, y + h))
            }
            4 -> { val h3 = h / 3; cmp(area(rows, x, y + h3, x + w, y + 2 * h3), area(rows, x, y, x + w, y + h3) + area(rows, x, y + 2 * h3, x + w, y + h)) }
            else -> { val w3 = w / 3; cmp(area(rows, x + w3, y, x + 2 * w3, y + h), area(rows, x, y, x + w3, y + h) + area(rows, x + 2 * w3, y, x + w, y + h)) }
        }
    }

    /** A plain fast Fourier transform, in place, of a length that is a power of two. */
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
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Any sound to 11,025 a second, one channel: the channels averaged, then slowed down with a smooth low-pass filter
     * (a windowed sinc, cut at 80% of the new top pitch) so no high pitches fold back in.
     */
    fun toRate(interleaved: ShortArray, length: Int, rate: Int, channels: Int): ShortArray {
        val frames = length / channels
        val mono = FloatArray(frames) { f -> var s = 0; for (c in 0 until channels) s += interleaved[f * channels + c]; s.toFloat() / channels }
        if (rate == RATE) return ShortArray(frames) { mono[it].toInt().coerceIn(-32768, 32767).toShort() }
        val step = rate.toDouble() / RATE
        val cutoff = 0.8 * minOf(1.0, 1.0 / step)
        val half = (16 * maxOf(1.0, step)).toInt()
        val outLen = (frames / step).toInt()
        val out = ShortArray(outLen)
        for (o in 0 until outLen) {
            val centre = o * step
            val base = floor(centre).toInt()
            var acc = 0.0
            var wsum = 0.0
            for (k in base - half + 1..base + half) {
                if (k < 0 || k >= frames) continue
                val x = k - centre
                val sinc = if (x == 0.0) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                val win = 0.42 + 0.5 * cos(PI * x / half) + 0.08 * cos(2 * PI * x / half)   // Blackman
                val wgt = sinc * win
                acc += mono[k] * wgt
                wsum += wgt
            }
            out[o] = (if (wsum != 0.0) acc / wsum else 0.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** The fingerprint written as AcoustID takes it: packed small, then in web-safe letters. */
    fun encode(fp: IntArray, algorithm: Int = 1): String {
        val normal = ArrayList<Int>()
        val exceptional = ArrayList<Int>()
        fun add(x0: Int) {
            var x = x0
            var bit = 1
            var last = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val v = bit - last
                    if (v >= 7) { normal += 7; exceptional += v - 7 } else normal += v
                    last = bit
                }
                x = x ushr 1
                bit++
            }
            normal += 0
        }
        if (fp.isNotEmpty()) {
            add(fp[0])
            for (i in 1 until fp.size) add(fp[i] xor fp[i - 1])
        }
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(algorithm and 255)
        bytes.write((fp.size shr 16) and 255)
        bytes.write((fp.size shr 8) and 255)
        bytes.write(fp.size and 255)
        pack(normal, 3, bytes)
        pack(exceptional, 5, bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    /** Each value's lowest [bits] bits, one after another, lowest first. */
    private fun pack(values: List<Int>, bits: Int, out: java.io.ByteArrayOutputStream) {
        var cur = 0
        var used = 0
        for (v in values) {
            for (b in 0 until bits) {
                if ((v shr b) and 1 != 0) cur = cur or (1 shl used)
                used++
                if (used == 8) { out.write(cur); cur = 0; used = 0 }
            }
        }
        if (used > 0) out.write(cur)
    }
}
