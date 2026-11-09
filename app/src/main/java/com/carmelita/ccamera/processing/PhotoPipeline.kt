package com.carmelita.ccamera.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.carmelita.ccamera.camera.CameraCapabilities
import com.carmelita.ccamera.camera.CapturedFrame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class DevelopParams(
    val style: PhotoStyle = PhotoStyle.STANDARD,
    val smartHdr: Boolean = true,
    val sharpen: Float = 0.7f,
    val exposure: Float = 1f,
    val ultraHdr: Boolean = true,
    val headroom: Float = 4f,
    /** Brillo automático tras la fusión (se apaga con exposición manual en Pro). */
    val autoBrightness: Boolean = true,
)

/**
 * Una foto en proceso: recibe frames (el primero es la referencia), los fusiona en C++ y
 * revela el resultado como Bitmap (con mapa de ganancia Ultra HDR en Android 14+).
 */
class PhotoJob(private val caps: CameraCapabilities, private val raw: Boolean) : AutoCloseable {
    private val handle = NativeEngine.createSession()
    private var refResult: TotalCaptureResult? = null
    private var closed = false
    var framesAdded = 0
        private set

    fun add(frame: CapturedFrame, result: TotalCaptureResult?, isShort: Boolean = false, ratio: Float = 1f) {
        check(!closed)
        if (!isShort && framesAdded == 0) {
            refResult = result
            noiseProfile(result)?.let { (s, o) -> NativeEngine.setNoiseProfile(handle, s, o) }
        }
        when (frame) {
            is CapturedFrame.Raw -> NativeEngine.addRaw(
                handle, frame.data, frame.rowStride, frame.width, frame.height,
                caps.cfa, blackLevels(result), whiteLevel(result), isShort, ratio,
            )
            is CapturedFrame.Yuv -> NativeEngine.addYuv(
                handle, frame.y, frame.yRowStride, frame.u, frame.v, frame.uvRowStride, frame.uvPixelStride,
                frame.width, frame.height, isShort, ratio,
            )
        }
        if (!isShort) framesAdded++
    }

    private fun noiseProfile(r: TotalCaptureResult?): Pair<Float, Float>? {
        if (!raw) return null
        val np = r?.get(CaptureResult.SENSOR_NOISE_PROFILE) ?: return null
        if (np.isEmpty()) return null
        val s = np.map { it.first }.average().toFloat()
        val o = np.map { it.second }.average().toFloat()
        return if (s > 0 && o >= 0) s to o else null
    }

    private fun blackLevels(r: TotalCaptureResult?): FloatArray {
        if (Build.VERSION.SDK_INT >= 28) {
            r?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { if (it.size >= 4) return it.copyOf(4) }
        }
        val p = caps.chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return if (p != null) floatArrayOf(
            p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
            p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
        ) else FloatArray(4) { 64f }
    }

    private fun whiteLevel(r: TotalCaptureResult?): Float {
        if (Build.VERSION.SDK_INT >= 28) r?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { if (it > 0) return it.toFloat() }
        return caps.whiteLevel.toFloat()
    }

    /** [wb x4, ccm x9] del resultado de la referencia. */
    private fun colorMeta(): FloatArray {
        val r = refResult
        val out = FloatArray(13)
        val g = r?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        if (g != null) { out[0] = g.red; out[1] = g.greenEven; out[2] = g.greenOdd; out[3] = g.blue }
        else { out[0] = 2f; out[1] = 1f; out[2] = 1f; out[3] = 1.6f }
        val t = r?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (t != null) {
            for (row in 0 until 3) for (col in 0 until 3) out[4 + row * 3 + col] = t.getElement(col, row).toFloat()
        } else {
            // Matriz típica de smartphone (cámara -> sRGB) si el HAL no la publica.
            floatArrayOf(1.7f, -0.55f, -0.15f, -0.25f, 1.55f, -0.3f, 0.02f, -0.6f, 1.58f).copyInto(out, 4)
        }
        return out
    }

    private fun lensShading(): Triple<FloatArray, Int, Int>? {
        val m = refResult?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val w = m.columnCount; val h = m.rowCount
        if (w < 2 || h < 2) return null
        val a = FloatArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 4) a[(y * w + x) * 4 + c] = m.getGainFactor(c, x, y)
        return Triple(a, w, h)
    }

    /** Revela la foto. El Bitmap está en orientación del sensor (la rotación va en EXIF). */
    fun develop(p: DevelopParams): Bitmap {
        val (w, h) = NativeEngine.outputSize(handle).let { it[0] to it[1] }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lsc = if (raw) lensShading() else null
        val wantGain = p.ultraHdr && Build.VERSION.SDK_INT >= 34
        val gain = NativeEngine.process(
            handle, bmp, if (raw) colorMeta() else null, lsc?.first, lsc?.second ?: 0, lsc?.third ?: 0,
            p.exposure, p.smartHdr, p.style.ordinal, p.sharpen, wantGain, p.headroom, p.autoBrightness,
        )
        if (gain != null && Build.VERSION.SDK_INT >= 34) {
            val (gw, gh) = NativeEngine.gainMapSize(handle).let { it[0] to it[1] }
            runCatching { attachGainmap(bmp, gain, gw, gh, p.headroom) }.onFailure { Log.w(TAG, "Gainmap", it) }
        }
        return bmp
    }

    override fun close() {
        if (!closed) { closed = true; NativeEngine.release(handle) }
    }

    companion object {
        private const val TAG = "PhotoJob"

        @RequiresApi(34)
        fun attachGainmap(bmp: Bitmap, data: ByteArray, gw: Int, gh: Int, headroom: Float) {
            val contents = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
            val rb = contents.rowBytes
            val buf = ByteBuffer.allocate(rb * gh)
            for (y in 0 until gh) { buf.position(y * rb); buf.put(data, y * gw, gw) }
            buf.rewind()
            contents.copyPixelsFromBuffer(buf)
            val g = Gainmap(contents)
            g.setRatioMin(1f, 1f, 1f)
            g.setRatioMax(headroom, headroom, headroom)
            g.setGamma(1f, 1f, 1f)
            g.setEpsilonSdr(1f / 64, 1f / 64, 1f / 64)
            g.setEpsilonHdr(1f / 64, 1f / 64, 1f / 64)
            g.minDisplayRatioForHdrTransition = 1f
            g.displayRatioForFullHdr = headroom
            bmp.gainmap = g
        }
    }
}

/** Modo retrato: segmenta a la persona (ML Kit) y desenfoca el fondo en C++. */
object Portrait {
    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
    }

    /** Devuelve false si no se detecta a nadie (la foto queda sin cambios). */
    suspend fun apply(bmp: Bitmap, rotation: Int, strength: Float = 0.025f): Boolean {
        // Segmentar en vertical (la red funciona mejor con la persona derecha).
        val scale = 512f / max(bmp.width, bmp.height)
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val small = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val mask = segmenter.process(InputImage.fromBitmap(small, 0)).await()
        val mw = mask.width; val mh = mask.height
        val fb = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val upright = FloatArray(mw * mh)
        fb.get(upright)
        small.recycle()
        val coverage = upright.count { it > 0.5f }.toFloat() / upright.size
        if (coverage < 0.03f) return false
        // Volver a la orientación del sensor.
        val (sw, sh, sensor) = rotateMask(upright, mw, mh, (360 - rotation) % 360)
        NativeEngine.bokeh(bmp, sensor, sw, sh, strength)
        return true
    }

    /** Gira una máscara 0/90/180/270 grados en sentido horario. */
    fun rotateMask(src: FloatArray, w: Int, h: Int, deg: Int): Triple<Int, Int, FloatArray> = when (deg) {
        90 -> Triple(h, w, FloatArray(w * h).also { d -> for (y in 0 until h) for (x in 0 until w) d[x * h + (h - 1 - y)] = src[y * w + x] })
        180 -> Triple(w, h, FloatArray(w * h).also { d -> for (y in 0 until h) for (x in 0 until w) d[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x] })
        270 -> Triple(h, w, FloatArray(w * h).also { d -> for (y in 0 until h) for (x in 0 until w) d[(w - 1 - x) * h + y] = src[y * w + x] })
        else -> Triple(w, h, src)
    }
}
