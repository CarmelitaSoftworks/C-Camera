#include "finish.h"

namespace foton {

StyleParams styleParams(Style s) {
    switch (s) {
        // Calibrado contra la vista previa del ISP: la versión anterior salía ~35 % menos
        // saturada, con blancos grises y algo fría.
        case Style::RichContrast: return {0.6f, 0.2f, 0.45f};
        case Style::Vibrant: return {0.15f, 0.1f, 0.75f};
        case Style::Warm: return {0.f, 0.7f, 0.4f};
        case Style::Cool: return {0.f, -0.5f, 0.4f};
        default: return {0.f, 0.12f, 0.4f};
    }
}

void outputSize(const MergeResult& m, int& w, int& h) {
    if (m.kind == FrameKind::Bayer) { w = m.planes[0].w * 2; h = m.planes[0].h * 2; }
    else { w = m.planes[0].w; h = m.planes[0].h; }
}

namespace {

// ---------------- Tono y color (común a RAW y YUV) ----------------

struct Renderer {
    float warmR, warmB, contrast, vib, satMul;
    explicit Renderer(Style s, float saturation = 1.f) : satMul(saturation) {
        StyleParams p = styleParams(s);
        warmR = 1.f + 0.06f * p.warmth;
        warmB = 1.f - 0.06f * p.warmth;
        contrast = 0.42f + 0.4f * p.tone;
        vib = p.vibrance;
    }
    static float curve(float s, float c) {
        float ss = s * s * (3.f - 2.f * s);
        return s + c * (ss - s);
    }
    /** rgb lineal (ya con ganancia) -> sRGB 0..1 */
    void sdr(float r, float g, float b, float* o) const {
        r *= warmR; b *= warmB;
        // Punto negro: el ISP del teléfono deja los negros en negro; sin esto las sombras salen lavadas.
        const float black = 0.0035f;
        r = std::max(r - black, 0.f) * (1.f + black);
        g = std::max(g - black, 0.f) * (1.f + black);
        b = std::max(b - black, 0.f) * (1.f + black);
        const float knee = 0.88f;
        float m = std::max(r, std::max(g, b));
        if (m > knee) {
            // Hombro suave: llega a ~95 % del blanco hacia 4x el blanco (con gradación en las luces).
            float sm = knee + (1.f - knee) * (1.f - std::exp(-(m - knee) / 1.07f));
            float k = sm / m;
            // Las luces muy altas se desaturan un poco hacia blanco (como la película).
            float desat = smoothstep(1.0f, 3.0f, m) * 0.6f;
            float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = (r + (l - r) * desat) * k; g = (g + (l - g) * desat) * k; b = (b + (l - b) * desat) * k;
        }
        r = curve(linToSrgbFast(r), contrast);
        g = curve(linToSrgbFast(g), contrast);
        b = curve(linToSrgbFast(b), contrast);
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        float mx = std::max(r, std::max(g, b)), mn = std::min(r, std::min(g, b));
        float sat = mx - mn;
        // Saturación global moderada + vibrancia (más en colores apagados que en los ya intensos).
        float fac = 1.f + vib * (0.35f + 0.65f * (1.f - sat));
        bool skin = r >= g && g >= b && sat > 0.05f && sat < 0.6f && (g - b) > 0.25f * (r - b);
        if (skin) fac = 1.f + (fac - 1.f) * 0.55f;
        // Saturación extra (con ISO alto la reducción de ruido de croma se lleva color).
        fac *= skin ? 1.f + (satMul - 1.f) * 0.5f : satMul;
        o[0] = clamp01(l + (r - l) * fac);
        o[1] = clamp01(l + (g - l) * fac);
        o[2] = clamp01(l + (b - l) * fac);
    }
};

inline uint8_t to8(float v) { return uint8_t(clamp01(v) * 255.f + 0.5f); }

// ---------------- Smart HDR: fusión de exposiciones sintéticas ----------------

std::vector<Plane> gaussPyr(const Plane& a, int n) {
    std::vector<Plane> p{a};
    for (int i = 1; i < n; i++) p.push_back(down2(gauss5(p.back())));
    return p;
}

std::vector<Plane> lapPyr(const Plane& a, int n) {
    auto g = gaussPyr(a, n);
    std::vector<Plane> out;
    for (int i = 0; i < n - 1; i++) {
        Plane up = up2(g[i + 1], g[i].w, g[i].h);
        Plane l(g[i].w, g[i].h);
        for (size_t k = 0; k < l.d.size(); k++) l.d[k] = g[i].d[k] - up.d[k];
        out.push_back(std::move(l));
    }
    out.push_back(g.back());
    return out;
}

/** Devuelve la ganancia lineal local (Mertens sobre exposiciones sintéticas de la luma). */
Plane fuseGain(const Plane& lum, const std::vector<float>& exposures, float localMix) {
    int w = lum.w, h = lum.h;
    int n = std::max(1, int(std::floor(std::log2(float(std::min(w, h))))) - 2);
    std::vector<Plane> L, W;
    for (float e : exposures) {
        Plane l(w, h), wt(w, h);
        for (size_t i = 0; i < l.d.size(); i++) {
            float v = linToSrgb(lum.d[i] * e);
            l.d[i] = v;
            float d = v - 0.5f;
            wt.d[i] = std::exp(-d * d / (2.f * 0.25f * 0.25f)) + 1e-6f;
        }
        L.push_back(std::move(l)); W.push_back(std::move(wt));
    }
    for (size_t i = 0; i < lum.d.size(); i++) {
        float s = 0;
        for (auto& wt : W) s += wt.d[i];
        for (auto& wt : W) wt.d[i] /= s;
    }
    std::vector<Plane> acc;
    for (size_t k = 0; k < L.size(); k++) {
        auto lp = lapPyr(L[k], n);
        auto gp = gaussPyr(W[k], n);
        for (int i = 0; i < n; i++) {
            for (size_t q = 0; q < lp[i].d.size(); q++) lp[i].d[q] *= gp[i].d[q];
            if (acc.size() < size_t(n)) acc.push_back(lp[i]);
            else for (size_t q = 0; q < lp[i].d.size(); q++) acc[i].d[q] += lp[i].d[q];
        }
    }
    Plane f = acc.back();
    for (int i = n - 2; i >= 0; i--) {
        Plane up = up2(f, acc[i].w, acc[i].h);
        for (size_t q = 0; q < up.d.size(); q++) up.d[q] += acc[i].d[q];
        f = std::move(up);
    }
    Plane g(w, h);
    for (size_t i = 0; i < g.d.size(); i++) {
        float fl = srgbToLin(clamp01(f.d[i]));
        float local = std::min(std::max(fl / std::max(lum.d[i], 1e-4f), 1.f / 32.f), 8.f);
        // Mezcla local/global (global = 1) para evitar halos; la compresión de luces se aplica
        // con más fuerza que el levantamiento de sombras (las sombras levantadas de más se ven planas).
        float lg = std::log(local);
        // Compresión de luces según lo brillante que sea la zona: los blancos normales (camisa,
        // pared) casi no se tocan y quedan blancos; ventanas y cielos (por encima del blanco)
        // se comprimen con fuerza para conservar textura.
        float strength = localMix;
        if (lg < 0) strength = localMix + (1.f - localMix) * smoothstep(0.7f, 2.0f, lum.d[i]);
        g.d[i] = std::exp(lg * strength);
    }
    return g;
}

/** Imagen lineal de baja resolución (para Smart HDR y el mapa de ganancia). */
struct LowRes { Plane r, g, b; };

LowRes downsampleRgb(const Plane& r, const Plane& g, const Plane& b, int maxSide) {
    int f = std::max(1, int(std::ceil(std::max(r.w, r.h) / float(maxSide))));
    int lw = r.w / f, lh = r.h / f;
    LowRes o{Plane(lw, lh), Plane(lw, lh), Plane(lw, lh)};
    parallelFor(lh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < lw; x++) {
                float sr = 0, sg = 0, sb = 0;
                for (int dy = 0; dy < f; dy++)
                    for (int dx = 0; dx < f; dx++) {
                        int xx = x * f + dx, yy = y * f + dy;
                        sr += r.at(xx, yy); sg += g.at(xx, yy); sb += b.at(xx, yy);
                    }
                float k = 1.f / (f * f);
                o.r.at(x, y) = sr * k; o.g.at(x, y) = sg * k; o.b.at(x, y) = sb * k;
            }
    });
    return o;
}

/** Mapa de ganancia local + luma de baja resolución (para el reescalado conjunto). */
struct GainField {
    Plane gain, logLum;
    /**
     * Ganancia en un píxel de salida: reescalado bilateral conjunto (los 4 vecinos de baja
     * resolución pesan según se parezca su luma a la del píxel). Evita bordes oscuros finos
     * alrededor de zonas muy brillantes.
     */
    float at(float fx, float fy, float pixLum) const {
        if (gain.w == 1) return gain.d[0];
        fx = std::min(std::max(fx, 0.f), gain.w - 1.001f);
        fy = std::min(std::max(fy, 0.f), gain.h - 1.001f);
        int x0 = int(fx), y0 = int(fy);
        float ax = fx - x0, ay = fy - y0;
        float ll = std::log(std::max(pixLum, 1e-4f));
        float wsum = 0, g = 0;
        const float bw[4] = {(1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay};
        const int ox[4] = {0, 1, 0, 1}, oy[4] = {0, 0, 1, 1};
        for (int k = 0; k < 4; k++) {
            float d = ll - logLum.at(x0 + ox[k], y0 + oy[k]);
            float w = bw[k] * std::exp(-d * d * 2.f) + 1e-6f;
            g += w * gain.at(x0 + ox[k], y0 + oy[k]);
            wsum += w;
        }
        return g / wsum;
    }
};

Plane localGain(const LowRes& lr, const FinishParams& p, float pre) {
    if (!p.smartHdr) return Plane(1, 1, 1.f);
    Plane lum(lr.r.w, lr.r.h);
    for (size_t i = 0; i < lum.d.size(); i++)
        lum.d[i] = pre * (0.2126f * lr.r.d[i] + 0.7152f * lr.g.d[i] + 0.0722f * lr.b.d[i]);
    // La exposición sintética más oscura se adapta a las luces de la escena (percentil 99),
    // así una ventana o un cielo muy brillantes conservan textura.
    std::vector<float> sorted(lum.d);
    size_t k99 = std::min(sorted.size() - 1, size_t(sorted.size() * 0.99));
    std::nth_element(sorted.begin(), sorted.begin() + k99, sorted.end());
    float p99 = std::max(sorted[k99], 1e-4f);
    float dark = std::min(0.3f, 0.35f / p99);
    Plane g = fuseGain(lum, {dark, 1.f, 2.2f}, 0.6f);
    // Los halos de la fusión aparecen porque la ganancia "se derrama" alrededor de los bordes
    // fuertes (ventanas, cielo). Un filtro guiado por la luma la pega a los bordes.
    Plane lg(g.w, g.h), ll(g.w, g.h);
    for (size_t i = 0; i < g.d.size(); i++) {
        lg.d[i] = std::log(g.d[i]);
        ll.d[i] = std::log(std::max(lum.d[i], 1e-4f)) / 8.f;   // escala ~0..1
    }
    Plane sm = guidedFilter(ll, lg, std::max(2, g.w / 128), 1e-3f);
    for (size_t i = 0; i < g.d.size(); i++) g.d[i] = std::exp(sm.d[i]);
    return g;
}

}  // namespace

float autoBrightnessGain(const std::vector<float>& lum, bool smartHdr, float maxGain) {
    if (lum.empty() || maxGain <= 1.f) return 1.f;
    std::vector<float> s(lum);
    auto pct = [&](float q) {
        size_t k = std::min(s.size() - 1, size_t(s.size() * q));
        std::nth_element(s.begin(), s.begin() + k, s.end());
        return std::max(s[k], 1e-5f);
    };
    // Medio tono: la media geométrica de la mitad central pesa la escena entera, no sólo el
    // fondo brillante (en contraluz el sujeto queda en esa mitad).
    float p25 = pct(0.25f), p50 = pct(0.5f), p75 = pct(0.75f);
    float key = std::exp((std::log(p25) + 2.f * std::log(p50) + std::log(p75)) / 4.f);
    const float target = 0.14f;                 // ≈ 0,41 en sRGB
    float g = target / key;
    // Límite por luces: con Smart HDR las luces se comprimen, así que admite más.
    float p99 = pct(0.99f);
    float hiLimit = (smartHdr ? 1.6f : 0.95f) / p99;
    g = std::min(g, hiLimit);
    return std::min(std::max(g, 1.f), maxGain);
}

namespace {

GainField makeField(const LowRes& lr, const FinishParams& p) {
    GainField gf;
    float A = 1.f;
    if (p.autoBrightness) {
        std::vector<float> lum(lr.r.d.size());
        for (size_t i = 0; i < lum.size(); i++)
            lum[i] = 0.2126f * lr.r.d[i] + 0.7152f * lr.g.d[i] + 0.0722f * lr.b.d[i];
        A = autoBrightnessGain(lum, p.smartHdr, p.maxAutoGain);
    }
    gf.gain = localGain(lr, p, A);
    for (auto& v : gf.gain.d) v *= A;
    gf.logLum = Plane(lr.r.w, lr.r.h);
    for (size_t i = 0; i < gf.logLum.d.size(); i++)
        gf.logLum.d[i] = std::log(std::max(0.2126f * lr.r.d[i] + 0.7152f * lr.g.d[i] + 0.0722f * lr.b.d[i], 1e-4f));
    return gf;
}

/** Mapa de ganancia Ultra HDR: ratio entre la versión HDR (sin compresión de luces) y la SDR. */
void buildGainMap(const LowRes& lr, const Plane& gain, const Renderer& rd, float headroom, GainMapOut* out) {
    if (!out) return;
    out->w = lr.r.w; out->h = lr.r.h;
    out->ratioMax = headroom;
    out->data.assign(size_t(out->w) * out->h, 0);
    float logMax = std::log(headroom);
    const float eps = 1.f / 64.f;
    parallelFor(out->h, [&](int y0, int y1) {
        float o[3];
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < out->w; x++) {
                float g = gain.w == 1 ? gain.d[0] : gain.at(x, y);
                float r = lr.r.at(x, y) * g, gg = lr.g.at(x, y) * g, b = lr.b.at(x, y) * g;
                rd.sdr(r, gg, b, o);
                // La versión HDR comprime las luces mucho menos (en una pantalla HDR brillan).
                float gh = g < 1.f ? std::pow(g, 0.4f) : g;
                float hdrY = std::min(gh * (0.2126f * lr.r.at(x, y) + 0.7152f * lr.g.at(x, y) + 0.0722f * lr.b.at(x, y)), headroom);
                float sdrY = 0.2126f * srgbToLin(o[0]) + 0.7152f * srgbToLin(o[1]) + 0.0722f * srgbToLin(o[2]);
                float ratio = std::min(std::max((hdrY + eps) / (sdrY + eps), 1.f), headroom);
                out->data[size_t(y) * out->w + x] = to8(std::log(ratio) / logMax);
            }
    });
}

/** Máscara de enfoque con umbral suave (no realza el ruido). */
void sharpenPlane(Plane& p, float sigma, float amount) {
    if (amount <= 0) return;
    Plane b = gauss5(p);
    float t = 0.75f * sigma;
    parallelFor(p.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float* r = p.row(y);
            const float* br = b.row(y);
            for (int x = 0; x < p.w; x++) {
                float d = r[x] - br[x];
                float ds = d > 0 ? std::max(0.f, d - t) : std::min(0.f, d + t);
                r[x] += amount * ds;
            }
        }
    });
}

/** Bilateral 5x5 (reducción de ruido espacial cuando quedan pocos frames efectivos). */
Plane bilateral5(const Plane& p, float sigmaR) {
    Plane o(p.w, p.h);
    float inv = 1.f / (2.f * sigmaR * sigmaR);
    float ws[5][5];
    for (int j = -2; j <= 2; j++)
        for (int i = -2; i <= 2; i++) ws[j + 2][i + 2] = std::exp(-(i * i + j * j) / (2.f * 1.5f * 1.5f));
    parallelFor(p.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < p.w; x++) {
                float c = p.at(x, y), s = 0, sw = 0;
                for (int j = -2; j <= 2; j++)
                    for (int i = -2; i <= 2; i++) {
                        float v = p.atc(x + i, y + j);
                        float d = v - c;
                        float w = ws[j + 2][i + 2] * std::exp(-d * d * inv);
                        s += w * v; sw += w;
                    }
                o.at(x, y) = s / sw;
            }
    });
    return o;
}

// ---------------- RAW ----------------

void finishBayer(MergeResult& m, const RawMetadata& md, const FinishParams& p,
                 uint8_t* out, int stride, GainMapOut* gm) {
    std::vector<Plane>& P = m.planes;   // R, G1, G2, B (media resolución, lineal)
    int pw = P[0].w, ph = P[0].h;
    bool hasShort = !m.shortPlanes.empty();
    float clipLevel = std::min(std::min(md.wb[0], md.wb[1]), std::min(md.wb[2], md.wb[3]));

    // Máximo RAW de la referencia en un entorno 3x3: el frame corto sustituye la zona quemada
    // entera (con margen), así no quedan bordes con unos canales recortados y otros no.
    Plane rawMaxP(pw, ph);
    for (int y = 0; y < ph; y++)
        for (int x = 0; x < pw; x++) {
            float mx = 0;
            for (int c = 0; c < 4; c++) mx = std::max(mx, P[c].at(x, y));
            rawMaxP.at(x, y) = mx;
        }
    if (hasShort) {
        Plane dil(pw, ph);
        parallelFor(ph, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++)
                for (int x = 0; x < pw; x++) {
                    float mx = 0;
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++) mx = std::max(mx, rawMaxP.atc(x + dx, y + dy));
                    dil.at(x, y) = mx;
                }
        });
        rawMaxP = std::move(dil);
    }

    // 1) Sombreado de lente, recuperación de luces con el frame corto, balance de blancos, exposición.
    parallelFor(ph, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < pw; x++) {
                float v[4];
                for (int c = 0; c < 4; c++) v[c] = P[c].at(x, y);
                float rawMax = rawMaxP.at(x, y);
                float lsc[4] = {1, 1, 1, 1};
                if (md.lscW > 1 && md.lscH > 1) {
                    float u = float(x) / std::max(1, pw - 1) * (md.lscW - 1);
                    float t = float(y) / std::max(1, ph - 1) * (md.lscH - 1);
                    int u0 = std::min(int(u), md.lscW - 2), t0 = std::min(int(t), md.lscH - 2);
                    float fu = u - u0, ft = t - t0;
                    for (int c = 0; c < 4; c++) {
                        auto L = [&](int a, int b) { return md.lsc[(size_t(b) * md.lscW + a) * 4 + c]; };
                        lsc[c] = (L(u0, t0) * (1 - fu) + L(u0 + 1, t0) * fu) * (1 - ft) +
                                 (L(u0, t0 + 1) * (1 - fu) + L(u0 + 1, t0 + 1) * fu) * ft;
                    }
                }
                float wHi = hasShort ? smoothstep(0.75f, 0.92f, rawMax) : 0.f;
                bool clipped = !hasShort && rawMax > 0.97f;
                for (int c = 0; c < 4; c++) {
                    float val = v[c];
                    if (wHi > 0) val += (m.shortPlanes[c].at(x, y) * m.shortRatio - val) * wHi;
                    val *= md.wb[c];
                    if (clipped) val = std::min(val, clipLevel);   // evita luces magenta
                    P[c].at(x, y) = val * lsc[c] * p.exposure;
                }
            }
    });

    // 2) Detalle y ruido de color (a media resolución).
    // Enfoque en dominio raíz cuadrada (≈ perceptual): en lineal, los bordes de alto contraste
    // (ventana vs. interior) generarían anillos oscuros y franjas de color.
    float sigma = m.residualSigma() * p.exposure;
    for (auto& pl : P) {
        Plane sq(pl.w, pl.h);
        for (size_t i = 0; i < sq.d.size(); i++) sq.d[i] = std::sqrt(std::max(pl.d[i], 0.f));
        float sqSigma = sigma / (2.f * std::sqrt(0.18f));   // d(sqrt x) = dx / (2 sqrt x) a nivel medio
        sharpenPlane(sq, sqSigma, p.sharpen * 0.8f);
        for (size_t i = 0; i < sq.d.size(); i++) {
            float v = std::max(sq.d[i], 0.f);
            // Limitar el sobreimpulso a ±35 % del valor original.
            pl.d[i] = std::min(std::max(v * v, pl.d[i] * 0.65f), pl.d[i] * 1.35f + 1e-4f);
        }
    }
    {
        // Ruido de color: diferencias de color en raíz cuadrada, suavizadas con un filtro guiado
        // por la luma (no cruza bordes: sin halos de color alrededor de luces fuertes).
        Plane sg(pw, ph), cr(pw, ph), cb(pw, ph);
        for (size_t i = 0; i < sg.d.size(); i++) {
            float g = std::sqrt(std::max(0.5f * (P[1].d[i] + P[2].d[i]), 0.f));
            sg.d[i] = g;
            cr.d[i] = std::sqrt(std::max(P[0].d[i], 0.f)) - g;
            cb.d[i] = std::sqrt(std::max(P[3].d[i], 0.f)) - g;
        }
        int radius = sigma > 0.01f ? 4 : (sigma > 0.004f ? 3 : 2);
        float eps = 4e-4f;
        cr = guidedFilter(sg, cr, radius, eps);
        cb = guidedFilter(sg, cb, radius, eps);
        for (size_t i = 0; i < cr.d.size(); i++) {
            float r = std::max(sg.d[i] + cr.d[i], 0.f), b = std::max(sg.d[i] + cb.d[i], 0.f);
            P[0].d[i] = r * r; P[3].d[i] = b * b;
        }
    }

    const float* M = md.ccm;
    // 3) Baja resolución en sRGB lineal -> Smart HDR + mapa de ganancia.
    Plane hr(pw, ph), hg(pw, ph), hb(pw, ph);
    for (size_t i = 0; i < hr.d.size(); i++) {
        float r = P[0].d[i], g = 0.5f * (P[1].d[i] + P[2].d[i]), b = P[3].d[i];
        hr.d[i] = M[0] * r + M[1] * g + M[2] * b;
        hg.d[i] = M[3] * r + M[4] * g + M[5] * b;
        hb.d[i] = M[6] * r + M[7] * g + M[8] * b;
    }
    LowRes lr = downsampleRgb(hr, hg, hb, 1024);
    hr = Plane(); hg = Plane(); hb = Plane();
    GainField field = makeField(lr, p);
    const Plane& gain = field.gain;
    Renderer rd(p.style, p.saturation);
    if (p.gainMap) buildGainMap(lr, gain, rd, p.hdrHeadroom, gm);

    // 4) Mosaico RGGB canónico + demosaico Malvar-He-Cutler + render por franjas.
    //    El demosaico se hace en raíz cuadrada (≈ gamma): en lineal, los bordes HDR muy
    //    contrastados (ventanas, farolas) producen franjas de color.
    int W = pw * 2, H = ph * 2;
    Plane mos(W, H);
    auto enc = [](float v) { return std::sqrt(std::max(v, 0.f)); };
    for (int y = 0; y < ph; y++)
        for (int x = 0; x < pw; x++) {
            mos.at(2 * x, 2 * y) = enc(P[0].at(x, y));
            mos.at(2 * x + 1, 2 * y) = enc(P[1].at(x, y));
            mos.at(2 * x, 2 * y + 1) = enc(P[2].at(x, y));
            mos.at(2 * x + 1, 2 * y + 1) = enc(P[3].at(x, y));
        }
    P.clear();
    float gsx = gain.w / float(W), gsy = gain.h / float(H);
    parallelFor(H, [&](int y0, int y1) {
        float o[3];
        for (int y = y0; y < y1; y++) {
            uint8_t* row = out + size_t(y) * stride;
            float gy = (y + 0.5f) * gsy - 0.5f;
            for (int x = 0; x < W; x++) {
                auto I = [&](int dx, int dy) { return mos.atc(x + dx, y + dy); };
                float c = I(0, 0);
                float cross = I(-1, 0) + I(1, 0) + I(0, -1) + I(0, 1);
                float axial2 = I(-2, 0) + I(2, 0) + I(0, -2) + I(0, 2);
                float diag = I(-1, -1) + I(1, -1) + I(-1, 1) + I(1, 1);
                float h2 = I(-2, 0) + I(2, 0), v2 = I(0, -2) + I(0, 2);
                float hor = I(-1, 0) + I(1, 0), ver = I(0, -1) + I(0, 1);
                bool evenX = (x & 1) == 0, evenY = (y & 1) == 0;
                float R, G, B;
                if (evenX && evenY) {              // R
                    R = c;
                    G = (4 * c + 2 * cross - axial2) / 8.f;
                    B = (6 * c + 2 * diag - 1.5f * axial2) / 8.f;
                } else if (!evenX && !evenY) {     // B
                    B = c;
                    G = (4 * c + 2 * cross - axial2) / 8.f;
                    R = (6 * c + 2 * diag - 1.5f * axial2) / 8.f;
                } else {
                    G = c;
                    float a = (5 * c + 4 * hor - diag - h2 + 0.5f * v2) / 8.f;   // canal de la fila
                    float b = (5 * c + 4 * ver - diag - v2 + 0.5f * h2) / 8.f;   // canal de la columna
                    if (evenY) { R = a; B = b; }   // G en fila roja
                    else { B = a; R = b; }         // G en fila azul
                }
                R = std::max(R, 0.f); G = std::max(G, 0.f); B = std::max(B, 0.f);
                R *= R; G *= G; B *= B;
                float r = M[0] * R + M[1] * G + M[2] * B;
                float g = M[3] * R + M[4] * G + M[5] * B;
                float b = M[6] * R + M[7] * G + M[8] * B;
                float k = field.at((x + 0.5f) * gsx - 0.5f, gy, 0.2126f * r + 0.7152f * g + 0.0722f * b);
                rd.sdr(r * k, g * k, b * k, o);
                row[4 * x] = to8(o[0]); row[4 * x + 1] = to8(o[1]); row[4 * x + 2] = to8(o[2]); row[4 * x + 3] = 255;
            }
        }
    });
}

// ---------------- YUV ----------------

inline void yuvToRgbGamma(float y, float u, float v, float* o) {
    o[0] = clamp01(y + 1.402f * v);
    o[1] = clamp01(y - 0.344136f * u - 0.714136f * v);
    o[2] = clamp01(y + 1.772f * u);
}

void finishYuv(MergeResult& m, const FinishParams& p, uint8_t* out, int stride, GainMapOut* gm) {
    Plane& Y = m.planes[0];
    float sigma = m.residualSigma();
    if (sigma > 1.5f / 255.f) Y = bilateral5(Y, 2.5f * sigma);
    sharpenPlane(Y, sigma, p.sharpen);
    int passes = sigma > 2.f / 255.f ? 2 : 1;
    Plane U = blur(m.planes[1], passes), V = blur(m.planes[2], passes);
    bool hasShort = !m.shortPlanes.empty();
    int w = Y.w, h = Y.h;

    auto linAt = [&](int x, int y, float* o) {
        int cx = std::min(x / 2, U.w - 1), cy = std::min(y / 2, U.h - 1);
        float g[3];
        yuvToRgbGamma(Y.at(x, y), U.at(cx, cy), V.at(cx, cy), g);
        float wHi = hasShort ? smoothstep(0.88f, 0.98f, std::max(g[0], std::max(g[1], g[2]))) : 0.f;
        for (int c = 0; c < 3; c++) o[c] = srgbToLinFast(g[c]);
        if (wHi > 0) {
            float s[3];
            yuvToRgbGamma(m.shortPlanes[0].at(x, y), m.shortPlanes[1].at(cx, cy), m.shortPlanes[2].at(cx, cy), s);
            for (int c = 0; c < 3; c++) o[c] += (srgbToLinFast(s[c]) * m.shortRatio - o[c]) * wHi;
        }
        for (int c = 0; c < 3; c++) o[c] *= p.exposure;
    };

    // Baja resolución.
    int f = std::max(1, int(std::ceil(std::max(w, h) / 1024.f)));
    int lw = w / f, lh = h / f;
    LowRes lr{Plane(lw, lh), Plane(lw, lh), Plane(lw, lh)};
    parallelFor(lh, [&](int y0, int y1) {
        float o[3];
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < lw; x++) {
                float s[3] = {0, 0, 0};
                for (int dy = 0; dy < f; dy++)
                    for (int dx = 0; dx < f; dx++) {
                        linAt(x * f + dx, y * f + dy, o);
                        s[0] += o[0]; s[1] += o[1]; s[2] += o[2];
                    }
                float k = 1.f / (f * f);
                lr.r.at(x, y) = s[0] * k; lr.g.at(x, y) = s[1] * k; lr.b.at(x, y) = s[2] * k;
            }
    });
    GainField field = makeField(lr, p);
    const Plane& gain = field.gain;
    Renderer rd(p.style, p.saturation);
    if (p.gainMap) buildGainMap(lr, gain, rd, p.hdrHeadroom, gm);

    float gsx = gain.w / float(w), gsy = gain.h / float(h);
    parallelFor(h, [&](int y0, int y1) {
        float o[3], c[3];
        for (int y = y0; y < y1; y++) {
            uint8_t* row = out + size_t(y) * stride;
            float gy = (y + 0.5f) * gsy - 0.5f;
            for (int x = 0; x < w; x++) {
                linAt(x, y, c);
                float k = field.at((x + 0.5f) * gsx - 0.5f, gy, 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]);
                rd.sdr(c[0] * k, c[1] * k, c[2] * k, o);
                row[4 * x] = to8(o[0]); row[4 * x + 1] = to8(o[1]); row[4 * x + 2] = to8(o[2]); row[4 * x + 3] = 255;
            }
        }
    });
}

}  // namespace

void finish(MergeResult& m, const RawMetadata* raw, const FinishParams& p,
            uint8_t* out, int strideBytes, GainMapOut* gain) {
    if (m.kind == FrameKind::Bayer) {
        RawMetadata def;
        finishBayer(m, raw ? *raw : def, p, out, strideBytes, gain);
    } else {
        finishYuv(m, p, out, strideBytes, gain);
    }
}

}  // namespace foton
