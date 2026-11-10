package com.carmelita.ccamera.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.util.Size
import java.nio.ByteBuffer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guardado en la galería (MediaStore): fotos (Ultra HDR / Motion Photo), vídeos y exportaciones. */
object MediaSaver {

    private fun stamp(d: Date) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d)
    fun exifDate(d: Date): String = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(d)

    private fun imagesCollection(): Uri = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun videosCollection(): Uri = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** Codifica a JPEG. Si el Bitmap tiene mapa de ganancia (Android 14+) el JPEG sale Ultra HDR. */
    fun encodeJpeg(bmp: Bitmap, quality: Int = 95): ByteArray {
        val bos = ByteArrayOutputStream(bmp.width * bmp.height / 3)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    private fun writeImage(
        context: Context, bytes: ByteArray, name: String, subdir: String, date: Date, mime: String = "image/jpeg",
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_TAKEN, date.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, subdir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(imagesCollection(), values) ?: error("No se pudo crear la foto en la galería")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * Foto final: JPEG (Ultra HDR si el Bitmap trae mapa de ganancia) + EXIF + vídeo de Foto en vivo opcional.
     */
    fun savePhoto(context: Context, bmp: Bitmap, exif: ExifWriter.Info, live: Pair<File, Long>?, date: Date = Date()): Uri {
        var jpeg = ExifWriter.insert(encodeJpeg(bmp), exif)
        var name = "CCAM_${stamp(date)}.jpg"
        if (live != null) {
            val mp4 = live.first.readBytes()
            val mp = MotionPhotoWriter.build(jpeg, mp4, live.second)
            if (mp.size > jpeg.size) { jpeg = mp; name = "CCAM_${stamp(date)}.MP.jpg" }
        }
        return writeImage(context, jpeg, name, Environment.DIRECTORY_DCIM + "/CCamera", date)
    }

    /**
     * RAW en DNG (el primer frame de la ráfaga, sin procesar) junto a la foto.
     * [data] es RAW16 con [rowStride] bytes por fila; DngCreator lo quiere sin relleno.
     */
    fun saveDng(
        context: Context, chars: CameraCharacteristics, result: CaptureResult,
        data: ByteBuffer, rowStride: Int, width: Int, height: Int, exifOrientation: Int, date: Date = Date(),
    ): Uri {
        val dense = if (rowStride == width * 2) data.duplicate().also { it.rewind() } else {
            val out = ByteBuffer.allocateDirect(width * height * 2)
            val src = data.duplicate()
            val row = ByteArray(width * 2)
            for (y in 0 until height) {
                src.position(y * rowStride)
                src.get(row)
                out.put(row)
            }
            out.rewind()
            out
        }
        val bos = ByteArrayOutputStream(width * height * 2 + 65_536)
        DngCreator(chars, result).use { dng ->
            dng.setOrientation(exifOrientation)
            dng.writeByteBuffer(bos, Size(width, height), dense, 0)
        }
        return writeImage(context, bos.toByteArray(), "CCAM_${stamp(date)}.dng", Environment.DIRECTORY_DCIM + "/CCamera", date, "image/x-adobe-dng")
    }

    /** JPEG ya terminado (p. ej. el del procesado del teléfono), tal cual, en DCIM/CCamera. */
    fun saveJpegBytes(context: Context, jpeg: ByteArray, date: Date = Date()): Uri =
        writeImage(context, jpeg, "CCAM_${stamp(date)}.jpg", Environment.DIRECTORY_DCIM + "/CCamera", date)

    /** Exportación para redes (Pictures/CCamera/Instagram). */
    fun saveExport(context: Context, bmp: Bitmap, suffix: String, exif: ExifWriter.Info): Uri {
        val d = Date()
        val jpeg = ExifWriter.insert(encodeJpeg(bmp, 95), exif)
        return writeImage(context, jpeg, "CCAM_${stamp(d)}_$suffix.jpg", Environment.DIRECTORY_PICTURES + "/CCamera/Instagram", d)
    }

    /** Reserva un vídeo en la galería; devuelve la Uri y el descriptor para MediaRecorder. */
    fun createVideo(context: Context): Pair<Uri, ParcelFileDescriptor> {
        val d = Date()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CCAM_${stamp(d)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, d.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/CCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(videosCollection(), values) ?: error("No se pudo crear el vídeo")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("No se pudo abrir el vídeo")
        return uri to pfd
    }

    fun finishVideo(context: Context, uri: Uri, ok: Boolean) {
        val resolver = context.contentResolver
        if (!ok) { resolver.delete(uri, null, null); return }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
    }
}
