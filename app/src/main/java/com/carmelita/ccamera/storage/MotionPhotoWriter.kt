package com.carmelita.ccamera.storage

import java.io.ByteArrayOutputStream

/**
 * Convierte un JPEG (normal o Ultra HDR) + un MP4 en una "Motion Photo" (formato de Google
 * Photos, Motion Photo 1.0): el MP4 va concatenado al final y el XMP lo declara.
 *
 * Código Kotlin puro (sin Android) para poder probarlo en la JVM.
 */
object MotionPhotoWriter {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    private class Segment(val marker: Int, val start: Int, val end: Int)   // [start, end) incluye marcador y longitud

    /** Recorre los segmentos APPn/COM del principio del JPEG (hasta SOS/DQT/SOF...). */
    private fun headerSegments(j: ByteArray): List<Segment> {
        require(j.size > 4 && (j[0].toInt() and 0xFF) == 0xFF && (j[1].toInt() and 0xFF) == 0xD8) { "No es un JPEG" }
        val out = ArrayList<Segment>()
        var i = 2
        while (i + 4 <= j.size && (j[i].toInt() and 0xFF) == 0xFF) {
            val m = j[i + 1].toInt() and 0xFF
            if (m !in 0xE0..0xEF && m != 0xFE) break
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            out += Segment(m, i, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    private fun startsWith(j: ByteArray, at: Int, s: String): Boolean {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        if (at + b.size > j.size) return false
        for (k in b.indices) if (j[at + k] != b[k]) return false
        return true
    }

    private fun xmpSegment(xmp: String): ByteArray {
        val payload = XMP_NS.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        require(len <= 0xFFFF) { "XMP demasiado grande" }
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
    }

    private val motionItem = """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="%d" Item:Padding="0"/></rdf:li>"""

    private fun gcameraAttrs(tsUs: Long) =
        """ xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$tsUs""""

    fun freshXmp(videoLength: Int, tsUs: Long): String = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="C Camera"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""${gcameraAttrs(tsUs)} xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>${motionItem.format(videoLength)}</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    /**
     * @param jpeg   JPEG completo (si es Ultra HDR incluye ya el mapa de ganancia al final).
     * @param mp4    clip de vídeo.
     * @param tsUs   instante de la foto dentro del clip (µs).
     * @return el archivo Motion Photo, o el JPEG sin cambios si su estructura no lo permite.
     */
    fun build(jpeg: ByteArray, mp4: ByteArray, tsUs: Long): ByteArray {
        val segs = headerSegments(jpeg)
        val xmpSeg = segs.firstOrNull { it.marker == 0xE1 && startsWith(jpeg, it.start + 4, XMP_NS) }
        val out = ByteArrayOutputStream(jpeg.size + mp4.size + 1024)
        if (xmpSeg == null) {
            // JPEG simple: insertar un XMP nuevo tras los APP existentes (después de EXIF).
            val insertAt = segs.lastOrNull { it.marker == 0xE0 || it.marker == 0xE1 }?.end ?: 2
            out.write(jpeg, 0, insertAt)
            out.write(xmpSegment(freshXmp(mp4.size, tsUs)))
            out.write(jpeg, insertAt, jpeg.size - insertAt)
        } else {
            // Ultra HDR: ya hay un XMP con Container:Directory (Primary + GainMap). Le añadimos el vídeo.
            // Sólo es seguro si el MPF (offsets del mapa de ganancia) va DESPUÉS del XMP.
            val mpf = segs.firstOrNull { it.marker == 0xE2 && startsWith(jpeg, it.start + 4, "MPF\u0000") }
            if (mpf != null && mpf.start < xmpSeg.start) return jpeg
            val xmp = String(jpeg, xmpSeg.start + 4 + XMP_NS.length, xmpSeg.end - (xmpSeg.start + 4 + XMP_NS.length), Charsets.UTF_8)
            val updated = addMotionToXmp(xmp, mp4.size, tsUs) ?: return jpeg
            out.write(jpeg, 0, xmpSeg.start)
            out.write(xmpSegment(updated))
            out.write(jpeg, xmpSeg.end, jpeg.size - xmpSeg.end)
        }
        out.write(mp4)
        return out.toByteArray()
    }

    /** Añade los atributos GCamera y el item de vídeo al final del Container:Directory existente. */
    fun addMotionToXmp(xmp: String, videoLength: Int, tsUs: Long): String? {
        val desc = Regex("<rdf:Description\\b").find(xmp) ?: return null
        val seqEnd = xmp.lastIndexOf("</rdf:Seq>")
        if (seqEnd < 0 || seqEnd < desc.range.last) {
            // Sin directorio: añadirlo dentro de la descripción.
            val descClose = xmp.indexOf("</rdf:Description>", desc.range.last)
            val selfClose = xmp.indexOf("/>", desc.range.last)
            val tagEnd = xmp.indexOf('>', desc.range.last)
            if (tagEnd < 0) return null
            val dir = """<Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>${motionItem.format(videoLength)}</rdf:Seq></Container:Directory>"""
            val ns = " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
            return if (selfClose >= 0 && selfClose < tagEnd + 1 && (descClose < 0 || selfClose < descClose) && xmp[tagEnd - 1] == '/') {
                xmp.substring(0, tagEnd - 1) + gcameraAttrs(tsUs) + nsIfMissing(xmp, ns) + ">" + dir + "</rdf:Description>" + xmp.substring(tagEnd + 1)
            } else {
                xmp.substring(0, tagEnd) + gcameraAttrs(tsUs) + nsIfMissing(xmp, ns) + ">" + dir + xmp.substring(tagEnd + 1)
            }
        }
        val withItem = xmp.substring(0, seqEnd) + motionItem.format(videoLength) + xmp.substring(seqEnd)
        val insertAttrs = desc.range.last + 1
        return withItem.substring(0, insertAttrs) + gcameraAttrs(tsUs) + withItem.substring(insertAttrs)
    }

    private fun nsIfMissing(xmp: String, ns: String) = if (xmp.contains("xmlns:Container=")) "" else ns
}
