package com.carmelita.ccamera.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carmelita.ccamera.camera.CameraCapabilities
import com.carmelita.ccamera.camera.CameraController
import com.carmelita.ccamera.camera.CapturedFrame
import com.carmelita.ccamera.camera.FlashMode
import com.carmelita.ccamera.camera.Lens
import com.carmelita.ccamera.camera.VideoRecorder
import com.carmelita.ccamera.camera.ZoomStop
import com.carmelita.ccamera.processing.DevelopParams
import com.carmelita.ccamera.processing.PhotoJob
import com.carmelita.ccamera.processing.PhotoStyle
import com.carmelita.ccamera.processing.Portrait
import com.carmelita.ccamera.qr.QrActions
import com.carmelita.ccamera.qr.QrContent
import com.carmelita.ccamera.qr.QrScanner
import com.carmelita.ccamera.share.InstagramExporter
import com.carmelita.ccamera.share.InstagramFormat
import com.carmelita.ccamera.storage.ExifWriter
import com.carmelita.ccamera.storage.MediaSaver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

enum class CaptureMode(val label: String) { NIGHT("NOCHE"), PHOTO("FOTO"), PORTRAIT("RETRATO"), VIDEO("VÍDEO") }

data class FocusMark(val x: Float, val y: Float, val id: Long)

data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val style: PhotoStyle = PhotoStyle.STANDARD,
    val smartHdr: Boolean = true,
    val livePhoto: Boolean = true,
    val liveActive: Boolean = false,
    val qrEnabled: Boolean = true,
    val flash: FlashMode = FlashMode.OFF,
    val lens: Lens = Lens.BACK,
    val zoom: Float = 1f,
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
    val zoomStops: List<ZoomStop> = emptyList(),
    val capturing: Boolean = false,
    val captureProgress: Float = 0f,
    val processing: Int = 0,
    val thumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    val lastLiveClip: File? = null,
    val focus: FocusMark? = null,
    val shutterFlash: Long = 0L,
    val message: String? = null,
    val qr: QrContent? = null,
    val recording: Boolean = false,
    val recordStartMs: Long = 0L,
    val video4k: Boolean = true,
    val video60: Boolean = false,
    // Capacidades detectadas
    val hasFlash: Boolean = false,
    val rawActive: Boolean = false,
    val zslActive: Boolean = false,
    val ultraHdr: Boolean = false,
    val can4k: Boolean = false,
    val can60: Boolean = false,
    val hasFront: Boolean = true,
    val capsSummary: List<Pair<String, String>> = emptyList(),
    val sessionSummary: String = "",
    val showInfo: Boolean = false,
    val viewer: Uri? = null,
    val exporting: Boolean = false,
    // Exposición, cuadrícula, temporizador y sugerencia de modo Noche
    val ev: Float = 0f,
    val evSupported: Boolean = false,
    val grid: Boolean = false,
    val timerSec: Int = 0,
    val countdown: Int = 0,
    val nightSuggested: Boolean = false,
    val maxQueued: Int = 2,
    val saveDng: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ccamera", 0)
    private val controller = CameraController(app)
    private val camMutex = Mutex()
    private val processMutex = Mutex()
    // Los frames copiados viven en el heap de la app: con poca memoria, menos frames y sin cola.
    private val bigHeap = Runtime.getRuntime().maxMemory() >= (400L shl 20)
    private val photoFrames = if (bigHeap) 6 else 4
    private val maxQueued = if (bigHeap) 2 else 1
    private val qrScanner by lazy { QrScanner() }

    private val _state = MutableStateFlow(
        CameraUiState(
            style = runCatching { PhotoStyle.valueOf(prefs.getString("style", null) ?: "") }.getOrDefault(PhotoStyle.STANDARD),
            smartHdr = prefs.getBoolean("hdr", true),
            livePhoto = prefs.getBoolean("live", true),
            qrEnabled = prefs.getBoolean("qr", true),
            video4k = prefs.getBoolean("4k", true),
            video60 = prefs.getBoolean("60fps", false),
            flash = runCatching { FlashMode.valueOf(prefs.getString("flash", null) ?: "") }.getOrDefault(FlashMode.OFF),
            grid = prefs.getBoolean("grid", false),
            saveDng = prefs.getBoolean("dng", false),
            maxQueued = if (Runtime.getRuntime().maxMemory() >= (400L shl 20)) 2 else 1,
            timerSec = prefs.getInt("timer", 0).let { if (it == 3 || it == 10) it else 0 },
            hasFront = CameraCapabilities.hasLens(controller.manager, Lens.FRONT),
        )
    )
    val state: StateFlow<CameraUiState> = _state

    private var texture: SurfaceTexture? = null
    private var started = false
    private var focusResetJob: Job? = null
    private val processingCount = AtomicInteger(0)
    private var recorder: VideoRecorder? = null
    private var recordingUri: Uri? = null
    private var recordingPfd: ParcelFileDescriptor? = null
    private var countdownJob: Job? = null
    // Compensación de exposición: valor continuo (EV) mientras se arrastra, y límites del sensor.
    private var evTarget = 0f
    private var evStep = 0f
    private var evMin = 0f
    private var evMax = 0f

    @Volatile var deviceRotation = 0
    /** La UI registra aquí cómo obtener un fotograma pequeño de la vista previa (para el QR). */
    @Volatile var previewFrameProvider: (() -> Bitmap?)? = null

    /** Resolución máxima según la memoria (la fusión trabaja en float en memoria nativa). */
    private val maxPixels: Long = run {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (info.totalMem >= 6L shl 30) 16_000_000L else if (info.totalMem >= 4L shl 30) 13_000_000L else 8_500_000L
    }

    init { startQrLoop() }

    // ---------------- Ciclo de vida ----------------

    fun onResume() { started = true; viewModelScope.launch { openIfReady() } }

    fun onPause() {
        started = false
        countdownJob?.cancel()
        _state.update { it.copy(countdown = 0) }
        if (_state.value.recording) toggleRecording()
        viewModelScope.launch { camMutex.withLock { controller.close() } }
    }

    fun onSurfaceAvailable(t: SurfaceTexture) { texture = t; viewModelScope.launch { openIfReady() } }

    fun onSurfaceDestroyed() {
        texture = null
        viewModelScope.launch { camMutex.withLock { controller.close() } }
    }

    private suspend fun openIfReady() = camMutex.withLock {
        val t = texture ?: return@withLock
        if (!started || controller.isOpen) return@withLock
        try {
            controller.open(_state.value.lens)
            configureLocked(t)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            controller.close()
            _state.update { it.copy(message = "No se pudo abrir la cámara: ${e.message}") }
        }
    }

    /** Configura la sesión según el modo (llamar con camMutex tomado). */
    private suspend fun configureLocked(t: SurfaceTexture) {
        val s = _state.value
        val caps = controller.caps
        if (s.mode == CaptureMode.VIDEO) {
            controller.startVideoPreview(t)
            _state.update { it.copy(liveActive = false, rawActive = false, zslActive = false, sessionSummary = "Vídeo") }
        } else {
            val info = controller.startPhotoSession(t, wantLive = s.livePhoto, maxPixels = maxPixels)
            _state.update {
                it.copy(
                    liveActive = info.live, rawActive = info.raw, zslActive = info.zsl,
                    sessionSummary = "${if (info.raw) "RAW" else "YUV"} ${info.captureSize.width}×${info.captureSize.height}" +
                        (if (info.zsl) " · ZSL" else "") + (if (info.live) " · En vivo" else ""),
                )
            }
        }
        controller.setFlash(if (s.mode == CaptureMode.VIDEO) FlashMode.OFF else s.flash)
        controller.setZoom(1f)
        evStep = caps.evStep
        evMin = (caps.evRange.lower * caps.evStep).coerceAtLeast(-3f)
        evMax = (caps.evRange.upper * caps.evStep).coerceAtMost(3f)
        evTarget = 0f
        controller.setExposureCompensation(0)
        _state.update {
            it.copy(
                ev = 0f, evSupported = caps.supportsEv, nightSuggested = false,
                zoom = 1f.coerceIn(caps.zoomRange.start, caps.zoomRange.endInclusive),
                zoomRange = caps.zoomRange, zoomStops = caps.zoomStops,
                hasFlash = caps.hasFlash, ultraHdr = caps.ultraHdr,
                can4k = caps.video4k, can60 = caps.supportsFps(caps.videoSize(it.video4k), 60),
                capsSummary = caps.summary(),
            )
        }
    }

    private fun reconfigure() {
        viewModelScope.launch {
            camMutex.withLock {
                val t = texture ?: return@withLock
                if (!controller.isOpen) return@withLock
                runCatching { configureLocked(t) }.onFailure {
                    Log.e(TAG, "Reconfigurar", it)
                    _state.update { s -> s.copy(message = "Error de cámara: ${it.message}") }
                }
            }
        }
    }

    fun switchLens() {
        val s = _state.value
        if (s.capturing || s.recording) return
        _state.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK, focus = null) }
        viewModelScope.launch {
            camMutex.withLock { controller.close() }
            openIfReady()
        }
    }

    // ---------------- Ajustes ----------------

    fun setMode(m: CaptureMode) {
        val old = _state.value.mode
        if (old == m || _state.value.recording || _state.value.capturing) return
        countdownJob?.cancel()
        _state.update { it.copy(mode = m, qr = null, countdown = 0, nightSuggested = false) }
        if (old == CaptureMode.VIDEO || m == CaptureMode.VIDEO) reconfigure()
    }

    fun setStyle(s: PhotoStyle) { prefs.edit().putString("style", s.name).apply(); _state.update { it.copy(style = s) } }

    fun toggleHdr() { val v = !_state.value.smartHdr; prefs.edit().putBoolean("hdr", v).apply(); _state.update { it.copy(smartHdr = v) } }

    fun toggleLive() {
        val v = !_state.value.livePhoto
        prefs.edit().putBoolean("live", v).apply()
        _state.update { it.copy(livePhoto = v) }
        reconfigure()
    }

    fun toggleQr() { val v = !_state.value.qrEnabled; prefs.edit().putBoolean("qr", v).apply(); _state.update { it.copy(qrEnabled = v, qr = null) } }

    fun cycleFlash() {
        val next = when (_state.value.flash) { FlashMode.OFF -> FlashMode.AUTO; FlashMode.AUTO -> FlashMode.ON; FlashMode.ON -> FlashMode.OFF }
        prefs.edit().putString("flash", next.name).apply()
        _state.update { it.copy(flash = next) }
        viewModelScope.launch {
            camMutex.withLock {
                if (_state.value.mode == CaptureMode.VIDEO) controller.setTorch(next == FlashMode.ON) else controller.setFlash(next)
            }
        }
    }

    fun toggle4k() { val v = !_state.value.video4k; prefs.edit().putBoolean("4k", v).apply(); _state.update { it.copy(video4k = v) } }
    fun toggle60() { val v = !_state.value.video60; prefs.edit().putBoolean("60fps", v).apply(); _state.update { it.copy(video60 = v) } }

    fun setZoom(z: Float) {
        val r = _state.value.zoomRange
        val zz = z.coerceIn(r.start, r.endInclusive)
        _state.update { it.copy(zoom = zz) }
        viewModelScope.launch { camMutex.withLock { controller.setZoom(zz) } }
    }

    fun focusAt(nx: Float, ny: Float) {
        evTarget = 0f
        _state.update { it.copy(focus = FocusMark(nx, ny, System.nanoTime()), ev = 0f) }
        viewModelScope.launch {
            camMutex.withLock {
                controller.setExposureCompensation(0)
                controller.focusAt(nx, ny)
            }
        }
        scheduleFocusReset()
    }

    /** El recuadro de enfoque se va a los 5 s sin tocar (la exposición elegida se mantiene). */
    private fun scheduleFocusReset() {
        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            delay(5_000)
            _state.update { it.copy(focus = null) }
            camMutex.withLock { controller.resetFocus() }
        }
    }

    /**
     * Compensación de exposición al deslizar tras tocar para enfocar (como el sol del iPhone).
     * [fraction]: desplazamiento vertical relativo a la altura del visor; hacia arriba aclara.
     */
    fun adjustEv(fraction: Float) {
        if (evStep <= 0f || _state.value.focus == null) return
        val before = Math.round(evTarget / evStep)
        evTarget = (evTarget + fraction * 5f).coerceIn(evMin, evMax)
        val index = Math.round(evTarget / evStep)
        scheduleFocusReset()
        if (index == before) return
        _state.update { it.copy(ev = index * evStep) }
        viewModelScope.launch { camMutex.withLock { controller.setExposureCompensation(index) } }
    }

    fun resetEv() {
        evTarget = 0f
        _state.update { it.copy(ev = 0f) }
        viewModelScope.launch { camMutex.withLock { controller.setExposureCompensation(0) } }
    }

    fun toggleDng() { val v = !_state.value.saveDng; prefs.edit().putBoolean("dng", v).apply(); _state.update { it.copy(saveDng = v) } }

    fun toggleGrid() { val v = !_state.value.grid; prefs.edit().putBoolean("grid", v).apply(); _state.update { it.copy(grid = v) } }

    fun cycleTimer() {
        val next = when (_state.value.timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
        prefs.edit().putInt("timer", next).apply()
        _state.update { it.copy(timerSec = next) }
    }

    fun showInfo(v: Boolean) = _state.update { it.copy(showInfo = v) }
    fun openViewer() { _state.value.lastUri?.let { u -> _state.update { it.copy(viewer = u) } } }
    fun closeViewer() = _state.update { it.copy(viewer = null) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    private fun toast(m: String) = _state.update { it.copy(message = m) }

    // ---------------- QR ----------------

    private fun startQrLoop() {
        viewModelScope.launch {
            var lastSeen = 0L
            while (isActive) {
                delay(450)
                val s = _state.value
                // Sugerir el modo Noche cuando el AE ya va con exposición larga e ISO alto.
                val dark = s.mode == CaptureMode.PHOTO && controller.isOpen && !s.capturing &&
                    runCatching { controller.sceneIsDark() }.getOrDefault(false)
                if (dark != s.nightSuggested) _state.update { it.copy(nightSuggested = dark) }
                if (!s.qrEnabled || s.capturing || s.recording || s.viewer != null ||
                    (s.mode != CaptureMode.PHOTO && s.mode != CaptureMode.PORTRAIT) || !controller.isOpen
                ) {
                    if (s.qr != null) _state.update { it.copy(qr = null) }
                    continue
                }
                val bmp = runCatching { previewFrameProvider?.invoke() }.getOrNull() ?: continue
                val found = withContext(Dispatchers.Default) { qrScanner.scan(bmp) }
                bmp.recycle()
                val now = System.currentTimeMillis()
                if (found != null) {
                    lastSeen = now
                    if (_state.value.qr?.raw != found.raw) _state.update { it.copy(qr = found) }
                } else if (now - lastSeen > 1_500 && _state.value.qr != null) {
                    _state.update { it.copy(qr = null) }
                }
            }
        }
    }

    fun onQrTap() {
        val c = _state.value.qr ?: return
        QrActions.run(getApplication<Application>(), c)?.let { toast(it) }
    }

    // ---------------- Foto ----------------

    fun shutter() {
        val s = _state.value
        if (s.mode == CaptureMode.VIDEO) { toggleRecording(); return }
        // Pulsar durante la cuenta atrás la cancela.
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            _state.update { it.copy(countdown = 0) }
            return
        }
        if (s.capturing || processingCount.get() >= maxQueued || !controller.isOpen) return
        if (s.timerSec > 0) {
            countdownJob = viewModelScope.launch {
                for (i in s.timerSec downTo 1) {
                    _state.update { it.copy(countdown = i) }
                    delay(1_000)
                }
                _state.update { it.copy(countdown = 0) }
                capturePhoto()
            }
            return
        }
        capturePhoto()
    }

    /** Pone como referencia el más nítido de los 3 frames más recientes (si gana con claridad). */
    private fun sharpestFirst(frames: List<CapturedFrame>): List<CapturedFrame> {
        val n = minOf(3, frames.size)
        val scores = runCatching { (0 until n).map { PhotoJob.sharpness(frames[it]) } }.getOrNull() ?: return frames
        var best = 0
        for (i in 1 until n) if (scores[i] > scores[best] * 1.1) best = i
        if (best == 0) return frames
        return listOf(frames[best]) + frames.filterIndexed { i, _ -> i != best }
    }

    private fun capturePhoto() {
        val s = _state.value
        if (s.capturing || processingCount.get() >= maxQueued || !controller.isOpen) return
        val rotation = controller.jpegOrientation(deviceRotation)
        _state.update { it.copy(capturing = true, captureProgress = 0f, shutterFlash = System.nanoTime(), qr = null) }

        viewModelScope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            var job: PhotoJob? = null
            var liveClip: Deferred<Pair<File, Long>?>? = null
            var refResult: TotalCaptureResult? = null
            var kind = ""
            var counted = false
            try {
                val caps = controller.caps
                val info = controller.photoInfo ?: error("Sesión no lista")
                val j = PhotoJob(caps, info.raw)
                job = j
                val flashNeeded = caps.hasFlash && (s.flash == FlashMode.ON || (s.flash == FlashMode.AUTO && controller.flashRequired()))

                // Frames ya copiados que esperan a fusionarse: (frame, resultado, ¿es el frame corto?).
                val pending = ArrayList<Triple<CapturedFrame, TotalCaptureResult?, Boolean>>()
                camMutex.withLock {
                    when {
                        flashNeeded -> {
                            kind = "Flash"
                            controller.captureWithFlash { f, r, _ -> pending += Triple(f, r, false); progress(1f) }
                        }
                        s.mode == CaptureMode.NIGHT -> {
                            kind = "Noche"
                            // Son muchos frames largos: se fusionan según llegan para no acumularlos en memoria.
                            processMutex.withLock {
                                controller.frames.setPaused(true)
                                try {
                                    val (reqs, t) = controller.nightRequests(NIGHT_FRAMES)
                                    controller.capture(reqs, 3_000 + (t ?: 0L) / 1_000_000L * 3) { f, r, i ->
                                        val isRef = j.framesAdded == 0
                                        if (j.add(f, r) && isRef) { refResult = r; saveDngAsync(s, caps, f, r, rotation) }
                                        progress((i + 1f) / NIGHT_FRAMES)
                                    }
                                } finally { controller.frames.setPaused(false) }
                            }
                        }
                        else -> {
                            kind = if (s.mode == CaptureMode.PORTRAIT) "Retrato" else "Foto"
                            val latest = if (info.zsl) controller.frames.takeLatest(photoFrames) else emptyList()
                            if (latest.size >= 2) {
                                kind += " ZSL"
                                val shutterUs = latest.first().timestamp / 1000
                                val lr = controller.liveRecorder
                                if (lr != null && s.mode == CaptureMode.PHOTO && s.livePhoto) {
                                    val file = File(getApplication<Application>().cacheDir, "live_${System.currentTimeMillis()}.mp4")
                                    liveClip = async(Dispatchers.IO) { runCatching { lr.export(shutterUs, file, rotation) }.getOrNull() }
                                }
                                for (f in sharpestFirst(latest)) pending += Triple(f, controller.frames.result(f.timestamp), false)
                            } else {
                                controller.capture(controller.burstRequests(photoFrames), 3_000) { f, r, i ->
                                    pending += Triple(f, r, false)
                                    progress((i + 1f) / (photoFrames + 1))
                                }
                            }
                            // Smart HDR: un frame subexpuesto para las altas luces.
                            if (s.smartHdr) {
                                controller.shortExposureRequest(SHORT_RATIO)?.let { req ->
                                    runCatching {
                                        controller.capture(listOf(req), 2_000) { f, r, _ -> pending += Triple(f, r, true) }
                                    }.onFailure { Log.w(TAG, "Frame corto", it) }
                                }
                            }
                            progress(1f)
                        }
                    }
                }
                // La cámara ya está libre: se puede disparar otra vez mientras esta foto se procesa.
                val count = processingCount.incrementAndGet()
                counted = true
                _state.update { it.copy(capturing = false, processing = count) }

                val params = DevelopParams(
                    style = s.style, smartHdr = s.smartHdr,
                    sharpen = when { kind == "Noche" -> 0.5f; kind == "Flash" -> 0.6f; else -> 0.7f },
                    exposure = if (kind == "Noche") 1.15f else 1f,
                    // En retrato no se guarda mapa HDR: quedaría nítido sobre un fondo ya desenfocado.
                    ultraHdr = caps.ultraHdr && s.mode != CaptureMode.PORTRAIT,
                )
                // El procesado va de uno en uno (usa mucha memoria); las fotos siguientes esperan su turno.
                val bmp = processMutex.withLock {
                    for ((f, r, isShort) in pending) {
                        val isRef = !isShort && j.framesAdded == 0
                        if (j.add(f, r, isShort = isShort, ratio = SHORT_RATIO) && isRef) { refResult = r; saveDngAsync(s, caps, f, r, rotation) }
                    }
                    pending.clear()
                    val full = j.develop(params)
                    val developed = if (j.zoomCropped) PhotoJob.upscaleIfSmall(full) else full
                    j.close(); job = null
                    if (s.mode == CaptureMode.PORTRAIT) {
                        val ok = runCatching { Portrait.apply(developed, rotation) }.getOrElse { Log.w(TAG, "Retrato", it); false }
                        if (!ok) toast("No se detectó a ninguna persona: foto sin desenfoque")
                    }
                    developed
                }
                val clip = liveClip?.await()
                val r = refResult
                val exif = ExifWriter.Info(
                    orientation = ExifWriter.degreesToOrientation(rotation),
                    make = Build.MANUFACTURER, model = Build.MODEL,
                    dateTime = MediaSaver.exifDate(Date()),
                    exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    iso = r?.get(CaptureResult.SENSOR_SENSITIVITY),
                    focalLengthMm = r?.get(CaptureResult.LENS_FOCAL_LENGTH),
                    aperture = r?.get(CaptureResult.LENS_APERTURE),
                    description = "C Camera · $kind · ${s.style.label}" + (if (s.smartHdr) " · Smart HDR" else "") +
                        (if (controller.photoInfo?.raw == true) " · RAW" else ""),
                )
                val uri = withContext(Dispatchers.IO) { MediaSaver.savePhoto(getApplication<Application>(), bmp, exif, clip) }
                val thumb = thumbnail(bmp, rotation)
                bmp.recycle()
                Log.i(TAG, "Foto ($kind) en ${System.currentTimeMillis() - t0} ms")
                _state.update {
                    it.copy(processing = processingCount.decrementAndGet(), thumbnail = thumb, lastUri = uri,
                        lastIsVideo = false, lastLiveClip = clip?.first)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Fallo en la captura", e)
                job?.close()
                val busy = if (counted) processingCount.decrementAndGet().coerceAtLeast(0) else processingCount.get()
                _state.update {
                    it.copy(
                        capturing = false, processing = busy,
                        message = if (e is OutOfMemoryError) "Memoria insuficiente para procesar la foto" else "No se pudo hacer la foto: ${e.message}",
                    )
                }
            }
        }
    }

    /** Si está activado, guarda en segundo plano el RAW de referencia como DNG. */
    private fun saveDngAsync(s: CameraUiState, caps: CameraCapabilities, frame: CapturedFrame, result: TotalCaptureResult?, rotation: Int) {
        if (!s.saveDng || frame !is CapturedFrame.Raw || result == null) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val uri = MediaSaver.saveDng(app, caps.characteristicsFor(result), result, frame, ExifWriter.degreesToOrientation(rotation))
            if (uri == null) Log.w(TAG, "No se pudo guardar el DNG")
        }
    }

    private fun progress(p: Float) = _state.update { it.copy(captureProgress = p) }

    private fun thumbnail(src: Bitmap, rotation: Int): Bitmap {
        val scale = 240f / maxOf(src.width, src.height)
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ---------------- Vídeo ----------------

    fun toggleRecording() {
        val app = getApplication<Application>()
        if (_state.value.recording) {
            val rec = recorder ?: return
            recorder = null
            _state.update { it.copy(recording = false) }
            viewModelScope.launch(Dispatchers.Default) {
                val ok = rec.stop()
                recordingPfd?.close(); recordingPfd = null
                recordingUri?.let { MediaSaver.finishVideo(app, it, ok) }
                if (ok) _state.update { it.copy(lastUri = recordingUri, lastIsVideo = true, thumbnail = null) }
                else toast("No se pudo guardar el vídeo")
                recordingUri = null
                camMutex.withLock { texture?.let { t -> runCatching { controller.startVideoPreview(t) } } }
            }
            return
        }
        val t = texture ?: return
        if (!controller.isOpen) return
        viewModelScope.launch {
            camMutex.withLock {
                try {
                    val caps = controller.caps
                    val s = _state.value
                    val size = caps.videoSize(s.video4k)
                    val fps = if (s.video60 && caps.supportsFps(size, 60)) 60 else 30
                    val audio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val (uri, pfd) = MediaSaver.createVideo(app)
                    recordingUri = uri; recordingPfd = pfd
                    val rec = VideoRecorder(app, size, fps, caps.hevc, audio, controller.jpegOrientation(deviceRotation), pfd)
                    controller.startVideoSession(t, rec)
                    if (s.flash == FlashMode.ON) controller.setTorch(true)
                    rec.start()
                    recorder = rec
                    _state.update { it.copy(recording = true, recordStartMs = System.currentTimeMillis()) }
                } catch (e: Exception) {
                    Log.e(TAG, "Grabación", e)
                    recordingPfd?.close(); recordingPfd = null
                    recordingUri?.let { MediaSaver.finishVideo(app, it, false) }; recordingUri = null
                    toast("No se pudo grabar: ${e.message}")
                    runCatching { controller.startVideoPreview(t) }
                }
            }
        }
    }

    // ---------------- Instagram ----------------

    /** [anchor]: qué parte se conserva al recortar (0 = arriba/izquierda, 0.5 = centro, 1 = abajo/derecha). */
    fun exportInstagram(format: InstagramFormat, anchor: Float = 0.5f) {
        val uri = _state.value.viewer ?: _state.value.lastUri ?: return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val out = InstagramExporter.export(getApplication<Application>(), uri, format, anchor)
                InstagramExporter.share(getApplication<Application>(), out)
            } catch (e: Throwable) {
                Log.e(TAG, "Exportar", e)
                toast("No se pudo preparar la foto: ${e.message}")
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun share() {
        val s = _state.value
        val uri = s.viewer ?: s.lastUri ?: return
        InstagramExporter.shareGeneric(getApplication<Application>(), uri, if (s.lastIsVideo && s.viewer == null) "video/mp4" else "image/jpeg")
    }

    override fun onCleared() {
        recorder?.stop()
        controller.release()
        runCatching { qrScanner.close() }
    }

    companion object {
        private const val TAG = "CCamera"
        private const val NIGHT_FRAMES = 8
        private const val SHORT_RATIO = 6f
    }
}
