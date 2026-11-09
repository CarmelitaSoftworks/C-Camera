package com.carmelita.ccamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.carmelita.ccamera.ui.CameraScreen
import com.carmelita.ccamera.ui.CameraViewModel

class MainActivity : ComponentActivity() {

    /** La cámara es obligatoria; el micrófono (vídeo) y el almacenamiento (Android 9-) son opcionales. */
    private val requested: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    /** El mismo ViewModel que usa CameraScreen (ámbito de la actividad). */
    private val vm: CameraViewModel by viewModels()

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var granted by remember { mutableStateOf(hasCamera()) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                granted = hasCamera()
            }
            LaunchedEffect(Unit) { if (!granted) launcher.launch(requested) }
            if (granted) {
                CameraScreen()
            } else {
                Column(
                    Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("C Camera necesita acceso a la cámara", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Dar permiso", color = Color.Black, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFD60A))
                            .clickable { launcher.launch(requested) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    /** Botones de volumen como disparador (como en el iPhone), si está activado en Ajustes. */
    private fun volumeShutterActive(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (!hasCamera()) return false
        val s = vm.state.value
        return s.volumeShutter && s.viewer == null && !s.showInfo && !s.showSettings
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!volumeShutterActive(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) vm.shutter()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (volumeShutterActive(keyCode)) true else super.onKeyUp(keyCode, event)
}
