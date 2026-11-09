// Fotón/C Camera — retrato (desenfoque de fondo) y exportación para redes.
#include "finish.h"

namespace foton {

namespace {

struct Rgb { Plane r, g, b; };

/** RGBA -> planos lineales, promediando bloques de k x k. */
Rgb toLinear(const uint8_t* src, int stride, int x0, int y0, int w, int h, int k) {
    int ow = w / k, oh = h / k;
    Rgb o{Plane(ow, oh), Plane(ow, oh), Plane(ow, oh)};
    float inv = 1.f / (k * k * 255.f);
    parallelFor(oh, [&](int a, int b) {
        for (int y = a; y < b; y++)
            for (int x = 0; x < ow; x++) {
                float s[3] = {0, 0, 0};
                for (int dy = 0; dy < k; dy++) {
                    const uint8_t* row = src + size_t(y0 + y * k + dy) * stride;
                    for (int dx = 0; dx < k; dx++) {
                        const uint8_t* px = row + 4 * (x0 + x * k + dx);
                        // Promediar en lineal (correcto para reducir).
                        s[0] += srgbToLinFast(px[0] / 255.f);
                        s[1] += srgbToLinFast(px[1] / 255.f);
                        s[2] += srgbToLinFast(px[2] / 255.f);
                    }
                }
                o.r.at(x, y) = s[0] / (k * k); o.g.at(x, y) = s[1] / (k * k); o.b.at(x, y) = s[2] / (k * k);
            }
    });
    (void)inv;
    return o;
}

inline uint8_t enc(float lin) { return uint8_t(clamp01(linToSrgbFast(lin)) * 255.f + 0.5f); }

}  // namespace

void portraitBokeh(uint8_t* rgba, int w, int h, int stride, const float* mask, int mw, int mh, float strength) {
    int k = std::max(1, int(std::ceil(std::max(w, h) / 1024.f)));
    int ww = w / k, wh = h / k;
    Rgb img = toLinear(rgba, stride, 0, 0, ww * k, wh * k, k);

    // Máscara al tamaño de trabajo + refinado guiado por la luma.
    Plane m(mw, mh);
    std::copy(mask, mask + size_t(mw) * mh, m.d.begin());
    Plane mr = resizeBilinear(m, ww, wh);
    Plane lum(ww, wh);
    for (size_t i = 0; i < lum.d.size(); i++)
        lum.d[i] = linToSrgbFast(0.2126f * img.r.d[i] + 0.7152f * img.g.d[i] + 0.0722f * img.b.d[i]);
    Plane alpha = guidedFilter(lum, mr, std::max(2, ww / 160), 1e-3f);
    for (auto& v : alpha.d) v = smoothstep(0.3f, 0.7f, v);

    // Fondo: desenfoque sin que el sujeto se "derrame" y con brillos que forman bokeh.
    int radius = std::max(2, int(strength * ww));
    Plane wgt(ww, wh);
    Rgb acc{Plane(ww, wh), Plane(ww, wh), Plane(ww, wh)};
    for (size_t i = 0; i < wgt.d.size(); i++) {
        float l = 0.2126f * img.r.d[i] + 0.7152f * img.g.d[i] + 0.0722f * img.b.d[i];
        float wv = (1.f - alpha.d[i]) * (1.f + 6.f * smoothstep(0.6f, 1.0f, l));
        wgt.d[i] = wv;
        acc.r.d[i] = img.r.d[i] * wv; acc.g.d[i] = img.g.d[i] * wv; acc.b.d[i] = img.b.d[i] * wv;
    }
    softBlur(wgt, radius); softBlur(acc.r, radius); softBlur(acc.g, radius); softBlur(acc.b, radius);
    for (size_t i = 0; i < wgt.d.size(); i++) {
        float d = std::max(wgt.d[i], 1e-4f);
        acc.r.d[i] /= d; acc.g.d[i] /= d; acc.b.d[i] /= d;
    }

    // Composición a resolución completa.
    float sx = float(ww) / w, sy = float(wh) / h;
    parallelFor(h, [&](int a, int b) {
        for (int y = a; y < b; y++) {
            uint8_t* row = rgba + size_t(y) * stride;
            float fy = (y + 0.5f) * sy - 0.5f;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                float al = alpha.sample(fx, fy);
                if (al > 0.995f) continue;
                uint8_t* px = row + 4 * x;
                float r = srgbToLinFast(px[0] / 255.f), g = srgbToLinFast(px[1] / 255.f), bb = srgbToLinFast(px[2] / 255.f);
                px[0] = enc(r * al + acc.r.sample(fx, fy) * (1 - al));
                px[1] = enc(g * al + acc.g.sample(fx, fy) * (1 - al));
                px[2] = enc(bb * al + acc.b.sample(fx, fy) * (1 - al));
            }
        }
    });
}

void exportResize(const uint8_t* src, int sw, int sh, int sStride,
                  int cx, int cy, int cw, int ch,
                  uint8_t* dst, int dw, int dh, int dStride, float sharpen) {
    (void)sw; (void)sh;
    // Pre-reducción entera (mantiene >= 2x el tamaño final) para ahorrar memoria.
    int k = std::max(1, std::min(cw / (dw * 2), ch / (dh * 2)));
    Rgb lin = toLinear(src, sStride, cx, cy, cw, ch, k);
    Plane* chs[3] = {&lin.r, &lin.g, &lin.b};
    Plane outp[3];
    for (int c = 0; c < 3; c++) {
        outp[c] = resizeLanczos(*chs[c], dw, dh);
        *chs[c] = Plane();
    }
    // Enfoque de salida (compensa la reducción y la recompresión de Instagram) sobre la luma.
    Plane l(dw, dh);
    for (size_t i = 0; i < l.d.size(); i++) l.d[i] = 0.2126f * outp[0].d[i] + 0.7152f * outp[1].d[i] + 0.0722f * outp[2].d[i];
    Plane bl = gauss5(l);
    parallelFor(dh, [&](int a, int b) {
        for (int y = a; y < b; y++) {
            uint8_t* row = dst + size_t(y) * dStride;
            for (int x = 0; x < dw; x++) {
                size_t i = size_t(y) * dw + x;
                float lv = l.d[i];
                float boost = lv > 1e-5f ? (lv + sharpen * (lv - bl.d[i])) / lv : 1.f;
                boost = std::min(std::max(boost, 0.5f), 2.f);
                row[4 * x] = enc(outp[0].d[i] * boost);
                row[4 * x + 1] = enc(outp[1].d[i] * boost);
                row[4 * x + 2] = enc(outp[2].d[i] * boost);
                row[4 * x + 3] = 255;
            }
        }
    });
}

void composeStory(const uint8_t* src, int sw, int sh, int sStride, uint8_t* dst, int dw, int dh, int dStride) {
    // Fondo: la foto ampliada para cubrir, muy desenfocada y oscurecida.
    float cover = std::max(float(dw) / sw, float(dh) / sh);
    int cw = int(dw / cover), ch = int(dh / cover);
    int cx = (sw - cw) / 2, cy = (sh - ch) / 2;
    int bw = dw / 8, bh = dh / 8;
    int k = std::max(1, std::min(cw / bw, ch / bh));
    Rgb bg = toLinear(src, sStride, cx, cy, cw, ch, k);
    for (Plane* p : {&bg.r, &bg.g, &bg.b}) {
        *p = resizeBilinear(*p, bw, bh);
        softBlur(*p, bw / 12);
        for (auto& v : p->d) v *= 0.45f;
    }
    // Primer plano: la foto completa ajustada al ancho.
    int fw = dw, fh = std::min(dh, int(std::lround(double(sh) * dw / sw)));
    int fy0 = (dh - fh) / 2;
    std::vector<uint8_t> fg(size_t(fw) * fh * 4);
    exportResize(src, sw, sh, sStride, 0, 0, sw, sh, fg.data(), fw, fh, fw * 4, 0.25f);
    float sx = float(bw) / dw, sy = float(bh) / dh;
    parallelFor(dh, [&](int a, int b) {
        for (int y = a; y < b; y++) {
            uint8_t* row = dst + size_t(y) * dStride;
            bool inFg = y >= fy0 && y < fy0 + fh;
            for (int x = 0; x < dw; x++) {
                uint8_t* px = row + 4 * x;
                if (inFg) {
                    const uint8_t* s = &fg[(size_t(y - fy0) * fw + x) * 4];
                    px[0] = s[0]; px[1] = s[1]; px[2] = s[2];
                } else {
                    float fx = (x + 0.5f) * sx - 0.5f, fyy = (y + 0.5f) * sy - 0.5f;
                    px[0] = enc(bg.r.sample(fx, fyy)); px[1] = enc(bg.g.sample(fx, fyy)); px[2] = enc(bg.b.sample(fx, fyy));
                }
                px[3] = 255;
            }
        }
    });
}

}  // namespace foton
