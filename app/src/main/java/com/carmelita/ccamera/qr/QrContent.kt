package com.carmelita.ccamera.qr

/**
 * Interpreta el texto de un código QR (o de barras 2D) como hace la cámara del iPhone.
 * Kotlin puro (sin Android) para poder probarlo en la JVM.
 */
sealed class QrContent(val raw: String) {
    abstract val title: String
    abstract val subtitle: String

    class Url(raw: String, val url: String) : QrContent(raw) {
        override val title get() = "Abrir enlace"
        override val subtitle get() = url.removePrefix("https://").removePrefix("http://").take(60)
    }
    class Wifi(raw: String, val ssid: String, val password: String?, val security: String, val hidden: Boolean) : QrContent(raw) {
        override val title get() = "Conectarse a la red Wi‑Fi"
        override val subtitle get() = "\"$ssid\""
    }
    class Phone(raw: String, val number: String) : QrContent(raw) {
        override val title get() = "Llamar"
        override val subtitle get() = number
    }
    class Sms(raw: String, val number: String, val body: String) : QrContent(raw) {
        override val title get() = "Enviar mensaje"
        override val subtitle get() = number
    }
    class Email(raw: String, val to: String, val subject: String, val body: String) : QrContent(raw) {
        override val title get() = "Enviar correo"
        override val subtitle get() = to
    }
    class Geo(raw: String, val lat: Double, val lng: Double, val query: String?) : QrContent(raw) {
        override val title get() = "Abrir en mapas"
        override val subtitle get() = query ?: "%.5f, %.5f".format(lat, lng)
    }
    class Contact(raw: String, val name: String, val phones: List<String>, val emails: List<String>, val org: String?) : QrContent(raw) {
        override val title get() = "Añadir contacto"
        override val subtitle get() = name.ifEmpty { phones.firstOrNull() ?: emails.firstOrNull() ?: "" }
    }
    class Event(raw: String, val summary: String, val start: String?, val end: String?, val location: String?) : QrContent(raw) {
        override val title get() = "Añadir al calendario"
        override val subtitle get() = summary
    }
    class Text(raw: String) : QrContent(raw) {
        override val title get() = "Copiar texto"
        override val subtitle get() = raw.replace('\n', ' ').take(60)
    }

    companion object {
        fun parse(raw: String): QrContent {
            val s = raw.trim()
            val lower = s.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") -> Url(s, s)
                lower.startsWith("www.") && !s.contains(' ') -> Url(s, "https://$s")
                lower.startsWith("wifi:") -> parseWifi(s)
                lower.startsWith("tel:") -> Phone(s, s.substring(4))
                lower.startsWith("smsto:") || lower.startsWith("sms:") -> {
                    val rest = s.substringAfter(':')
                    Sms(s, rest.substringBefore(':'), if (rest.contains(':')) rest.substringAfter(':') else "")
                }
                lower.startsWith("mailto:") -> {
                    val rest = s.substring(7)
                    val to = rest.substringBefore('?')
                    val q = query(rest.substringAfter('?', ""))
                    Email(s, to, q["subject"] ?: "", q["body"] ?: "")
                }
                lower.startsWith("matmsg:") -> {
                    val f = fields(s.substring(7))
                    Email(s, f["TO"] ?: "", f["SUB"] ?: "", f["BODY"] ?: "")
                }
                lower.startsWith("geo:") -> parseGeo(s)
                lower.startsWith("begin:vcard") -> parseVcard(s)
                lower.startsWith("mecard:") -> {
                    val f = fieldsMulti(s.substring(7))
                    Contact(s, (f["N"]?.firstOrNull() ?: "").replace(",", " ").trim(), f["TEL"] ?: emptyList(), f["EMAIL"] ?: emptyList(), f["ORG"]?.firstOrNull())
                }
                lower.startsWith("begin:vevent") || lower.contains("begin:vevent") -> parseEvent(s)
                else -> Text(s)
            }
        }

        /** Campos "K:v;K:v;;" con escapes \; \: \\ \, (formato WIFI/MECARD/MATMSG). */
        private fun splitFields(body: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            val cur = StringBuilder()
            var i = 0
            fun flush() {
                val f = cur.toString()
                cur.clear()
                val idx = indexOfUnescaped(f, ':')
                if (idx > 0) out += f.substring(0, idx).uppercase() to unescape(f.substring(idx + 1))
            }
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) { cur.append(c).append(body[i + 1]); i += 2; continue }
                if (c == ';') flush() else cur.append(c)
                i++
            }
            if (cur.isNotEmpty()) flush()
            return out
        }

        private fun indexOfUnescaped(s: String, ch: Char): Int {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\') { i += 2; continue }
                if (s[i] == ch) return i
                i++
            }
            return -1
        }

        private fun unescape(s: String): String {
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) { b.append(s[i + 1]); i += 2 } else { b.append(s[i]); i++ }
            }
            return b.toString()
        }

        private fun fields(body: String) = splitFields(body).toMap()
        private fun fieldsMulti(body: String) = splitFields(body).groupBy({ it.first }, { it.second })

        private fun parseWifi(s: String): QrContent {
            val f = fields(s.substring(5))
            val ssid = f["S"] ?: return Text(s)
            val type = (f["T"] ?: "nopass").uppercase()
            return Wifi(s, ssid, f["P"]?.takeIf { it.isNotEmpty() }, if (type == "") "NOPASS" else type, f["H"].equals("true", true))
        }

        private fun query(q: String): Map<String, String> = q.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        private fun parseGeo(s: String): QrContent {
            val body = s.substring(4)
            val coords = body.substringBefore('?').split(',')
            val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return Text(s)
            val lng = coords.getOrNull(1)?.toDoubleOrNull() ?: return Text(s)
            val q = query(body.substringAfter('?', ""))["q"]
            return Geo(s, lat, lng, q)
        }

        private fun unfoldLines(s: String): List<String> =
            s.replace("\r\n ", "").replace("\n ", "").split('\n').map { it.trimEnd('\r') }

        private fun parseVcard(s: String): QrContent {
            var name = ""; var fn = ""
            val phones = mutableListOf<String>(); val emails = mutableListOf<String>(); var org: String? = null
            for (line in unfoldLines(s)) {
                val key = line.substringBefore(':').substringBefore(';').uppercase()
                val v = line.substringAfter(':', "")
                when (key) {
                    "FN" -> fn = v
                    "N" -> name = v.split(';').let { p -> listOfNotNull(p.getOrNull(1), p.getOrNull(0)).joinToString(" ").trim() }
                    "TEL" -> phones += v
                    "EMAIL" -> emails += v
                    "ORG" -> org = v.replace(';', ' ').trim()
                }
            }
            return Contact(s, fn.ifEmpty { name }, phones, emails, org)
        }

        private fun parseEvent(s: String): QrContent {
            var summary = ""; var start: String? = null; var end: String? = null; var loc: String? = null
            for (line in unfoldLines(s)) {
                val key = line.substringBefore(':').substringBefore(';').uppercase()
                val v = line.substringAfter(':', "")
                when (key) {
                    "SUMMARY" -> summary = v
                    "DTSTART" -> start = v
                    "DTEND" -> end = v
                    "LOCATION" -> loc = v
                }
            }
            return Event(s, summary, start, end, loc)
        }
    }
}
