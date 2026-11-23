package art.kquinn.kuetv

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Puts the TV to sleep from a phone on the same Wi-Fi: from KueTV on the phone, or from any phone's browser at
 * the TV's address on [PORT]. The TV answers for as long as the Home button watcher runs, so it works whatever
 * app is on screen. Nothing leaves the home network.
 */
object TvRemote {
    const val PORT = 8769
    const val HELLO = "KueTV remote"

    @Volatile private var socket: ServerSocket? = null

    /** Started by the Home button watcher; it keeps running while the watcher does. */
    fun start() {
        if (socket != null) return
        val server = runCatching { ServerSocket(PORT) }.getOrNull() ?: run {
            Report.note("TV remote", "not started: its door is in use")
            return
        }
        socket = server
        Report.note("TV remote", "listening on " + (PhoneSetup.localAddress() ?: "?") + ":" + PORT)
        thread(isDaemon = true, name = "tv-remote") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { answer(client) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun answer(client: Socket) {
        client.soTimeout = 8_000
        val first = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine() ?: return
        val path = first.split(' ').getOrNull(1).orEmpty()
        when {
            path == "/hello" -> send(client, "text/plain", HELLO)
            path.startsWith("/sleep") -> {
                Report.note("TV remote", "asked to sleep by " + (client.inetAddress?.hostAddress ?: "?"))
                val ok = HomeGuard.sleepTv()
                send(client, "text/html", page(if (ok) "<h1>The TV is going to sleep</h1><p>Good night. You can close this page.</p>"
                    else "<h1>The TV didn't go to sleep</h1><p>Its Home button watcher isn't running. Use the TV's remote this time.</p>"))
            }
            else -> send(client, "text/html", page("<h1>KueTV</h1><form method=\"get\" action=\"/sleep\"><button type=\"submit\">Put the TV to sleep</button></form>"))
        }
    }

    private fun page(inner: String) = "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>KueTV</title><style>" +
        "body{font-family:sans-serif;background:#0e1626;color:#fff;margin:0;padding:22px;font-size:18px}h1{font-size:24px}p{color:#b9c2d3}" +
        "button{margin-top:22px;width:100%;padding:22px;font-size:22px;border:0;border-radius:12px;background:#ffd23f;font-weight:bold}" +
        "</style></head><body>$inner</body></html>"

    private fun send(client: Socket, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("tv-remote", Context.MODE_PRIVATE)

    /**
     * From the phone: finds the TV on the Wi-Fi (the one found last time first) and asks it to sleep.
     * The answer is what to tell the viewer. Off the screen's thread.
     */
    fun sleepFromPhone(context: Context): String {
        val mine = PhoneSetup.localAddress() ?: return "Your phone isn't on Wi-Fi."
        val known = prefs(context).getString("tv", "").orEmpty()
        val ask: (String) -> Boolean = { host -> SetUpTaker.ask(host, PORT, "/sleep", 2_000)?.first == 200 }
        if (known.isNotEmpty() && SetUpTaker.ask(known, PORT, "/hello", 1_500)?.second == HELLO && ask(known)) return "The TV is going to sleep."
        val found = SetUpTaker.find(mine, PORT, HELLO).filter { it != mine }
        val tv = found.firstOrNull() ?: return "No TV with KueTV found on this Wi-Fi. Is the TV on, and the phone on the same Wi-Fi?"
        prefs(context).edit().putString("tv", tv).apply()
        return if (ask(tv)) "The TV is going to sleep." else "The TV didn't answer. Try again."
    }
}
