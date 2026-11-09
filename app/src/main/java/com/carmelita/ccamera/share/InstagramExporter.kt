package com.carmelita.ccamera.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.carmelita.ccamera.processing.NativeEngine
import com.carmelita.ccamera.storage.ExifWriter
import com.carmelita.ccamera.storage.MediaSaver
import java.util.Date
import kotlin.math.roundToInt

/**
 * Formatos de Instagram (2026): ancho 1080 px. 3:4 (1080x1440) es el más alto del feed y el
 * único que la cuadrícula del perfil no recorta; coincide con el 4:3 del sensor.
 */
enum class InstagramFormat(val label: String, val w: Int, val h: Int, val suffix: String) {
    FEED_3_4("Feed 3:4 (sin recorte)", 1080, 1440, "IG_3x4"),
    FEED_4_5("Feed 4:5", 1080, 1350, "IG_4x5"),
    SQUARE("Cuadrada 1:1", 1080, 1080, "IG_1x1"),
    STORY("Historia / Reel 9:16", 1080, 1920, "IG_story"),
}

object InstagramExporter {
    const val PACKAGE = "com.instagram.android"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    /** Decodifica la foto ya girada, en memoria normal (necesario para el motor nativo). */
    fun decode(context: Context, uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { dec, _, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                dec.isMutableRequired = false
            }
        } else {
            val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            val rot = context.contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).rotationDegrees
            }
            if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        }
    }

    /**
     * Prepara la versión para Instagram: tamaño exacto (Instagram no vuelve a escalarla),
     * reducción Lanczos en luz lineal, enfoque de salida y JPEG de calidad alta. Si la foto
     * es Ultra HDR se conserva el mapa de ganancia (Instagram en Android lo muestra en HDR).
     */
    fun export(context: Context, photo: Uri, format: InstagramFormat): Uri {
        val src = decode(context, photo)
        val landscape = src.width > src.height
        val (dw, dh) = when {
            format == InstagramFormat.STORY -> format.w to format.h
            // Foto horizontal en "feed sin recorte": 4:3 apaisado (Instagram admite hasta 1.91:1).
            landscape && format == InstagramFormat.FEED_3_4 -> 1080 to 810
            landscape && format == InstagramFormat.FEED_4_5 -> 1080 to 864   // 5:4 apaisado
            else -> format.w to format.h
        }
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        if (format == InstagramFormat.STORY) {
            NativeEngine.composeStory(src, out)
        } else {
            val target = dw.toFloat() / dh
            var cw = src.width; var ch = (src.width / target).roundToInt()
            if (ch > src.height) { ch = src.height; cw = (src.height * target).roundToInt() }
            val cx = (src.width - cw) / 2; val cy = (src.height - ch) / 2
            NativeEngine.exportResize(src, cx, cy, cw, ch, out, 0.35f)
            if (Build.VERSION.SDK_INT >= 34) copyGainmap(src, out, cx, cy, cw, ch)
        }
        src.recycle()
        val uri = MediaSaver.saveExport(
            context, out, format.suffix,
            ExifWriter.Info(make = Build.MANUFACTURER, model = Build.MODEL, dateTime = MediaSaver.exifDate(Date())),
        )
        out.recycle()
        return uri
    }

    @RequiresApi(34)
    private fun copyGainmap(src: Bitmap, dst: Bitmap, cx: Int, cy: Int, cw: Int, ch: Int) {
        val g = src.gainmap ?: return
        val c = g.gainmapContents
        val sx = c.width.toFloat() / src.width; val sy = c.height.toFloat() / src.height
        val gx = (cx * sx).toInt().coerceIn(0, c.width - 1); val gy = (cy * sy).toInt().coerceIn(0, c.height - 1)
        val gw = (cw * sx).toInt().coerceIn(1, c.width - gx); val gh = (ch * sy).toInt().coerceIn(1, c.height - gy)
        val crop = Bitmap.createBitmap(c, gx, gy, gw, gh)
        val scaled = Bitmap.createScaledBitmap(crop, (dst.width / 4).coerceAtLeast(1), (dst.height / 4).coerceAtLeast(1), true)
        val ng = Gainmap(scaled)
        g.ratioMin.let { ng.setRatioMin(it[0], it[1], it[2]) }
        g.ratioMax.let { ng.setRatioMax(it[0], it[1], it[2]) }
        g.gamma.let { ng.setGamma(it[0], it[1], it[2]) }
        g.epsilonSdr.let { ng.setEpsilonSdr(it[0], it[1], it[2]) }
        g.epsilonHdr.let { ng.setEpsilonHdr(it[0], it[1], it[2]) }
        ng.minDisplayRatioForHdrTransition = g.minDisplayRatioForHdrTransition
        ng.displayRatioForFullHdr = g.displayRatioForFullHdr
        dst.gainmap = ng
    }

    /** Abre Instagram con la imagen (el usuario elige Feed, Historia o Reel), o el selector si no está. */
    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isInstalled(context)) {
            send.setPackage(PACKAGE)
            if (context.packageManager.resolveActivity(send, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                context.startActivity(send); return
            }
            send.setPackage(null)
        }
        context.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareGeneric(context: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
