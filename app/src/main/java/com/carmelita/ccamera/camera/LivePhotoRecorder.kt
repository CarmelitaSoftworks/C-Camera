package com.carmelita.ccamera.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Fotos en vivo: codifica continuamente el stream de cámara a H.264 y guarda en memoria los
 * últimos segundos. Al disparar se exporta un MP4 con ~1,5 s antes y ~1,5 s después.
 */
class LivePhotoRecorder(val size: Size, private val fps: Int = 30) {

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val thread = HandlerThread("live-photo").apply { start() }
    private val handler = Handler(thread.looper)
    private val samples = ArrayDeque<Sample>()
    @Volatile private var format: MediaFormat? = null
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    @Volatile private var running = true

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)   // keyframes frecuentes = recorte preciso
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!running) return
                val buf = runCatching { c.getOutputBuffer(index) }.getOrNull()
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                    synchronized(samples) {
                        samples.addLast(Sample(bytes, info.presentationTimeUs, info.flags))
                        // Mantener ~4 s
                        while (samples.size > 2 && samples.last.ptsUs - samples.first.ptsUs > 4_000_000) samples.removeFirst()
                    }
                }
                runCatching { c.releaseOutputBuffer(index, false) }
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { Log.e(TAG, "Encoder", e) }
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) { format = f }
        }, handler)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /**
     * Escribe el clip alrededor de [shutterUs] (timestamp de sensor en µs). Espera a tener
     * suficiente "después". Devuelve el archivo y el tiempo de la foto dentro del clip, o null.
     */
    suspend fun export(shutterUs: Long, out: File, rotation: Int, beforeUs: Long = 1_500_000, afterUs: Long = 1_500_000): Pair<File, Long>? {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val lastPts = synchronized(samples) { samples.peekLast()?.ptsUs ?: 0L }
            if (lastPts >= shutterUs + afterUs) break
            delay(50)
        }
        val fmt = format ?: return null
        val clip: List<Sample> = synchronized(samples) {
            val all = samples.toList()
            val startIdx = all.indexOfLast { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && it.ptsUs <= shutterUs - beforeUs }
                .takeIf { it >= 0 } ?: all.indexOfFirst { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
            if (startIdx < 0) return null
            all.subList(startIdx, all.size).filter { it.ptsUs <= shutterUs + afterUs }
        }
        if (clip.size < 5) return null
        val base = clip.first().ptsUs
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            val track = muxer.addTrack(fmt)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            for (s in clip) {
                info.set(0, s.data.size, s.ptsUs - base, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        return out to (shutterUs - base).coerceAtLeast(0)
    }

    fun release() {
        running = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        thread.quitSafely()
        synchronized(samples) { samples.clear() }
    }

    companion object { private const val TAG = "LivePhoto" }
}
