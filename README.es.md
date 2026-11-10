# C Camera

[English](README.md) · **Español** · [Português](README.pt.md)

Cámara para Android de **Carmelita Softworks** que procesa las fotos como un iPhone: guarda los
fotogramas de **antes** de pulsar (zero shutter lag), los **alinea y fusiona en RAW**, recupera las
luces con un fotograma corto (**Smart HDR**) y guarda en **Ultra HDR**. Tiene modos de **Foto,
Retrato, Noche, Pro, Panorámica, Vídeo 4K, Cámara lenta y Time-lapse**, estabilización propia,
**Fotos en vivo**, **lector de QR** y una exportación pensada para que **Instagram** muestre las
fotos lo mejor posible.

Kotlin · Camera2 · Jetpack Compose · motor de imagen en C++ (NDK) · OpenGL ES · ML Kit · minSdk 26

## Modos

| Modo | Qué hace |
|---|---|
| **Foto** | 4–6 fotogramas RAW del búfer ZSL fusionados; el disparador se libera al instante y la fusión sigue en segundo plano. Con poca luz pasa sola a **Noche automática** |
| **Retrato** | Desenfoque con forma de diafragma (bolas de bokeh) que crece con la distancia a la persona; apertura ƒ1,4–ƒ16 y 6 iluminaciones (natural, estudio, contorno, escenario, escenario mono, clave alta mono); usa el teleobjetivo si lo hay |
| **Noche** | 12 exposiciones largas fusionadas, con reducción de ruido según el ruido que queda |
| **Pro** | ISO, velocidad de obturación, balance de blancos y foco manuales |
| **Panorámica** | Se gira el teléfono hacia la derecha; los fotogramas se unen en el propio teléfono |
| **Vídeo** | 4K o 1080p, 30/60 fps, HEVC si está disponible, audio estéreo |
| **Cámara lenta** | 120/240 fps con la sesión de alta velocidad de Camera2 |
| **Time-lapse** | 2 fotogramas por segundo reproducidos a 30 (15× más rápido) |

## Procesado de imagen

| Función | Cómo |
|---|---|
| **Fusión multi-frame** (Deep Fusion / HDR+) | Alineación jerárquica con precisión subpíxel y fusión robusta según el perfil de ruido del sensor; se usa como referencia el fotograma más nítido |
| **Zero shutter lag** | Un búfer circular guarda los últimos fotogramas: la foto es el instante en que pulsas |
| **Smart HDR** | Un frame subexpuesto recupera las luces; tone mapping local (Mertens) con filtro guiado y reescalado bilateral para evitar halos |
| **Brillo y color automáticos** | Brillo automático tras la fusión, corrección del tinte que deja el balance de blancos del teléfono y punto negro que se adapta a escenas oscuras. Calibrado comparando con la cámara de fábrica |
| **Ultra HDR** (Android 14+) | Mapa de ganancia: en pantallas HDR las luces brillan; en las demás se ve la versión SDR |
| **Fotos en vivo** | ~1,5 s antes y después del disparo, guardado como *Motion Photo* |
| **Procesado del teléfono** | Si el fabricante lo ofrece (Camera2 Extensions), Foto, Noche y Retrato usan su propio procesado; si no, el motor de C Camera |

## Controles y ajustes

- Zoom por lentes (0,5× / 1× / 2× / 3× según el teléfono), tocar para enfocar, **mantener pulsado para
  bloquear AE/AF** y deslizar para cambiar la exposición.
- Temporizador de 3/10 s, **ráfaga** manteniendo el disparador, macro, flash y botones de volumen como
  disparador.
- Cuadrícula, nivel, proporción 4:3 / 16:9 / 1:1, espejo en la cámara frontal y estilos fotográficos.
- **Estabilización**: detecta el estabilizador óptico y el electrónico del teléfono; si falta,
  C Camera estabiliza el vídeo con el giroscopio (OpenGL) y elige el fotograma más nítido en las fotos.
- **Privacidad**: la ubicación está desactivada por defecto, y al compartir una foto que la tenga se
  envía una copia sin GPS.
- Guardar también el RAW en **DNG**.
- **Ahorro de batería**: tras 15 s sin tocar la pantalla la cámara pasa a reposo.
- La pantalla **Info de cámara** muestra todo lo que el teléfono permite (RAW, ZSL, OIS, ISO, cámara
  lenta, procesado del fabricante…).

### Instagram

Desde el visor de la última foto, **Instagram** prepara una copia y abre la app:

- **Tamaño exacto** con 1080 px de ancho, así Instagram no la vuelve a escalar: **3:4 (1080×1440)**,
  que es el formato más alto del feed y el único que la cuadrícula del perfil no recorta, además de
  4:5, 1:1 e Historia 9:16 (sobre fondo desenfocado).
- **Reducción Lanczos en luz lineal** + un enfoque de salida suave que compensa la recompresión.
- **JPEG de calidad 95**, **Ultra HDR conservado** y sin ubicación.

## Arquitectura

```
app/src/main/
├── cpp/                      motor nativo (C++17, multihilo)
│   ├── core/image.*          planos, filtros, Lanczos, filtro guiado, sRGB
│   ├── core/burst.*          alineación subpíxel + fusión robusta (Bayer o YUV)
│   ├── core/finish.*         revelado RAW, Smart HDR, brillo y color automáticos, Ultra HDR
│   ├── core/outputs.cpp      retrato, exportación para Instagram, historia
│   ├── jni_bridge.cpp
│   └── tests/                pruebas en el host (sin Android)
└── java/com/carmelita/ccamera/
    ├── camera/               Camera2, capacidades, ZSL, estabilización, vídeo, extensiones
    ├── processing/           NativeEngine (JNI), PhotoJob, Retrato, Panorámica
    ├── storage/              guardado, EXIF (con GPS), Motion Photo, DNG
    ├── share/                exportación e intent de Instagram
    ├── qr/                   lector e intérprete de QR
    └── ui/                   Compose: cámara, ajustes, visor
```

## Compilar

El workflow de GitHub Actions **no se ejecuta solo**: lánzalo desde *Actions → Build → Run workflow*
(o subiendo un tag `v*`, que además publica el APK en una Release). Los pushes y los PR no lanzan nada.

En local:

```bash
# Pruebas del motor nativo (sólo necesita cmake y un compilador de C++)
cmake -S app/src/main/cpp/tests -B build-tests && cmake --build build-tests && ./build-tests/foton_tests

./gradlew testDebugUnitTest   # pruebas Kotlin (EXIF, Motion Photo, QR, panorámica, estabilización)
./gradlew assembleRelease     # APK en app/build/outputs/apk/release/
```

Hace falta JDK 17 y el Android SDK con NDK (Android Studio los instala).

> El APK de release se firma con la clave de debug para poder instalarlo directamente. Antes de
> publicarlo en una tienda, configura tu propia `signingConfig`.

## Estado y límites

- Compila y pasan las pruebas del motor nativo y de Kotlin. Se está probando en un vivo V30; algunos
  modos (cámara lenta, time-lapse, panorámica, Pro) aún no se han probado en un teléfono.
- Cada fabricante expone cosas distintas en Camera2. Si un teléfono no da RAW, ZSL o el procesado del
  fabricante, la app baja automáticamente a lo que sí funciona (lo verás en *Info de cámara*).
- Las Fotos en vivo no llevan audio.
- El modo Retrato separa personas; no mascotas ni objetos.

## Licencia

GPL-3.0 (ver `LICENSE`). Usa ML Kit de Google (gratuito pero no libre) para el QR y el retrato.
