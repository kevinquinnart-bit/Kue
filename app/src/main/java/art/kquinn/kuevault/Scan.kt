package art.kquinn.kuevault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.gson.JsonParser
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reading paper and labels on the phone itself: the words on a scan or photo, and the barcode on a box.
 * Nothing is sent anywhere to be read. Only a barcode number is looked up, in Open Products Facts, the
 * free open list of products.
 */
object Scan {
    /** The words in a picture, line by line. */
    suspend fun words(context: Context, uri: Uri): String = runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { it.process(InputImage.fromFilePath(context, uri)).await().text }
    }.getOrDefault("")

    suspend fun words(bitmap: Bitmap): String = runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { it.process(InputImage.fromBitmap(bitmap, 0)).await().text }
    }.getOrDefault("")

    /** The product barcode in a picture (the long one on the box), or empty. */
    suspend fun barcode(context: Context, uri: Uri): String = runCatching {
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build()
        BarcodeScanning.getClient(options).use { s -> s.process(InputImage.fromFilePath(context, uri)).await().firstNotNullOfOrNull { it.rawValue } }
    }.getOrNull().orEmpty()

    /**
     * The words in a file the person picked or shared: a PDF's own words (or, for a scanned PDF, the words read
     * off its first page), a picture's words, or a text file's text.
     */
    suspend fun wordsOfFile(context: Context, file: File, mime: String): String = withContext(Dispatchers.IO) {
        when {
            mime == "application/pdf" || file.name.endsWith(".pdf", true) -> {
                val text = runCatching { Manuals.pagesOf(context, file).take(4).joinToString("\n") }.getOrDefault("")
                if (text.isNotBlank()) text else Manuals.pagePicture(file, 0)?.let { words(it) }.orEmpty()
            }
            mime.startsWith("image/") -> words(context, Uri.fromFile(file))
            mime.startsWith("text/") || mime.isEmpty() -> runCatching { file.readText().take(200_000) }.getOrDefault("").let { if (mime == "text/html") html(it) else it }
            else -> ""
        }
    }

    /** An email or web page's words with its markup taken out. */
    fun html(text: String): String = text
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&pound;", "£").replace("&euro;", "€").replace("&#163;", "£")
        .lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** A product's name and maker, looked up by its barcode in Open Products Facts. Null when it isn't listed. */
    fun lookUp(barcode: String): Pair<String, String>? = runCatching {
        if (!Regex("[0-9]{8,14}").matches(barcode)) return null
        val req = Request.Builder().url("https://world.openproductsfacts.org/api/v2/product/$barcode.json?fields=product_name,brands")
            .header("User-Agent", "KueVault/" + BuildConfig.VERSION_NAME + " (https://kaykue.com)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val j = JsonParser.parseString(r.body.string()).asJsonObject
            if (j.get("status")?.asInt != 1) return null
            val p = j.getAsJsonObject("product")
            val name = p.get("product_name")?.asString.orEmpty().trim()
            val brand = p.get("brands")?.asString.orEmpty().split(',').first().trim()
            if (name.isEmpty() && brand.isEmpty()) null else name to brand
        }
    }.getOrNull()
}
