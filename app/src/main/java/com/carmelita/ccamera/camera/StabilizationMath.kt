package com.carmelita.ccamera.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Matemáticas del estabilizador digital de vídeo (Kotlin puro: se prueba en la JVM).
 *
 * Ejes del teléfono (Android): X a la derecha, Y hacia arriba, Z saliendo de la pantalla; el
 * giroscopio da la velocidad angular en esos ejes (regla de la mano derecha). Un giro pequeño δ
 * del teléfono desplaza el contenido de la imagen (en vertical "natural", x derecha, y abajo):
 *   cámara trasera (mira a -Z):  du = f·δy,  dv =  f·δx,  giro del contenido = +δz
 *   cámara frontal (mira a +Z):  du = f·δy,  dv = -f·δx,  giro del contenido = -δz
 * (desarrollado proyectando un punto fijo del mundo tras rotar los ejes; ángulo positivo = horario
 * con y hacia abajo). El búfer del sensor está girado SENSOR_ORIENTATION grados respecto a esa
 * vista vertical, así que el desplazamiento se gira de vuelta a coordenadas del búfer.
 */
object StabilizationMath {

    /** Desplazamiento (px, y hacia abajo) y giro (rad, horario) del contenido en el búfer del sensor. */
    data class Motion(val dx: Float, val dy: Float, val angle: Float)

    fun contentMotion(dThetaX: Float, dThetaY: Float, dThetaZ: Float, focalPx: Float, front: Boolean, sensorOrientation: Int): Motion {
        val du = focalPx * dThetaY
        val dv = if (front) -focalPx * dThetaX else focalPx * dThetaX
        val rot = if (front) -dThetaZ else dThetaZ
        // Vista vertical -> búfer: girar en sentido antihorario SENSOR_ORIENTATION grados.
        val o = Math.toRadians(sensorOrientation.toDouble())
        val c = cos(o).toFloat(); val s = sin(o).toFloat()
        val bx = du * c + dv * s
        val by = -du * s + dv * c
        return Motion(bx, by, rot)
    }

    /**
     * Matriz afín 3x3 (por columnas, para GLSL mat3) que convierte la coordenada de salida
     * (u, v en 0..1, v hacia arriba como en GL) en la coordenada del búfer de la que se lee.
     * [zoom] > 1 recorta para dejar margen; el desplazamiento y el giro se limitan a ese margen.
     */
    fun sampleMatrix(m: Motion, width: Int, height: Int, zoom: Float): FloatArray {
        val w = width.toFloat(); val h = height.toFloat()
        // Con y hacia arriba el desplazamiento vertical y el sentido del giro cambian de signo.
        var tx = m.dx
        var ty = -m.dy
        var a = -m.angle
        // Margen disponible en cada eje tras el zoom (px), descontando lo que gasta el giro.
        val maxA = 0.06f
        a = a.coerceIn(-maxA, maxA)
        val rotSpendX = abs(sin(a)) * h / 2 / zoom
        val rotSpendY = abs(sin(a)) * w / 2 / zoom
        val mx = max(0f, (w - w / zoom) / 2 - rotSpendX)
        val my = max(0f, (h - h / zoom) / 2 - rotSpendY)
        tx = min(max(tx, -mx), mx)
        ty = min(max(ty, -my), my)
        // S = C + R(a) · (P - C) / zoom + t, en píxeles; después a 0..1.
        val ca = cos(a) / zoom; val sa = sin(a) / zoom
        val cx = w / 2; val cy = h / 2
        // En píxeles: Sx = ca·Px - sa·Py + (cx - ca·cx + sa·cy + tx); Sy = sa·Px + ca·Py + (cy - sa·cx - ca·cy + ty)
        // Con P = (u·w, v·h) y S normalizado por (w, h):
        val m00 = ca
        val m01 = sa * w / h          // aporta u a Sy
        val m10 = -sa * h / w         // aporta v a Sx
        val m11 = ca
        val m20 = (cx - ca * cx + sa * cy + tx) / w
        val m21 = (cy - sa * cx - ca * cy + ty) / h
        // Por columnas: col0 = (m00, m01, 0), col1 = (m10, m11, 0), col2 = (m20, m21, 1).
        return floatArrayOf(m00, m01, 0f, m10, m11, 0f, m20, m21, 1f)
    }

    /** Aplica la matriz de [sampleMatrix] a (u, v) (para pruebas). */
    fun apply(mat: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        (mat[0] * u + mat[3] * v + mat[6]) to (mat[1] * u + mat[4] * v + mat[7])
}

/**
 * Trayectoria suavizada: sigue la orientación real con un filtro de paso bajo; la diferencia
 * (real - suavizada) es el temblor que se compensa. Si la diferencia se sale del margen, la
 * trayectoria suavizada se arrastra (un barrido intencionado no se "resiste").
 */
class SmoothPath(private val timeConstantS: Float = 0.45f, private val maxDelta: Float = 0.05f) {
    private var sx = 0f; private var sy = 0f; private var sz = 0f
    private var lastNs = 0L
    private var started = false

    /** Devuelve el temblor (rad) a compensar para la orientación integrada (x, y, z) en [tNs]. */
    fun update(tNs: Long, x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        if (!started) { sx = x; sy = y; sz = z; lastNs = tNs; started = true; return Triple(0f, 0f, 0f) }
        val dt = ((tNs - lastNs) / 1e9f).coerceIn(0f, 0.2f)
        lastNs = tNs
        val k = 1f - kotlin.math.exp(-dt / timeConstantS)
        sx += (x - sx) * k; sy += (y - sy) * k; sz += (z - sz) * k
        fun clamp(real: Float, smooth: Float): Float {
            val d = real - smooth
            return if (d > maxDelta) real - maxDelta else if (d < -maxDelta) real + maxDelta else smooth
        }
        sx = clamp(x, sx); sy = clamp(y, sy); sz = clamp(z, sz)
        return Triple(x - sx, y - sy, z - sz)
    }
}
