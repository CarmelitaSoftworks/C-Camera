package com.carmelita.ccamera

import com.carmelita.ccamera.processing.PanoramaStitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Panorámica: fotogramas recortados de una escena sintética ancha, como al girar el teléfono. */
class PanoramaStitcherTest {

    private val sceneW = 1600
    private val sceneH = 480

    /** Escena con textura (no periódica) para que la coincidencia sea única. */
    private fun scene(x: Int, y: Int): Int {
        val v = 128 + 50 * sin(x * 0.031 + y * 0.017) + 40 * sin(x * 0.0071 * (1 + y % 7)) + ((x * 7919 + y * 104729) % 23)
        val g = v.toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (((g + x / 8) and 0xFF) shl 8) or ((255 - g) and 0xFF)
    }

    private fun frame(x0: Int, y0: Int, w: Int, h: Int) = IntArray(w * h) { i -> scene(x0 + i % w, y0 + i / w) }

    @Test
    fun stitchesShiftedFrames() {
        val w = 320; val h = 400
        val st = PanoramaStitcher(w, h, maxWidth = sceneW)
        var accepted = 0
        var x = 0
        var y = 40
        while (x + w <= sceneW - 40) {
            val step = st.add(frame(x, y, w, h))
            if (step == PanoramaStitcher.Step.ACCEPTED) accepted++
            x += 24                      // giro lento: varios fotogramas por paso aceptado
            y += if ((x / 24) % 5 == 0) 1 else 0   // pequeña deriva vertical
        }
        assertTrue("debería aceptar varios fotogramas ($accepted)", accepted >= 4)
        val res = st.result()
        assertNotNull("debería haber panorámica", res)
        val (px, pw, ph) = res!!
        assertTrue("la panorámica debería ser más ancha que un fotograma ($pw)", pw > w * 2)
        assertTrue(ph > h / 2)
        // El contenido debe coincidir con la escena: comparar una fila central con un desplazamiento.
        assertEquals(pw * ph, px.size)
        var err = 0L
        var n = 0
        for (c in 0 until pw step 7) {
            val p = px[(ph / 2) * pw + c]
            if (p == 0) continue
            // Busca el mejor desplazamiento vertical de la escena (la deriva acumulada).
            val best = (0 until 80).minOf { dy ->
                abs(((scene(c, dy + ph / 2) shr 16) and 0xFF) - ((p shr 16) and 0xFF))
            }
            err += best; n++
        }
        assertTrue("la panorámica debería parecerse a la escena (error medio ${err / n.coerceAtLeast(1)})", n > 0 && err / n < 12)
    }

    @Test
    fun skipsFramesWithoutMotion() {
        val w = 320; val h = 400
        val st = PanoramaStitcher(w, h, maxWidth = sceneW)
        val f = frame(100, 40, w, h)
        assertEquals(PanoramaStitcher.Step.ACCEPTED, st.add(f))
        assertEquals(PanoramaStitcher.Step.SKIPPED, st.add(f))
    }
}
