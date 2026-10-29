package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** QR and barcode reading with ZXing (offline). */
object QrCodes {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF))

    private fun decodeGray(bytes: ByteArray, w: Int, h: Int): Result? = runCatching {
        val reader = MultiFormatReader().apply { setHints(hints) }
        val source = PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false) // the Y plane is just greyscale
        runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
            .getOrElse { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))) } // light code on dark ground
    }.getOrNull()

    fun decode(image: Mat): Result? {
        val gray = Mat()
        if (image.channels() == 1) image.copyTo(gray) else Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        val bytes = ByteArray(gray.cols() * gray.rows()).also { gray.get(0, 0, it) }
        return decodeGray(bytes, gray.cols(), gray.rows()).also { gray.release() }
    }

    suspend fun decode(context: Context, uri: Uri): Result? = withContext(Dispatchers.Default) {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            ?: return@withContext null
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(bmp, mat)
        val bgr = Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_RGBA2BGR) }
        mat.release()
        decode(bgr).also { bgr.release() }
    }

    /** What a code contains, for the result sheet. */
    sealed interface Content {
        data class Link(val url: String) : Content
        data class Wifi(val ssid: String, val password: String?, val security: String?) : Content
        data class Contact(val vcard: String, val name: String?) : Content
        data class Mail(val to: String, val subject: String?, val body: String?) : Content
        data class Phone(val number: String) : Content
        data class Geo(val uri: String) : Content
        data class Product(val code: String) : Content
        data class Plain(val text: String) : Content
    }

    /** Field of a MECARD/WIFI-style payload ("S:name;P:pw;"), with backslash escapes. */
    private fun field(payload: String, key: String): String? {
        val m = Regex("""(?:^|[:;])$key:((?:\\.|[^;])*)""").find(payload) ?: return null
        return m.groupValues[1].replace(Regex("""\\(.)"""), "$1")
    }

    fun classify(r: Result): Content {
        val t = r.text.trim()
        val lower = t.lowercase()
        return when {
            r.barcodeFormat in setOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E) -> Content.Product(t)
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") -> Content.Link(if (lower.startsWith("www.")) "https://$t" else t)
            lower.startsWith("wifi:") -> Content.Wifi(field(t, "S") ?: "", field(t, "P"), field(t, "T"))
            lower.startsWith("begin:vcard") -> Content.Contact(t, Regex("""(?m)^FN[^:]*:(.*)$""").find(t)?.groupValues?.get(1)?.trim())
            lower.startsWith("mecard:") -> {
                val name = field(t.substring(7), "N")?.split(',')?.reversed()?.joinToString(" ")?.trim()
                val vcard = buildString {
                    append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
                    name?.let { append("FN:$it\r\n") }
                    field(t.substring(7), "TEL")?.let { append("TEL:$it\r\n") }
                    field(t.substring(7), "EMAIL")?.let { append("EMAIL:$it\r\n") }
                    field(t.substring(7), "ADR")?.let { append("ADR:;;$it;;;;\r\n") }
                    field(t.substring(7), "URL")?.let { append("URL:$it\r\n") }
                    append("END:VCARD\r\n")
                }
                Content.Contact(vcard, name)
            }
            lower.startsWith("mailto:") -> Content.Mail(t.substring(7).substringBefore('?'), null, null)
            lower.startsWith("matmsg:") -> Content.Mail(field(t.substring(7), "TO") ?: "", field(t.substring(7), "SUB"), field(t.substring(7), "BODY"))
            lower.startsWith("tel:") -> Content.Phone(t.substring(4))
            lower.startsWith("geo:") -> Content.Geo(t)
            else -> Content.Plain(t)
        }
    }
}

private fun copy(context: Context, text: String) =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(tr("Code"), text))

/** Result of a scanned code with the actions that fit its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrResultSheet(result: Result, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val content = QrCodes.classify(result)
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }; onDismiss() }
    val (icon, title) = when (content) {
        is QrCodes.Content.Link -> Icons.Filled.Link to tr("Link")
        is QrCodes.Content.Wifi -> Icons.Filled.Wifi to "WLAN"
        is QrCodes.Content.Contact -> Icons.Filled.Person to tr("Kontakt")
        is QrCodes.Content.Mail -> Icons.Filled.Email to tr("E-Mail")
        is QrCodes.Content.Phone -> Icons.Filled.Call to tr("Telefonnummer")
        is QrCodes.Content.Geo -> Icons.Filled.Map to tr("Ort")
        is QrCodes.Content.Product -> Icons.Filled.ShoppingCart to tr("Produktcode")
        is QrCodes.Content.Plain -> Icons.Filled.QrCode2 to tr("Text")
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(result.barcodeFormat.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(14.dp))
            SelectionContainer {
                Text(when (content) {
                    is QrCodes.Content.Wifi -> tr("Netzwerk: %s", content.ssid) + (content.password?.let { "\n" + tr("Passwort: %s", it) } ?: "") + (content.security?.let { "\n" + tr("Verschlüsselung: %s", it) } ?: "")
                    is QrCodes.Content.Contact -> content.name ?: result.text
                    else -> result.text
                }, style = MaterialTheme.typography.bodyLarge, maxLines = 12)
            }
            Spacer(Modifier.size(18.dp))
            val actions = ArrayList<Triple<ImageVector, String, () -> Unit>>()
            when (content) {
                is QrCodes.Content.Link -> actions += Triple(Icons.AutoMirrored.Filled.OpenInNew, tr("Öffnen")) { open(Intent(Intent.ACTION_VIEW, Uri.parse(content.url))) }
                is QrCodes.Content.Wifi -> {
                    content.password?.let { pw -> actions += Triple(Icons.Filled.ContentCopy, tr("Passwort kopieren")) { copy(context, pw); onDismiss() } }
                    if (Build.VERSION.SDK_INT >= 30) actions += Triple(Icons.Filled.Wifi, tr("Verbinden")) {
                        val sug = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(content.ssid).apply {
                            if (!content.password.isNullOrEmpty()) { if (content.security?.uppercase()?.contains("SAE") == true) setWpa3Passphrase(content.password) else setWpa2Passphrase(content.password) }
                        }.build()
                        open(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(sug)))
                    }
                }
                is QrCodes.Content.Contact -> actions += Triple(Icons.Filled.PersonAdd, tr("Zu Kontakten")) {
                    val f = java.io.File(context.cacheDir, "exports").apply { mkdirs() }.let { java.io.File(it, "kontakt.vcf") }.also { it.writeText(content.vcard) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x-vcard").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                is QrCodes.Content.Mail -> actions += Triple(Icons.Filled.Email, tr("E-Mail schreiben")) {
                    open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + content.to)).apply {
                        content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; content.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    })
                }
                is QrCodes.Content.Phone -> actions += Triple(Icons.Filled.Call, tr("Anrufen")) { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + content.number))) }
                is QrCodes.Content.Geo -> actions += Triple(Icons.Filled.Map, tr("In Karten öffnen")) { open(Intent(Intent.ACTION_VIEW, Uri.parse(content.uri))) }
                else -> {}
            }
            actions += Triple(Icons.Filled.ContentCopy, tr("Kopieren")) { copy(context, result.text); onDismiss() }
            actions += Triple(Icons.Filled.Share, tr("Teilen")) { shareText(context, title, result.text); onDismiss() }
            actions.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { i, (ic, label, action) ->
                        if (row == 0 && i == 0) Button(onClick = action, modifier = Modifier.weight(1f)) { Icon(ic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
                        else OutlinedButton(onClick = action, modifier = Modifier.weight(1f)) { Icon(ic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
