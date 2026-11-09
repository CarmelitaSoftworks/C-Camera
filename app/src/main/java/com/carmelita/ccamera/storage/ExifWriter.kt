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
        val gps: Gps? = null,
    )

    /** Ubicación (grados decimales, altitud en metros sobre el nivel del mar). */
    data class Gps(val latitude: Double, val longitude: Double, val altitudeM: Double? = null)

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
    private fun rationals(tag: Int, vararg v: Pair<Long, Long>) =
        Entry(tag, 5, v.size, v.fold(ByteArray(0)) { acc, (n, d) -> acc + be32(n.toInt()) + be32(d.toInt()) })

    /** Grados decimales -> grados, minutos, segundos (segundos con 2 decimales). */
    private fun dms(deg: Double): Array<Pair<Long, Long>> {
        val a = kotlin.math.abs(deg)
        val d = a.toLong()
        val mFull = (a - d) * 60.0
        val m = mFull.toLong()
        val sec = ((mFull - m) * 60.0 * 100.0).let { kotlin.math.round(it).toLong() }
        return arrayOf(d to 1L, m to 1L, sec to 100L)
    }

    private fun gpsEntries(g: Gps): List<Entry> {
        val out = mutableListOf(
            Entry(0x0000, 1, 4, byteArrayOf(2, 2, 0, 0)),                          // GPSVersionID
            ascii(0x0001, if (g.latitude >= 0) "N" else "S"),
            rationals(0x0002, *dms(g.latitude)),
            ascii(0x0003, if (g.longitude >= 0) "E" else "W"),
            rationals(0x0004, *dms(g.longitude)),
        )
        g.altitudeM?.let { alt ->
            out += Entry(0x0005, 1, 1, byteArrayOf(if (alt < 0) 1 else 0, 0, 0, 0))   // AltitudeRef
            out += rational(0x0006, kotlin.math.round(kotlin.math.abs(alt) * 100).toLong(), 100)
        }
        return out
    }

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
        // Punteros al Exif IFD (y al GPS IFD): se calculan después de conocer el tamaño del IFD0
        // (que no depende del valor de los punteros).
        val gps = info.gps?.let { gpsEntries(it) }
        fun ifd0With(exifPtr: Int, gpsPtr: Int) =
            ifd0 + long(0x8769, exifPtr) + (if (gps != null) listOf(long(0x8825, gpsPtr)) else emptyList())
        val ifd0Size = ifd(ifd0With(0, 0), 8, 0).size
        val exifOffset = 8 + ifd0Size
        val exifBytes = ifd(exif, exifOffset, 0)
        val gpsOffset = exifOffset + exifBytes.size
        val ifd0Bytes = ifd(ifd0With(exifOffset, gpsOffset), 8, 0)
        val gpsBytes = if (gps != null) ifd(gps, gpsOffset, 0) else ByteArray(0)

        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        tiff.write(be32(8))
        tiff.write(ifd0Bytes)
        tiff.write(exifBytes)
        tiff.write(gpsBytes)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** Resultado de [stripGps]. */
    enum class GpsStrip {
        /** No había ubicación: se puede compartir tal cual. */
        NONE,
        /** Había ubicación y se borró. */
        STRIPPED,
        /** No se pudo comprobar (estructura inesperada): NO compartir, podría llevar ubicación. */
        FAILED,
    }

    /**
     * Quita la ubicación de un JPEG (modifica [jpeg] en su sitio, sin cambiar su tamaño: así no se
     * toca lo que va detrás, como el mapa Ultra HDR o el vídeo de la Foto en vivo).
     * Borra los datos del GPS IFD y lo deja vacío. Ante cualquier duda devuelve FAILED.
     */
    fun stripGps(jpeg: ByteArray): GpsStrip {
        fun u8(i: Int) = jpeg[i].toInt() and 0xFF
        if (jpeg.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return GpsStrip.FAILED
        var i = 2
        var result = GpsStrip.NONE
        while (true) {
            if (i >= jpeg.size) return GpsStrip.FAILED            // sin SOS: JPEG cortado
            if (u8(i) != 0xFF) return GpsStrip.FAILED             // estructura inesperada
            while (i < jpeg.size && u8(i) == 0xFF) i++            // bytes de relleno 0xFF
            if (i >= jpeg.size) return GpsStrip.FAILED
            val marker = u8(i)
            i++
            if (marker == 0xDA || marker == 0xD9) return result   // empieza la imagen: no hay más metadatos
            if (marker == 0x01 || marker in 0xD0..0xD7) continue  // marcadores sin longitud
            if (i + 2 > jpeg.size) return GpsStrip.FAILED
            val len = (u8(i) shl 8) or u8(i + 1)
            if (len < 2 || i + len > jpeg.size) return GpsStrip.FAILED
            if (marker == 0xE1 && len >= 8 && String(jpeg, i + 2, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                when (stripGpsInTiff(jpeg, i + 8, i + len)) {
                    GpsStrip.FAILED -> return GpsStrip.FAILED
                    GpsStrip.STRIPPED -> result = GpsStrip.STRIPPED
                    GpsStrip.NONE -> Unit
                }
            }
            i += len
        }
    }

    private fun stripGpsInTiff(b: ByteArray, tiff: Int, end: Int): GpsStrip {
        if (tiff + 8 > end) return GpsStrip.FAILED
        val little = when {
            b[tiff].toInt() == 'I'.code && b[tiff + 1].toInt() == 'I'.code -> true
            b[tiff].toInt() == 'M'.code && b[tiff + 1].toInt() == 'M'.code -> false
            else -> return GpsStrip.FAILED
        }
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun r16(i: Int) = if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        fun r32(i: Int): Long = (if (little) r16(i).toLong() or (r16(i + 2).toLong() shl 16)
            else (r16(i).toLong() shl 16) or r16(i + 2).toLong()) and 0xFFFFFFFFL
        fun ok(i: Long, n: Long) = i >= tiff && i + n <= end
        val ifd0 = tiff + r32(tiff + 4)
        if (!ok(ifd0, 2)) return GpsStrip.FAILED
        val n = r16(ifd0.toInt())
        if (!ok(ifd0, 2L + n * 12L)) return GpsStrip.FAILED
        var gps = -1L
        for (k in 0 until n) {
            val e = ifd0.toInt() + 2 + k * 12
            if (r16(e) == 0x8825) gps = tiff + r32(e + 8)
        }
        if (gps < 0) return GpsStrip.NONE
        if (!ok(gps, 2)) return GpsStrip.FAILED
        val g = gps.toInt()
        val cnt = r16(g)
        val ifdLen = 2 + cnt * 12 + 4
        if (!ok(gps, ifdLen.toLong())) return GpsStrip.FAILED
        for (k in 0 until cnt) {
            val e = g + 2 + k * 12
            val unit = when (r16(e + 2)) { 3, 8 -> 2L; 4, 9, 11 -> 4L; 5, 10, 12 -> 8L; else -> 1L }
            val size = unit * r32(e + 4)
            if (size > 4) {
                val off = tiff + r32(e + 8)
                if (!ok(off, size)) return GpsStrip.FAILED
                java.util.Arrays.fill(b, off.toInt(), (off + size).toInt(), 0)
            }
        }
        // IFD vacío: 0 entradas y "siguiente IFD" = 0.
        java.util.Arrays.fill(b, g, g + ifdLen, 0)
        return if (cnt > 0) GpsStrip.STRIPPED else GpsStrip.NONE
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
