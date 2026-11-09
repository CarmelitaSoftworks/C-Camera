// Fotón — utilidades de imagen (C++17, sin dependencias de Android).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <thread>
#include <vector>

namespace foton {

inline float clamp01(float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); }
inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float smoothstep(float a, float b, float x) {
    float t = clamp01((x - a) / (b - a));
    return t * t * (3.f - 2.f * t);
}

/** Ejecuta fn(y0, y1) sobre franjas de [0, n) en varios hilos. */
void parallelFor(int n, const std::function<void(int, int)>& fn);
int numThreads();

/** Plano de floats de un canal. */
struct Plane {
    int w = 0, h = 0;
    std::vector<float> d;

    Plane() = default;
    Plane(int w_, int h_, float v = 0.f) : w(w_), h(h_), d(size_t(w_) * h_, v) {}

    float* row(int y) { return d.data() + size_t(y) * w; }
    const float* row(int y) const { return d.data() + size_t(y) * w; }
    float& at(int x, int y) { return d[size_t(y) * w + x]; }
    float at(int x, int y) const { return d[size_t(y) * w + x]; }
    float atc(int x, int y) const { return d[size_t(clampi(y, 0, h - 1)) * w + clampi(x, 0, w - 1)]; }
    bool empty() const { return d.empty(); }

    /** Bilineal con borde replicado. */
    float sample(float x, float y) const {
        x = std::min(std::max(x, 0.f), w - 1.001f);
        y = std::min(std::max(y, 0.f), h - 1.001f);
        int x0 = int(x), y0 = int(y);
        float fx = x - x0, fy = y - y0;
        const float* p = d.data() + size_t(y0) * w + x0;
        float a = p[0], b = p[1], c = p[w], e = p[w + 1];
        return (a + (b - a) * fx) * (1 - fy) + (c + (e - c) * fx) * fy;
    }

    /** Catmull-Rom (bicúbica): conserva más detalle que la bilineal al deformar frames. */
    float sampleCubic(float x, float y) const {
        int x0 = int(std::floor(x)), y0 = int(std::floor(y));
        float fx = x - x0, fy = y - y0;
        float wx[4], wy[4];
        cr(fx, wx); cr(fy, wy);
        float acc = 0;
        for (int j = 0; j < 4; j++) {
            const float* r = row(clampi(y0 - 1 + j, 0, h - 1));
            float s = 0;
            for (int i = 0; i < 4; i++) s += wx[i] * r[clampi(x0 - 1 + i, 0, w - 1)];
            acc += wy[j] * s;
        }
        return acc;
    }

private:
    static void cr(float t, float* w4) {
        float t2 = t * t, t3 = t2 * t;
        w4[0] = -0.5f * t3 + t2 - 0.5f * t;
        w4[1] = 1.5f * t3 - 2.5f * t2 + 1.f;
        w4[2] = -1.5f * t3 + 2.f * t2 + 0.5f * t;
        w4[3] = 0.5f * t3 - 0.5f * t2;
    }
};

Plane down2(const Plane& s);                 // media 2x2
Plane box3(const Plane& s);
Plane gauss5(const Plane& s);                // (1 4 6 4 1)/16 separable
Plane blur(const Plane& s, int passes);      // gauss5 repetido
Plane resizeBilinear(const Plane& s, int w, int h);
/** Ampliación 2x bilineal a (w, h) con el mapeo de centros de down2. */
Plane up2(const Plane& s, int w, int h);
/** Reducción de alta calidad (Lanczos-3, separable). */
Plane resizeLanczos(const Plane& s, int w, int h);

/** Caja deslizante O(1) por píxel (radio r) en horizontal / vertical. */
void boxH(Plane& p, int r);
void boxV(Plane& p, int r);
/** Tres pasadas de caja ~ gaussiana de sigma ≈ r. */
void softBlur(Plane& p, int r);
/** Filtro guiado (He et al.): suaviza pIn respetando los bordes de I. */
Plane guidedFilter(const Plane& I, const Plane& pIn, int r, float eps);

// ---- Color ----
float srgbToLin(float x);
float linToSrgb(float x);
float srgbToLinFast(float x);   // LUT
float linToSrgbFast(float x);   // LUT (precisión en sombras)

}  // namespace foton
