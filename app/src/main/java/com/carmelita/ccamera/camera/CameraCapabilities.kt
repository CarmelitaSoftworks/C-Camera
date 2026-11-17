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

    // ---- Zoom y lentes ----
    val logicalMultiCamera = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    val usesZoomRatio: Boolean = Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
    val zoomRange: ClosedFloatingPointRange<Float> =
        (if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper } else null)
            ?: 1f..(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

    /**
     * Zoom que corresponde a cada lente física de una cámara lógica (p. ej. 0.6 gran angular,
     * 1 principal, 3 tele). Se deduce del campo de visión (focal / ancho de sensor): la lente
     * más angular es la que cubre el zoom mínimo de la cámara lógica.
     */
    val physicalRatios: Map<String, Float> = buildPhysicalRatios()

    private fun buildPhysicalRatios(): Map<String, Float> {
        if (!logicalMultiCamera || Build.VERSION.SDK_INT < 28) return emptyMap()
        val keys = HashMap<String, Float>()
        for (pid in chars.physicalCameraIds) {
            val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
            val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: continue
            val w = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
            if (f > 0f && w > 0f) keys[pid] = f / w
        }
        val widest = keys.values.minOrNull() ?: return emptyMap()
        val base = if (zoomRange.start < 1f) zoomRange.start else 1f
        return keys.mapValues { (_, k) -> k / widest * base }
    }

    /** Botones de zoom: una parada por cada lente física detectada (+ 2x de recorte si no hay tele). */
    val zoomStops: List<ZoomStop> = buildZoomStops()

    private fun buildZoomStops(): List<ZoomStop> {
        val stops = mutableListOf(ZoomStop(1f, true))
        for (ratio in physicalRatios.values) {
            val r = ((ratio * 10).roundToInt() / 10f).coerceIn(zoomRange.start, zoomRange.endInclusive)
            if (stops.none { abs(it.ratio - r) < 0.15f }) stops += ZoomStop(r, true)
        }
        if (zoomRange.start < 0.95f && stops.none { it.ratio < 0.95f }) stops += ZoomStop(zoomRange.start, true)
        if (zoomRange.endInclusive >= 2f && stops.none { it.ratio > 1.5f }) stops += ZoomStop(2f, false)
        return stops.sortedBy { it.ratio }
    }

    // ---- Geometría del RAW ----

    /** Datos del sensor que entrega el RAW: el de la cámara lógica o el de la lente física activa. */
    class RawSensor(
        val id: String?,
        val cfa: Int,
        val whiteLevel: Float,
        val black: FloatArray,       // por posición del 2x2 (fila a fila)
        val active: Rect,            // zona útil dentro de la imagen RAW
        val lensRatio: Float,        // zoom al que equivale esta lente sin recortar
    )

    private val rawSensors = HashMap<String, RawSensor>()

    @Synchronized
    fun rawSensor(activePhysicalId: String?): RawSensor {
        val key = activePhysicalId ?: ""
        rawSensors[key]?.let { return it }
        val c = (if (activePhysicalId != null) runCatching { manager.getCameraCharacteristics(activePhysicalId) }.getOrNull() else null) ?: chars
        val size = rawSize ?: Size(activeArray.width(), activeArray.height())
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        // El RAW mide la matriz de píxeles completa (con bordes no útiles) o ya la zona activa.
        val active = if (pre != null && pixelArray != null && pixelArray.width == size.width && pixelArray.height == size.height &&
            pre.left >= 0 && pre.top >= 0 && pre.right <= size.width && pre.bottom <= size.height && pre.width() > 64 && pre.height() > 64
        ) Rect(pre.left, pre.top, pre.right, pre.bottom) else Rect(0, 0, size.width, size.height)
        val p = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = if (p != null) floatArrayOf(
            p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
            p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
        ) else FloatArray(4) { 64f }
        val sensor = RawSensor(
            id = activePhysicalId,
            cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: cfa,
            whiteLevel = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: whiteLevel).toFloat(),
            black = black,
            active = active,
            lensRatio = (if (activePhysicalId != null) physicalRatios[activePhysicalId] else null) ?: 1f,
        )
        rawSensors[key] = sensor
        return sensor
    }

    /**
     * Rango de fps para la vista previa de foto: el de mínimo más alto con tope en 30. Así el AE
     * no alarga la exposición más allá de ~1/30 s (menos fotos movidas); la luz que falta se
     * recupera fusionando frames, y para escenas muy oscuras está el modo Noche.
     */
    val photoFpsRange: Range<Int>? = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.filter { it.upper == 30 }?.maxByOrNull { it.lower }

    /** Características del sensor que produjo este frame (la lente física activa, si la hay). */
    fun characteristicsFor(result: android.hardware.camera2.CaptureResult): CameraCharacteristics {
        if (Build.VERSION.SDK_INT < 29 || !logicalMultiCamera) return chars
        val id = result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return chars
        return runCatching { manager.getCameraCharacteristics(id) }.getOrDefault(chars)
    }

    // ---- Compensación de exposición ----
    val evRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStep: Float = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    val supportsEv: Boolean = evStep > 0f && evRange.upper > evRange.lower

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

    val hevc: Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
    }

    val hdrVideo: Boolean = Build.VERSION.SDK_INT >= 33 &&
        chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true

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
            "Estabilización de vídeo" to yes(videoStabilization),
            "Vídeo 4K" to yes(video4k),
            "Vídeo 60 fps" to yes(supportsFps(videoSize(false), 60)),
            "HEVC" to yes(hevc),
            "Vídeo HDR (HLG 10 bits)" to yes(hdrVideo),
            "Ultra HDR (fotos)" to yes(ultraHdr),
            "Detección de caras" to yes(faceDetection),
            "Mapa de sombreado de lente" to yes(lensShadingMap),
        )
    }

    companion object {
        fun is43(s: Size) = abs(s.width * 3 - s.height * 4) <= s.width / 100

        fun fmtZoom(z: Float): String {
            val r = (z * 10).roundToInt() / 10f
            return if (r < 1f) ".${(r * 10).roundToInt()}" else if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }

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
