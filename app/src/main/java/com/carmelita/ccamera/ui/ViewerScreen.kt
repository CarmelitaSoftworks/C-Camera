package com.carmelita.ccamera.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.carmelita.ccamera.share.InstagramExporter
import com.carmelita.ccamera.share.InstagramFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Visor de la última foto: Foto en vivo, compartir y exportar para Instagram. */
@Composable
fun PhotoViewer(uri: Uri, state: CameraUiState, vm: CameraViewModel) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    val liveClip = if (uri == state.lastUri) state.lastLiveClip?.takeIf { it.exists() } else null

    BackHandler { vm.closeViewer() }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                        val s = maxOf(1, maxOf(info.size.width, info.size.height) / 2048)
                        d.setTargetSampleSize(s)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                }
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Close, "Cerrar", tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { vm.closeViewer() }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                if (liveClip != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (playing) Yellow else Color(0x33FFFFFF))
                            .clickable { playing = !playing }.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MotionPhotosOn, null, tint = if (playing) Color.Black else Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("EN VIVO", color = if (playing) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val b = bmp
                if (playing && liveClip != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(liveClip.absolutePath)
                                setOnCompletionListener { playing = false }
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (b != null) {
                    Image(b.asImageBitmap(), "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerButton(Icons.Outlined.Share, "Compartir") { vm.share() }
                Text(
                    if (state.exporting) "Preparando…" else "Instagram",
                    color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Yellow)
                        .clickable(enabled = !state.exporting) { showFormats = true }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                )
                ViewerButton(Icons.Outlined.PhotoLibrary, "Galería") {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        }
        if (showFormats) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { showFormats = false }, contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Panel).padding(16.dp),
                ) {
                    Text("Preparar para Instagram", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "1080 px de ancho exactos (Instagram no la vuelve a escalar), reducción de alta calidad y " +
                            "enfoque de salida. Se mantiene el Ultra HDR si la foto lo tiene.",
                        color = Color(0xFF8E8E93), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    InstagramFormat.entries.forEach { f ->
                        Text(
                            f.label + "  ·  ${f.w}×${f.h}", color = Color.White, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { showFormats = false; vm.exportInstagram(f) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                    if (!InstagramExporter.isInstalled(context)) {
                        Text("Instagram no está instalado: se abrirá el menú de compartir.", color = Color(0xFF8E8E93), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/** Información de la cámara detectada y ajustes. */
@Composable
fun InfoSheet(state: CameraUiState, vm: CameraViewModel) {
    BackHandler { vm.showInfo(false) }
    Box(Modifier.fillMaxSize().background(Color(0xEE000000)).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Info de cámara", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Cerrar", tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { vm.showInfo(false) }.padding(8.dp))
            }
            if (state.sessionSummary.isNotEmpty()) {
                Text("Usando ahora: ${state.sessionSummary}", color = Yellow, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            state.capsSummary.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(k, color = Color(0xFF8E8E93), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(v, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Ajustes", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            SettingRow("Lector de QR automático", state.qrEnabled, vm::toggleQr)
            SettingRow("Fotos en vivo", state.livePhoto, vm::toggleLive)
            SettingRow("Smart HDR", state.smartHdr, vm::toggleHdr)
            if (state.can4k) SettingRow("Vídeo en 4K", state.video4k, vm::toggle4k)
            if (state.can60) SettingRow("Vídeo a 60 fps", state.video60, vm::toggle60)
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Color.Black),
        )
    }
}
