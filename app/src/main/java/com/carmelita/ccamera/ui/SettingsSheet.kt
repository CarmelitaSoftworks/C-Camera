package com.carmelita.ccamera.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmelita.ccamera.processing.PhotoAspect
import com.carmelita.ccamera.storage.LocationHelper

/** Ajustes de la cámara (se guardan entre usos). */
@Composable
fun SettingsSheet(state: CameraUiState, vm: CameraViewModel) {
    BackHandler { vm.showSettings(false) }
    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.setSaveLocation(res.values.any { it })
    }
    Box(Modifier.fillMaxSize().background(Color(0xF2000000)).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Cerrar", tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { vm.showSettings(false) }.padding(8.dp))
            }

            Section("Composición")
            Toggle("Cuadrícula", state.grid, vm::toggleGrid)
            Toggle("Nivel", state.level, vm::toggleLevel)
            Toggle("Espejo en la cámara frontal", state.mirrorFront, vm::toggleMirror)

            Section("Procesado")
            Toggle("Procesado del teléfono (recomendado)", state.phoneProcessing, vm::togglePhoneProcessing)
            Text(
                if (state.extensions.isEmpty())
                    "Este teléfono no ofrece su procesado a otras apps: se usa el motor de C Camera."
                else
                    "Usa el mismo procesado que la cámara de fábrica (" +
                        state.extensions.joinToString(", ") { com.carmelita.ccamera.camera.CameraCapabilities.extensionName(it) } +
                        ") en Foto, Noche y Retrato. Pro, Panorámica y vídeo usan el motor de C Camera. " +
                        "Con el procesado del teléfono no se aplican los estilos, la proporción ni la ráfaga.",
                color = Color(0xFF8E8E93), fontSize = 13.sp,
            )

            Section("Fotos")
            Text("Proporción", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoAspect.entries.forEach { a ->
                    val sel = a == state.aspect
                    Text(
                        a.label, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Yellow else Color(0x33FFFFFF))
                            .clickable { vm.setAspect(a) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
            Toggle("Smart HDR", state.smartHdr, vm::toggleHdr)
            Toggle("Noche automática con poca luz", state.autoNight, vm::toggleAutoNight)
            if (state.ultraHdr) Toggle("Ultra HDR (brilla en pantallas HDR)", state.ultraHdrOn, vm::toggleUltraHdr)
            Toggle("Fotos en vivo", state.livePhoto, vm::toggleLive)
            if (state.rawActive) Toggle("Guardar también RAW (DNG)", state.saveDng, vm::toggleDng)
            Toggle("Incluir ubicación en las fotos", state.saveLocation) {
                if (state.saveLocation) vm.setSaveLocation(false)
                else if (LocationHelper.hasPermission(context)) vm.setSaveLocation(true)
                else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Text(
                if (state.saveLocation) "Las fotos nuevas guardan dónde se hicieron (GPS en los metadatos)."
                else "Desactivado: las fotos no llevan ninguna información de ubicación.",
                color = Color(0xFF8E8E93), fontSize = 13.sp,
            )
            Toggle("Compartir sin ubicación", state.shareWithoutLocation, vm::toggleShareWithoutLocation)
            Text(
                "Al compartir una foto que tenga ubicación, se envía una copia sin ella (la de la galería no cambia). " +
                    "Las exportaciones para Instagram nunca llevan ubicación.",
                color = Color(0xFF8E8E93), fontSize = 13.sp,
            )
            Toggle("Lector de QR automático", state.qrEnabled, vm::toggleQr)

            Section("Estabilización")
            Text(
                "Este teléfono: " +
                    (if (state.hasOis) "estabilizador óptico ✓" else "sin estabilizador óptico") + " · " +
                    (if (state.hasHalEis) "estabilización de vídeo propia ✓" else "sin estabilización de vídeo propia") +
                    (if (!state.hasGyro) " · sin giroscopio (no hay estabilizador digital de vídeo)" else ""),
                color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stabilization.entries.forEach { v ->
                    val sel = v == state.stabilization
                    Text(
                        v.label, color = if (sel) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Yellow else Color(0x33FFFFFF))
                            .clickable { vm.setStabilization(v) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Text(
                when (state.stabilization) {
                    Stabilization.AUTO ->
                        "Usa la del teléfono si la tiene. Si no: en fotos elige el fotograma más nítido de la ráfaga, " +
                            "y en vídeo C Camera compensa el temblor con el giroscopio (recorta un poco la imagen)."
                    Stabilization.CCAMERA ->
                        "Siempre la de C Camera: fotograma más nítido en fotos y compensación con giroscopio en vídeo " +
                            "(en lugar de la electrónica del teléfono). El estabilizador óptico, si lo hay, sigue activo."
                    Stabilization.OFF ->
                        "Sin estabilización digital. El estabilizador óptico, si lo hay, sigue activo (es físico)."
                },
                color = Color(0xFF8E8E93), fontSize = 13.sp,
            )

            Section("Vídeo")
            if (state.can4k) Toggle("Vídeo en 4K", state.video4k, vm::toggle4k)
            if (state.can60) Toggle("Vídeo a 60 fps", state.video60, vm::toggle60)
            Text(
                state.slowMoLabel?.let { "Cámara lenta: $it fps" } ?: "Cámara lenta: no disponible en este teléfono",
                color = Color(0xFF8E8E93), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
            Text("Time-lapse: 2 fotogramas por segundo (15× más rápido)", color = Color(0xFF8E8E93), fontSize = 13.sp)

            Section("Controles")
            Toggle("Los botones de volumen disparan", state.volumeShutter, vm::toggleVolumeShutter)
            Text(
                "Mantén pulsado el disparador para hacer una ráfaga. Mantén pulsado en la imagen para bloquear " +
                    "enfoque y exposición; desliza arriba o abajo tras tocar para cambiar la exposición.",
                color = Color(0xFF8E8E93), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Color(0xFF8E8E93), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Color.Black),
        )
    }
}
