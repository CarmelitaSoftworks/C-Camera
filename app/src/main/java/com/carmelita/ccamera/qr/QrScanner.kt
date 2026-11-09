package com.carmelita.ccamera.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/** Lector de QR sobre fotogramas de la vista previa (ML Kit, modelo incluido en la app). */
class QrScanner {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417)
            .build()
    )

    /** Devuelve el código más grande de la imagen, o null. */
    suspend fun scan(bitmap: Bitmap): QrContent? {
        val codes = runCatching { scanner.process(InputImage.fromBitmap(bitmap, 0)).await() }.getOrNull() ?: return null
        val best = codes.filter { !it.rawValue.isNullOrBlank() }
            .maxByOrNull { (it.boundingBox?.width() ?: 0) * (it.boundingBox?.height() ?: 0) } ?: return null
        return QrContent.parse(best.rawValue!!)
    }

    fun close() = scanner.close()
}

/** Acción del "chip" del QR (como en el iPhone). */
object QrActions {
    fun run(context: Context, c: QrContent): String? {
        fun start(i: Intent): Boolean = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        when (c) {
            is QrContent.Url -> if (!start(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)))) return copy(context, c.url)
            is QrContent.Phone -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.number}")))
            is QrContent.Sms -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.number}")).putExtra("sms_body", c.body))
            is QrContent.Email -> start(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(c.to))
                    .putExtra(Intent.EXTRA_SUBJECT, c.subject).putExtra(Intent.EXTRA_TEXT, c.body)
            )
            is QrContent.Geo -> start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${c.lat},${c.lng}" + (c.query?.let { "?q=" + Uri.encode(it) } ?: ""))))
            is QrContent.Contact -> start(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, c.name)
                c.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                c.emails.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                c.org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            })
            is QrContent.Event -> start(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, c.summary)
                c.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                parseIcsDate(c.start)?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                parseIcsDate(c.end)?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            })
            is QrContent.Wifi -> return connectWifi(context, c)
            is QrContent.Text -> return copy(context, c.raw)
        }
        return null
    }

    private fun copy(context: Context, text: String): String {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QR", text))
        return "Copiado al portapapeles"
    }

    /** Android 11+: diálogo del sistema para añadir la red. Antes: copia la clave y abre los ajustes de Wi‑Fi. */
    private fun connectWifi(context: Context, w: QrContent.Wifi): String? {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsHiddenSsid(w.hidden)
            val pass = w.password
            if (pass != null) {
                when {
                    w.security.startsWith("WPA3") || w.security == "SAE" -> b.setWpa3Passphrase(pass)
                    w.security.startsWith("WPA") || w.security == "WPA2" -> b.setWpa2Passphrase(pass)
                    else -> b.setWpa2Passphrase(pass)
                }
            }
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return null
        }
        w.password?.let { copy(context, it) }
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return if (w.password != null) "Contraseña copiada: pégala en la red \"${w.ssid}\"" else null
    }

    private fun parseIcsDate(s: String?): Long? {
        if (s == null) return null
        val fmts = listOf("yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss", "yyyyMMdd")
        for (f in fmts) {
            val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US)
            if (f.endsWith("'Z'")) sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            runCatching { return sdf.parse(s)?.time }
        }
        return null
    }
}
