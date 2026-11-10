# C Camera

[English](README.md) · [Español](README.es.md) · **Português**

Câmera para Android da **Carmelita Softworks** que processa as fotos como um iPhone: guarda os quadros
de **antes** de você apertar (zero shutter lag), **alinha e funde em RAW**, recupera as altas luzes com
um quadro curto (**Smart HDR**) e salva em **Ultra HDR**. Tem modos **Foto, Retrato, Noite, Pro,
Panorâmica, Vídeo 4K, Câmera lenta e Time-lapse**, estabilização própria, **Fotos ao vivo**, **leitor
de QR** e uma exportação pensada para que o **Instagram** mostre suas fotos da melhor forma.

Kotlin · Camera2 · Jetpack Compose · motor de imagem em C++ (NDK) · OpenGL ES · ML Kit · minSdk 26

## Modos

| Modo | O que faz |
|---|---|
| **Foto** | 4–6 quadros RAW do buffer ZSL fundidos; o obturador é liberado na hora e a fusão continua em segundo plano. Com pouca luz passa sozinho para **Noite automática** |
| **Retrato** | Desfoque com forma de diafragma (bolas de bokeh) que cresce com a distância até a pessoa; abertura ƒ1,4–ƒ16 e 6 iluminações (natural, estúdio, contorno, palco, palco mono, high-key mono); usa a teleobjetiva se houver |
| **Noite** | 12 exposições longas fundidas, com redução de ruído de acordo com o ruído restante |
| **Pro** | ISO, velocidade do obturador, balanço de branco e foco manuais |
| **Panorâmica** | Gire o celular para a direita; os quadros são unidos no próprio celular |
| **Vídeo** | 4K ou 1080p, 30/60 fps, HEVC quando disponível, áudio estéreo |
| **Câmera lenta** | 120/240 fps com a sessão de alta velocidade do Camera2 |
| **Time-lapse** | 2 quadros por segundo reproduzidos a 30 (15× mais rápido) |

## Processamento de imagem

| Função | Como |
|---|---|
| **Fusão multi-quadro** (Deep Fusion / HDR+) | Alinhamento hierárquico com precisão subpixel e fusão robusta conforme o perfil de ruído do sensor; o quadro mais nítido é usado como referência |
| **Zero shutter lag** | Um buffer circular guarda os últimos quadros: a foto é o instante em que você aperta |
| **Smart HDR** | Um quadro subexposto recupera as altas luzes; tone mapping local (Mertens) com filtro guiado e reamostragem bilateral para evitar halos |
| **Brilho e cor automáticos** | Brilho automático após a fusão, correção da tonalidade deixada pelo balanço de branco do celular e ponto preto que se adapta a cenas escuras. Calibrado comparando com a câmera de fábrica |
| **Ultra HDR** (Android 14+) | Mapa de ganho: em telas HDR as altas luzes brilham; nas demais aparece a versão SDR |
| **Fotos ao vivo** | ~1,5 s antes e depois do disparo, salvo como *Motion Photo* |
| **Processamento do celular** | Se o fabricante oferecer (Camera2 Extensions), Foto, Noite e Retrato usam o processamento dele; senão, o motor do C Camera |

## Controles e ajustes

- Zoom por lentes (0,5× / 1× / 2× / 3× conforme o celular), toque para focar, **pressione e segure para
  travar AE/AF** e deslize para mudar a exposição.
- Temporizador de 3/10 s, **rajada** segurando o obturador, macro, flash e botões de volume como obturador.
- Grade, nível, proporção 4:3 / 16:9 / 1:1, espelhamento na câmera frontal e estilos fotográficos.
- **Estabilização**: detecta o estabilizador óptico e o eletrônico do celular; se faltar, o C Camera
  estabiliza o vídeo com o giroscópio (OpenGL) e escolhe o quadro mais nítido nas fotos.
- **Privacidade**: a localização vem desativada, e ao compartilhar uma foto que a tenha é enviada uma
  cópia sem GPS.
- Salvar também o RAW em **DNG**.
- **Economia de bateria**: após 15 s sem tocar na tela a câmera entra em repouso.
- A tela **Info da câmera** mostra tudo o que o celular permite (RAW, ZSL, OIS, ISO, câmera lenta,
  processamento do fabricante…).

### Instagram

No visualizador da última foto, **Instagram** prepara uma cópia e abre o app:

- **Tamanho exato** com 1080 px de largura, assim o Instagram não redimensiona: **3:4 (1080×1440)**, o
  formato mais alto do feed e o único que a grade do perfil não corta, além de 4:5, 1:1 e Story 9:16
  (sobre fundo desfocado).
- **Redução Lanczos em luz linear** + uma nitidez de saída suave que compensa a recompressão.
- **JPEG de qualidade 95**, **Ultra HDR preservado** e sem localização.

## Arquitetura

```
app/src/main/
├── cpp/                      motor nativo (C++17, multithread)
│   ├── core/image.*          planos, filtros, Lanczos, filtro guiado, sRGB
│   ├── core/burst.*          alinhamento subpixel + fusão robusta (Bayer ou YUV)
│   ├── core/finish.*         revelação RAW, Smart HDR, brilho e cor automáticos, Ultra HDR
│   ├── core/outputs.cpp      retrato, exportação para Instagram, story
│   ├── jni_bridge.cpp
│   └── tests/                testes no host (sem Android)
└── java/com/carmelita/ccamera/
    ├── camera/               Camera2, capacidades, ZSL, estabilização, vídeo, extensões
    ├── processing/           NativeEngine (JNI), PhotoJob, Retrato, Panorâmica
    ├── storage/              salvamento, EXIF (com GPS), Motion Photo, DNG
    ├── share/                exportação e intent do Instagram
    ├── qr/                   leitor e intérprete de QR
    └── ui/                   Compose: câmera, ajustes, visualizador
```

A interface do app está em espanhol.

## Compilar

O workflow do GitHub Actions **não roda sozinho**: inicie em *Actions → Build → Run workflow* (ou
enviando uma tag `v*`, que também publica o APK em uma Release). Pushes e PRs não disparam nada.

Localmente:

```bash
# Testes do motor nativo (só precisa de cmake e um compilador C++)
cmake -S app/src/main/cpp/tests -B build-tests && cmake --build build-tests && ./build-tests/foton_tests

./gradlew testDebugUnitTest   # testes Kotlin (EXIF, Motion Photo, QR, panorâmica, estabilização)
./gradlew assembleRelease     # APK em app/build/outputs/apk/release/
```

Requer JDK 17 e o Android SDK com NDK (o Android Studio instala).

> O APK de release é assinado com a chave de debug para poder ser instalado diretamente. Antes de
> publicá-lo em uma loja, configure sua própria `signingConfig`.

## Estado e limitações

- Compila e os testes do motor nativo e do Kotlin passam. Está sendo testado em um vivo V30; alguns
  modos (câmera lenta, time-lapse, panorâmica, Pro) ainda não foram testados em um celular.
- Cada fabricante expõe coisas diferentes no Camera2. Se um celular não oferece RAW, ZSL ou o
  processamento do fabricante, o app recorre automaticamente ao que funciona (veja em *Info da câmera*).
- As Fotos ao vivo não têm áudio.
- O modo Retrato separa pessoas, não animais nem objetos.

## Licença

GPL-3.0 (veja `LICENSE`). Usa o ML Kit do Google (gratuito, mas não livre) para o QR e o retrato.
