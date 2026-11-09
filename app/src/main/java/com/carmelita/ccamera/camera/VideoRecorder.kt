package com.carmelita.ccamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import android.view.Surface

/**
 * Grabación de vídeo con MediaRecorder (H.264 o HEVC + AAC).
 * El audio sólo se activa si el permiso RECORD_AUDIO está concedido (lo comprueba quien la crea).
 *
 * [captureRate] distinto de [fps] cambia la velocidad de reproducción (sin audio):
 *  - mayor (cámara lenta): se capturan 120/240 fps y el archivo se reproduce a 30 → 4x/8x más lento;
 *  - menor (time-lapse): se guarda 1 fotograma cada 1/captureRate s y se reproduce a 30.
 */
@SuppressLint("MissingPermission")
class VideoRecorder(
    context: Context,
    val size: Size,
    val fps: Int,
    hevc: Boolean,
    withAudio: Boolean,
    rotation: Int,
    output: ParcelFileDescriptor,
    captureRate: Double? = null,
) {
    @Suppress("DEPRECATION")
    private val recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
    val surface: Surface
    private var started = false

    init {
        val pixels = size.width.toLong() * size.height
        // ~ Calidad parecida a la del iPhone: 4K30 ≈ 45-50 Mb/s en H.264, menos en HEVC.
        val base = when {
            pixels >= 3840L * 2160 -> 48_000_000
            pixels >= 1920L * 1080 -> 20_000_000
            else -> 10_000_000
        }
        val bitrate = ((if (hevc) base * 0.7 else base.toDouble()) * fps / 30.0).toInt()
        val audio = withAudio && captureRate == null
        if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(output.fileDescriptor)
        recorder.setVideoEncodingBitRate(bitrate)
        if (captureRate != null) {
            recorder.setCaptureRate(captureRate)
            recorder.setVideoFrameRate(PLAYBACK_FPS)
        } else {
            recorder.setVideoFrameRate(fps)
        }
        recorder.setVideoSize(size.width, size.height)
        recorder.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(192_000)
            recorder.setAudioChannels(2)
        }
        recorder.setOrientationHint(rotation)
        recorder.prepare()
        surface = recorder.surface
    }

    fun start() { recorder.start(); started = true }

    /** Para y libera. Devuelve false si no llegó a grabarse nada válido. */
    fun stop(): Boolean {
        val ok = runCatching { if (started) recorder.stop() }.isSuccess && started
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
        return ok
    }

    companion object {
        const val PLAYBACK_FPS = 30
    }
}
