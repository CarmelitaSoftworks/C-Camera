package com.carmelita.ccamera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmelita.ccamera.camera.ProSettings
import com.carmelita.ccamera.processing.PhotoAspect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cuadrícula de tercios. */
@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x66FFFFFF)
        val sw = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), sw)
            drawLine(c, Offset(0f, y), Offset(size.width, y), sw)
        }
    }
}

/**
 * Nivel: una línea que gira con la inclinación del teléfono y se pone amarilla al quedar recto.
 * No aparece con el teléfono plano (mirando al suelo o al techo).
 */
@Composable
fun LevelOverlay() {
    val context = LocalContext.current
    var deviation by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var smooth = 0f
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
                visible = abs(z) / g < 0.8f
                // Ángulo de giro sobre el eje de la pantalla; 0 = vertical, ±90 = horizontal.
                val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val dev = angle - (angle / 90f).roundToInt() * 90f
                smooth += (dev - smooth) * 0.25f
                deviation = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (acc != null) sm?.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    if (!visible) return
    Canvas(Modifier.fillMaxSize()) {
        val level = abs(deviation) < 1f
        val color = if (level) Yellow else Color.White
        val cx = size.width / 2
        val cy = size.height / 2
        val half = size.width * 0.18f
        val gap = 10.dp.toPx()
        // Referencias fijas a los lados.
        drawLine(Color(0x99FFFFFF), Offset(cx - half - gap - 18.dp.toPx(), cy), Offset(cx - half - gap, cy), 2.dp.toPx())
        drawLine(Color(0x99FFFFFF), Offset(cx + half + gap, cy), Offset(cx + half + gap + 18.dp.toPx(), cy), 2.dp.toPx())
        rotate(-deviation, Offset(cx, cy)) {
            drawLine(color, Offset(cx - half, cy), Offset(cx + half, cy), 2.dp.toPx())
        }
    }
}

/** Franjas oscuras que muestran el recorte de 16:9 o 1:1 sobre la vista previa 3:4. */
@Composable
fun AspectOverlay(aspect: PhotoAspect) {
    if (aspect == PhotoAspect.FOUR_THREE) return
    Canvas(Modifier.fillMaxSize()) {
        val shade = Color(0xCC000000)
        val w = size.width
        val h = size.height
        // Se conserva el lado largo (alto en vertical) si cabe; si no, el corto.
        var visW = h * aspect.short / aspect.long
        var visH = h
        if (visW > w) { visW = w; visH = w * aspect.long / aspect.short }
        val dx = (w - visW) / 2
        val dy = (h - visH) / 2
        if (dx > 0.5f) {
            drawRect(shade, Offset(0f, 0f), androidx.compose.ui.geometry.Size(dx, h))
            drawRect(shade, Offset(w - dx, 0f), androidx.compose.ui.geometry.Size(dx, h))
        }
        if (dy > 0.5f) {
            drawRect(shade, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, dy))
            drawRect(shade, Offset(0f, h - dy), androidx.compose.ui.geometry.Size(w, dy))
        }
    }
}

/** Cuenta atrás del temporizador, grande en el centro. */
@Composable
fun CountdownOverlay(seconds: Int) {
    if (seconds <= 0) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$seconds", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light)
    }
}

/** Guía de la panorámica: flecha, barra de avance e indicación. */
@Composable
fun PanoOverlay(active: Boolean, progress: Float, hint: String) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            drawLine(Color(0x88FFFFFF), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (active) hint else "Pulsa y gira el teléfono hacia la derecha",
                color = Color.White, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            if (active) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(200.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(Yellow))
                }
            }
        }
    }
}

/** Píldora pequeña en la parte superior de la vista previa (bloqueo AE/AF, ráfaga...). */
@Composable
fun TopPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Yellow).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun fmtEv(ev: Float): String {
    val r = (ev * 10).roundToInt() / 10f
    return when {
        r > 0f -> "+%.1f".format(r)
        r < 0f -> "%.1f".format(r)
        else -> "0"
    }.replace('.', ',')
}

fun fmtShutter(ns: Long): String =
    if (ns >= 1_000_000_000L) "${(ns / 1e9).roundToInt()} s" else "1/${(1e9 / ns).roundToInt()}"

private fun awbLabel(mode: Int): String = when (mode) {
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Sol"
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Nublado"
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Bombilla"
    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluoresc."
    else -> "Auto"
}

private fun focusLabel(d: Float?): String = when {
    d == null -> "Auto"
    d <= 0f -> "∞"
    d >= 1f -> "${(100 / d).roundToInt()} cm"
    else -> "%.1f m".format(1f / d).replace('.', ',')
}

/** Panel del modo Pro: cuatro parámetros; al tocar uno aparecen sus valores. */
@Composable
fun ProPanel(pro: ProSettings, caps: ProCaps, vm: CameraViewModel) {
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val o = open
        if (o != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (o) {
                    "ISO" -> {
                        ProChip("Auto", pro.iso == null) { vm.setProIso(null) }
                        caps.isoValues.forEach { v -> ProChip("$v", pro.iso == v) { vm.setProIso(v) } }
                    }
                    "VEL" -> {
                        ProChip("Auto", pro.exposureNs == null) { vm.setProShutter(null) }
                        caps.shutterValues.forEach { v -> ProChip(fmtShutter(v), pro.exposureNs == v) { vm.setProShutter(v) } }
                    }
                    "BB" -> caps.awbModes.forEach { m -> ProChip(awbLabel(m), pro.awbMode == m) { vm.setProWb(m) } }
                    "FOCO" -> {
                        ProChip("Auto", pro.focusDiopters == null) { vm.setProFocus(null) }
                        listOf(0f, 0.33f, 1f, 2f, 5f, caps.minFocusDiopters).distinct()
                            .filter { it <= caps.minFocusDiopters }
                            .forEach { d -> ProChip(focusLabel(d), pro.focusDiopters == d) { vm.setProFocus(d) } }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (caps.manualExposure) {
                ProParam("ISO", pro.iso?.toString() ?: "Auto", open == "ISO") { open = if (open == "ISO") null else "ISO" }
                ProParam("VEL", pro.exposureNs?.let { fmtShutter(it) } ?: "Auto", open == "VEL") { open = if (open == "VEL") null else "VEL" }
            }
            if (caps.awbModes.size > 1) ProParam("BB", awbLabel(pro.awbMode), open == "BB") { open = if (open == "BB") null else "BB" }
            if (caps.manualFocus) ProParam("FOCO", focusLabel(pro.focusDiopters), open == "FOCO") { open = if (open == "FOCO") null else "FOCO" }
        }
        if (!caps.manualExposure && caps.awbModes.size <= 1 && !caps.manualFocus) {
            Text("Este teléfono no permite controles manuales", color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProParam(name: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0x33FFD60A) else Color(0x22FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = Color(0xFF8E8E93), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = if (value == "Auto") Color.White else Yellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Yellow else Color(0x33FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** Indicador de exposición junto al recuadro de enfoque. */
@Composable
fun EvIndicator(ev: Float, modifier: Modifier = Modifier) {
    Text(
        "☀ ${fmtEv(ev)}", color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------- Retrato ----------------

fun fmtF(f: Float): String = if (f == f.toInt().toFloat()) "${f.toInt()}" else "%.1f".format(f).replace('.', ',')

/** Aviso del retrato: "LUZ NATURAL" en amarillo con persona; si no, qué falta. */
@Composable
fun PortraitStatus(subject: Boolean?, lighting: com.carmelita.ccamera.processing.PortraitLighting, modifier: Modifier = Modifier) {
    when (subject) {
        true -> TopPill(lighting.label.uppercase(), modifier)
        false -> Text(
            "Se necesita una persona", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        null -> Unit
    }
}

/** Apertura (profundidad de campo), como el control "f" del iPhone. */
@Composable
fun AperturePicker(current: Float, onPick: (Float) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.carmelita.ccamera.processing.Portrait.apertures.forEach { f ->
            ProChipPublic("ƒ${fmtF(f)}", f == current) { onPick(f) }
        }
    }
}

@Composable
fun LightingPicker(current: com.carmelita.ccamera.processing.PortraitLighting, onPick: (com.carmelita.ccamera.processing.PortraitLighting) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.carmelita.ccamera.processing.PortraitLighting.entries.forEach { l ->
            ProChipPublic(l.label, l == current) { onPick(l) }
        }
    }
}

@Composable
fun ProChipPublic(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Yellow else Color(0x99000000))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
