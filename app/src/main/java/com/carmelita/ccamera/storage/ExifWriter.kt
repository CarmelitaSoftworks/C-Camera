package com.carmelita.ccamera.storage

import java.io.ByteArrayOutputStream

/**
 * Escribe un bloque EXIF mínimo (APP1) y lo inserta tras el SOI de un JPEG.
 * Lo hacemos a mano (en lugar de ExifInterface) para no tocar lo que va detrás de la imagen
 * principal: el mapa de ganancia Ultra HDR y el vídeo de las Fotos en vivo.
 * Kotlin puro: se prueba en la JVM.
 */
object ExifWriter {

    data class Info(
        val orientation: Int = 1,           // valor EXIF (1, 3, 6, 8)
        val make: String = "",
        val model: String = "",
        val software: String = "C Camera",
        val dateTime: String = "",          // "yyyy:MM:dd HH:mm:ss"
        val exposureNs: Long? = null,
        val iso: Int? = null,
        val focalLengthMm: Float? = null,
        val aperture: Float? = null,
        val description: String = "",
    )

    fun degreesToOrientation(deg: Int) = when ((deg % 360 + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** EXIF ASCII: quita tildes y cambia símbolos no ASCII (· → -). */
    fun toAscii(s: String): String =
        java.text.Normalizer.normalize(s.replace('·', '-').replace('×', 'x'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .map { if (it.code in 32..126) it else '?' }.joinToString("")

    private fun ascii(tag: Int, s: String): Entry {
        val b = (toAscii(s) + "\u0000").toByteArray(Charsets.US_ASCII)
        return Entry(tag, 2, b.size, b)
    }
    private fun short(tag: Int, v: Int) = Entry(tag, 3, 1, byteArrayOf((v shr 8).toByte(), v.toByte(), 0, 0))
    private fun long(tag: Int, v: Int) = Entry(tag, 4, 1, be32(v))
    private fun rational(tag: Int, num: Long, den: Long) = Entry(tag, 5, 1, be32(num.toInt()) + be32(den.toInt()))

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** Serializa un IFD en [offset] (relativo al TIFF). Devuelve los bytes del IFD + datos. */
    private fun ifd(entries: List<Entry>, offset: Int, nextIfd: Int): ByteArray {
        val sorted = entries.sortedBy { it.tag }
        val headerSize = 2 + sorted.size * 12 + 4
        var dataOff = offset + headerSize
        val head = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        head.write(be16(sorted.size))
        for (e in sorted) {
            head.write(be16(e.tag)); head.write(be16(e.type)); head.write(be32(e.count))
            if (e.data.size <= 4) {
                head.write(e.data.copyOf(4))
            } else {
                head.write(be32(dataOff))
                data.write(e.data)
                if (e.data.size % 2 == 1) data.write(0)
                dataOff += e.data.size + (e.data.size % 2)
            }
        }
        head.write(be32(nextIfd))
        return head.toByteArray() + data.toByteArray()
    }

    fun app1(info: Info): ByteArray {
        val exif = mutableListOf<Entry>()
        if (info.dateTime.isNotEmpty()) {
            exif += ascii(0x9003, info.dateTime)   // DateTimeOriginal
            exif += ascii(0x9004, info.dateTime)   // DateTimeDigitized
        }
        info.exposureNs?.let { ns ->
            // ExposureTime como fracción 1/x si es corta
            if (ns < 1_000_000_000L && ns > 0) exif += rational(0x829A, 1, (1_000_000_000L + ns / 2) / ns)
            else exif += rational(0x829A, ns / 1_000_000, 1000)
        }
        info.iso?.let { exif += short(0x8827, it.coerceIn(0, 65535)) }
        info.aperture?.let { exif += rational(0x829D, (it * 100).toLong(), 100) }
        info.focalLengthMm?.let { exif += rational(0x920A, (it * 100).toLong(), 100) }

        val ifd0 = mutableListOf(short(0x0112, info.orientation))
        if (info.make.isNotEmpty()) ifd0 += ascii(0x010F, info.make)
        if (info.model.isNotEmpty()) ifd0 += ascii(0x0110, info.model)
        if (info.software.isNotEmpty()) ifd0 += ascii(0x0131, info.software)
        if (info.dateTime.isNotEmpty()) ifd0 += ascii(0x0132, info.dateTime)
        if (info.description.isNotEmpty()) ifd0 += ascii(0x010E, info.description)
        // Puntero al Exif IFD: se calcula después de conocer el tamaño del IFD0.
        val ifd0WithPtr = ifd0 + long(0x8769, 0)
        val ifd0Size = ifd(ifd0WithPtr, 8, 0).size
        val exifOffset = 8 + ifd0Size
        val ifd0Bytes = ifd(ifd0 + long(0x8769, exifOffset), 8, 0)
        val exifBytes = ifd(exif, exifOffset, 0)

        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        tiff.write(be32(8))
        tiff.write(ifd0Bytes)
        tiff.write(exifBytes)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** Inserta el EXIF justo después del SOI (quitando un APP1 Exif previo si lo hubiera). */
    fun insert(jpeg: ByteArray, info: Info): ByteArray {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8)
        val out = ByteArrayOutputStream(jpeg.size + 512)
        out.write(jpeg, 0, 2)
        out.write(app1(info))
        var i = 2
        // Saltar un APP1 Exif existente.
        if (i + 10 < jpeg.size && (jpeg[i + 1].toInt() and 0xFF) == 0xE1 &&
            String(jpeg, i + 4, 4, Charsets.ISO_8859_1) == "Exif"
        ) {
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }
}
