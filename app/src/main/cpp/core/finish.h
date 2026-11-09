// Fotón — revelado: RAW/YUV fusionado -> imagen SDR + mapa de ganancia Ultra HDR.
#pragma once

#include "burst.h"

namespace foton {

enum class Style { Standard = 0, RichContrast = 1, Vibrant = 2, Warm = 3, Cool = 4 };

struct StyleParams { float tone, warmth, vibrance; };
StyleParams styleParams(Style s);

/** Metadatos de color para RAW (de CaptureResult / CameraCharacteristics). */
struct RawMetadata {
    float wb[4] = {2.f, 1.f, 1.f, 1.6f};               // ganancias R, G1, G2, B
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};       // cámara (tras WB) -> sRGB lineal
    int lscW = 0, lscH = 0;                            // mapa de sombreado de lente (4 canales)
    std::vector<float> lsc;                            // lscW*lscH*4: R, G1, G2, B
};

struct FinishParams {
    float exposure = 1.f;      // ganancia lineal global
    bool smartHdr = true;
    Style style = Style::Standard;
    float sharpen = 0.7f;
    float hdrHeadroom = 4.f;   // ratio máximo del mapa de ganancia (Ultra HDR)
    bool gainMap = true;
    // Brillo automático: sube la exposición global si la escena quedó oscura (como el iPhone,
    // que no se queda con la exposición del sensor tal cual). Nunca oscurece.
    bool autoBrightness = true;
    float maxAutoGain = 2.5f;
};

/** Ganancia global del brillo automático para una luma lineal de baja resolución (>= 1). */
float autoBrightnessGain(const std::vector<float>& lum, bool smartHdr, float maxGain);

struct GainMapOut {
    int w = 0, h = 0;
    std::vector<uint8_t> data;   // 1 canal, codificado log(ratio)/log(ratioMax)
    float ratioMax = 1.f;
};

/**
 * Revela el resultado de la fusión. Escribe RGBA_8888 (orden de bytes R,G,B,A) en `out`
 * con `strideBytes` por fila. Las dimensiones de salida se consultan con outputSize().
 */
void outputSize(const MergeResult& m, int& w, int& h);
void finish(MergeResult& m, const RawMetadata* raw, const FinishParams& p,
            uint8_t* out, int strideBytes, GainMapOut* gain);

// ---------------- Utilidades de salida ----------------

/** Iluminación de retrato (mismo orden que PortraitLighting en Kotlin). */
enum class PortraitLight { Natural = 0, Studio = 1, Contour = 2, Stage = 3, StageMono = 4, HighKeyMono = 5 };

/**
 * Retrato: desenfoque de fondo con forma de diafragma (disco, con brillos que forman bolas de
 * bokeh) que crece con la distancia al sujeto, + iluminación opcional.
 * mask: 0..1 (1 = sujeto), de cualquier tamaño (se reescala y se refina con la imagen).
 * strength = radio del desenfoque respecto al ancho (≈ apertura; ver Portrait.strengthFor).
 */
void portraitBokeh(uint8_t* rgba, int w, int h, int strideBytes,
                   const float* mask, int mw, int mh, float strength, int lighting = 0);

/**
 * Exportación para redes: recorta (cx, cy, cw, ch), reduce con Lanczos en luz lineal
 * y aplica un enfoque de salida suave. dst debe medir dw x dh.
 */
void exportResize(const uint8_t* src, int sw, int sh, int sStride,
                  int cx, int cy, int cw, int ch,
                  uint8_t* dst, int dw, int dh, int dStride, float sharpen);

/** Historia 9:16: la foto entera centrada sobre una versión desenfocada y oscurecida. */
void composeStory(const uint8_t* src, int sw, int sh, int sStride,
                  uint8_t* dst, int dw, int dh, int dStride);

}  // namespace foton
