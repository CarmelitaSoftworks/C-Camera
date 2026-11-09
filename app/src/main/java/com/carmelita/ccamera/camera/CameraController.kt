package com.carmelita.ccamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class FlashMode { OFF, AUTO, ON }

/** Cómo quedó configurada la sesión de foto tras probar lo que soporta el teléfono. */
data class PhotoSessionInfo(
    val raw: Boolean,
    val captureSize: Size,
    val zsl: Boolean,
    val live: Boolean,
)

/**
 * Envoltorio de Camera2. Sesión de foto: vista previa + lector RAW/YUV (ZSL si se puede) +
 * codificador de Fotos en vivo (si se puede). Sesión de vídeo: vista previa + MediaRecorder.
 */
class CameraController(private val context: Context) {

    val manager: CameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("ccamera").apply { start() }
    val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var live: LivePhotoRecorder? = null

    lateinit var caps: CameraCapabilities
        private set
    var photoInfo: PhotoSessionInfo? = null
        private set
    var frames = FrameBuffer(0)
        private set

    val isOpen get() = device != null
    val liveRecorder get() = live

    private var zoom = 1f
    private var focusRegions: Array<MeteringRectangle>? = null
    var flashMode = FlashMode.OFF
        private set
    private var videoMode = false
    private var videoFps = 30
    private var torch = false

    @Volatile var lastResult: TotalCaptureResult? = null
        private set

    // ---------------- Apertura ----------------

    @SuppressLint("MissingPermission")
    suspend fun open(lens: Lens) {
        caps = CameraCapabilities.detect(manager, lens)
        zoom = 1f.coerceIn(caps.zoomRange.start, caps.zoomRange.endInclusive)
        focusRegions = null
        device = suspendCancellableCoroutine { cont ->
            manager.openCamera(caps.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Cámara desconectada"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Error de cámara $error"))
                }
            }, handler)
        }
    }

    // ---------------- Sesiones ----------------

    @Suppress("DEPRECATION")
    private suspend fun createSession(dev: CameraDevice, outputs: List<Surface>): CameraCaptureSession? =
        suspendCancellableCoroutine { cont ->
            try {
                dev.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { if (cont.isActive) cont.resume(null) }
                }, handler)
            } catch (e: Exception) {
                Log.w(TAG, "createCaptureSession", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun closeOutputs() {
        runCatching { session?.close() }
        session = null
        frames.clear()
        runCatching { reader?.close() }
        reader = null
        live?.release()
        live = null
        recordSurface = null
    }

    /**
     * Sesión de foto. Prueba, en orden: RAW+ZSL+vivo, RAW+ZSL, RAW, YUV+ZSL+vivo, YUV+ZSL, YUV.
     * Así cada teléfono acaba con lo mejor que realmente soporta.
     */
    suspend fun startPhotoSession(texture: SurfaceTexture, wantLive: Boolean, maxPixels: Long): PhotoSessionInfo {
        val dev = device ?: error("Cámara cerrada")
        closeOutputs()
        videoMode = false
        val preview = caps.previewSize(4f / 3f)
        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface?.release()
        val surface = Surface(texture)
        previewSurface = surface

        data class Attempt(val raw: Boolean, val size: Size, val zsl: Boolean, val live: Boolean)
        val attempts = mutableListOf<Attempt>()
        val rawSize = caps.rawSize
        if (caps.supportsRaw && rawSize != null && rawSize.width.toLong() * rawSize.height <= maxPixels * 13 / 10) {
            val zslRaw = caps.canStream(ImageFormat.RAW_SENSOR, rawSize)
            if (zslRaw && wantLive) attempts += Attempt(true, rawSize, true, true)
            if (zslRaw) attempts += Attempt(true, rawSize, true, false)
            attempts += Attempt(true, rawSize, false, false)
        }
        val yuv = caps.yuvSize(maxPixels)
        val zslYuv = caps.canStream(ImageFormat.YUV_420_888, yuv)
        if (zslYuv && wantLive) attempts += Attempt(false, yuv, true, true)
        if (zslYuv) attempts += Attempt(false, yuv, true, false)
        attempts += Attempt(false, yuv, false, false)

        for (a in attempts) {
            val ring = if (a.zsl) RING else 0
            val r = ImageReader.newInstance(
                a.size.width, a.size.height,
                if (a.raw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888,
                if (a.zsl) RING + 4 else 12,
            )
            val fb = FrameBuffer(ring)
            r.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                fb.onImage(img)
            }, handler)
            val liveSize = caps.liveVideoSize()
            val lv = if (a.live && liveSize != null) runCatching { LivePhotoRecorder(liveSize) }.getOrNull() else null
            if (a.live && lv == null) { r.close(); continue }
            val outs = listOfNotNull(surface, r.surface, lv?.surface)
            val s = createSession(dev, outs)
            if (s != null) {
                reader = r; frames = fb; live = lv; session = s
                val info = PhotoSessionInfo(a.raw, a.size, a.zsl, lv != null)
                photoInfo = info
                updateRepeating()
                Log.i(TAG, "Sesión de foto: $info")
                return info
            }
            r.close(); lv?.release()
        }
        error("Ninguna configuración de sesión funcionó en esta cámara")
    }

    suspend fun startVideoSession(texture: SurfaceTexture, recorder: VideoRecorder) {
        val dev = device ?: error("Cámara cerrada")
        closeOutputs()
        videoMode = true
        videoFps = recorder.fps
        val preview = caps.previewSize(16f / 9f)
        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface?.release()
        val surface = Surface(texture)
        previewSurface = surface
        recordSurface = recorder.surface
        session = createSession(dev, listOf(surface, recorder.surface)) ?: error("La cámara no admite esta configuración de vídeo")
        photoInfo = null
        updateRepeating()
    }

    /** Vista previa de vídeo antes de grabar (sin MediaRecorder todavía). */
    suspend fun startVideoPreview(texture: SurfaceTexture) {
        val dev = device ?: error("Cámara cerrada")
        closeOutputs()
        videoMode = true
        val preview = caps.previewSize(16f / 9f)
        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface?.release()
        val surface = Surface(texture)
        previewSurface = surface
        session = createSession(dev, listOf(surface)) ?: error("No se pudo iniciar la vista previa")
        photoInfo = null
        updateRepeating()
    }

    // ---------------- Petición continua ----------------

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            frames.onResult(result)
        }
    }

    private fun updateRepeating() {
        val s = session ?: return
        val dev = device ?: return
        val surface = previewSurface ?: return
        val b = dev.createCaptureRequest(if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        recordSurface?.let { b.addTarget(it) }
        if (!videoMode) {
            val info = photoInfo
            if (info?.zsl == true) reader?.surface?.let { b.addTarget(it) }
            live?.surface?.let { b.addTarget(it) }
            if (info?.raw == true && caps.lensShadingMap) {
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
            if (info?.zsl == true && info.raw.not()) {
                // ZSL en YUV: el ISP usa su modo pensado para ZSL (menos procesado por frame).
                set3(b, CaptureRequest.NOISE_REDUCTION_MODE, caps.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                set3(b, CaptureRequest.EDGE_MODE, caps.edgeModes, CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG, CameraMetadata.EDGE_MODE_FAST)
            }
        } else {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(videoFps, videoFps))
            if (caps.videoStabilization) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        apply3A(b, captureFlash = false)
        if (videoMode && torch && caps.hasFlash) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        runCatching { s.setRepeatingRequest(b.build(), repeatingCallback, handler) }
            .onFailure { Log.w(TAG, "setRepeatingRequest", it) }
    }

    private fun set3(b: CaptureRequest.Builder, key: CaptureRequest.Key<Int>, avail: IntArray, preferred: Int, fallback: Int) {
        when {
            avail.contains(preferred) -> b.set(key, preferred)
            avail.contains(fallback) -> b.set(key, fallback)
        }
    }

    private fun apply3A(b: CaptureRequest.Builder, @Suppress("UNUSED_PARAMETER") captureFlash: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // Con flash activado, la vista previa también usa el modo de AE con flash (como Camera2Basic):
        // así la precaptura mide bien. El flash sólo se dispara en capturas fijas.
        val aeMode = when {
            !caps.hasFlash || videoMode -> CameraMetadata.CONTROL_AE_MODE_ON
            flashMode == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            flashMode == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        if (caps.ois) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (caps.faceDetection) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        val regions = focusRegions
        if (regions != null && caps.hasAutofocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            if (caps.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            if (caps.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        } else if (videoMode && caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else if (caps.hasContinuousAf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (caps.usesZoomRatio && Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        else b.set(CaptureRequest.SCALER_CROP_REGION, cropRect())
    }

    private fun cropRect(): Rect {
        val a = caps.activeArray
        val z = max(1f, zoom)
        val cw = (a.width() / z).roundToInt(); val ch = (a.height() / z).roundToInt()
        val l = a.left + (a.width() - cw) / 2; val t = a.top + (a.height() - ch) / 2
        return Rect(l, t, l + cw, t + ch)
    }

    // ---------------- Controles ----------------

    fun setZoom(z: Float) {
        if (!isOpen) return
        zoom = z.coerceIn(caps.zoomRange.start, caps.zoomRange.endInclusive)
        updateRepeating()
    }

    fun setFlash(mode: FlashMode) { flashMode = mode; updateRepeating() }

    fun setTorch(on: Boolean) { torch = on; updateRepeating() }

    /** Enfoque/medición en un punto de la vista previa (normalizado 0..1, vista en vertical). */
    fun focusAt(nx: Float, ny: Float) {
        val s = session ?: return
        val dev = device ?: return
        val surface = previewSurface ?: return
        if (!caps.hasAutofocus && caps.maxAeRegions == 0) return
        val x = if (caps.lens == Lens.FRONT) 1f - nx else nx
        val (sx, sy) = when (caps.sensorOrientation) {
            90 -> ny to 1f - x
            180 -> 1f - x to 1f - ny
            270 -> 1f - ny to x
            else -> x to ny
        }
        val area = if (caps.usesZoomRatio) caps.activeArray else cropRect()
        val half = (area.width() * 0.06f).roundToInt()
        val cx = area.left + (sx * area.width()).roundToInt()
        val cy = area.top + (sy * area.height()).roundToInt()
        val rect = Rect(
            (cx - half).coerceIn(area.left, area.right - 1), (cy - half).coerceIn(area.top, area.bottom - 1),
            (cx + half).coerceIn(area.left + 1, area.right), (cy + half).coerceIn(area.top + 1, area.bottom),
        )
        focusRegions = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        runCatching {
            val trigger = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface); apply3A(this, false)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            s.capture(trigger.build(), null, handler)
        }
        updateRepeating()
    }

    fun resetFocus() {
        if (focusRegions == null) return
        focusRegions = null
        updateRepeating()
    }

    fun jpegOrientation(deviceDegrees: Int): Int {
        val d = if (caps.lens == Lens.FRONT) -deviceDegrees else deviceDegrees
        return (caps.sensorOrientation + d + 360) % 360
    }

    /** ¿El AE pide flash ahora mismo? (para el modo automático) */
    fun flashRequired(): Boolean =
        lastResult?.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
            (lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) >= 3200

    // ---------------- Captura ----------------

    private fun stillBuilder(): CaptureRequest.Builder {
        val dev = device ?: error("Cámara cerrada")
        val r = reader ?: error("Sesión no lista")
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            previewSurface?.let { addTarget(it) }
            apply3A(this, captureFlash = false)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
            // El ISP no debe reducir ruido ni enfocar: lo hace la fusión.
            set3(this, CaptureRequest.NOISE_REDUCTION_MODE, caps.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            set3(this, CaptureRequest.EDGE_MODE, caps.edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
            if (photoInfo?.raw == true && caps.lensShadingMap) {
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
        }
    }

    /** Exposición manual (si se puede). Devuelve false si el sensor no lo admite. */
    private fun CaptureRequest.Builder.manualExposure(t: Long, iso: Int): Boolean {
        if (!caps.manualSensor) return false
        val er = caps.exposureRange ?: return false
        val ir = caps.isoRange ?: return false
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, t.coerceIn(er.lower, er.upper))
        set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(ir.lower, ir.upper))
        set(CaptureRequest.SENSOR_FRAME_DURATION, t.coerceIn(er.lower, er.upper))
        return true
    }

    /** Exposición del AE en este momento: (tiempo ns, ISO). */
    fun currentExposure(): Pair<Long, Int>? {
        val r = lastResult ?: return null
        val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        return t to iso
    }

    /**
     * Envía [requests] y entrega cada frame (con su resultado) por orden a [onFrame].
     */
    suspend fun capture(
        requests: List<CaptureRequest>,
        perFrameTimeoutMs: Long,
        onFrame: suspend (CapturedFrame, TotalCaptureResult?, Int) -> Unit,
    ) {
        val s = session ?: error("Sesión no lista")
        val fb = frames
        val ts = List(requests.size) { CompletableDeferred<Long>() }
        val res = List(requests.size) { CompletableDeferred<TotalCaptureResult>() }
        val cb = object : CameraCaptureSession.CaptureCallback() {
            private fun idx(r: CaptureRequest) = requests.indexOfFirst { it === r }
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                val i = idx(r); if (i < 0) return
                fb.reserve(timestamp); ts[i].complete(timestamp)
            }
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                lastResult = result; fb.onResult(result)
                val i = idx(r); if (i >= 0) res[i].complete(result)
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                val i = idx(r); if (i < 0) return
                ts[i].completeExceptionally(IllegalStateException("Captura fallida (${failure.reason})"))
            }
        }
        if (requests.size == 1) s.capture(requests[0], cb, handler) else s.captureBurst(requests, cb, handler)
        for (i in requests.indices) {
            val frame = withTimeout(perFrameTimeoutMs) { fb.await(ts[i].await()) }
            val result = withTimeoutOrNull(1_000) { res[i].await() }
            onFrame(frame, result, i)
        }
    }

    /** Frame subexpuesto (exposición / ratio) para recuperar altas luces. */
    fun shortExposureRequest(ratio: Float): CaptureRequest? {
        val (t, iso) = currentExposure() ?: return null
        val er = caps.exposureRange ?: return null
        val b = stillBuilder()
        var tt = (t / ratio).toLong()
        var ii = iso
        if (tt < er.lower) { ii = (iso * tt.toFloat() / er.lower).roundToInt(); tt = er.lower }
        return if (b.manualExposure(tt, ii)) b.build() else null
    }

    /**
     * Ráfaga de noche: exposiciones largas e ISO bajo con la misma luz total que el AE x 2^ev.
     * Sin control manual, usa el AE bloqueado.
     */
    fun nightRequests(count: Int): Pair<List<CaptureRequest>, Long?> {
        val exp = currentExposure()
        val ir = caps.isoRange
        var usedT: Long? = null
        val list = (0 until count).map {
            val b = stillBuilder()
            if (exp != null && ir != null && caps.manualSensor) {
                val (t0, iso0) = exp
                val product = t0.toDouble() * iso0 * 1.6        // algo más de luz: +2/3 EV
                val maxT = 1_000_000_000.0 / 6                 // 1/6 s a pulso (con OIS)
                val t = (product / max(ir.lower, 50)).coerceAtMost(if (caps.ois) maxT else maxT / 2).coerceAtLeast(t0.toDouble())
                val iso = (product / t).roundToInt()
                b.manualExposure(t.toLong(), iso)
                usedT = t.toLong()
            } else {
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            b.build()
        }
        return list to usedT
    }

    /** Ráfaga normal (cuando no hay ZSL). */
    fun burstRequests(count: Int): List<CaptureRequest> = (0 until count).map {
        stillBuilder().apply { set(CaptureRequest.CONTROL_AE_LOCK, true) }.build()
    }

    /** Secuencia de precaptura con flash y una única foto con flash. */
    suspend fun captureWithFlash(onFrame: suspend (CapturedFrame, TotalCaptureResult?, Int) -> Unit) {
        val s = session ?: error("Sesión no lista")
        val dev = device ?: error("Cámara cerrada")
        val surface = previewSurface ?: error("Sesión no lista")
        val pre = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface); apply3A(this, captureFlash = true)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }.build()
        s.capture(pre, null, handler)
        // Esperar a que el AE converja con el flash (máx. 1,5 s).
        val start = System.currentTimeMillis()
        var sawPrecapture = false
        while (System.currentTimeMillis() - start < 1_500) {
            val st = lastResult?.get(CaptureResult.CONTROL_AE_STATE)
            if (st == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
            if (sawPrecapture && st != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) break
            delay(30)
        }
        val still = stillBuilder().apply {
            apply3A(this, captureFlash = true)
            set(CaptureRequest.CONTROL_AWB_LOCK, false)   // el flash cambia el color de la luz
        }.build()
        capture(listOf(still), 3_000, onFrame)
    }

    // ---------------- Cierre ----------------

    fun close() {
        closeOutputs()
        runCatching { device?.close() }
        runCatching { previewSurface?.release() }
        device = null; previewSurface = null; lastResult = null; photoInfo = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CCamera"
        const val RING = 6
    }
}

/** Utilidad: ¿Es aproximadamente 4:3? */
internal fun Size.isFourThree() = abs(width * 3 - height * 4) <= width / 100
