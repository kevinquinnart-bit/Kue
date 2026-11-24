package art.kquinn.kuevault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.math.ln

/**
 * Asking your manuals: each manual's words are read once on the phone and kept, so a question finds the
 * parts of the manuals that answer it, with no internet. The answer is the manual's own words with the
 * page they're on, never made up; the person can have it read out.
 */
object Manuals {
    /** A part of a manual: which thing and paper it's from, the page, and the words. */
    data class Passage(val thingId: String, val paperId: String, val page: Int, val text: String)

    /** A part that answers a question, and how well. */
    data class Hit(val passage: Passage, val score: Double)

    private val gson = Gson()

    private fun folder(context: Context) = File(context.filesDir, "manuals").apply { mkdirs() }
    private fun fileOf(context: Context, thingId: String, paperId: String) =
        File(folder(context), (thingId + "_" + paperId).hashCode().toUInt().toString(16) + ".json")

    /** Whether [paper]'s words have been read and kept. */
    fun indexed(context: Context, thingId: String, paper: Paperwork) = fileOf(context, thingId, paper.id).exists()

    /** The words on each page of a PDF. A page that is only a picture gives nothing here. */
    fun pagesOf(context: Context, pdf: File): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(pdf).use { doc ->
            val strip = PDFTextStripper()
            (1..doc.numberOfPages).map { p ->
                strip.startPage = p
                strip.endPage = p
                runCatching { strip.getText(doc) }.getOrDefault("")
            }
        }
    }

    /** How many pages a PDF has, or 0 when it can't be opened. */
    fun pageCount(pdf: File): Int = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrDefault(0)

    /** Page [index] (from 0) of a PDF as a picture, for reading the words off a scanned page. */
    fun pagePicture(pdf: File, index: Int, width: Int = 1600): Bitmap? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (index >= r.pageCount) null else r.openPage(index).use { page ->
                    val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    /** Keeps words already read for a manual: one string per page. */
    fun keep(context: Context, thingId: String, paper: Paperwork, pages: List<String>): Boolean {
        if (pages.none { it.isNotBlank() }) return false
        fileOf(context, thingId, paper.id).writeText(gson.toJson(pages))
        return true
    }

    fun forget(context: Context, thingId: String, paperId: String) { fileOf(context, thingId, paperId).delete() }

    /** Every kept part of the manuals of [things]. */
    fun passages(context: Context, things: List<Thing>): List<Passage> {
        val type = object : TypeToken<List<String>>() {}.type
        return things.flatMap { t ->
            t.papersOf(Paper.MANUAL).flatMap { p ->
                val f = fileOf(context, t.id, p.id)
                if (!f.exists()) emptyList() else {
                    val pages: List<String> = runCatching { gson.fromJson<List<String>>(f.readText(), type) }.getOrDefault(emptyList())
                    split(t.id, p.id, pages)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Finding the answer (no Android in here)

    /** Cuts pages into parts of a paragraph or so, each knowing its page. */
    fun split(thingId: String, paperId: String, pages: List<String>, size: Int = 700): List<Passage> {
        val out = ArrayList<Passage>()
        for ((i, page) in pages.withIndex()) {
            val paragraphs = page.replace("\r", "").split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 }
            val part = StringBuilder()
            fun flush() { if (part.isNotBlank()) out += Passage(thingId, paperId, i + 1, part.toString().trim()); part.clear() }
            for (p in paragraphs) {
                if (p.length > size) {
                    flush()
                    // A very long paragraph is cut at sentence ends.
                    for (s in p.split(Regex("(?<=[.!?])\\s+"))) {
                        if (part.length + s.length > size) flush()
                        part.append(s).append(' ')
                    }
                    flush()
                } else {
                    if (part.length + p.length > size) flush()
                    part.append(p).append(' ')
                }
            }
            flush()
        }
        return out
    }

    private val stop = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "is", "are", "it", "its", "my", "me", "i", "do", "does",
        "how", "what", "when", "where", "why", "which", "who", "can", "could", "should", "would", "will", "with", "this",
        "that", "be", "by", "at", "as", "from", "if", "you", "your", "there", "their", "has", "have", "not", "no", "so",
        "up", "out", "get", "make", "use", "using", "about", "into", "than", "then", "them", "they", "we", "our", "was",
    )

    /** The words that matter, plain and cut back to their stem ("cleaning" and "cleans" both "clean"). */
    fun words(text: String): List<String> = Library.plain(text).split(' ').filter { it.length > 1 && it !in stop }.map { stem(it) }

    fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") && (w.dropLast(2).endsWith("s") || w.dropLast(2).endsWith("x") || w.dropLast(2).endsWith("ch") || w.dropLast(2).endsWith("sh")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /**
     * The parts that best answer [question], best first: words of the question that are rare in the manuals
     * count for more, and a part counts for more the more of the question's words it has (BM25).
     */
    fun ask(question: String, passages: List<Passage>, top: Int = 3): List<Hit> {
        val q = words(question).distinct()
        if (q.isEmpty() || passages.isEmpty()) return emptyList()
        val docs = passages.map { words(it.text) }
        val avg = docs.map { it.size }.average().coerceAtLeast(1.0)
        val n = docs.size.toDouble()
        val df = q.associateWith { w -> docs.count { w in it }.toDouble() }
        val k1 = 1.4
        val b = 0.75
        val scored = passages.indices.map { i ->
            val d = docs[i]
            val counts = d.groupingBy { it }.eachCount()
            var s = 0.0
            var matched = 0
            for (w in q) {
                val f = counts[w] ?: continue
                matched++
                val idf = ln(1 + (n - df.getValue(w) + 0.5) / (df.getValue(w) + 0.5))
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * d.size / avg))
            }
            // Parts holding more of the question's different words come first.
            Hit(passages[i], if (matched == 0) 0.0 else s * (1 + matched.toDouble() / q.size))
        }
        return scored.filter { it.score > 0 }.sortedByDescending { it.score }.take(top)
    }
}
