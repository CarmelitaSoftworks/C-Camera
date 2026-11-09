package com.carmelita.ccamera.camera

import android.hardware.camera2.TotalCaptureResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Estabilización digital de fotos ("fotograma afortunado"): de los primeros fotogramas de la
 * ráfaga se usa como referencia el más nítido (el que menos movió el pulso); el resto se alinea
 * con él en la fusión. Sin estabilizador óptico es lo que más se nota.
 */
object FrameSharpness {

    /**
     * Nitidez relativa: energía de gradiente en el 40 % central, normalizada por el brillo medio
     * (así no gana un fotograma sólo por estar más claro). Muestreo disperso: ~100k píxeles.
     */
    fun score(frame: CapturedFrame): Float = when (frame) {
        is CapturedFrame.Raw -> {
            // RAW16: se comparan píxeles del mismo color del patrón Bayer (separados 2).
            val b = frame.data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            gradient(frame.width, frame.height, step = 4, pairDist = 2) { x, y ->
                b.getShort(y * frame.rowStride + x * 2).toInt() and 0xFFFF
            }
        }
        is CapturedFrame.Yuv -> {
            val b = frame.y
            gradient(frame.width, frame.height, step = 3, pairDist = 1) { x, y ->
                b.get(y * frame.yRowStride + x).toInt() and 0xFF
            }
        }
    }

    private inline fun gradient(w: Int, h: Int, step: Int, pairDist: Int, px: (Int, Int) -> Int): Float {
        val x0 = (w * 0.3f).toInt() and 1.inv()
        val x1 = (w * 0.7f).toInt() - pairDist
        val y0 = (h * 0.3f).toInt() and 1.inv()
        val y1 = (h * 0.7f).toInt() - pairDist
        var g = 0L
        var s = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = px(x, y)
                g += abs(px(x + pairDist, y) - c) + abs(px(x, y + pairDist) - c)
                s += c
                n++
                x += step
            }
            y += step
        }
        if (n == 0 || s == 0L) return 0f
        return g.toFloat() / s
    }
}

/**
 * Recibe los fotogramas de una ráfaga en orden y entrega a [add] primero el más nítido de los
 * [window] primeros (será la referencia de la fusión) y después el resto. Sólo guarda en memoria
 * esos primeros fotogramas, no la ráfaga entera.
 */
class ReferencePicker(
    private val enabled: Boolean,
    private val window: Int = 3,
    private val add: (CapturedFrame, TotalCaptureResult?) -> Unit,
) {
    private val pending = ArrayList<Pair<CapturedFrame, TotalCaptureResult?>>()
    private var flushed = false
    /** Fotograma y resultado elegidos como referencia (para EXIF y DNG). */
    var reference: Pair<CapturedFrame, TotalCaptureResult?>? = null
        private set

    fun offer(frame: CapturedFrame, result: TotalCaptureResult?) {
        if (flushed) { add(frame, result); return }
        pending += frame to result
        if (!enabled || pending.size >= window) flush()
    }

    /** Llamar al terminar la ráfaga (por si tuvo menos de [window] fotogramas). */
    fun finish() { if (!flushed) flush() }

    private fun flush() {
        flushed = true
        if (pending.isEmpty()) return
        val best = if (enabled && pending.size > 1) pending.indices.maxBy { FrameSharpness.score(pending[it].first) } else 0
        reference = pending[best]
        add(pending[best].first, pending[best].second)
        pending.forEachIndexed { i, p -> if (i != best) add(p.first, p.second) }
        pending.clear()
    }
}

/** Para pruebas: ByteBuffer directo de un RAW16 sintético. */
internal fun rawBuffer(w: Int, h: Int, f: (Int, Int) -> Int): ByteBuffer {
    val b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (y in 0 until h) for (x in 0 until w) b.putShort(f(x, y).toShort())
    b.rewind()
    return b
}
