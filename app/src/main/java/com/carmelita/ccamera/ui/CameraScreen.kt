package com.carmelita.ccamera.ui

import android.graphics.SurfaceTexture
import android.view.OrientationEventListener
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carmelita.ccamera.camera.CameraCapabilities
import com.carmelita.ccamera.camera.FlashMode
import com.carmelita.ccamera.camera.ZoomStop
import com.carmelita.ccamera.processing.PhotoStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

val Yellow = Color(0xFFFFD60A)
val Panel = Color(0xFF1C1C1E)
private val RecordRed = Color(0xFFFF3B30)

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var showStyles by remember { mutableStateOf(false) }
    var showAperture by remember { mutableStateOf(false) }
    var iconRotation by remember { mutableStateOf(0f) }
    val rot by animateFloatAsState(iconRotation, label = "iconRot")

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                Lifecycle.Event.ON_PAUSE -> vm.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Orientación física: gira los iconos (como el iPhone) y fija la orientación EXIF / del vídeo.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val snapped = ((o + 45) / 90 * 90) % 360
                vm.deviceRotation = snapped
                var t = -snapped.toFloat()
                while (t - iconRotation > 180) t -= 360
                while (t - iconRotation < -180) t += 360
                if (abs(t - iconRotation) > 1) iconRotation = t
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val video = state.mode.isVideo
    val photoLike = !video && state.mode != CaptureMode.PANO
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Cualquier toque (sin consumirlo) cuenta como actividad: saca la cámara del reposo.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        vm.onUserInteraction()
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(state, rot, showStyles, vm, onStyles = { showStyles = !showStyles },
                apertureOpen = showAperture, onAperture = { showAperture = !showAperture })

            val previewModifier = if (video) Modifier.weight(1f).aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            else Modifier.fillMaxWidth().aspectRatio(3f / 4f)
            Box(previewModifier.align(Alignment.CenterHorizontally)) {
                Preview(vm)
                if (photoLike) AspectOverlay(state.aspect)
                if (state.grid) GridOverlay()
                if (state.level) LevelOverlay()
                if (state.mode == CaptureMode.PANO) PanoOverlay(state.panoActive, state.panoProgress, state.panoHint)
                FocusOverlay(state.focus)
                state.focus?.let { f ->
                    if (state.evStep > 0f && state.mode != CaptureMode.PRO) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val d = LocalDensity.current
                            val x = with(d) { (f.x * constraints.maxWidth).toDp() } + 44.dp
                            val y = with(d) { (f.y * constraints.maxHeight).toDp() } - 12.dp
                            EvIndicator(state.ev, Modifier.offset(x.coerceAtMost(maxWidth - 64.dp), y.coerceAtLeast(0.dp)))
                        }
                    }
                }
                ShutterFlash(state.shutterFlash)
                CountdownOverlay(state.countdown)
                if (state.recording) RecordingTimer(state.recordStartMs, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                else if (state.bursting) TopPill("${state.burstCount}", Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                else if (state.aeAfLocked) TopPill("BLOQUEO AE/AF", Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                else if (state.mode == CaptureMode.PORTRAIT) {
                    PortraitStatus(state.portraitSubject, state.portraitLight, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(state.qr != null && !state.capturing, enter = fadeIn(), exit = fadeOut()) {
                        state.qr?.let { QrChip(it.title, it.subtitle, vm::onQrTap) }
                    }
                    AnimatedVisibility(state.capturing, enter = fadeIn(), exit = fadeOut()) {
                        CaptureHint(state.nightCapture, state.captureProgress)
                    }
                    AnimatedVisibility(showStyles && !video, enter = fadeIn(), exit = fadeOut()) {
                        StylePicker(state.style, vm::setStyle)
                    }
                    if (state.mode == CaptureMode.PORTRAIT && !state.capturing && !showStyles) {
                        if (showAperture) AperturePicker(state.portraitF, vm::setPortraitF)
                        LightingPicker(state.portraitLight, vm::setPortraitLight)
                    }
                    Spacer(Modifier.height(10.dp))
                    ZoomChips(state.zoom, state.zoomStops, vm::setZoom)
                }
            }

            if (state.mode == CaptureMode.PRO) {
                Spacer(Modifier.height(8.dp))
                ProPanel(state.pro, state.proCaps, vm)
            }
            ModeSelector(state.mode, vm::setMode)

            Box(if (video) Modifier.fillMaxWidth().height(130.dp) else Modifier.fillMaxWidth().weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(state, rot) { vm.openViewer() }
                    ShutterButton(
                        video = video, recording = state.recording || state.panoActive,
                        enabled = !state.capturing && state.processing < 2,
                        burstAllowed = (state.mode == CaptureMode.PHOTO || state.mode == CaptureMode.PRO) && state.extActive == null,
                        onClick = vm::shutter,
                        onBurstStart = vm::startBurst,
                        onBurstEnd = vm::stopBurst,
                    )
                    if (state.hasFront && !state.recording && !state.panoActive) {
                        RoundIconButton(onClick = vm::switchLens, rotation = rot) {
                            Icon(Icons.Outlined.Cameraswitch, "Cambiar cámara", tint = Color.White)
                        }
                    } else Spacer(Modifier.size(52.dp))
                }
            }
        }

        state.message?.let { msg ->
            LaunchedEffect(msg) { delay(3500); vm.clearMessage() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    msg, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.padding(32.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC000000)).padding(16.dp),
                )
            }
        }
        if (state.showInfo) InfoSheet(state, vm)
        if (state.showSettings) SettingsSheet(state, vm)
        state.viewer?.let { PhotoViewer(it, state, vm) }
    }
}

@Composable
private fun Preview(vm: CameraViewModel) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = vm.onSurfaceAvailable(st)
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            vm.previewFrameProvider = null
                            vm.onSurfaceDestroyed(); return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    val tv = this
                    // Fotogramas pequeños para el lector de QR (se llama en el hilo principal).
                    vm.previewFrameProvider = { if (tv.isAvailable) tv.getBitmap(480, 640) else null }
                    vm.previewBitmapProvider = { w, h -> if (tv.isAvailable) tv.getBitmap(w, h) else null }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p -> vm.focusAt(p.x / size.width, p.y / size.height) },
                        onLongPress = { p -> vm.focusAt(p.x / size.width, p.y / size.height, lock = true) },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        if (zoomChange != 1f) vm.setZoom(vm.state.value.zoom * zoomChange)
                        else if (vm.state.value.focus != null && pan.y != 0f) {
                            // Deslizar hacia arriba sube la exposición (unos 12 pasos por la altura de la vista).
                            vm.adjustEv(-pan.y / size.height * 12f)
                        }
                    }
                },
        )
    }
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, tint: Color, rot: Float, enabled: Boolean = true, onClick: () -> Unit) {
    Icon(
        icon, desc, tint = tint,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp).rotate(rot).alpha(if (enabled) 1f else 0.4f),
    )
}

@Composable
private fun TopBar(
    state: CameraUiState, rot: Float, stylesOpen: Boolean, vm: CameraViewModel, onStyles: () -> Unit,
    apertureOpen: Boolean, onAperture: () -> Unit,
) {
    val video = state.mode.isVideo
    val stills = !video && state.mode != CaptureMode.PANO
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.hasFlash) {
                val (icon, tint) = when (state.flash) {
                    FlashMode.OFF -> Icons.Filled.FlashOff to Color.White
                    FlashMode.AUTO -> Icons.Filled.FlashAuto to Color.White
                    FlashMode.ON -> Icons.Filled.FlashOn to Yellow
                }
                TopIcon(icon, "Flash", tint, rot, onClick = vm::cycleFlash)
            }
            if (stills) TopIcon(if (state.smartHdr) Icons.Filled.HdrOn else Icons.Filled.HdrOff, "Smart HDR",
                if (state.smartHdr) Yellow else Color.White, rot, onClick = vm::toggleHdr)
            if (stills) {
                val timerIcon = when (state.timer) { 3 -> Icons.Filled.Timer3; 10 -> Icons.Filled.Timer10; else -> Icons.Filled.Timer }
                TopIcon(timerIcon, "Temporizador", if (state.timer > 0) Yellow else Color.White, rot, onClick = vm::cycleTimer)
            }
            if (state.hasMacro && (state.mode == CaptureMode.PHOTO || state.mode == CaptureMode.PRO)) {
                TopIcon(Icons.Filled.LocalFlorist, "Macro", if (state.macro) Yellow else Color.White, rot, onClick = vm::toggleMacro)
            }
        }
        when {
            state.extActive != null && !video -> Text(
                "PROCESADO DEL TELÉFONO" + if (state.mode == CaptureMode.NIGHT) " · NOCHE" else "",
                color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
            state.mode == CaptureMode.NIGHT -> Pill(Icons.Filled.NightsStay, "Noche")
            state.mode == CaptureMode.PHOTO && state.lowLight -> Pill(Icons.Filled.NightsStay, "Noche auto")
            state.mode == CaptureMode.PORTRAIT -> SmallToggle("ƒ${fmtF(state.portraitF)}", apertureOpen, onAperture)
            state.mode == CaptureMode.SLOWMO -> Text(state.slowMoLabel?.let { "$it fps" } ?: "No disponible",
                color = Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            state.mode == CaptureMode.TIMELAPSE -> Text("15×", color = Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            state.mode == CaptureMode.PANO -> Text("PANORÁMICA", color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            video -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.digitalStabActive) Text("EST.", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (state.can4k) SmallToggle(if (state.video4k) "4K" else "HD", state.video4k, vm::toggle4k)
                if (state.can60) SmallToggle(if (state.video60) "60" else "30", state.video60, vm::toggle60)
            }
            else -> Text(
                buildString {
                    if (state.rawActive) append("RAW")
                    if (state.ultraHdr) { if (isNotEmpty()) append(" · "); append("ULTRA HDR") }
                },
                color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.mode == CaptureMode.PHOTO) {
                TopIcon(if (state.livePhoto) Icons.Filled.MotionPhotosOn else Icons.Filled.MotionPhotosOff, "Foto en vivo",
                    if (state.livePhoto && state.liveActive) Yellow else Color.White, rot, onClick = vm::toggleLive)
            }
            if (stills) TopIcon(Icons.Outlined.AutoAwesome, "Estilos fotográficos", if (stylesOpen) Yellow else Color.White, rot, onClick = onStyles)
            TopIcon(Icons.Outlined.Settings, "Ajustes", Color.White, rot) { vm.showSettings(true) }
            TopIcon(Icons.Outlined.Info, "Info de cámara", Color.White, rot) { vm.showInfo(true) }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Yellow).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallToggle(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Yellow else Color(0x33FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun RecordingTimer(startMs: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val s = ((now - startMs) / 1000).coerceAtLeast(0)
    Text(
        "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60),
        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(RecordRed).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun QrChip(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(14.dp)).background(Yellow)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.QrCode2, null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color(0xCC000000), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FocusOverlay(focus: FocusMark?) {
    if (focus == null) return
    val anim = remember(focus.id) { Animatable(1.4f) }
    LaunchedEffect(focus.id) { anim.animateTo(1f, tween(250)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val sizeDp = 76.dp
        val x = with(d) { (focus.x * constraints.maxWidth).toDp() } - sizeDp / 2
        val y = with(d) { (focus.y * constraints.maxHeight).toDp() } - sizeDp / 2
        Box(Modifier.offset(x, y).size(sizeDp).scale(anim.value).border(1.5.dp, Yellow, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ShutterFlash(trigger: Long) {
    if (trigger == 0L) return
    val fade = remember(trigger) { Animatable(0.85f) }
    LaunchedEffect(trigger) { fade.animateTo(0f, tween(220)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black))
}

@Composable
private fun CaptureHint(night: Boolean, progress: Float) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (night) "Mantén el teléfono quieto" else "Capturando…", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(140.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Yellow))
        }
    }
}

@Composable
private fun StylePicker(current: PhotoStyle, onPick: (PhotoStyle) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoStyle.entries.forEach { s ->
            val sel = s == current
            Text(
                s.label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Yellow else Color(0x99000000))
                    .clickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ZoomChips(zoom: Float, stops: List<ZoomStop>, onZoom: (Float) -> Unit) {
    if (stops.size < 2) return
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stops.forEachIndexed { i, stop ->
            val next = stops.getOrNull(i + 1)?.ratio ?: Float.MAX_VALUE
            val active = zoom >= stop.ratio - 0.01f && zoom < next - 0.01f
            val label = if (active) "${CameraCapabilities.fmtZoom(zoom)}×" else CameraCapabilities.fmtZoom(stop.ratio)
            Box(
                Modifier.size(if (active) 38.dp else 32.dp).clip(CircleShape).background(Color(0xCC1C1C1E))
                    .clickable { onZoom(stop.ratio) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Yellow else Color.White, fontSize = if (active) 12.sp else 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: CaptureMode, onMode: (CaptureMode) -> Unit) {
    // Con 8 modos no caben todos: el seleccionado se centra deslizando la fila.
    val scroll = rememberScrollState()
    val centers = remember { mutableStateMapOf<CaptureMode, Float>() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = constraints.maxWidth.toFloat()
        val side = maxWidth / 2 - 40.dp
        val sidePx = with(LocalDensity.current) { side.toPx() }
        LaunchedEffect(mode, centers[mode], viewport) {
            // Las posiciones de los textos no incluyen el margen lateral de la fila.
            val c = centers[mode] ?: return@LaunchedEffect
            scroll.animateScrollTo((sidePx + c - viewport / 2).roundToInt().coerceIn(0, scroll.maxValue))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 14.dp).padding(horizontal = side),
        ) {
            CaptureMode.entries.forEach { m ->
                Text(
                    m.label, color = if (m == mode) Yellow else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1,
                    modifier = Modifier
                        .onGloballyPositioned { c -> centers[m] = c.positionInParent().x + c.size.width / 2f }
                        .clickable { onMode(m) }.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    video: Boolean, recording: Boolean, enabled: Boolean, burstAllowed: Boolean,
    onClick: () -> Unit, onBurstStart: () -> Unit, onBurstEnd: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val inner by animateFloatAsState(if (pressed) 0.86f else if (recording) 0.5f else 1f, label = "shutter")
    Box(
        Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
            .pointerInput(enabled, recording, burstAllowed) {
                var bursting = false
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        if (bursting) { bursting = false; onBurstEnd() }
                    },
                    onLongPress = {
                        if (burstAllowed && enabled) {
                            bursting = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBurstStart()
                        }
                    },
                    onTap = {
                        if (enabled || recording) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            video -> RecordRed
            enabled -> Color.White
            else -> Color(0xFF8E8E93)
        }
        Box(
            Modifier.fillMaxSize().scale(inner)
                .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun Thumbnail(state: CameraUiState, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).rotate(rotation).clip(RoundedCornerShape(8.dp)).background(Panel)
            .clickable(enabled = state.lastUri != null && !state.lastIsVideo, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = state.thumbnail
        if (thumb != null) {
            Image(thumb.asImageBitmap(), "Última foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (state.lastIsVideo) {
            Icon(Icons.Filled.Videocam, "Último vídeo", tint = Color.White)
        }
        if (state.processing > 0) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun RoundIconButton(onClick: () -> Unit, rotation: Float, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).rotate(rotation).clip(CircleShape).background(Panel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
