package com.carmelita.ccamera.processing

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Estilos fotográficos (mismo orden que foton::Style en C++). */
enum class PhotoStyle(val label: String) {
    STANDARD("Estándar"),
    RICH_CONTRAST("Contraste intenso"),
    VIBRANT("Vibrante"),
    WARM("Cálido"),
    COOL("Frío"),
}

/**
 * Motor nativo (C++): fusión de ráfagas RAW/YUV, Smart HDR, Ultra HDR, retrato y exportación.
 * Uso de una sesión: createSession -> add* (el primero es la referencia) -> outputSize -> process -> release.
 */
object NativeEngine {
    init { System.loadLibrary("ccamera") }

    external fun createSession(): Long
    external fun release(session: Long)

    /** Perfil de ruido del sensor (varianza = s * x + o, x normalizado 0..1). Antes del primer frame. */
    external fun setNoiseProfile(session: Long, s: Float, o: Float)

    /** RAW16. cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. black: 4 valores en orden del 2x2. */
    external fun addRaw(
        session: Long, buffer: ByteBuffer, rowStride: Int, width: Int, height: Int,
        cfa: Int, black: FloatArray, white: Float, isShort: Boolean, ratio: Float,
    )

    external fun addYuv(
        session: Long, y: ByteBuffer, yRowStride: Int, u: ByteBuffer, v: ByteBuffer,
        uvRowStride: Int, uvPixelStride: Int, width: Int, height: Int, isShort: Boolean, ratio: Float,
    )

    external fun framesAdded(session: Long): Int

    /** [ancho, alto] de la imagen final (en orientación del sensor). */
    external fun outputSize(session: Long): IntArray

    /**
     * Revela en [bitmap] (ARGB_8888, tamaño de outputSize). meta = [wb x4, ccm x9] (sólo RAW).
     * Devuelve el mapa de ganancia Ultra HDR (1 byte por píxel) o null.
     */
    external fun process(
        session: Long, bitmap: Bitmap, meta: FloatArray?, lsc: FloatArray?, lscW: Int, lscH: Int,
        exposure: Float, smartHdr: Boolean, style: Int, sharpen: Float, wantGainMap: Boolean, headroom: Float,
        autoBrightness: Boolean,
    ): ByteArray?

    external fun gainMapSize(session: Long): IntArray

    /**
     * Retrato: desenfoque de fondo en disco que crece con la distancia al sujeto + iluminación.
     * mask 0..1 (1 = sujeto) de tamaño mw x mh, alineada con el bitmap. lighting = PortraitLighting.ordinal.
     */
    external fun bokeh(bitmap: Bitmap, mask: FloatArray, mw: Int, mh: Int, strength: Float, lighting: Int)

    /** Recorte + reducción Lanczos en luz lineal + enfoque de salida. */
    external fun exportResize(src: Bitmap, cx: Int, cy: Int, cw: Int, ch: Int, dst: Bitmap, sharpen: Float)

    /** Historia 9:16 con fondo desenfocado. */
    external fun composeStory(src: Bitmap, dst: Bitmap)
}
