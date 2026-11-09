package com.carmelita.ccamera.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Panorámica a partir de fotogramas de la vista previa (en vertical, girando el teléfono hacia
 * la derecha). Kotlin puro sobre píxeles ARGB: se puede probar en la JVM.
 *
 * Para cada fotograma se estima el desplazamiento (dx, dy) respecto al último aceptado comparando
 * versiones reducidas en gris; se acepta cuando avanzó al menos [minStepFraction] del ancho.
 * Cada fotograma aceptado pinta en el lienzo desde el punto medio entre su centro y el del
 * anterior hasta su borde derecho, con una mezcla lineal en la costura.
 */
class PanoramaStitcher(
    val frameW: Int,
    val frameH: Int,
    val maxWidth: Int = frameW * 7,
    private val minStepFraction: Float = 0.2f,
) {
    enum class Step { ACCEPTED, SKIPPED, LOST, FULL }

    private val scale = 4
    private val sw = frameW / scale
    private val sh = frameH / scale
    private val margin = frameH / 8
    private val canvasH = frameH + 2 * margin
    private val canvas = IntArray(maxWidth * canvasH)
    private val blend = max(8, frameW / 24)

    private var prevSmall: FloatArray? = null
    private var lastX = 0
    private var lastY = margin
    private var top = margin
    private var bottom = margin + frameH
    var frames = 0
        private set
    var full = false
        private set

    /** Ancho cubierto hasta ahora respecto al máximo (0..1), para la barra de progreso. */
    val progress: Float get() = if (frames == 0) 0f else min(1f, (lastX + frameW).toFloat() / maxWidth)

    fun add(pixels: IntArray): Step {
        require(pixels.size >= frameW * frameH)
        if (full) return Step.FULL
        val small = downsample(pixels)
        val prev = prevSmall
        if (prev == null) {
            paint(pixels, 0, lastY, seamInFrame = 0)
            prevSmall = small
            frames = 1
            return Step.ACCEPTED
        }
        val (dx, dy) = estimate(prev, small) ?: return Step.LOST
        val step = dx * scale
        if (step < frameW * minStepFraction) return Step.SKIPPED
        val x = lastX + step
        if (x + frameW > maxWidth) { full = true; return Step.FULL }
        val y = (lastY + dy * scale).coerceIn(0, canvasH - frameH)
        // Costura en el punto medio entre los dos centros, en coordenadas del fotograma nuevo.
        paint(pixels, x, y, seamInFrame = frameW / 2 - step / 2)
        lastX = x; lastY = y
        top = max(top, y); bottom = min(bottom, y + frameH)
        prevSmall = small
        frames++
        return Step.ACCEPTED
    }

    /** Panorámica final (píxeles ARGB, ancho, alto) o null si no hay al menos dos fotogramas. */
    fun result(): Triple<IntArray, Int, Int>? {
        if (frames < 2 || bottom - top < frameH / 2) return null
        val w = lastX + frameW
        val h = bottom - top
        val out = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(canvas, (top + r) * maxWidth, out, r * w, w)
        return Triple(out, w, h)
    }

    private fun paint(px: IntArray, x0: Int, y0: Int, seamInFrame: Int) {
        val from = if (seamInFrame <= 0) 0 else max(0, seamInFrame - blend)
        for (r in 0 until frameH) {
            val cy = y0 + r
            if (cy < 0 || cy >= canvasH) continue
            val src = r * frameW
            val dst = cy * maxWidth + x0
            for (c in from until frameW) {
                val p = px[src + c]
                if (seamInFrame > 0 && c < seamInFrame + blend) {
                    val a = (c - (seamInFrame - blend)).toFloat() / (2 * blend)
                    canvas[dst + c] = mix(canvas[dst + c], p, a.coerceIn(0f, 1f))
                } else {
                    canvas[dst + c] = p
                }
            }
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (a == 0) return b   // lienzo vacío (zona sin pintar)
        val u = 1f - t
        val r = (((a shr 16) and 0xFF) * u + ((b shr 16) and 0xFF) * t).toInt()
        val g = (((a shr 8) and 0xFF) * u + ((b shr 8) and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * u + (b and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun downsample(px: IntArray): FloatArray {
        val out = FloatArray(sw * sh)
        val n = (scale * scale * 255).toFloat()
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (dy in 0 until scale) {
                val row = (y * scale + dy) * frameW + x * scale
                for (dx in 0 until scale) {
                    val p = px[row + dx]
                    s += (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                }
            }
            out[y * sw + x] = s / n
        }
        return out
    }

    /**
     * Busca (dx, dy) con cur(x, y) ≈ prev(x + dx, y + dy), dx >= 0 (giro a la derecha).
     * Devuelve null si no hay una coincidencia fiable (giro demasiado rápido o escena sin textura).
     */
    private fun estimate(prev: FloatArray, cur: FloatArray): Pair<Int, Int>? {
        val maxDx = (sw * 0.65f).toInt()
        val maxDy = max(2, sh / 12)
        val y0 = sh / 10 + maxDy
        val y1 = sh - sh / 10 - maxDy
        var best = Float.MAX_VALUE
        var bx = 0; var by = 0
        for (dy in -maxDy..maxDy) {
            for (dx in 0..maxDx) {
                var sum = 0f
                var n = 0
                var y = y0
                while (y < y1) {
                    val pr = (y + dy) * sw
                    val cr = y * sw
                    var x = 0
                    while (x < sw - dx) {
                        sum += abs(prev[pr + x + dx] - cur[cr + x])
                        n++
                        x += 2
                    }
                    y += 2
                }
                if (n == 0) continue
                val cost = sum / n
                if (cost < best) { best = cost; bx = dx; by = dy }
            }
        }
        // Umbral de coincidencia (gris 0..1): por encima, el fotograma no encaja con el anterior.
        return if (best < 0.09f) bx to by else null
    }
}
