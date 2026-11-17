package com.carmelita.ccamera.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Rect
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
import kotlin.math.min

data class DevelopParams(
    val style: PhotoStyle = PhotoStyle.STANDARD,
    val smartHdr: Boolean = true,
    val sharpen: Float = 0.7f,
    val denoise: Float = 1f,
    val exposure: Float = 1f,
    val ultraHdr: Boolean = true,
    val headroom: Float = 4f,
)

/**
 * Una foto en proceso: recibe frames (el primero es la referencia), los fusiona en C++ y
 * revela el resultado como Bitmap (con mapa de ganancia Ultra HDR en Android 14+).
 */
class PhotoJob(private val caps: CameraCapabilities, private val raw: Boolean) : AutoCloseable {
    private val handle = NativeEngine.createSession()
    private var refResult: TotalCaptureResult? = null
    private var refExposure: Double? = null
    private var sensor: CameraCapabilities.RawSensor? = null
    private var crop: Rect? = null
    private var closed = false
    var framesAdded = 0
        private set

    /** ¿La foto salió de un recorte del sensor por zoom digital? */
    val zoomCropped: Boolean
        get() {
            val c = crop ?: return false
            val a = sensor?.active ?: return false
            return c.width() < a.width() * 0.9f
        }

    /**
     * Añade un frame. Devuelve false si se descarta: otra lente (cambio de cámara física a mitad
     * de ráfaga) o una exposición demasiado distinta de la referencia.
     */
    fun add(frame: CapturedFrame, result: TotalCaptureResult?, isShort: Boolean = false, ratio: Float = 1f): Boolean {
        check(!closed)
        val first = !isShort && framesAdded == 0
        val exposure = exposureOf(result)
        if (first) {
            refResult = result
            refExposure = exposure
            noiseProfile(result)?.let { (s, o) -> NativeEngine.setNoiseProfile(handle, s, o) }
        }
        val refExp = refExposure
        // Los frames del búfer ZSL pueden tener exposiciones distintas (el AE se mueve): se igualan.
        var scale = 1f
        var shortRatio = ratio
        if (!first && exposure != null && refExp != null) {
            val rel = (refExp / exposure).toFloat()
            if (isShort) {
                if (rel in 1.5f..32f) shortRatio = rel
            } else {
                if (rel !in 0.67f..1.5f) return false
                if (frame is CapturedFrame.Yuv && (rel < 0.94f || rel > 1.06f)) return false
                scale = rel
            }
        }
        when (frame) {
            is CapturedFrame.Raw -> {
                val activeId = activePhysicalId(result)
                val sn = sensor ?: caps.rawSensor(activeId).also { sensor = it }
                if (!first && result != null && activeId != sn.id) return false
                val c = crop ?: zoomCrop(sn, frame.width, frame.height, zoomOf(result)).also { crop = it }
                val black = dynamicBlack(result) ?: sn.black
                val white = dynamicWhite(result) ?: sn.whiteLevel
                NativeEngine.addRaw(
                    handle, frame.data, frame.rowStride, frame.width, frame.height,
                    sn.cfa, black, white, c.left, c.top, c.width(), c.height(), scale, isShort, shortRatio,
                )
            }
            is CapturedFrame.Yuv -> NativeEngine.addYuv(
                handle, frame.y, frame.yRowStride, frame.u, frame.v, frame.uvRowStride, frame.uvPixelStride,
                frame.width, frame.height, isShort, shortRatio,
            )
        }
        if (!isShort) framesAdded++
        return true
    }

    /** Luz total del frame (tiempo x sensibilidad), para comparar exposiciones. */
    private fun exposureOf(r: TotalCaptureResult?): Double? {
        val t = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        return if (t > 0 && iso > 0) t.toDouble() * iso else null
    }

    private fun activePhysicalId(r: TotalCaptureResult?): String? =
        if (Build.VERSION.SDK_INT >= 29 && caps.logicalMultiCamera) r?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null

    /** Zoom pedido en este frame (el RAW no viene recortado: hay que recortarlo aquí). */
    private fun zoomOf(r: TotalCaptureResult?): Float {
        if (r == null) return 1f
        if (Build.VERSION.SDK_INT >= 30 && caps.usesZoomRatio) r.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { return it }
        val region = r.get(CaptureResult.SCALER_CROP_REGION) ?: return 1f
        return if (region.width() > 0) caps.activeArray.width().toFloat() / region.width() else 1f
    }

    private fun noiseProfile(r: TotalCaptureResult?): Pair<Float, Float>? {
        if (!raw) return null
        val np = r?.get(CaptureResult.SENSOR_NOISE_PROFILE) ?: return null
        if (np.isEmpty()) return null
        val s = np.map { it.first }.average().toFloat()
        val o = np.map { it.second }.average().toFloat()
        return if (s > 0 && o >= 0) s to o else null
    }

    private fun dynamicBlack(r: TotalCaptureResult?): FloatArray? {
        if (Build.VERSION.SDK_INT < 28) return null
        val b = r?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) ?: return null
        return if (b.size >= 4) b.copyOf(4) else null
    }

    private fun dynamicWhite(r: TotalCaptureResult?): Float? {
        if (Build.VERSION.SDK_INT < 28) return null
        val w = r?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: return null
        return if (w > 0) w.toFloat() else null
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

    /** Parte del mapa de sombreado de lente que cubre el recorte, como [u0, v0, u1, v1]. */
    private fun lscWindow(): FloatArray? {
        val sn = sensor ?: return null
        val c = crop ?: return null
        val a = sn.active
        if (a.width() <= 0 || a.height() <= 0) return null
        return floatArrayOf(
            (c.left - a.left).toFloat() / a.width(), (c.top - a.top).toFloat() / a.height(),
            (c.right - a.left).toFloat() / a.width(), (c.bottom - a.top).toFloat() / a.height(),
        )
    }

    /** Revela la foto. El Bitmap está en orientación del sensor (la rotación va en EXIF). */
    fun develop(p: DevelopParams): Bitmap {
        val (w, h) = NativeEngine.outputSize(handle).let { it[0] to it[1] }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lsc = if (raw) lensShading() else null
        val wantGain = p.ultraHdr && Build.VERSION.SDK_INT >= 34
        val gain = NativeEngine.process(
            handle, bmp, if (raw) colorMeta() else null, lsc?.first, lsc?.second ?: 0, lsc?.third ?: 0,
            lscWindow(), p.exposure, p.smartHdr, p.style.ordinal, p.sharpen, p.denoise, wantGain, p.headroom,
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

        /**
         * Recorte del RAW para un zoom dado: zona útil del sensor, reducida al centro según el
         * zoom que falta por aplicar (zoom pedido / zoom propio de la lente), con bordes pares.
         */
        fun zoomCrop(sensor: CameraCapabilities.RawSensor, width: Int, height: Int, zoom: Float): Rect {
            val a = sensor.active
            val residual = (zoom / sensor.lensRatio).coerceIn(1f, 16f)
            var w = (a.width() / residual).toInt() and 1.inv()
            var h = (a.height() / residual).toInt() and 1.inv()
            w = w.coerceIn(64, width and 1.inv()); h = h.coerceIn(64, height and 1.inv())
            // El origen par mantiene la fase del mosaico de color.
            val x = (a.left + (a.width() - w) / 2).coerceIn(0, width - w) and 1.inv()
            val y = (a.top + (a.height() - h) / 2).coerceIn(0, height - h) and 1.inv()
            return Rect(x, y, x + w, y + h)
        }

        /**
         * Tras un zoom digital la foto sale pequeña (el RAW se recorta). Se amplía con Lanczos en
         * luz lineal hasta un tamaño razonable, como hacen las cámaras de serie.
         */
        fun upscaleIfSmall(bmp: Bitmap, targetLongSide: Int = 3264): Bitmap {
            val longSide = max(bmp.width, bmp.height)
            if (longSide >= targetLongSide * 0.85f) return bmp
            val k = min(2.5f, targetLongSide.toFloat() / longSide)
            val out = Bitmap.createBitmap((bmp.width * k).toInt() and 1.inv(), (bmp.height * k).toInt() and 1.inv(), Bitmap.Config.ARGB_8888)
            NativeEngine.exportResize(bmp, 0, 0, bmp.width, bmp.height, out, 0.2f)
            // El mapa de ganancia cubre la imagen entera a cualquier tamaño: se reutiliza tal cual.
            if (Build.VERSION.SDK_INT >= 34) bmp.gainmap?.let { out.gainmap = it }
            return out
        }

        /** Nitidez de un frame (mayor = más nítido), medida en la zona central. */
        fun sharpness(frame: CapturedFrame): Double = when (frame) {
            is CapturedFrame.Raw -> NativeEngine.frameSharpness(
                frame.data, frame.rowStride, (frame.width / 4) and 1.inv(), (frame.height / 4) and 1.inv(),
                frame.width / 2, frame.height / 2, 2,
            )
            is CapturedFrame.Yuv -> NativeEngine.frameSharpness(
                frame.y, frame.yRowStride, frame.width / 4, frame.height / 4, frame.width / 2, frame.height / 2, 1,
            )
        }

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
