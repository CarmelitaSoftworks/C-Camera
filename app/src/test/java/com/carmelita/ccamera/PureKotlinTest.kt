package com.carmelita.ccamera

import com.carmelita.ccamera.qr.QrContent
import com.carmelita.ccamera.storage.ExifWriter
import com.carmelita.ccamera.storage.MotionPhotoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de la parte en Kotlin puro (formatos de archivo y lector de QR). */
class PureKotlinTest {

    /** JPEG mínimo válido: SOI, APP0 JFIF, cuerpo ficticio, EOI. */
    private fun fakeJpeg(extraSegments: List<ByteArray> = emptyList()): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(9)
        val body = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2) + ByteArray(100) { it.toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + extraSegments.fold(ByteArray(0)) { a, b -> a + b } +
            body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun exifIsInsertedAfterSoi() {
        val j = ExifWriter.insert(fakeJpeg(), ExifWriter.Info(orientation = 6, make = "Test", model = "M", dateTime = "2026:11:09 00:00:00", exposureNs = 10_000_000, iso = 100))
        assertEquals(0xFF, j[0].toInt() and 0xFF); assertEquals(0xD8, j[1].toInt() and 0xFF)
        assertEquals(0xE1, j[3].toInt() and 0xFF)
        assertEquals("Exif", String(j, 6, 4, Charsets.ISO_8859_1))
        // Insertar de nuevo reemplaza el EXIF anterior (no duplica)
        val j2 = ExifWriter.insert(j, ExifWriter.Info(orientation = 3))
        assertEquals(1, Regex("Exif\u0000\u0000").findAll(String(j2, Charsets.ISO_8859_1)).count())
    }

    @Test
    fun exifWithGps() {
        val info = ExifWriter.Info(orientation = 1, dateTime = "2026:11:09 00:00:00",
            gps = ExifWriter.Gps(-33.4489, -70.6693, 570.0))
        val app1 = ExifWriter.app1(info)
        val tiffStart = 10   // FF E1 + longitud (2) + "Exif\0\0"
        fun u16(i: Int) = ((app1[i].toInt() and 0xFF) shl 8) or (app1[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        // IFD0: buscar el puntero al GPS IFD (0x8825).
        val ifd0 = tiffStart + u32(tiffStart + 4)
        val n = u16(ifd0)
        val gpsPtr = (0 until n).map { ifd0 + 2 + it * 12 }.firstOrNull { u16(it) == 0x8825 }?.let { u32(it + 8) }
        assertTrue("falta el puntero GPS", gpsPtr != null)
        val gps = tiffStart + gpsPtr!!
        val tags = (0 until u16(gps)).map { u16(gps + 2 + it * 12) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), tags)
        // Latitud sur y longitud oeste.
        assertEquals('S'.code, app1[gps + 2 + 1 * 12 + 8].toInt())
        assertEquals('W'.code, app1[gps + 2 + 3 * 12 + 8].toInt())
        // 33° de latitud (primer racional).
        val latOff = tiffStart + u32(gps + 2 + 2 * 12 + 8)
        assertEquals(33, u32(latOff)); assertEquals(1, u32(latOff + 4))
    }

    @Test
    fun motionPhotoFromPlainJpeg() {
        val jpeg = fakeJpeg()
        val mp4 = ByteArray(5000) { (it % 251).toByte() }
        val out = MotionPhotoWriter.build(jpeg, mp4, 1_500_000)
        // El vídeo va al final, íntegro.
        assertTrue(out.copyOfRange(out.size - mp4.size, out.size).contentEquals(mp4))
        val s = String(out, Charsets.ISO_8859_1)
        assertTrue(s.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(s.contains("Item:Length=\"5000\""))
        assertTrue(s.contains("MotionPhotoPresentationTimestampUs=\"1500000\""))
    }

    @Test
    fun motionPhotoKeepsUltraHdrDirectory() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="777"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val mpfPayload = "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(20)
        val mpf = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, (mpfPayload.size + 2).toByte()) + mpfPayload
        val primary = fakeJpeg(listOf(seg, mpf))
        val gainmap = fakeJpeg()
        val ultra = primary + gainmap
        val mp4 = ByteArray(1234) { 7 }
        val out = MotionPhotoWriter.build(ultra, mp4, 0)
        val s = String(out, Charsets.ISO_8859_1)
        // Orden del directorio: Primary, GainMap, MotionPhoto
        val p = s.indexOf("Semantic=\"Primary\""); val g = s.indexOf("Semantic=\"GainMap\""); val m = s.indexOf("Semantic=\"MotionPhoto\"")
        assertTrue(p in 0 until g && g < m)
        assertTrue(s.contains("GCamera:MotionPhoto=\"1\""))
        // La distancia del MPF al mapa de ganancia no cambia (los offsets del MPF siguen siendo válidos).
        val mpfMarker = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)
        val before = indexOf(ultra, gainmap) - indexOf(ultra, mpfMarker)
        val after = indexOf(out.copyOfRange(0, out.size - mp4.size), gainmap).let { it } - indexOf(out, mpfMarker)
        assertEquals(before, after)
        assertTrue(out.copyOfRange(out.size - mp4.size, out.size).contentEquals(mp4))
    }

    @Test
    fun motionPhotoRefusesUnsafeLayout() {
        // MPF antes del XMP: no se puede crecer el XMP sin romper offsets -> se devuelve igual.
        val mpfPayload = "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(20)
        val mpf = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, (mpfPayload.size + 2).toByte()) + mpfPayload
        val xmp = "<x:xmpmeta><rdf:RDF><rdf:Description rdf:about=\"\"><Container:Directory><rdf:Seq></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        val jpeg = fakeJpeg(listOf(mpf, seg))
        assertTrue(MotionPhotoWriter.build(jpeg, ByteArray(10), 0).contentEquals(jpeg))
    }

    @Test
    fun qrParsing() {
        assertTrue(QrContent.parse("https://carmelita.dev/x") is QrContent.Url)
        val w = QrContent.parse("WIFI:T:WPA;S:Casa\\;Cerda;P:clave\\:123;H:false;;")
        assertTrue(w is QrContent.Wifi)
        w as QrContent.Wifi
        assertEquals("Casa;Cerda", w.ssid); assertEquals("clave:123", w.password); assertEquals("WPA", w.security)
        val open = QrContent.parse("WIFI:S:Libre;T:nopass;;") as QrContent.Wifi
        assertEquals(null, open.password)
        assertEquals("+56912345678", (QrContent.parse("tel:+56912345678") as QrContent.Phone).number)
        val m = QrContent.parse("mailto:hola@x.cl?subject=Hola%20mundo&body=Texto") as QrContent.Email
        assertEquals("hola@x.cl", m.to); assertEquals("Hola mundo", m.subject)
        val g = QrContent.parse("geo:-33.45,-70.66?q=Santiago") as QrContent.Geo
        assertEquals(-33.45, g.lat, 1e-9); assertEquals("Santiago", g.query)
        val v = QrContent.parse("BEGIN:VCARD\nVERSION:3.0\nN:Cerda;Cristian\nFN:Cristian Cerda\nTEL:+56 9 1111 2222\nEMAIL:c@x.cl\nEND:VCARD") as QrContent.Contact
        assertEquals("Cristian Cerda", v.name); assertEquals(listOf("+56 9 1111 2222"), v.phones)
        val me = QrContent.parse("MECARD:N:Perez,Ana;TEL:123;EMAIL:a@b.c;;") as QrContent.Contact
        assertEquals("Perez Ana", me.name)
        val sms = QrContent.parse("SMSTO:12345:Hola") as QrContent.Sms
        assertEquals("12345", sms.number); assertEquals("Hola", sms.body)
        assertTrue(QrContent.parse("Texto cualquiera") is QrContent.Text)
        assertTrue(QrContent.parse("BEGIN:VEVENT\nSUMMARY:Reunión\nDTSTART:20261110T120000Z\nEND:VEVENT") is QrContent.Event)
    }
}
