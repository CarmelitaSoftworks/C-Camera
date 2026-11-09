package com.carmelita.ccamera

import com.carmelita.ccamera.camera.CapturedFrame
import com.carmelita.ccamera.camera.FrameSharpness
import com.carmelita.ccamera.camera.ReferencePicker
import com.carmelita.ccamera.camera.SmoothPath
import com.carmelita.ccamera.camera.StabilizationMath
import com.carmelita.ccamera.camera.rawBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StabilizationTest {

    @Test
    fun backCameraYawMovesContentSideways() {
        // Sensor girado 90° (lo normal en la trasera): girar a la izquierda (δy > 0) mueve el
        // contenido a la derecha en vertical; como la vista vertical es el búfer girado 90° en
        // sentido horario, "derecha en vertical" es -y en el búfer.
        val m = StabilizationMath.contentMotion(0f, 0.01f, 0f, focalPx = 1000f, front = false, sensorOrientation = 90)
        assertEquals(0f, m.dx, 1e-3f)
        assertEquals(-10f, m.dy, 1e-3f)
        // Sin orientación: directamente en x.
        val m0 = StabilizationMath.contentMotion(0f, 0.01f, 0f, 1000f, false, 0)
        assertEquals(10f, m0.dx, 1e-3f)
        assertEquals(0f, m0.dy, 1e-3f)
        // Inclinar hacia arriba (δx > 0) baja el contenido en la trasera y lo sube en la frontal.
        assertEquals(10f, StabilizationMath.contentMotion(0.01f, 0f, 0f, 1000f, false, 0).dy, 1e-3f)
        assertEquals(-10f, StabilizationMath.contentMotion(0.01f, 0f, 0f, 1000f, true, 0).dy, 1e-3f)
    }

    @Test
    fun sampleMatrixFollowsContentAndRespectsMargin() {
        val w = 1920; val h = 1080
        // Sin movimiento: el centro sale del centro y las esquinas se recortan con el zoom.
        val id = StabilizationMath.sampleMatrix(StabilizationMath.Motion(0f, 0f, 0f), w, h, 1.2f)
        val c = StabilizationMath.apply(id, 0.5f, 0.5f)
        assertEquals(0.5f, c.first, 1e-5f); assertEquals(0.5f, c.second, 1e-5f)
        val corner = StabilizationMath.apply(id, 0f, 0f)
        assertEquals(0.5f - 0.5f / 1.2f, corner.first, 1e-5f)
        // El contenido se movió 20 px a la derecha: se lee 20 px más a la derecha.
        val r = StabilizationMath.sampleMatrix(StabilizationMath.Motion(20f, 0f, 0f), w, h, 1.2f)
        assertEquals(0.5f + 20f / w, StabilizationMath.apply(r, 0.5f, 0.5f).first, 1e-5f)
        // Movido 20 px hacia abajo (y abajo): en coordenadas GL (v arriba) se lee más abajo.
        val d = StabilizationMath.sampleMatrix(StabilizationMath.Motion(0f, 20f, 0f), w, h, 1.2f)
        assertEquals(0.5f - 20f / h, StabilizationMath.apply(d, 0.5f, 0.5f).second, 1e-5f)
        // Un movimiento enorme se limita al margen: las esquinas nunca leen fuera de la imagen.
        val big = StabilizationMath.sampleMatrix(StabilizationMath.Motion(5000f, -5000f, 0.2f), w, h, 1.2f)
        for (u in listOf(0f, 1f)) for (v in listOf(0f, 1f)) {
            val (su, sv) = StabilizationMath.apply(big, u, v)
            assertTrue("esquina fuera ($su, $sv)", su in -1e-3f..1.001f && sv in -1e-3f..1.001f)
        }
    }

    @Test
    fun smoothPathRemovesShakeButFollowsPans() {
        val p = SmoothPath()
        // Temblor rápido de ±0,01 rad: la compensación lo sigue casi entero.
        var maxJ = 0f
        for (i in 0..300) {
            val t = i * 10_000_000L
            val shake = if (i % 2 == 0) 0.01f else -0.01f
            val (_, jy, _) = p.update(t, 0f, shake, 0f)
            if (i > 50) maxJ = maxOf(maxJ, abs(jy))
        }
        assertTrue("debería compensar el temblor ($maxJ)", maxJ > 0.008f)
        // Barrido lento de 1 rad en 3 s: la compensación nunca pasa del margen.
        val q = SmoothPath()
        for (i in 0..300) {
            val (_, jy, _) = q.update(i * 10_000_000L, 0f, i / 300f, 0f)
            assertTrue(abs(jy) <= 0.0501f)
        }
    }

    @Test
    fun pickerChoosesSharpestFrame() {
        val w = 64; val h = 48
        fun frame(sharp: Boolean, ts: Long) = CapturedFrame.Raw(
            rawBuffer(w, h) { x, y -> if (sharp) (if ((x / 4 + y / 4) % 2 == 0) 3000 else 1000) else 2000 + (x % 3) },
            w * 2, w, h, ts,
        )
        val blurry1 = frame(false, 1)
        val sharp = frame(true, 2)
        val blurry2 = frame(false, 3)
        assertTrue(FrameSharpness.score(sharp) > FrameSharpness.score(blurry1))
        val order = mutableListOf<Long>()
        val picker = ReferencePicker(enabled = true) { f, _ -> order += f.timestamp }
        picker.offer(blurry1, null); picker.offer(sharp, null); picker.offer(blurry2, null)
        picker.offer(frame(false, 4), null)
        picker.finish()
        assertEquals(listOf(2L, 1L, 3L, 4L), order)
        assertEquals(2L, picker.reference!!.first.timestamp)
        // Desactivado: el orden no cambia.
        val order2 = mutableListOf<Long>()
        val off = ReferencePicker(enabled = false) { f, _ -> order2 += f.timestamp }
        off.offer(blurry1, null); off.offer(sharp, null); off.finish()
        assertEquals(listOf(1L, 2L), order2)
    }
}
