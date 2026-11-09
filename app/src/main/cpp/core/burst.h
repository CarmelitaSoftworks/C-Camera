// Fotón — alineación y fusión de ráfagas (RAW Bayer o YUV).
#pragma once

#include "image.h"

namespace foton {

enum class FrameKind { Bayer, Yuv };

/**
 * Un frame normalizado.
 *  - Bayer: planes = {R, G1, G2, B} a media resolución, lineales 0..1 (ya sin nivel de negro).
 *           guide  = media de los 4 canales (misma resolución).
 *  - Yuv:   planes = {Y (completa), U, V (media res)}, Y en 0..1, U/V centrados en 0.
 *           guide  = Y.
 */
struct Frame {
    FrameKind kind = FrameKind::Yuv;
    Plane guide;
    std::vector<Plane> planes;
    std::vector<int> scale;  // resolución de guide / resolución del plano (1 o 2)

    static Frame makeYuv(Plane y, Plane u, Plane v);
    static Frame makeBayer(Plane r, Plane g1, Plane g2, Plane b);
};

/** Ruido de un canal: varianza = s * nivel + o (nivel normalizado 0..1). */
struct NoiseModel {
    float s = 0.f, o = 1e-5f;
    float sigma(float level) const { return std::sqrt(std::max(1e-10f, s * std::max(level, 0.f) + o)); }
};

/** Estimación de ruido desde la imagen cuando el sensor no da perfil (devuelve modelo constante). */
NoiseModel estimateNoise(const Plane& p);

/** Desplazamientos por tile en la rejilla de la guía (con precisión subpíxel en el último nivel). */
struct TileOffsets {
    int tx = 0, ty = 0, ts = 32;
    std::vector<float> dx, dy;
};

TileOffsets alignPyramids(const std::vector<Plane>& ref, const std::vector<Plane>& alt, int tile0);

struct MergeResult {
    FrameKind kind;
    std::vector<Plane> planes;        // fusionados
    std::vector<Plane> shortPlanes;   // frame corto alineado, sin escalar (vacío si no hay)
    float shortRatio = 1.f;           // exposición ref / exposición corta
    std::vector<int> scale;
    NoiseModel noise;                 // del canal individual (antes de fusionar)
    float effectiveFrames = 1.f;
    /** Sigma residual aproximada a nivel 0.18 (para enfoque/denoise). */
    float residualSigma() const { return noise.sigma(0.18f) / std::sqrt(std::max(1.f, effectiveFrames)); }
};

/**
 * Fusión en streaming: el primer frame es la referencia; los demás se alinean y acumulan con
 * pesos de robustez (por tile + rechazo de fantasmas por píxel en todos los planos).
 */
class Merger {
public:
    /** noise: modelo de ruido de UN canal (Bayer) o de Y (Yuv). */
    Merger(Frame ref, NoiseModel noise);
    void add(const Frame& alt);
    /** Frame subexpuesto para recuperar altas luces (ratio = exposición ref / exposición corta). */
    void setShort(const Frame& shortFrame, float ratio);
    MergeResult result();
    int framesAdded() const { return frames_; }
    int outputWidth() const { return ref_.kind == FrameKind::Bayer ? ref_.planes[0].w * 2 : ref_.planes[0].w; }
    int outputHeight() const { return ref_.kind == FrameKind::Bayer ? ref_.planes[0].h * 2 : ref_.planes[0].h; }

private:
    Frame ref_;
    NoiseModel noise_;
    NoiseModel guideNoise_;
    int tile0_;
    std::vector<Plane> pyr_;              // pirámide de la guía de referencia (niveles 0..3)
    std::vector<Plane> refBox_;           // box3 de cada plano de referencia
    std::vector<Plane> acc_, wsum_;
    std::vector<Plane> short_;
    float shortRatio_ = 1.f;
    double weightSum_ = 0;                // suma de pesos de la guía (para nEff)
    int frames_ = 1;

    void warpWithOffsets(const Frame& alt, const TileOffsets& off, std::vector<Plane>& out) const;
};

std::vector<Plane> pyramid(const Plane& p, int levels);

}  // namespace foton
