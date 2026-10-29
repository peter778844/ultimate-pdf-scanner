package de.ultimatepdfscanner.app.share

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn

import android.util.Log
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.Storage
import de.ultimatepdfscanner.app.export.ExportOptions
import de.ultimatepdfscanner.app.export.Exporter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Minimal HTTP server for the local network: a PC in the same Wi-Fi opens the shown address and
 * downloads scans as PDF or text. Nothing leaves the network; a random token in the path keeps
 * other devices out, and the server only runs while its screen is open.
 */
class LocalServer(private val storage: Storage, private val docs: () -> List<DocInfo>, private val onDownload: (String) -> Unit) {
    val token: String = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
    private var socket: ServerSocket? = null
    var port = 0
        private set

    fun start(): Boolean {
        for (p in listOf(8080, 8081, 8888, 0)) {
            socket = runCatching { ServerSocket(p) }.getOrNull()
            if (socket != null) break
        }
        val s = socket ?: return false
        port = s.localPort
        thread(name = "ultimatepdfscanner-http", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) { runCatching { handle(client) }.onFailure { Log.w("UltimatePDFScanner", "http", it) }; runCatching { client.close() } }
            }
        }
        return true
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    private fun handle(client: Socket) {
        client.soTimeout = 15000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine() ?: return
        while (reader.readLine()?.isNotEmpty() == true) { /* skip headers */ }
        val path = request.split(' ').getOrNull(1) ?: "/"
        val out = client.getOutputStream()
        val parts = path.trim('/').split('/')
        if (parts.firstOrNull() != token) return respond(out, 404, "text/plain; charset=utf-8", tr("Nicht gefunden").toByteArray())
        val list = docs().filter { !it.inTrash && it.pages.isNotEmpty() }
        when {
            parts.size == 1 -> respond(out, 200, "text/html; charset=utf-8", page(list).toByteArray())
            parts.size == 3 && parts[1] == "thumb" -> {
                val d = list.firstOrNull { it.id == parts[2] } ?: return respond(out, 404, "text/plain", ByteArray(0))
                val (bytes, _) = Exporter.pageJpeg(storage, d, d.pages.first(), de.ultimatepdfscanner.app.export.Resolution.DPI150, de.ultimatepdfscanner.app.export.Compression.HIGH, rotate = true, ptPerPx = 0.12f)
                respond(out, 200, "image/jpeg", bytes)
            }
            parts.size == 3 && (parts[1] == "pdf" || parts[1] == "txt") -> {
                val d = list.firstOrNull { it.id == parts[2] } ?: return respond(out, 404, "text/plain", ByteArray(0))
                val name = d.name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "_") + "." + parts[1]
                val disposition = "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                if (parts[1] == "pdf") {
                    val buf = java.io.ByteArrayOutputStream()
                    Exporter.writePdf(storage, d, buf, ExportOptions())
                    respond(out, 200, "application/pdf", buf.toByteArray(), disposition)
                } else respond(out, 200, "text/plain; charset=utf-8", Exporter.text(storage, d).toByteArray(), disposition)
                onDownload(d.name)
            }
            else -> respond(out, 404, "text/plain", ByteArray(0))
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, disposition: String? = null) {
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val head = buildString {
            append("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            disposition?.let { append("Content-Disposition: $it\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(list: List<DocInfo>): String {
        val fmt = de.ultimatepdfscanner.app.I18n.dateTime()
        val rows = list.joinToString("") { d ->
            """<li><img src="/$token/thumb/${d.id}" alt="" loading="lazy"><div class="m"><b>${esc(d.name)}</b><span>${trn(d.pages.size, "1 Seite", "%d Seiten")} · ${fmt.format(Date(d.created))}</span></div>""" +
                """<a class="b" href="/$token/pdf/${d.id}">PDF</a><a class="b s" href="/$token/txt/${d.id}">${tr("Text")}</a></li>"""
        }
        return """<!doctype html><html lang="${de.ultimatepdfscanner.app.I18n.language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Ultimate PDF Scanner</title>
<style>:root{color-scheme:light dark;--bg:#f7f8fc;--card:#fff;--ink:#14171f;--mut:#5d6472;--pri:#1f4e8c;--line:#e3e6ee}
@media(prefers-color-scheme:dark){:root{--bg:#111318;--card:#1c1f25;--ink:#e8eaf0;--mut:#9aa1ad;--pri:#9dc1ff;--line:#2c3038}}
body{margin:0;font:15px/1.4 system-ui,sans-serif;background:var(--bg);color:var(--ink)}main{max-width:760px;margin:0 auto;padding:24px 16px}
h1{font-size:22px;margin:0 0 4px}p{color:var(--mut);margin:0 0 20px}ul{list-style:none;padding:0;margin:0;display:grid;gap:10px}
li{display:flex;align-items:center;gap:14px;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:10px 12px}
img{width:44px;height:58px;object-fit:cover;border-radius:6px;background:var(--line)}.m{flex:1;min-width:0}.m b{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.m span{color:var(--mut);font-size:13px}.b{background:var(--pri);color:var(--bg);text-decoration:none;padding:8px 14px;border-radius:999px;font-weight:600;font-size:14px}
.b.s{background:transparent;color:var(--pri);border:1px solid var(--line)}</style></head>
<body><main><h1>Ultimate PDF Scanner</h1><p>${tr("%d Scans auf dem Handy · direkt über das WLAN, ohne Cloud", list.size)}</p><ul>$rows</ul></main></body></html>"""
    }

    companion object {
        /** The phone's IPv4 address in the local network (Wi-Fi or hotspot), or null. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
