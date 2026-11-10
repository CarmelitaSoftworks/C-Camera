package com.carmelita.ccamera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.CamcorderProfile
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Lens { BACK, FRONT }

/** Un botón de zoom (0.5x, 1x, 2x, 3x...) que corresponde a una lente física o a un recorte. */
data class ZoomStop(val ratio: Float, val optical: Boolean)

/**
 * Detección automática de lo que soporta cada cámara del teléfono.
 * La interfaz y el pipeline se adaptan a esto (RAW o YUV, ZSL, noche, flash, vídeo, etc.).
 */
class CameraCapabilities private constructor(
    val id: String,
    val lens: Lens,
    val chars: CameraCharacteristics,
    private val manager: CameraManager,
) {
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: error("La cámara $id no expone configuraciones de stream")
    private val caps: IntArray = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val hardwareLevel: Int = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    val sensorOrientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val activeArray: Rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

    // ---- Fotografía ----
    val manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val rawCapability = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    val rawSize: Size? = if (rawCapability) map.getOutputSizes(ImageFormat.RAW_SENSOR)
        ?.maxByOrNull { it.width.toLong() * it.height } else null
    val cfa: Int = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
    /** Sensores monocromo o con CFA no Bayer (RGB, NIR...) no se procesan como RAW. */
    val supportsRaw: Boolean = rawSize != null && cfa in 0..3
    val whiteLevel: Int = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
    val exposureRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

    fun yuvSize(maxPixels: Long): Size {
        val all = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width % 2 == 0 && it.height % 2 == 0 }
        return all.filter { is43(it) && it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width.toLong() * it.height }
            ?: all.filter { it.width.toLong() * it.height <= maxPixels }.maxByOrNull { it.width.toLong() * it.height }
            ?: all.minByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1440)
    }

    /** ¿Puede este formato/tamaño ir en el stream continuo a ~30 fps (para ZSL)? */
    fun canStream(format: Int, size: Size): Boolean {
        val min = runCatching { map.getOutputMinFrameDuration(format, size) }.getOrDefault(Long.MAX_VALUE)
        val stall = runCatching { map.getOutputStallDuration(format, size) }.getOrDefault(Long.MAX_VALUE)
        return min in 1..34_000_000L && stall == 0L
    }

    fun previewSize(aspect: Float, maxWidth: Int = 1920): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)
        return all.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= maxWidth }
            .maxByOrNull { it.width * it.height }
            ?: all.minByOrNull { abs(it.width.toFloat() / it.height - aspect) }!!
    }

    // ---- Óptica y enfoque ----
    val afModes: IntArray = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val hasAutofocus = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
    val hasContinuousAf = afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    val videoStabilization = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
    val previewStabilization = Build.VERSION.SDK_INT >= 33 &&
        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true
    val nrModes: IntArray = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
    val edgeModes: IntArray = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
    val faceDetection = (chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0) > 0
    val lensShadingMap = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true

    // ---- Exposición, macro y controles manuales ----
    /** Rango de compensación de exposición en pasos (p. ej. -12..12) y tamaño del paso en EV. */
    val evRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStep: Float = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    val hasEv: Boolean = evRange.upper > evRange.lower && evStep > 0f
    val hasMacroAf = afModes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO)
    /** Distancia mínima de enfoque en dioptrías (0 = foco fijo). */
    val minFocusDiopters: Float = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val manualFocus: Boolean = minFocusDiopters > 0f &&
        caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val awbModes: IntArray = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val aeLockAvailable: Boolean = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true

    // ---- Estabilización ----
    /** ¿Los timestamps de los fotogramas usan el mismo reloj que los sensores (giroscopio)? */
    val realtimeTimestamps: Boolean = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    val hasGyro: Boolean = gyroPresent

    /** Focal en píxeles para una imagen de [width] px de ancho (el ancho cubre todo el sensor). */
    fun focalPx(width: Int): Float {
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4.3f
        val sw = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 6.4f
        return f / sw * width
    }

    /** Estabilización de vídeo que ofrece el propio teléfono (electrónica, en el ISP). */
    val halVideoStabilization: Boolean get() = videoStabilization || previewStabilization

    // ---- Zoom y lentes ----
    val logicalMultiCamera = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    val usesZoomRatio: Boolean = Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
    val zoomRange: ClosedFloatingPointRange<Float> =
        (if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper } else null)
            ?: 1f..(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

    /** Botones de zoom: una parada por cada lente física detectada (+ 2x de recorte si no hay tele). */
    val zoomStops: List<ZoomStop> = buildZoomStops()

    private fun buildZoomStops(): List<ZoomStop> {
        val stops = mutableListOf(ZoomStop(1f, true))
        val mainFocal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        if (logicalMultiCamera && Build.VERSION.SDK_INT >= 28 && mainFocal != null && mainFocal > 0) {
            val mainSensorW = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
            for (pid in chars.physicalCameraIds) {
                val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: continue
                val w = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
                if (w <= 0f || mainSensorW <= 0f) continue
                // Campo de visión equivalente respecto a la lente principal (focal / ancho de sensor).
                val ratio = (f / w) / (mainFocal / mainSensorW)
                val r = ((ratio * 10).roundToInt() / 10f).coerceIn(zoomRange.start, zoomRange.endInclusive)
                if (stops.none { abs(it.ratio - r) < 0.15f }) stops += ZoomStop(r, true)
            }
        }
        if (zoomRange.start < 0.95f && stops.none { it.ratio < 0.95f }) stops += ZoomStop(zoomRange.start, true)
        if (zoomRange.endInclusive >= 2f && stops.none { it.ratio > 1.5f }) stops += ZoomStop(2f, false)
        return stops.sortedBy { it.ratio }
    }

    /** Tamaño del clip de Fotos en vivo: 4:3 hasta 1440x1080, que la cámara pueda dar al codificador. */
    fun liveVideoSize(): Size? = map.getOutputSizes(android.media.MediaCodec::class.java)
        ?.filter { is43(it) && it.width <= 1440 && it.width >= 960 }
        ?.maxByOrNull { it.width * it.height }

    // ---- Vídeo ----
    private val recorderSizes: List<Size> = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
    val video4k: Boolean = recorderSizes.any { it.width == 3840 && it.height == 2160 } &&
        runCatching { CamcorderProfile.hasProfile(id.toInt(), CamcorderProfile.QUALITY_2160P) }.getOrDefault(true)

    fun videoSize(want4k: Boolean): Size =
        if (want4k && video4k) Size(3840, 2160)
        else recorderSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: recorderSizes.filter { abs(it.width * 9 - it.height * 16) < 32 }.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)

    fun supportsFps(size: Size, fps: Int): Boolean {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return fps <= 30
        val minDur = runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrDefault(Long.MAX_VALUE)
        return ranges.any { it.upper >= fps && it.lower <= fps } && minDur <= 1_000_000_000L / fps + 500_000L
    }

    /**
     * Cámara lenta: sesión de alta velocidad (120/240 fps). Devuelve tamaño y fps elegidos
     * (prefiere 1080p a 240, luego 1080p a 120, luego 720p) o null si el teléfono no la tiene.
     */
    val slowMotion: Pair<Size, Int>? = run {
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) return@run null
        val sizes = runCatching { map.highSpeedVideoSizes.toList() }.getOrDefault(emptyList())
        fun fixedFps(s: Size): List<Int> = runCatching {
            map.getHighSpeedVideoFpsRangesFor(s).filter { it.lower == it.upper }.map { it.upper }
        }.getOrDefault(emptyList())
        val ordered = sizes.sortedWith(compareByDescending<Size> { it.width == 1920 && it.height == 1080 }
            .thenByDescending { it.width == 1280 && it.height == 720 }
            .thenByDescending { it.width * it.height })
        for (fps in listOf(240, 120)) for (s in ordered) if (fps in fixedFps(s)) return@run s to fps
        null
    }

    val hevc: Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
    }

    val hdrVideo: Boolean = Build.VERSION.SDK_INT >= 33 &&
        chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true

    // ---- Procesado del teléfono (Camera2 Extensions) ----
    /** Modos de procesado del fabricante disponibles para otras apps en esta cámara. */
    val extensions: List<Int> = if (Build.VERSION.SDK_INT >= 31) {
        runCatching { manager.getCameraExtensionCharacteristics(id).supportedExtensions.toList() }.getOrDefault(emptyList())
    } else emptyList()

    // ---- Salida ----
    val ultraHdr: Boolean = Build.VERSION.SDK_INT >= 34

    fun hardwareLevelName(): String = when (hardwareLevel) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNA"
        else -> "?"
    }

    /** Resumen legible para la pantalla "Info de cámara". */
    fun summary(): List<Pair<String, String>> {
        fun yes(b: Boolean) = if (b) "Sí" else "No"
        val rs = rawSize
        return listOf(
            "Cámara" to "${if (lens == Lens.BACK) "Trasera" else "Frontal"} (id $id)",
            "Nivel Camera2" to hardwareLevelName(),
            "RAW" to if (supportsRaw && rs != null) "Sí · ${rs.width}×${rs.height} (${"%.1f".format(rs.width * rs.height / 1e6)} MP)" else "No (se usa YUV)",
            "ZSL con RAW" to yes(supportsRaw && rs != null && canStream(ImageFormat.RAW_SENSOR, rs)),
            "Control manual (exposición/ISO)" to yes(manualSensor),
            "Rango ISO" to (isoRange?.let { "${it.lower}–${it.upper}" } ?: "?"),
            "Exposición máxima" to (exposureRange?.let { "%.2f s".format(it.upper / 1e9) } ?: "?"),
            "Lentes / zoom" to zoomStops.joinToString("  ") { (if (it.optical) "" else "~") + fmtZoom(it.ratio) + "×" },
            "Multicámara lógica" to yes(logicalMultiCamera),
            "Autoenfoque" to yes(hasAutofocus),
            "Flash" to yes(hasFlash),
            "Estabilización óptica (OIS)" to yes(ois),
            "Estabilización electrónica del teléfono" to yes(halVideoStabilization),
            "Giroscopio (estabilizador de C Camera)" to yes(hasGyro),
            "Vídeo 4K" to yes(video4k),
            "Vídeo 60 fps" to yes(supportsFps(videoSize(false), 60)),
            "HEVC" to yes(hevc),
            "Vídeo HDR (HLG 10 bits)" to yes(hdrVideo),
            "Ultra HDR (fotos)" to yes(ultraHdr),
            "Cámara lenta" to (slowMotion?.let { "${it.first.width}×${it.first.height} a ${it.second} fps" } ?: "No"),
            "Enfoque macro" to yes(hasMacroAf),
            "Enfoque manual" to yes(manualFocus),
            "Compensación de exposición" to if (hasEv) "±%.1f EV".format(evRange.upper * evStep) else "No",
            "Procesado del teléfono (Camera2 Extensions)" to
                if (extensions.isEmpty()) "No" else extensions.joinToString(", ") { extensionName(it) },
            "Detección de caras" to yes(faceDetection),
            "Mapa de sombreado de lente" to yes(lensShadingMap),
        )
    }

    companion object {
        fun is43(s: Size) = abs(s.width * 3 - s.height * 4) <= s.width / 100

        // Valores de CameraExtensionCharacteristics.EXTENSION_* (API 31).
        const val EXT_AUTOMATIC = 0
        const val EXT_FACE_RETOUCH = 1
        const val EXT_BOKEH = 2
        const val EXT_HDR = 3
        const val EXT_NIGHT = 4

        fun extensionName(e: Int): String = when (e) {
            EXT_AUTOMATIC -> "Automático"
            EXT_FACE_RETOUCH -> "Retoque facial"
            EXT_BOKEH -> "Retrato"
            EXT_HDR -> "HDR"
            EXT_NIGHT -> "Noche"
            else -> "Modo $e"
        }

        fun fmtZoom(z: Float): String {
            val r = (z * 10).roundToInt() / 10f
            return if (r < 1f) ".${(r * 10).roundToInt()}" else if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }

        /** Lo fija CameraController al crear las capacidades (hace falta el Context). */
        @Volatile var gyroPresent = false

        fun detect(manager: CameraManager, lens: Lens): CameraCapabilities {
            val facing = if (lens == Lens.BACK) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
            val candidates = manager.cameraIdList.filter {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
            // Preferimos la cámara lógica (agrupa ultra gran angular/tele) y si no, la primera.
            val id = candidates.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            } ?: candidates.firstOrNull() ?: manager.cameraIdList.first()
            return CameraCapabilities(id, lens, manager.getCameraCharacteristics(id), manager)
        }

        fun hasLens(manager: CameraManager, lens: Lens): Boolean {
            val facing = if (lens == Lens.BACK) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
            return manager.cameraIdList.any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        }
    }
}
