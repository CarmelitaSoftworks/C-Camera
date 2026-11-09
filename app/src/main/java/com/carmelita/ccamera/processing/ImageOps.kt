package com.carmelita.ccamera.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/** Proporción de la foto (lado largo : lado corto). */
enum class PhotoAspect(val label: String, val long: Int, val short: Int) {
    FOUR_THREE("4:3", 4, 3),
    SIXTEEN_NINE("16:9", 16, 9),
    SQUARE("1:1", 1, 1),
}

/**
 * Recortes y giros del Bitmap revelado conservando el mapa de ganancia Ultra HDR (Android 14+).
 * El Bitmap está en orientación del sensor; la rotación va en el EXIF.
 */
object ImageOps {

    /** Recorte centrado a la proporción pedida (el sensor ya es 4:3: 4:3 no cambia nada). */
    fun cropToAspect(src: Bitmap, aspect: PhotoAspect): Bitmap {
        if (aspect == PhotoAspect.FOUR_THREE) return src
        val landscape = src.width >= src.height
        val longSide = if (landscape) src.width else src.height
        val shortSide = if (landscape) src.height else src.width
        // Se conserva el lado largo si cabe; si no, se recorta el largo.
        var newShort = (longSide.toLong() * aspect.short / aspect.long).toInt()
        var newLong = longSide
        if (newShort > shortSide) {
            newShort = shortSide
            newLong = (shortSide.toLong() * aspect.long / aspect.short).toInt()
        }
        val w = if (landscape) newLong else newShort
        val h = if (landscape) newShort else newLong
        if (w == src.width && h == src.height) return src
        return transform(src, (src.width - w) / 2, (src.height - h) / 2, w, h, null)
    }

    /**
     * Espejo horizontal de la foto tal como se ve (en vertical) para la cámara frontal.
     * Con el sensor girado 90/270 grados, el eje horizontal de la foto es el vertical del sensor.
     */
    fun mirror(src: Bitmap, rotation: Int): Bitmap {
        val m = Matrix()
        if (rotation % 180 == 0) m.preScale(-1f, 1f) else m.preScale(1f, -1f)
        return transform(src, 0, 0, src.width, src.height, m)
    }

    /** createBitmap + mapa de ganancia transformado igual (si el sistema no lo hizo ya). */
    fun transform(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?): Bitmap {
        val out = Bitmap.createBitmap(src, x, y, w, h, m, true)
        if (Build.VERSION.SDK_INT >= 34) copyGainmap(src, out, x, y, w, h, m)
        return out
    }

    @RequiresApi(34)
    private fun copyGainmap(src: Bitmap, out: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?) {
        if (!src.hasGainmap() || out.hasGainmap()) return
        val g = src.gainmap ?: return
        val c = g.gainmapContents
        val sx = c.width.toFloat() / src.width
        val sy = c.height.toFloat() / src.height
        val gx = (x * sx).roundToInt().coerceIn(0, c.width - 1)
        val gy = (y * sy).roundToInt().coerceIn(0, c.height - 1)
        val gw = (w * sx).roundToInt().coerceIn(1, c.width - gx)
        val gh = (h * sy).roundToInt().coerceIn(1, c.height - gy)
        val contents = Bitmap.createBitmap(c, gx, gy, gw, gh, m, true)
        val ng = Gainmap(contents)
        ng.setRatioMin(g.ratioMin[0], g.ratioMin[1], g.ratioMin[2])
        ng.setRatioMax(g.ratioMax[0], g.ratioMax[1], g.ratioMax[2])
        ng.setGamma(g.gamma[0], g.gamma[1], g.gamma[2])
        ng.setEpsilonSdr(g.epsilonSdr[0], g.epsilonSdr[1], g.epsilonSdr[2])
        ng.setEpsilonHdr(g.epsilonHdr[0], g.epsilonHdr[1], g.epsilonHdr[2])
        ng.minDisplayRatioForHdrTransition = g.minDisplayRatioForHdrTransition
        ng.displayRatioForFullHdr = g.displayRatioForFullHdr
        out.gainmap = ng
    }
}
