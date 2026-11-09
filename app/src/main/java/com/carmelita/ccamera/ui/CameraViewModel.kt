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
import com.carmelita.ccamera.camera.ProSettings
import com.carmelita.ccamera.camera.VideoRecorder
import com.carmelita.ccamera.camera.ZoomStop
import com.carmelita.ccamera.processing.DevelopParams
import com.carmelita.ccamera.processing.ImageOps
import com.carmelita.ccamera.processing.PanoramaStitcher
import com.carmelita.ccamera.processing.PhotoAspect
import com.carmelita.ccamera.processing.PhotoJob
import com.carmelita.ccamera.processing.PhotoStyle
import com.carmelita.ccamera.processing.Portrait
import com.carmelita.ccamera.qr.QrActions
import com.carmelita.ccamera.qr.QrContent
import com.carmelita.ccamera.qr.QrScanner
import com.carmelita.ccamera.share.InstagramExporter
import com.carmelita.ccamera.share.InstagramFormat
import com.carmelita.ccamera.storage.ExifWriter
import com.carmelita.ccamera.storage.LocationHelper
import com.carmelita.ccamera.storage.MediaSaver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Modos en el orden del selector (como en el iPhone, más Noche y Pro). */
enum class CaptureMode(val label: String, val isVideo: Boolean = false) {
    TIMELAPSE("TIME-LAPSE", true),
    SLOWMO("CÁMARA LENTA", true),
    VIDEO("VÍDEO", true),
    PHOTO("FOTO"),
    PORTRAIT("RETRATO"),
    PANO("PANO"),
    NIGHT("NOCHE"),
    PRO("PRO"),
}

/** Opciones del modo Pro que la interfaz puede ofrecer en este teléfono. */
data class ProCaps(
    val manualExposure: Boolean = false,
    val isoValues: List<Int> = emptyList(),
    val shutterValues: List<Long> = emptyList(),
    val awbModes: List<Int> = emptyList(),
    val manualFocus: Boolean = false,
    val minFocusDiopters: Float = 0f,
)

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
    // Ajustes y controles nuevos
    val showSettings: Boolean = false,
    val timer: Int = 0,                 // 0, 3 o 10 s
    val countdown: Int = 0,             // segundos que faltan (0 = sin cuenta atrás)
    val grid: Boolean = false,
    val level: Boolean = false,
    val mirrorFront: Boolean = true,
    val saveLocation: Boolean = false,
    val volumeShutter: Boolean = true,
    val aspect: PhotoAspect = PhotoAspect.FOUR_THREE,
    val saveDng: Boolean = false,
    val ultraHdrOn: Boolean = true,
    val macro: Boolean = false,
    val hasMacro: Boolean = false,
    val evIndex: Int = 0,
    val evStep: Float = 0f,
    val evRange: IntRange = 0..0,
    val aeAfLocked: Boolean = false,
    val pro: ProSettings = ProSettings(),
    val proCaps: ProCaps = ProCaps(),
    val slowMoLabel: String? = null,
    val panoActive: Boolean = false,
    val panoProgress: Float = 0f,
    val panoHint: String = "",
    val bursting: Boolean = false,
    val burstCount: Int = 0,
) {
    val ev: Float get() = evIndex * evStep
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ccamera", 0)
    private val controller = CameraController(app)
    private val camMutex = Mutex()
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
            hasFront = CameraCapabilities.hasLens(controller.manager, Lens.FRONT),
            grid = prefs.getBoolean("grid", false),
            level = prefs.getBoolean("level", false),
            mirrorFront = prefs.getBoolean("mirror", true),
            saveLocation = prefs.getBoolean("location", false),
            volumeShutter = prefs.getBoolean("volume", true),
            aspect = runCatching { PhotoAspect.valueOf(prefs.getString("aspect", null) ?: "") }.getOrDefault(PhotoAspect.FOUR_THREE),
            saveDng = prefs.getBoolean("dng", false),
            ultraHdrOn = prefs.getBoolean("ultrahdr", true),
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

    @Volatile var deviceRotation = 0
    /** La UI registra aquí cómo obtener un fotograma pequeño de la vista previa (para el QR). */
    @Volatile var previewFrameProvider: (() -> Bitmap?)? = null
    /** Fotograma de la vista previa al tamaño pedido, en vertical (panorámica). Hilo principal. */
    @Volatile var previewBitmapProvider: ((Int, Int) -> Bitmap?)? = null

    private var timerJob: Job? = null
    private var burstJob: Job? = null
    private var panoJob: Job? = null
    @Volatile private var panoStop = false

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
        if (_state.value.recording) toggleRecording()
        stopBurst()
        panoStop = true
        timerJob?.cancel(); timerJob = null
        if (_state.value.countdown > 0) _state.update { it.copy(countdown = 0) }
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
        if (s.mode.isVideo) {
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
        controller.setFlash(if (s.mode.isVideo) FlashMode.OFF else s.flash)
        controller.setZoom(1f)
        controller.setExposureCompensation(0)
        applyModeControls(s)
        _state.update {
            it.copy(
                zoom = 1f.coerceIn(caps.zoomRange.start, caps.zoomRange.endInclusive),
                zoomRange = caps.zoomRange, zoomStops = caps.zoomStops,
                hasFlash = caps.hasFlash, ultraHdr = caps.ultraHdr,
                can4k = caps.video4k, can60 = caps.supportsFps(caps.videoSize(it.video4k), 60),
                capsSummary = caps.summary(),
                evIndex = 0, evStep = caps.evStep, evRange = caps.evRange.lower..caps.evRange.upper,
                aeAfLocked = false, hasMacro = caps.hasMacroAf,
                slowMoLabel = caps.slowMotion?.let { (sz, fps) -> "${if (sz.height >= 1080) "1080p" else "${sz.height}p"} $fps" },
                proCaps = proCaps(caps),
            )
        }
    }

    /** Pro y macro según el modo (llamar con camMutex tomado). */
    private fun applyModeControls(s: CameraUiState) {
        controller.setPro(if (s.mode == CaptureMode.PRO) s.pro else null)
        controller.setMacro(s.macro && (s.mode == CaptureMode.PHOTO || s.mode == CaptureMode.PRO))
    }

    private fun proCaps(caps: CameraCapabilities): ProCaps {
        val ir = caps.isoRange
        val er = caps.exposureRange
        val manual = caps.manualSensor && ir != null && er != null
        val isos = if (ir != null) listOf(50, 100, 200, 400, 800, 1600, 3200, 6400).filter { it in ir.lower..ir.upper } else emptyList()
        val shutters = if (er != null) listOf(
            1_000_000_000L / 4000, 1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250, 1_000_000_000L / 125,
            1_000_000_000L / 60, 1_000_000_000L / 30, 1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4,
            1_000_000_000L / 2, 1_000_000_000L,
        ).filter { it in er.lower..er.upper } else emptyList()
        val awb = listOf(
            android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
            android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        ).filter { caps.awbModes.contains(it) }
        return ProCaps(manual, isos, shutters, awb, caps.manualFocus, caps.minFocusDiopters)
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
        val st = _state.value
        val old = st.mode
        if (old == m || st.recording || st.capturing || st.panoActive || st.bursting || st.countdown > 0) return
        _state.update { it.copy(mode = m, qr = null) }
        if (old.isVideo != m.isVideo) reconfigure()
        else viewModelScope.launch { camMutex.withLock { if (controller.isOpen) applyModeControls(_state.value) } }
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
                if (_state.value.mode.isVideo) controller.setTorch(next == FlashMode.ON) else controller.setFlash(next)
            }
        }
    }

    fun toggle4k() { val v = !_state.value.video4k; prefs.edit().putBoolean("4k", v).apply(); _state.update { it.copy(video4k = v) } }
    fun toggle60() { val v = !_state.value.video60; prefs.edit().putBoolean("60fps", v).apply(); _state.update { it.copy(video60 = v) } }

    private fun pref(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun toggleGrid() { val v = !_state.value.grid; pref("grid", v); _state.update { it.copy(grid = v) } }
    fun toggleLevel() { val v = !_state.value.level; pref("level", v); _state.update { it.copy(level = v) } }
    fun toggleMirror() { val v = !_state.value.mirrorFront; pref("mirror", v); _state.update { it.copy(mirrorFront = v) } }
    fun toggleVolumeShutter() { val v = !_state.value.volumeShutter; pref("volume", v); _state.update { it.copy(volumeShutter = v) } }
    fun toggleDng() { val v = !_state.value.saveDng; pref("dng", v); _state.update { it.copy(saveDng = v) } }
    fun toggleUltraHdr() { val v = !_state.value.ultraHdrOn; pref("ultrahdr", v); _state.update { it.copy(ultraHdrOn = v) } }
    /** La UI pide el permiso de ubicación antes de activarlo. */
    fun setSaveLocation(v: Boolean) { pref("location", v); _state.update { it.copy(saveLocation = v) } }
    fun setAspect(a: PhotoAspect) { prefs.edit().putString("aspect", a.name).apply(); _state.update { it.copy(aspect = a) } }
    fun showSettings(v: Boolean) = _state.update { it.copy(showSettings = v) }

    fun cycleTimer() {
        val next = when (_state.value.timer) { 0 -> 3; 3 -> 10; else -> 0 }
        _state.update { it.copy(timer = next) }
    }

    fun toggleMacro() {
        val v = !_state.value.macro
        _state.update { it.copy(macro = v) }
        viewModelScope.launch { camMutex.withLock { if (controller.isOpen) applyModeControls(_state.value) } }
    }

    /** Compensación de exposición (deslizar en la vista previa tras tocar para enfocar). */
    fun adjustEv(deltaSteps: Float) {
        val s = _state.value
        if (s.evStep <= 0f || s.mode == CaptureMode.PRO) return
        evAccum += deltaSteps
        val whole = evAccum.toInt()
        if (whole == 0) return
        evAccum -= whole
        val idx = (s.evIndex + whole).coerceIn(s.evRange.first, s.evRange.last)
        if (idx == s.evIndex) return
        _state.update { it.copy(evIndex = idx) }
        viewModelScope.launch { camMutex.withLock { controller.setExposureCompensation(idx) } }
        focusResetJob?.cancel()
        if (!s.aeAfLocked) scheduleFocusReset()
    }
    private var evAccum = 0f

    // ---------------- Pro ----------------

    fun setProIso(iso: Int?) = updatePro { p ->
        if (iso == null) p.copy(iso = null, exposureNs = null)
        else p.copy(iso = iso, exposureNs = p.exposureNs ?: matchExposure(iso = iso)?.first)
    }

    fun setProShutter(ns: Long?) = updatePro { p ->
        if (ns == null) p.copy(iso = null, exposureNs = null)
        else p.copy(exposureNs = ns, iso = p.iso ?: matchExposure(exposureNs = ns)?.second)
    }

    fun setProWb(mode: Int) = updatePro { it.copy(awbMode = mode) }
    fun setProFocus(diopters: Float?) = updatePro { it.copy(focusDiopters = diopters) }

    /**
     * Completa el valor que falta (ISO o tiempo) para mantener la exposición que mide ahora el AE:
     * Camera2 no tiene prioridad a la velocidad ni al ISO.
     */
    private fun matchExposure(iso: Int? = null, exposureNs: Long? = null): Pair<Long, Int>? {
        val (t0, iso0) = controller.currentExposure() ?: return null
        val product = t0.toDouble() * iso0
        val caps = controller.caps
        return if (iso != null) {
            val t = (product / iso).roundToLong()
            (caps.exposureRange?.let { t.coerceIn(it.lower, it.upper) } ?: t) to iso
        } else if (exposureNs != null) {
            val i = (product / exposureNs).roundToInt()
            exposureNs to (caps.isoRange?.let { i.coerceIn(it.lower, it.upper) } ?: i)
        } else null
    }

    private fun updatePro(f: (ProSettings) -> ProSettings) {
        val p = f(_state.value.pro)
        // Exposición manual sólo con los dos valores; si falta uno, vuelve a automática.
        val fixed = if ((p.iso == null) != (p.exposureNs == null)) p.copy(iso = null, exposureNs = null) else p
        _state.update { it.copy(pro = fixed) }
        viewModelScope.launch { camMutex.withLock { if (controller.isOpen && _state.value.mode == CaptureMode.PRO) controller.setPro(fixed) } }
    }

    fun setZoom(z: Float) {
        val r = _state.value.zoomRange
        val zz = z.coerceIn(r.start, r.endInclusive)
        _state.update { it.copy(zoom = zz) }
        viewModelScope.launch { camMutex.withLock { controller.setZoom(zz) } }
    }

    /** Tocar: enfoca y mide ahí 5 s. Mantener pulsado ([lock]): bloqueo AE/AF hasta tocar otra vez. */
    fun focusAt(nx: Float, ny: Float, lock: Boolean = false) {
        if (_state.value.panoActive) return
        _state.update { it.copy(focus = FocusMark(nx, ny, System.nanoTime()), aeAfLocked = lock) }
        viewModelScope.launch { camMutex.withLock { controller.focusAt(nx, ny, lock) } }
        focusResetJob?.cancel()
        if (!lock) scheduleFocusReset()
    }

    private fun scheduleFocusReset() {
        focusResetJob = viewModelScope.launch {
            delay(5_000)
            if (_state.value.aeAfLocked) return@launch
            _state.update { it.copy(focus = null, evIndex = 0) }
            camMutex.withLock { controller.resetFocus(); controller.setExposureCompensation(0) }
        }
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
        if (s.mode.isVideo) { toggleRecording(); return }
        if (s.mode == CaptureMode.PANO) { togglePano(); return }
        if (s.bursting) return
        // Pulsar durante la cuenta atrás la cancela.
        timerJob?.let { it.cancel(); timerJob = null; _state.update { st -> st.copy(countdown = 0) }; return }
        if (s.timer > 0) {
            if (s.capturing || !controller.isOpen) return
            timerJob = viewModelScope.launch {
                for (i in s.timer downTo 1) {
                    _state.update { it.copy(countdown = i) }
                    delay(1_000)
                }
                _state.update { it.copy(countdown = 0) }
                timerJob = null
                takePhoto()
            }
            return
        }
        takePhoto()
    }

    private fun takePhoto() {
        val s = _state.value
        if (s.capturing || processingCount.get() >= 2 || !controller.isOpen) return
        val rotation = controller.jpegOrientation(deviceRotation)
        _state.update { it.copy(capturing = true, captureProgress = 0f, shutterFlash = System.nanoTime(), qr = null) }

        viewModelScope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            var job: PhotoJob? = null
            var liveClip: Deferred<Pair<File, Long>?>? = null
            var refResult: TotalCaptureResult? = null
            var firstRaw: CapturedFrame.Raw? = null
            var kind = ""
            try {
                val caps = controller.caps
                val info = controller.photoInfo ?: error("Sesión no lista")
                val j = PhotoJob(caps, info.raw)
                job = j
                val flashNeeded = caps.hasFlash && s.mode != CaptureMode.PRO &&
                    (s.flash == FlashMode.ON || (s.flash == FlashMode.AUTO && controller.flashRequired()))
                fun keepRaw(f: CapturedFrame, i: Int) { if (i == 0 && f is CapturedFrame.Raw) firstRaw = f }

                camMutex.withLock {
                    when {
                        flashNeeded -> {
                            kind = "Flash"
                            controller.captureWithFlash { f, r, _ -> keepRaw(f, 0); j.add(f, r); refResult = r; progress(1f) }
                        }
                        s.mode == CaptureMode.NIGHT -> {
                            kind = "Noche"
                            controller.frames.setPaused(true)
                            try {
                                val (reqs, t) = controller.nightRequests(NIGHT_FRAMES)
                                controller.capture(reqs, 3_000 + (t ?: 0L) / 1_000_000L * 3) { f, r, i ->
                                    if (i == 0) refResult = r
                                    keepRaw(f, i)
                                    j.add(f, r); progress((i + 1f) / NIGHT_FRAMES)
                                }
                            } finally { controller.frames.setPaused(false) }
                        }
                        else -> {
                            kind = when (s.mode) { CaptureMode.PORTRAIT -> "Retrato"; CaptureMode.PRO -> "Pro"; else -> "Foto" }
                            val zsl = if (info.zsl) controller.frames.takeLatest(PHOTO_FRAMES) else emptyList()
                            if (zsl.size >= 2) {
                                kind += " ZSL"
                                val shutterUs = zsl.first().timestamp / 1000
                                val lr = controller.liveRecorder
                                if (lr != null && s.mode == CaptureMode.PHOTO && s.livePhoto) {
                                    val file = File(getApplication<Application>().cacheDir, "live_${System.currentTimeMillis()}.mp4")
                                    liveClip = async(Dispatchers.IO) { runCatching { lr.export(shutterUs, file, rotation) }.getOrNull() }
                                }
                                zsl.forEachIndexed { i, f ->
                                    val r = controller.frames.result(f.timestamp)
                                    if (i == 0) refResult = r
                                    keepRaw(f, i)
                                    j.add(f, r); progress((i + 1f) / (zsl.size + 1))
                                }
                            } else {
                                controller.capture(controller.burstRequests(PHOTO_FRAMES), 3_000) { f, r, i ->
                                    if (i == 0) refResult = r
                                    keepRaw(f, i)
                                    j.add(f, r); progress((i + 1f) / (PHOTO_FRAMES + 1))
                                }
                            }
                            // Smart HDR: un frame subexpuesto para las altas luces.
                            if (s.smartHdr) {
                                controller.shortExposureRequest(SHORT_RATIO)?.let { req ->
                                    runCatching {
                                        controller.capture(listOf(req), 2_000) { f, _, _ -> j.add(f, null, isShort = true, ratio = SHORT_RATIO) }
                                    }.onFailure { Log.w(TAG, "Frame corto", it) }
                                }
                            }
                            progress(1f)
                        }
                    }
                }
                val count = processingCount.incrementAndGet()
                _state.update { it.copy(capturing = false, processing = count) }

                val manualExp = s.mode == CaptureMode.PRO && s.pro.iso != null && s.pro.exposureNs != null
                val params = DevelopParams(
                    style = s.style, smartHdr = s.smartHdr,
                    sharpen = when { kind == "Noche" -> 0.5f; kind == "Flash" -> 0.6f; else -> 0.7f },
                    exposure = if (kind == "Noche") 1.15f else 1f,
                    ultraHdr = caps.ultraHdr && s.ultraHdrOn,
                    autoBrightness = !manualExp,
                )
                val bmp = j.develop(params)
                j.close(); job = null
                if (s.mode == CaptureMode.PORTRAIT) {
                    val ok = runCatching { Portrait.apply(bmp, rotation) }.getOrElse { Log.w(TAG, "Retrato", it); false }
                    if (!ok) toast("No se detectó a ninguna persona: foto sin desenfoque")
                }
                val clip = liveClip?.await()
                saveFinished(bmp, rotation, refResult, kind, s, clip, firstRaw.takeIf { s.saveDng })
                Log.i(TAG, "Foto ($kind) en ${System.currentTimeMillis() - t0} ms")
                _state.update { it.copy(processing = processingCount.decrementAndGet()) }
            } catch (e: Throwable) {
                Log.e(TAG, "Fallo en la captura", e)
                job?.close()
                val busy = if (_state.value.capturing) processingCount.get() else processingCount.decrementAndGet().coerceAtLeast(0)
                _state.update {
                    it.copy(
                        capturing = false, processing = busy,
                        message = if (e is OutOfMemoryError) "Memoria insuficiente para procesar la foto" else "No se pudo hacer la foto: ${e.message}",
                    )
                }
            }
        }
    }


    /**
     * Pasos comunes al guardar una foto revelada: espejo (frontal), recorte de proporción, EXIF
     * (con ubicación si está activada), Foto en vivo, DNG opcional y miniatura. Recicla [bmp].
     */
    private suspend fun saveFinished(
        bmp: Bitmap, rotation: Int, r: TotalCaptureResult?, kind: String, s: CameraUiState,
        clip: Pair<File, Long>?, raw: CapturedFrame.Raw?,
    ) {
        val app = getApplication<Application>()
        var out = bmp
        if (s.lens == Lens.FRONT && s.mirrorFront) out = ImageOps.mirror(out, rotation).also { if (it !== out) out.recycle() }
        val cropped = ImageOps.cropToAspect(out, s.aspect)
        if (cropped !== out) { out.recycle(); out = cropped }
        val date = Date()
        val gps = if (s.saveLocation) runCatching { LocationHelper.lastKnown(app) }.getOrNull() else null
        val orientation = ExifWriter.degreesToOrientation(rotation)
        val exif = ExifWriter.Info(
            orientation = orientation,
            make = Build.MANUFACTURER, model = Build.MODEL,
            dateTime = MediaSaver.exifDate(date),
            exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r?.get(CaptureResult.SENSOR_SENSITIVITY),
            focalLengthMm = r?.get(CaptureResult.LENS_FOCAL_LENGTH),
            aperture = r?.get(CaptureResult.LENS_APERTURE),
            description = "C Camera · $kind · ${s.style.label}" + (if (s.smartHdr) " · Smart HDR" else "") +
                (if (controller.photoInfo?.raw == true) " · RAW" else "") +
                (if (s.aspect != PhotoAspect.FOUR_THREE) " · ${s.aspect.label}" else ""),
            gps = gps,
        )
        // Con otra proporción el vídeo de la Foto en vivo (4:3) ya no encaja: se guarda sin él.
        val liveClip = clip.takeIf { s.aspect == PhotoAspect.FOUR_THREE && !(s.lens == Lens.FRONT && s.mirrorFront) }
        val uri = withContext(Dispatchers.IO) { MediaSaver.savePhoto(app, out, exif, liveClip, date) }
        if (raw != null && r != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaSaver.saveDng(app, controller.caps.chars, r, raw.data, raw.rowStride, raw.width, raw.height, orientation, date)
                }.onFailure { Log.w(TAG, "DNG", it); toast("No se pudo guardar el DNG") }
            }
        }
        val thumb = thumbnail(out, rotation)
        out.recycle()
        _state.update { it.copy(thumbnail = thumb, lastUri = uri, lastIsVideo = false, lastLiveClip = liveClip?.first) }
    }

    // ---------------- Ráfaga (mantener pulsado el disparador) ----------------

    fun startBurst() {
        val s = _state.value
        if ((s.mode != CaptureMode.PHOTO && s.mode != CaptureMode.PRO) || s.capturing || s.bursting ||
            burstJob != null || timerJob != null || !controller.isOpen
        ) return
        val info = controller.photoInfo ?: return
        val caps = controller.caps
        val rotation = controller.jpegOrientation(deviceRotation)
        val queue = Channel<Pair<CapturedFrame, TotalCaptureResult?>>(capacity = 2)
        _state.update { it.copy(bursting = true, burstCount = 0, qr = null) }
        // Revelado en serie, con un solo frame cada foto (rápido y sin fusión).
        viewModelScope.launch(Dispatchers.Default) {
            for ((f, r) in queue) {
                val n = processingCount.incrementAndGet()
                _state.update { it.copy(processing = n) }
                try {
                    PhotoJob(caps, info.raw).use { j ->
                        j.add(f, r)
                        val bmp = j.develop(DevelopParams(style = s.style, smartHdr = false, sharpen = 0.6f, ultraHdr = false))
                        saveFinished(bmp, rotation, r, "Ráfaga", s, null, null)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Ráfaga", e)
                } finally {
                    _state.update { it.copy(processing = processingCount.decrementAndGet().coerceAtLeast(0)) }
                }
            }
        }
        burstJob = viewModelScope.launch(Dispatchers.Default) {
            var n = 0
            try {
                while (isActive && n < BURST_MAX) {
                    val got: Pair<CapturedFrame, TotalCaptureResult?>? = camMutex.withLock {
                        if (info.zsl) {
                            controller.frames.takeLatest(1).firstOrNull()?.let { it to controller.frames.result(it.timestamp) }
                        } else {
                            var one: Pair<CapturedFrame, TotalCaptureResult?>? = null
                            runCatching { controller.capture(controller.burstRequests(1), 2_000) { f, r, _ -> one = f to r } }
                            one
                        }
                    }
                    if (got != null && queue.trySend(got).isSuccess) {
                        n++
                        _state.update { it.copy(burstCount = n, shutterFlash = System.nanoTime()) }
                    }
                    delay(BURST_INTERVAL_MS)
                }
            } finally {
                queue.close()
            }
        }
    }

    fun stopBurst() {
        burstJob?.cancel()
        burstJob = null
        if (_state.value.bursting) _state.update { it.copy(bursting = false) }
    }

    // ---------------- Panorámica ----------------

    private fun togglePano() {
        if (panoJob != null) { panoStop = true; return }
        if (!controller.isOpen || _state.value.capturing) return
        panoStop = false
        panoJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val w = PANO_W
            val h = PANO_H
            val st = PanoramaStitcher(w, h)
            val px = IntArray(w * h)
            camMutex.withLock { controller.setExposureHold(true) }
            _state.update { it.copy(panoActive = true, panoProgress = 0f, panoHint = HINT_PANO, qr = null) }
            val t0 = System.currentTimeMillis()
            try {
                while (isActive && !panoStop && !st.full && System.currentTimeMillis() - t0 < PANO_MAX_MS) {
                    val bmp = runCatching { previewBitmapProvider?.invoke(w, h) }.getOrNull()
                    if (bmp != null) {
                        val ok = bmp.width == w && bmp.height == h
                        if (ok) bmp.getPixels(px, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        if (ok) {
                            val step = withContext(Dispatchers.Default) { st.add(px) }
                            val hint = if (step == PanoramaStitcher.Step.LOST) "Más despacio" else HINT_PANO
                            _state.update { it.copy(panoProgress = st.progress, panoHint = hint) }
                        }
                    }
                    delay(70)
                }
                val res = withContext(Dispatchers.Default) { st.result() }
                if (res == null) {
                    toast("Panorámica demasiado corta: gira el teléfono hacia la derecha")
                } else {
                    val n = processingCount.incrementAndGet()
                    _state.update { it.copy(processing = n) }
                    try {
                        val (pixels, pw, ph) = res
                        val bmp = withContext(Dispatchers.Default) { Bitmap.createBitmap(pixels, pw, ph, Bitmap.Config.ARGB_8888) }
                        val s = _state.value
                        val date = Date()
                        val gps = if (s.saveLocation) runCatching { LocationHelper.lastKnown(app) }.getOrNull() else null
                        val exif = ExifWriter.Info(
                            orientation = 1, make = Build.MANUFACTURER, model = Build.MODEL,
                            dateTime = MediaSaver.exifDate(date), description = "C Camera · Panorámica", gps = gps,
                        )
                        val uri = withContext(Dispatchers.IO) { MediaSaver.savePhoto(app, bmp, exif, null, date) }
                        val thumb = thumbnail(bmp, 0)
                        bmp.recycle()
                        _state.update { it.copy(thumbnail = thumb, lastUri = uri, lastIsVideo = false, lastLiveClip = null) }
                    } finally {
                        _state.update { it.copy(processing = processingCount.decrementAndGet().coerceAtLeast(0)) }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Panorámica", e)
                toast(if (e is OutOfMemoryError) "Memoria insuficiente para la panorámica" else "No se pudo hacer la panorámica: ${e.message}")
            } finally {
                camMutex.withLock { controller.setExposureHold(false) }
                panoJob = null
                panoStop = false
                _state.update { it.copy(panoActive = false, panoProgress = 0f, panoHint = "") }
            }
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
                    val audio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val rotation = controller.jpegOrientation(deviceRotation)
                    val slow = if (s.mode == CaptureMode.SLOWMO) (caps.slowMotion ?: error("Este teléfono no tiene cámara lenta")) else null
                    val (uri, pfd) = MediaSaver.createVideo(app)
                    recordingUri = uri; recordingPfd = pfd
                    val rec = when {
                        slow != null -> VideoRecorder(app, slow.first, VideoRecorder.PLAYBACK_FPS, caps.hevc, false, rotation, pfd,
                            captureRate = slow.second.toDouble())
                        s.mode == CaptureMode.TIMELAPSE -> VideoRecorder(app, caps.videoSize(false), VideoRecorder.PLAYBACK_FPS, caps.hevc,
                            false, rotation, pfd, captureRate = TIMELAPSE_CAPTURE_FPS)
                        else -> {
                            val size = caps.videoSize(s.video4k)
                            val fps = if (s.video60 && caps.supportsFps(size, 60)) 60 else 30
                            VideoRecorder(app, size, fps, caps.hevc, audio, rotation, pfd)
                        }
                    }
                    if (slow != null) controller.startHighSpeedSession(t, rec, slow.second)
                    else controller.startVideoSession(t, rec)
                    if (s.flash == FlashMode.ON && slow == null) controller.setTorch(true)
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

    fun exportInstagram(format: InstagramFormat) {
        val uri = _state.value.viewer ?: _state.value.lastUri ?: return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val out = InstagramExporter.export(getApplication<Application>(), uri, format)
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
        private const val PHOTO_FRAMES = 6
        private const val NIGHT_FRAMES = 8
        private const val SHORT_RATIO = 6f
        private const val BURST_MAX = 50
        private const val BURST_INTERVAL_MS = 220L
        private const val PANO_W = 720
        private const val PANO_H = 960
        private const val PANO_MAX_MS = 30_000L
        private const val HINT_PANO = "Gira el teléfono despacio hacia la derecha"
        /** Time-lapse: 2 fotogramas por segundo reproducidos a 30 → 15x más rápido. */
        private const val TIMELAPSE_CAPTURE_FPS = 2.0
    }
}
