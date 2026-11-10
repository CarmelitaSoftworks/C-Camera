# C Camera — contexto para Claude Code

App de cámara Android de Carmelita Softworks que procesa las fotos como un iPhone y las prepara
para Instagram. Repo: `CarmelitaSoftworks/C-Camera` (licencia GPL-3.0). Interfaz, comentarios y
mensajes al usuario **en español**.

## Reglas del dueño del proyecto

- **No compilar, hacer push ni publicar nada sin que el usuario lo pida.** El workflow de GitHub
  Actions sólo corre a mano (Actions → Build → Run workflow) o con un tag `v*`; pushes y PRs no
  lanzan nada. Compilar sólo cuando el usuario lo diga.
- **No martillear la API de GitHub** (nada de bucles de sondeo cada pocos segundos). El 9-nov-2026
  la cuenta de GitHub quedó suspendida justo después de una sesión automatizada que hizo push,
  abrió el PR #1, creó una etiqueta, lanzó Actions y consultó la API cada 15 s. Se recuperó
  la cuenta y el proyecto vuelve a trabajarse sólo en GitHub (la copia de GitLab queda sin uso).
- Commits con mensajes en español.

## Estado (9-nov-2026)

- Rama de trabajo en GitHub (no `c-camera-v0.2`: tiene el PR #1 con la etiqueta `compilar`).
- `./gradlew assembleDebug`, `assembleRelease` y `testDebugUnitTest` compilan y pasan (9-nov-2026,
  en local con SDK 35, build-tools 35, cmake 3.22.1 y el NDK por defecto de AGP 8.9.1).
- El motor C++ y el Kotlin puro (EXIF, Motion Photo, QR) sí están probados.
- 9-nov-2026: compila (debug y release, con lint) y pasan las 14 pruebas JVM y las nativas.
  **Nada de lo siguiente se ha probado en un teléfono todavía**: modos Time-lapse,
  Cámara lenta (sesión de alta velocidad), Panorámica (`PanoramaStitcher`, con prueba JVM) y Pro
  (ISO, velocidad, balance de blancos, foco); temporizador, ráfaga manteniendo el disparador,
  bloqueo AE/AF con pulsación larga, compensación de exposición deslizando, macro, cuadrícula,
  nivel, proporción 4:3/16:9/1:1, espejo frontal, ubicación en EXIF (GPS IFD), DNG opcional,
  botones de volumen y pantalla de Ajustes. Retrato nuevo: desenfoque en disco (bolas de bokeh)
  que crece con la distancia al sujeto, apertura ƒ1,4–ƒ16, 6 iluminaciones (natural, estudio,
  contorno, escenario, escenario mono, clave alta mono), aviso en vivo de persona y teleobjetivo
  2-3x si lo hay. Ubicación: desactivada por defecto; "Compartir sin ubicación" (ExifWriter.stripGps)
  envía una copia sin GPS. Estabilización (Ajustes): Automática / C Camera / Desactivada. Fotos:
  ReferencePicker elige el fotograma más nítido como referencia. Vídeo: DigitalStabilizer
  (giroscopio + OpenGL, recorte 1,2x) si el teléfono no tiene EIS propio; signos de los ejes en
  StabilizationMath (si en el teléfono el temblor aumenta en vez de bajar, revisar esos signos).
- No se ha probado en ningún teléfono.

### Siguiente paso

1. Probar en un teléfono: foto (RAW/ZSL), Smart HDR, noche, retrato, flash, vídeo 4K, Fotos en vivo
   (comprobar que Google Fotos reconoce el Motion Photo), QR, exportación a Instagram y pantalla
   *Info de cámara* (qué detectó en ese teléfono).

### Procesado del teléfono (modo principal; en el vivo V30 del usuario NO está disponible)

En el vivo V30 (V2318) Info de cámara muestra "Procesado del teléfono: No": el fabricante no ofrece
Camera2 Extensions a otras apps, así que allí siempre se usa el motor propio. Datos de ese teléfono:
RAW 4096x3072 con ZSL, LEVEL 3, ISO manual 50-3188 (la cámara de fábrica llega a ~17 000 con ganancia
del firmware), OIS sí, EIS del teléfono no, cámara lenta 1080p a 240 fps.

Por decisión del usuario, Foto/Noche/Retrato usan primero el procesado del propio teléfono
(Camera2 Extensions: Automático/HDR, Noche, Retrato) si la cámara lo ofrece; si no, o si se
desactiva en Ajustes ("Procesado del teléfono"), el motor de C Camera. El JPEG del fabricante se
guarda tal cual (sin estilos, proporción, ráfaga ni Foto en vivo). Pro, Panorámica y vídeo usan
siempre el motor propio. El APK de la cámara de fábrica (vivo) no contiene el procesado (0 .so:
está en el firmware/HAL); sus modelos son propietarios y no se copian.

## Comandos

```bash
# Motor nativo en el host (sólo cmake + compilador C++), escribe imágenes .ppm en test-out/
cmake -S app/src/main/cpp/tests -B build-tests && cmake --build build-tests -j && mkdir -p test-out && ./build-tests/foton_tests test-out

./gradlew testDebugUnitTest   # Kotlin puro: ExifWriter, MotionPhotoWriter, QrContent
./gradlew assembleDebug       # APK de depuración
./gradlew assembleRelease     # APK de release (firmado con la clave de debug a propósito)
```

## Arquitectura

```
app/src/main/cpp/                 motor nativo C++17, multihilo (namespace foton)
  core/image.*                    Plane, filtros, Lanczos, filtro guiado, sRGB (LUT)
  core/burst.*                    alineación jerárquica subpíxel + fusión robusta (Bayer o YUV),
                                  frame corto para altas luces
  core/finish.*                   revelado: sombreado de lente, WB, croma con filtro guiado,
                                  enfoque en raíz cuadrada, demosaico MHC, CCM, Smart HDR
                                  (Mertens + filtro guiado + reescalado bilateral), estilos,
                                  mapa de ganancia Ultra HDR
  core/outputs.cpp                retrato (bokeh), exportación Instagram, historia 9:16
  jni_bridge.cpp                  JNI -> com.carmelita.ccamera.processing.NativeEngine
  tests/test_main.cpp             ráfagas sintéticas RAW/YUV con comprobaciones (PSNR, etc.)
app/src/main/java/com/carmelita/ccamera/
  camera/CameraCapabilities.kt    detección de lo que soporta el teléfono (RAW, ZSL, lentes, vídeo...)
  camera/CameraController.kt      Camera2: sesiones de foto (prueba RAW+ZSL+vivo -> ... -> YUV) y vídeo,
                                  3A, flash con precaptura, ráfagas, frame corto
  camera/FrameBuffer.kt           anillo ZSL (guarda Image sin copiar) + entrega por timestamp
  camera/LivePhotoRecorder.kt     H.264 continuo en memoria; clip ±1,5 s
  camera/VideoRecorder.kt         MediaRecorder
  processing/PhotoPipeline.kt     PhotoJob (metadatos RAW -> nativo, Gainmap) y Portrait (ML Kit)
  storage/                        MediaSaver, ExifWriter y MotionPhotoWriter (estos dos Kotlin puro)
  share/InstagramExporter.kt      1080x1440/1350/1080/1920, conserva Ultra HDR, intent a Instagram
  qr/                             QrContent (intérprete, Kotlin puro) y QrScanner/QrActions (ML Kit)
  ui/                             Compose: CameraScreen, ViewerScreen (visor + Instagram + Info), ViewModel,
                                  SettingsSheet (Ajustes), Overlays (cuadrícula, nivel, Pro, panorámica...)
  processing/PanoramaStitcher.kt  panorámica con fotogramas de la vista previa (Kotlin puro)
  processing/ImageOps.kt          recorte de proporción y espejo conservando el mapa de ganancia
```

## Decisiones y detalles a no romper

- Planos Bayer canónicos en C++: R, G1 (G en fila de R), G2, B; `cfaMap` en el JNI traduce el CFA.
- RAW normalizado 0..1 con nivel de negro por posición del 2x2 (`SENSOR_DYNAMIC_BLACK_LEVEL`).
- Ruido: `SENSOR_NOISE_PROFILE` si existe; si no, estimación por percentil 25 de bloques.
- Ultra HDR: gain map 1 canal, ratioMin 1, ratioMax = headroom (4), gamma 1, epsilon 1/64.
- Motion Photo: el XMP del Ultra HDR se amplía sólo si el MPF va después del XMP (si no, se guarda
  sin vídeo). EXIF se escribe a mano para no perder lo que va tras la imagen principal.
- Los modelos de ML Kit van dentro del APK (no dependen de Google Play Services).
