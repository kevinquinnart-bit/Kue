package art.kquinn.kuetv

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A plain record of what the app did and what went wrong, kept on the device, for the user to copy and
 * send when something misbehaves. It never holds an address: those carry the provider's username and
 * password, so every address is taken out before a line is kept.
 */
object Report {
    private const val MOST = 300
    private val lines = ArrayDeque<String>()
    private val address = Regex("(?i)\\b(?:https?|magnet|file):[^\\s\"']+")
    private val stamp = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK)

    /** Where the record is also written, so it is still there after the app has been closed. */
    @Volatile var file: File? = null
        private set

    fun clean(text: String): String = address.replace(text, "[address]").replace('\n', ' ').take(400)

    /** Starts keeping the record in [to], picking up what was kept there before. */
    @Synchronized
    fun keepIn(to: File) {
        file = to
        runCatching {
            val old = if (to.exists()) to.readLines().takeLast(MOST) else emptyList()
            lines.clear()
            lines.addAll(old)
            // The file is cut back to what is kept, so it never grows without end.
            to.writeText(old.joinToString("") { it + "\n" })
        }
    }

    @Synchronized
    fun note(area: String, text: String) {
        val line = stamp.format(Date()) + "  " + area + ": " + clean(text)
        lines.addLast(line)
        while (lines.size > MOST) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun all(): List<String> = lines.toList()

    /** The whole record under a few lines saying what it came from. */
    fun text(about: List<String>): String = (about + "" + all()).joinToString("\n")
}

/** Shows one page to anyone on the home network who opens this device's address, while a screen wants it to. */
class PageServer(private val page: () -> String) {
    @Volatile private var socket: ServerSocket? = null

    fun start(port: Int = PORT): Int? {
        val server = try {
            ServerSocket(port)
        } catch (e: Exception) {
            // So the report shows why the page couldn't be opened.
            Report.note("Report page", "couldn't open on port $port: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            return null
        }
        socket = server
        Report.note("Report page", "open on port " + server.localPort)
        thread(isDaemon = true, name = "page-server") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (!server.isClosed) Report.note("Report page", "stopped answering: " + e.javaClass.simpleName + " " + (e.message ?: ""))
                    break
                }
                thread(isDaemon = true) {
                    runCatching { answer(client) }.onFailure {
                        Report.note("Report page", "answer to " + (client.inetAddress?.hostAddress ?: "?") + " failed: " + it.javaClass.simpleName + " " + (it.message ?: ""))
                    }
                    runCatching { client.close() }
                }
            }
        }
        return server.localPort
    }

    fun stop() {
        if (socket != null) Report.note("Report page", "closed")
        runCatching { socket?.close() }
        socket = null
    }

    private fun answer(client: Socket) {
        client.soTimeout = 8_000
        val first = client.getInputStream().bufferedReader().readLine() ?: run {
            Report.note("Report page", "asked by " + (client.inetAddress?.hostAddress ?: "?") + " with nothing in the request")
            return
        }
        Report.note("Report page", "asked for by " + (client.inetAddress?.hostAddress ?: "?") + ": " + first.substringBefore(" HTTP"))
        val bytes = page().toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PORT = 8767

        /** A page holding [text] in a box, with a button that copies it. */
        fun copyPage(title: String, text: String): String {
            val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>KueTV</title><style>" +
                "body{font-family:sans-serif;background:#0e1626;color:#fff;margin:0;padding:18px;font-size:17px}h1{font-size:22px}" +
                "textarea{width:100%;height:60vh;box-sizing:border-box;font-size:13px;border-radius:10px;border:0;padding:10px}" +
                "button{margin:14px 0;width:100%;padding:16px;font-size:20px;border:0;border-radius:10px;background:#ffd23f;font-weight:bold}p{color:#b9c2d3}" +
                "</style></head><body><h1>" + title + "</h1><p>Tap Copy, then paste it into your chat with Claude.</p>" +
                "<button onclick=\"var t=document.getElementById('t');t.select();t.setSelectionRange(0,999999);" +
                "(navigator.clipboard?navigator.clipboard.writeText(t.value):Promise.reject()).catch(function(){document.execCommand('copy')});this.textContent='Copied'\">Copy</button>" +
                "<textarea id=\"t\" readonly>" + safe + "</textarea></body></html>"
        }
    }
}
