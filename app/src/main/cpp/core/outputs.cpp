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

void portraitBokeh(uint8_t* rgba, int w, int h, int stride, const float* mask, int mw, int mh, float strength,
                   int lighting) {
    const auto light = static_cast<PortraitLight>(std::min(std::max(lighting, 0), 5));
    int k = std::max(1, int(std::ceil(std::max(w, h) / 1536.f)));
    int ww = w / k, wh = h / k;
    Rgb img = toLinear(rgba, stride, 0, 0, ww * k, wh * k, k);

    // Máscara al tamaño de trabajo + refinado guiado por la luma (pega el borde al pelo y la ropa).
    Plane m(mw, mh);
    std::copy(mask, mask + size_t(mw) * mh, m.d.begin());
    Plane mr = resizeBilinear(m, ww, wh);
    Plane lum(ww, wh);
    for (size_t i = 0; i < lum.d.size(); i++)
        lum.d[i] = linToSrgbFast(0.2126f * img.r.d[i] + 0.7152f * img.g.d[i] + 0.0722f * img.b.d[i]);
    Plane alpha = guidedFilter(lum, mr, std::max(2, ww / 200), 1e-3f);
    for (auto& v : alpha.d) v = smoothstep(0.3f, 0.7f, v);

    // Distancia al sujeto (rejilla de 4 px, chaflán de dos pasadas): hace de "profundidad".
    // El fondo pegado a la persona se desenfoca menos y el lejano del todo, como con una lente real.
    const int g = 4;
    int dw = std::max(1, ww / g), dh = std::max(1, wh / g);
    Plane dist(dw, dh);
    for (int y = 0; y < dh; y++)
        for (int x = 0; x < dw; x++)
            dist.at(x, y) = alpha.at(std::min(x * g + g / 2, ww - 1), std::min(y * g + g / 2, wh - 1)) > 0.5f ? 0.f : 1e9f;
    const float d1 = 1.f, d2 = 1.4142f;
    for (int y = 0; y < dh; y++)
        for (int x = 0; x < dw; x++) {
            float& v = dist.at(x, y);
            if (x > 0) v = std::min(v, dist.at(x - 1, y) + d1);
            if (y > 0) {
                v = std::min(v, dist.at(x, y - 1) + d1);
                if (x > 0) v = std::min(v, dist.at(x - 1, y - 1) + d2);
                if (x < dw - 1) v = std::min(v, dist.at(x + 1, y - 1) + d2);
            }
        }
    for (int y = dh - 1; y >= 0; y--)
        for (int x = dw - 1; x >= 0; x--) {
            float& v = dist.at(x, y);
            if (x < dw - 1) v = std::min(v, dist.at(x + 1, y) + d1);
            if (y < dh - 1) {
                v = std::min(v, dist.at(x, y + 1) + d1);
                if (x < dw - 1) v = std::min(v, dist.at(x + 1, y + 1) + d2);
                if (x > 0) v = std::min(v, dist.at(x - 1, y + 1) + d2);
            }
        }
    bool anySubject = false;
    for (float v : dist.d) if (v == 0.f) { anySubject = true; break; }

    const bool needBlur = light == PortraitLight::Natural || light == PortraitLight::Studio || light == PortraitLight::Contour;
    Rgb bg{Plane(ww, wh), Plane(ww, wh), Plane(ww, wh)};
    if (needBlur) {
        // Fondo premultiplicado por (1 - alpha): el sujeto no se "derrama" en el desenfoque.
        // Los brillos pesan más para que formen bolas de bokeh nítidas.
        Plane wgt(ww, wh);
        Rgb acc{Plane(ww, wh), Plane(ww, wh), Plane(ww, wh)};
        for (size_t i = 0; i < wgt.d.size(); i++) {
            float l = 0.2126f * img.r.d[i] + 0.7152f * img.g.d[i] + 0.0722f * img.b.d[i];
            float wv = (1.f - alpha.d[i]) * (1.f + 8.f * smoothstep(0.55f, 1.0f, l));
            wgt.d[i] = wv;
            acc.r.d[i] = img.r.d[i] * wv; acc.g.d[i] = img.g.d[i] * wv; acc.b.d[i] = img.b.d[i] * wv;
        }
        const float R = std::max(2.f, strength * ww);
        // Un pequeño suavizado previo rellena los huecos entre muestras del disco.
        int r0 = std::max(1, int(R / 9.f));
        softBlur(wgt, r0); softBlur(acc.r, r0); softBlur(acc.g, r0); softBlur(acc.b, r0);

        // Muestras de un disco uniforme (espiral de Vogel): forma del diafragma.
        const int N = 56;
        float ox[N], oy[N];
        for (int i = 0; i < N; i++) {
            float rr = std::sqrt((i + 0.5f) / N);
            float th = i * 2.3999632f;
            ox[i] = rr * std::cos(th); oy[i] = rr * std::sin(th);
        }
        const float falloff = 0.12f * ww;   // distancia (px de trabajo) a la que el desenfoque es completo
        parallelFor(wh, [&](int a, int b) {
            for (int y = a; y < b; y++)
                for (int x = 0; x < ww; x++) {
                    float dpx = anySubject ? dist.sample((x + 0.5f) / g - 0.5f, (y + 0.5f) / g - 0.5f) * g : 1e9f;
                    float f = 0.3f + 0.7f * smoothstep(0.f, falloff, dpx);
                    float rad = R * f;
                    float sw = 0, sr = 0, sg = 0, sb = 0;
                    for (int i = 0; i < N; i++) {
                        int sx = std::min(std::max(int(x + ox[i] * rad + 0.5f), 0), ww - 1);
                        int sy = std::min(std::max(int(y + oy[i] * rad + 0.5f), 0), wh - 1);
                        size_t q = size_t(sy) * ww + sx;
                        sw += wgt.d[q]; sr += acc.r.d[q]; sg += acc.g.d[q]; sb += acc.b.d[q];
                    }
                    size_t p = size_t(y) * ww + x;
                    if (sw > 1e-4f) { bg.r.d[p] = sr / sw; bg.g.d[p] = sg / sw; bg.b.d[p] = sb / sw; }
                    else { bg.r.d[p] = img.r.d[p]; bg.g.d[p] = img.g.d[p]; bg.b.d[p] = img.b.d[p]; }
                }
        });
    }

    // Composición a resolución completa + iluminación del sujeto.
    float sx = float(ww) / w, sy = float(wh) / h;
    parallelFor(h, [&](int a, int b) {
        for (int y = a; y < b; y++) {
            uint8_t* row = rgba + size_t(y) * stride;
            float fy = (y + 0.5f) * sy - 0.5f;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                float al = alpha.sample(fx, fy);
                if (light == PortraitLight::Natural && al > 0.995f) continue;
                uint8_t* px = row + 4 * x;
                float r = srgbToLinFast(px[0] / 255.f), gg = srgbToLinFast(px[1] / 255.f), bb = srgbToLinFast(px[2] / 255.f);
                float br, bgc, bbl;   // fondo
                switch (light) {
                    case PortraitLight::Studio:
                        // Luz de estudio: la cara un poco más luminosa y uniforme.
                        r = r * 1.12f + 0.006f; gg = gg * 1.12f + 0.006f; bb = bb * 1.12f + 0.006f;
                        break;
                    case PortraitLight::Contour: {
                        // Contorno: más contraste en el sujeto (sombras más marcadas).
                        float l = 0.2126f * r + 0.7152f * gg + 0.0722f * bb;
                        float k2 = l > 1e-4f ? std::pow(l / 0.18f, 0.18f) : 1.f;
                        r *= k2; gg *= k2; bb *= k2;
                        break;
                    }
                    case PortraitLight::StageMono:
                    case PortraitLight::HighKeyMono: {
                        float l = 0.2126f * r + 0.7152f * gg + 0.0722f * bb;
                        if (light == PortraitLight::HighKeyMono) l = std::min(1.f, l * 1.25f + 0.02f);
                        r = gg = bb = l;
                        break;
                    }
                    default: break;
                }
                if (light == PortraitLight::Stage || light == PortraitLight::StageMono) { br = bgc = bbl = 0.f; }
                else if (light == PortraitLight::HighKeyMono) { br = bgc = bbl = 1.f; }
                else { br = bg.r.sample(fx, fy); bgc = bg.g.sample(fx, fy); bbl = bg.b.sample(fx, fy); }
                px[0] = enc(r * al + br * (1 - al));
                px[1] = enc(gg * al + bgc * (1 - al));
                px[2] = enc(bb * al + bbl * (1 - al));
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
