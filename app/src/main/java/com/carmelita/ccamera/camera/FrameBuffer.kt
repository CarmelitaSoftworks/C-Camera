package com.carmelita.ccamera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Copia de un frame de cámara en memoria directa (el Image original se cierra enseguida). */
sealed class CapturedFrame {
    abstract val timestamp: Long
    abstract val width: Int
    abstract val height: Int

    class Raw(
        val data: ByteBuffer, val rowStride: Int,
        override val width: Int, override val height: Int, override val timestamp: Long,
    ) : CapturedFrame()

    class Yuv(
        val y: ByteBuffer, val yRowStride: Int, val u: ByteBuffer, val v: ByteBuffer,
        val uvRowStride: Int, val uvPixelStride: Int,
        override val width: Int, override val height: Int, override val timestamp: Long,
    ) : CapturedFrame()

    companion object {
        private fun copyDirect(src: ByteBuffer): ByteBuffer {
            val s = src.duplicate()
            s.rewind()
            val d = ByteBuffer.allocateDirect(s.remaining())
            d.put(s)
            d.rewind()
            return d
        }

        /** Copia el Image (RAW_SENSOR o YUV_420_888) y lo cierra. */
        fun from(img: Image): CapturedFrame = try {
            when (img.format) {
                ImageFormat.RAW_SENSOR -> {
                    val p = img.planes[0]
                    Raw(copyDirect(p.buffer), p.rowStride, img.width, img.height, img.timestamp)
                }
                else -> {
                    val (yp, up, vp) = Triple(img.planes[0], img.planes[1], img.planes[2])
                    Yuv(
                        copyDirect(yp.buffer), yp.rowStride, copyDirect(up.buffer), copyDirect(vp.buffer),
                        up.rowStride, up.pixelStride, img.width, img.height, img.timestamp,
                    )
                }
            }
        } finally {
            img.close()
        }
    }
}

/**
 * Búfer de los últimos frames del stream continuo (zero shutter lag) + entrega de los frames
 * pedidos explícitamente (por timestamp) a quien los espera.
 *
 * El anillo guarda los Image originales (sin copiar: a 30 fps copiar cada RAW sería carísimo);
 * sólo se copian los que se usan. El ImageReader necesita maxImages >= capacity + 3.
 */
class FrameBuffer(private val capacity: Int) {
    private val ring = ArrayDeque<Image>()
    private val waiting = HashMap<Long, CompletableDeferred<CapturedFrame>>()
    /** Fotogramas pedidos que llegaron antes de que alguien los esperara (ya copiados). */
    private val ready = HashMap<Long, CapturedFrame>()
    private val results = LinkedHashMap<Long, TotalCaptureResult>()
    private val reserved = HashSet<Long>()
    private var paused = false

    /** En pausa sólo se guardan los frames pedidos (p. ej. durante una ráfaga de noche). */
    @Synchronized
    fun setPaused(p: Boolean) {
        paused = p
        if (p) {
            val keep = ring.filter { it.timestamp in reserved }
            ring.filter { it.timestamp !in reserved }.forEach { runCatching { it.close() } }
            ring.clear(); ring.addAll(keep)
        }
    }

    @Synchronized
    fun onImage(img: Image) {
        val ts = img.timestamp
        val w = waiting.remove(ts)
        if (w != null) { w.complete(CapturedFrame.from(img)); return }
        // Pedido pero aún no esperado: se copia y se suelta el Image enseguida. Si se quedara en el
        // anillo, una ráfaga larga (noche de 12) podía agotar los maxImages del ImageReader.
        if (ts in reserved) { reserved -= ts; ready[ts] = CapturedFrame.from(img); return }
        if ((capacity <= 0 || paused) && ts !in reserved) { img.close(); return }
        ring.addLast(img)
        // Descarta los más viejos que nadie ha pedido.
        while (ring.size > capacity + reserved.size) {
            val victim = ring.firstOrNull { it.timestamp !in reserved } ?: break
            ring.remove(victim)
            victim.close()
        }
    }

    @Synchronized
    fun onResult(r: TotalCaptureResult) {
        val ts = r.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
        results[ts] = r
        while (results.size > 64) results.remove(results.keys.first())
    }

    @Synchronized
    fun result(ts: Long): TotalCaptureResult? = results[ts]

    @Synchronized
    fun latestResult(): TotalCaptureResult? = results.values.lastOrNull()

    /** Avisa de que el frame con este timestamp lo quiere una captura (que no se descarte). */
    @Synchronized
    fun reserve(ts: Long) { reserved += ts }

    /** Espera el frame con este timestamp (llegue antes o después de pedirlo). */
    suspend fun await(ts: Long): CapturedFrame {
        val d: CompletableDeferred<CapturedFrame>
        synchronized(this) {
            reserved -= ts
            ready.remove(ts)?.let { return it }
            val existing = ring.firstOrNull { it.timestamp == ts }
            if (existing != null) { ring.remove(existing); return CapturedFrame.from(existing) }
            d = waiting.getOrPut(ts) { CompletableDeferred() }
        }
        return d.await()
    }

    /** Copia los [n] frames más recientes del ZSL (el más nuevo primero) y los libera. */
    @Synchronized
    fun takeLatest(n: Int): List<CapturedFrame> {
        val out = ArrayList<CapturedFrame>()
        while (out.size < n && ring.isNotEmpty()) out += CapturedFrame.from(ring.removeLast())
        return out
    }

    @Synchronized
    fun size(): Int = ring.size

    /** Fotogramas del anillo posteriores a [ts]. */
    @Synchronized
    fun countNewerThan(ts: Long): Int = ring.count { it.timestamp > ts }

    @Synchronized
    fun clear() {
        ring.forEach { runCatching { it.close() } }
        ring.clear()
        reserved.clear()
        ready.clear()
        waiting.values.forEach { it.cancel() }
        waiting.clear()
    }
}
