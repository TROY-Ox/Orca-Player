package com.troy.orcaplayer.ui.tv

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.ui.common.ConfirmDialog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** One field of the phone form. */
data class SendField(val key: String, val label: String, val password: Boolean = false, val value: String = "")

/**
 * Send to TV (21.1): a one-time page on the local network where a phone fills in what is
 * painful to type with a remote. Only devices on the same local network can reach it, the
 * address carries a random token, and it closes once the form is sent or the dialog closes.
 */
class SendToTvServer(
    private val title: String,
    private val sentText: String,
    private val sendLabel: String,
    private val fields: List<SendField>,
    private val onReceived: (Map<String, String>) -> Unit,
) {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null

    /** Starts the server and returns the page address, or null without a local network. */
    fun start(context: Context): String? {
        val ip = localAddress(context) ?: return null
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip, 0)) }
        socket = s
        thread(name = "send-to-tv", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: continue
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
        return "http://${ip.hostAddress}:${s.localPort}/$token"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 20_000
        val remote = client.inetAddress
        val out = client.getOutputStream()
        if (!(remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress)) return respond(out, 403, "text/plain", "Forbidden")
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val request = input.readLine() ?: return
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val parts = request.split(' ')
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/')
        if (path != token) return respond(out, 404, "text/plain", "Not Found")
        when (parts.getOrNull(0)) {
            "GET" -> respond(out, 200, "text/html; charset=utf-8", page(sent = false))
            "POST" -> {
                if (length !in 0..MAX_BODY) return respond(out, 413, "text/plain", "Too Large")
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val values = parseForm(String(body, 0, read)).filterKeys { k -> fields.any { it.key == k } }
                respond(out, 200, "text/html; charset=utf-8", page(sent = true))
                onReceived(values)
            }
            else -> respond(out, 405, "text/plain", "Method Not Allowed")
        }
    }

    private fun page(sent: Boolean): String {
        val inputs = fields.joinToString("") { f ->
            val type = if (f.password) "password" else "text"
            "<label>${esc(f.label)}<input name=\"${esc(f.key)}\" type=\"$type\" value=\"${esc(f.value)}\" autocapitalize=\"off\" autocomplete=\"off\" spellcheck=\"false\"></label>"
        }
        val body = if (sent) "<p>${esc(sentText)}</p>" else "<form method=\"post\">$inputs<button type=\"submit\">${esc(sendLabel)}</button></form>"
        // Colours from the theme: background, panel, text and the default accent.
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>
body{margin:0;padding:24px 16px;background:#0D0D0C;color:#F6F5F2;font-family:system-ui,sans-serif}
h1{font-size:20px;margin:0 0 16px}label{display:block;margin:0 0 12px;color:#BAB9B3;font-size:14px}
input{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:12px;border-radius:12px;border:1px solid rgba(255,255,255,.08);background:#171716;color:#F6F5F2;font-size:16px}
button{width:100%;padding:14px;border:0;border-radius:12px;background:#14B8A6;color:#0D0D0C;font-size:16px;font-weight:600}
</style></head><body><h1>${esc(title)}</h1>$body</body></html>"""
    }

    private fun respond(out: OutputStream, code: Int, mime: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val MAX_BODY = 64 * 1024

        fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        fun esc(s: String): String = buildString {
            s.forEach {
                when (it) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(it)
                }
            }
        }

        fun localAddress(context: Context): InetAddress? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }

        fun qr(text: String): BitMatrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
    }
}

/** A QR code drawn dark on light, as phone cameras expect. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { SendToTvServer.qr(text) }
    Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp))) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * Shows the QR code for the phone page and hands what the phone sends to [onReceived].
 * [fields] keep their current values so the phone can edit them.
 */
@Composable
fun SendToTvDialog(title: String, fields: List<SendField>, onDismiss: () -> Unit, onReceived: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    val c = Troy.colors
    val sent = stringResource(R.string.tv_send_done)
    val sendLabel = stringResource(R.string.tv_send_button)
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val server = SendToTvServer(title, sent, sendLabel, fields) { values -> main.post { onReceived(values); onDismiss() } }
        address = runCatching { server.start(context) }.getOrNull()
        failed = address == null
        onDispose { server.stop() }
    }
    ConfirmDialog(
        title = title,
        message = if (failed) stringResource(R.string.tv_send_no_network) else stringResource(R.string.tv_send_message),
        confirm = stringResource(R.string.dialog_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        extra = {
            address?.let { a ->
                Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(220.dp)) { QrCode(a, Modifier.size(220.dp)) }
                    Text(a, style = Troy.type.bodySmall, color = c.text3)
                }
            }
        },
    )
}

/** "Send from phone", shown on TVs next to fields that are slow to type with a remote. */
@Composable
fun SendFromPhoneButton(title: String, fields: List<SendField>, modifier: Modifier = Modifier, onReceived: (Map<String, String>) -> Unit) {
    if (!LocalIsTv.current) return
    var open by remember { mutableStateOf(false) }
    TroyButton(stringResource(R.string.tv_send_from_phone), { open = true }, modifier, icon = Icons.Outlined.QrCode2)
    if (open) SendToTvDialog(title, fields, onDismiss = { open = false }, onReceived = onReceived)
}
