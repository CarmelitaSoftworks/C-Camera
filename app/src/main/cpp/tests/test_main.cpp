// Pruebas en el host (Linux/macOS) del motor nativo. Sin dependencias.
//   cmake -S app/src/main/cpp/tests -B build-tests && cmake --build build-tests && ./build-tests/foton_tests
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>

#include "../core/finish.h"

using namespace foton;

static int failures = 0;
#define CHECK(cond, ...)                                                        \
    do {                                                                        \
        if (!(cond)) { std::printf("  FALLO: " __VA_ARGS__); std::printf("\n"); failures++; } \
    } while (0)

static float hashf(int i, int j) {
    uint32_t n = uint32_t(i) * 374761393u + uint32_t(j) * 668265263u;
    n = (n ^ (n >> 13)) * 1274126177u;
    return float((n ^ (n >> 16)) & 0xFFFF) / 65535.f;
}
static float vnoise(float x, float y) {
    int i = int(std::floor(x)), j = int(std::floor(y));
    float fx = x - i, fy = y - j;
    float a = hashf(i, j), b = hashf(i + 1, j), c = hashf(i, j + 1), d = hashf(i + 1, j + 1);
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
}

/** Escena lineal (RGB) con textura, piel, ventana muy brillante y sombras. */
static void scene(float x, float y, float* rgb) {
    const float W = 1024, H = 768;
    float t = y / H;
    float r = 0.18f + 0.1f * t, g = 0.15f + 0.08f * t, b = 0.12f + 0.06f * t;
    float tex = vnoise(x / 4.f, y / 4.f) * 0.6f + vnoise(x / 17.f, y / 17.f) * 0.4f;
    if (y > H * 0.55f) { r = 0.02f + 0.05f * tex; g = 0.03f + 0.06f * tex; b = 0.06f + 0.08f * tex; }  // sombra con textura
    float dx = (x - W * 0.2f) / (W * 0.1f), dy = (y - H * 0.28f) / (H * 0.18f);
    if (dx * dx + dy * dy < 1) { r = 0.55f; g = 0.36f; b = 0.27f; }  // piel
    if (x > W * 0.55f && x < W * 0.9f && y > H * 0.08f && y < H * 0.45f) {  // ventana: 6x sobre el blanco
        r = 4.5f + 1.5f * tex; g = 4.6f + 1.5f * tex; b = 5.0f + 1.5f * tex;
    }
    if (x > W * 0.05f && x < W * 0.5f && y > H * 0.6f && y < H * 0.7f) {  // textura fina con contraste
        float v = tex > 0.5f ? 0.4f : 0.05f; r = v; g = v; b = v;
    }
    rgb[0] = r; rgb[1] = g; rgb[2] = b;
}

struct Synth { Frame frame; std::vector<Plane> clean; };

static const float WB[4] = {2.0f, 1.f, 1.f, 1.6f};

/** Ráfaga RAW sintética: mosaico RGGB, ruido de disparo + lectura, recorte en 1.0. */
static Synth rawFrame(float sx, float sy, float exposure, float objX, std::mt19937& rng, NoiseModel nm) {
    const int W = 1024, H = 768, pw = W / 2, ph = H / 2;
    std::normal_distribution<float> N(0, 1);
    std::vector<Plane> pl(4, Plane(pw, ph)), cl(4, Plane(pw, ph));
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float rgb[3];
            float fx = x + sx + 30, fy = y + sy + 30;
            scene(fx, fy, rgb);
            if (fx > objX && fx < objX + 80 && fy > 300 && fy < 380) { rgb[0] = 0.6f; rgb[1] = 0.04f; rgb[2] = 0.04f; }
            int c = (y & 1) * 2 + (x & 1);           // 0 R, 1 G1, 2 G2, 3 B
            float v = (c == 0 ? rgb[0] : c == 3 ? rgb[2] : rgb[1]) / WB[c] * exposure;
            float noisy = v + N(rng) * nm.sigma(v);
            pl[c].at(x / 2, y / 2) = std::min(std::max(noisy, 0.f), 1.f);
            cl[c].at(x / 2, y / 2) = std::min(v, 1.f);
        }
    Synth s;
    s.clean = cl;
    s.frame = Frame::makeBayer(pl[0], pl[1], pl[2], pl[3]);
    return s;
}

static double psnr(const Plane& a, const Plane& b, int y0 = 0, int y1 = -1) {
    if (y1 < 0) y1 = a.h;
    double s = 0; long n = 0;
    for (int y = y0; y < y1; y++)
        for (int x = 0; x < a.w; x++) { double d = a.at(x, y) - b.at(x, y); s += d * d; n++; }
    return 10 * std::log10(1.0 / (s / n));
}

static void writePpm(const std::string& path, const uint8_t* rgba, int w, int h) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    std::fprintf(f, "P6\n%d %d\n255\n", w, h);
    for (int i = 0; i < w * h; i++) std::fwrite(rgba + 4 * i, 1, 3, f);
    std::fclose(f);
}

static void testRaw(const char* outDir) {
    std::printf("RAW: ráfaga de 6 + frame corto\n");
    std::mt19937 rng(7);
    NoiseModel nm{4e-4f, 4e-6f};
    Synth ref = rawFrame(0, 0, 1.f, 100, rng, nm);
    std::uniform_real_distribution<float> U(-8, 8);
    std::vector<Frame> alts;
    for (int k = 1; k < 6; k++) alts.push_back(rawFrame(U(rng), U(rng), 1.f, 100 + 25 * k, rng, nm).frame);
    Synth sh = rawFrame(U(rng), U(rng), 0.125f, 300, rng, nm);
    auto mergeAll = [&]() {
        Merger mg(ref.frame, nm);
        for (auto& f : alts) mg.add(f);
        mg.setShort(sh.frame, 8.f);
        return mg.result();
    };
    MergeResult m = mergeAll();
    // PSNR en la zona de sombra con textura (fuera de la ventana y del objeto móvil)
    double g0 = psnr(ref.frame.planes[1], ref.clean[1], 275, 384);
    double g1 = psnr(m.planes[1], ref.clean[1], 275, 384);
    std::printf("  PSNR G1 banda de textura: %.2f -> %.2f dB\n", psnr(ref.frame.planes[1], ref.clean[1], 232, 268), psnr(m.planes[1], ref.clean[1], 232, 268));
    std::printf("  PSNR G1 sombras: 1 frame %.2f dB -> fusionado %.2f dB (nEff %.2f)\n", g0, g1, m.effectiveFrames);
    CHECK(g1 - g0 > 4.0, "la fusión RAW debería mejorar > 4 dB (mejoró %.2f)", g1 - g0);
    // Objeto en movimiento: la zona de la referencia no debe mezclarse con otras posiciones
    double o0 = psnr(ref.frame.planes[0], ref.clean[0], 150, 190), o1 = psnr(m.planes[0], ref.clean[0], 150, 190);
    std::printf("  PSNR R zona objeto móvil: %.2f -> %.2f dB\n", o0, o1);
    CHECK(o1 > o0 - 0.5, "fantasmas del objeto móvil");

    RawMetadata md;
    for (int c = 0; c < 4; c++) md.wb[c] = WB[c];
    int W, H; outputSize(m, W, H);
    std::vector<uint8_t> out(size_t(W) * H * 4);
    GainMapOut gm;
    FinishParams fp;
    finish(m, &md, fp, out.data(), W * 4, &gm);
    // Calidad final: comparar con el revelado de la misma escena sin ruido, con y sin
    // reducción de ruido espacial. Debe mejorar las sombras sin borrar la textura fina.
    {
        Frame cleanF = Frame::makeBayer(ref.clean[0], ref.clean[1], ref.clean[2], ref.clean[3]);
        Merger cm(cleanF, nm);
        cm.setShort(sh.frame, 8.f);
        MergeResult cmr = cm.result();
        std::vector<uint8_t> ideal(size_t(W) * H * 4), noDn(size_t(W) * H * 4);
        FinishParams f0 = fp; f0.denoise = 0.f;
        finish(cmr, &md, f0, ideal.data(), W * 4, nullptr);
        MergeResult m2 = mergeAll();
        finish(m2, &md, f0, noDn.data(), W * 4, nullptr);
        auto reg = [&](const std::vector<uint8_t>& a, int x0, int x1, int y0, int y1) {
            double e = 0; long cnt = 0;
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                    for (int c = 0; c < 3; c++) {
                        double d = (double(a[(size_t(y) * W + x) * 4 + c]) - ideal[(size_t(y) * W + x) * 4 + c]) / 255.0;
                        e += d * d; cnt++;
                    }
            return 10 * std::log10(1.0 / (e / cnt));
        };
        double sh0 = reg(noDn, 20, W - 20, 560, H - 20), sh1 = reg(out, 20, W - 20, 560, H - 20);
        double tx0 = reg(noDn, 60, 480, 440, 500), tx1 = reg(out, 60, 480, 440, 500);
        double up0 = reg(noDn, 20, 500, 20, 400), up1 = reg(out, 20, 500, 20, 400);
        std::printf("  imagen final vs ideal — sombras: %.2f -> %.2f dB · textura fina: %.2f -> %.2f dB · pared/piel: %.2f -> %.2f dB\n",
                    sh0, sh1, tx0, tx1, up0, up1);
        CHECK(up1 > up0 + 1.0, "la reducción de ruido debería limpiar las zonas lisas");
        CHECK(sh1 > sh0 - 0.2, "la reducción de ruido no debería empeorar las sombras con textura");
        CHECK(tx1 > tx0 - 0.3, "la reducción de ruido no debería borrar la textura fina");
    }

    // Ventana: con el frame corto debe conservar textura (no blanco plano).
    double s = 0, s2 = 0; int n = 0;
    for (int y = int(H * 0.12); y < int(H * 0.40); y++)
        for (int x = int(W * 0.6); x < int(W * 0.85); x++) { double v = out[(size_t(y) * W + x) * 4 + 1]; s += v; s2 += v * v; n++; }
    double mean = s / n, sd = std::sqrt(std::max(0.0, s2 / n - mean * mean));
    std::printf("  ventana: media %.1f, desviación %.2f; mapa de ganancia %dx%d\n", mean, sd, gm.w, gm.h);
    CHECK(sd > 2.0, "la ventana debería conservar detalle (sd %.2f)", sd);
    CHECK(mean < 254.0, "la ventana no debería quemarse");
    int gmax = 0;
    for (auto v : gm.data) gmax = std::max(gmax, int(v));
    CHECK(gmax > 100, "el mapa de ganancia debería tener luces HDR (máx %d)", gmax);
    // Piel: tono cálido, no saturado en exceso
    const uint8_t* skin = &out[(size_t(H * 0.28 - 30) * W + size_t(W * 0.2 - 30)) * 4];
    std::printf("  piel RGB = %d %d %d\n", skin[0], skin[1], skin[2]);
    CHECK(skin[0] > skin[1] && skin[1] > skin[2], "la piel debería ser R > G > B");
    writePpm(std::string(outDir) + "/raw_out.ppm", out.data(), W, H);

    // Referencia para la documentación: un solo frame, sin Smart HDR ni frame corto.
    {
        Merger one(ref.frame, nm);
        MergeResult m1 = one.result();
        std::vector<uint8_t> o1(size_t(W) * H * 4);
        FinishParams f1; f1.smartHdr = false; f1.sharpen = 0.f;
        finish(m1, &md, f1, o1.data(), W * 4, nullptr);
        writePpm(std::string(outDir) + "/raw_single.ppm", o1.data(), W, H);
    }

    // Retrato sobre el resultado (máscara: la "piel")
    std::vector<float> mask(256 * 192, 0.f);
    for (int y = 0; y < 192; y++)
        for (int x = 0; x < 256; x++) {
            float dx = (x / 256.f - (0.2f - 30.f / 1024)) / 0.1f, dy = (y / 192.f - (0.28f - 30.f / 768)) / 0.18f;  // la escena está desplazada 30 px
            mask[size_t(y) * 256 + x] = dx * dx + dy * dy < 1 ? 1.f : 0.f;
        }
    std::vector<uint8_t> por = out;
    portraitBokeh(por.data(), W, H, W * 4, mask.data(), 256, 192, 0.03f);
    writePpm(std::string(outDir) + "/portrait.ppm", por.data(), W, H);
    // El texto/textura del fondo debe quedar más suave que el original.
    auto localVar = [&](const std::vector<uint8_t>& im) {
        double acc = 0; int c = 0;
        for (int y = int(H * 0.62); y < int(H * 0.68); y++)
            for (int x = int(W * 0.1); x < int(W * 0.45); x++) {
                double d = double(im[(size_t(y) * W + x) * 4 + 1]) - im[(size_t(y) * W + x + 1) * 4 + 1];
                acc += d * d; c++;
            }
        return acc / c;
    };
    std::printf("  retrato: gradiente del fondo %.1f -> %.1f\n", localVar(out), localVar(por));
    CHECK(localVar(por) < localVar(out) * 0.5, "el fondo debería desenfocarse");
    const uint8_t* s0 = &out[(size_t(H * 0.28 - 30) * W + size_t(W * 0.2 - 30)) * 4];
    const uint8_t* s1 = &por[(size_t(H * 0.28 - 30) * W + size_t(W * 0.2 - 30)) * 4];
    CHECK(std::abs(int(s0[0]) - int(s1[0])) < 3, "el sujeto no debería cambiar");

    // Exportación Instagram 3:4 y historia
    std::vector<uint8_t> ig(1080 * 1440 * 4);
    // La salida es apaisada (1024x768); para la prueba recortamos 3:4 vertical del centro.
    int cw = H * 3 / 4, cx = (W - cw) / 2;
    exportResize(out.data(), W, H, W * 4, cx, 0, cw, H, ig.data(), 1080, 1440, 1080 * 4, 0.35f);
    writePpm(std::string(outDir) + "/instagram_3x4.ppm", ig.data(), 1080, 1440);
    std::vector<uint8_t> st(1080 * 1920 * 4);
    composeStory(out.data(), W, H, W * 4, st.data(), 1080, 1920, 1080 * 4);
    writePpm(std::string(outDir) + "/story.ppm", st.data(), 1080, 1920);
    CHECK(ig[3] == 255 && st[3] == 255, "salidas RGBA opacas");
}

static void testYuv(const char* outDir) {
    std::printf("YUV: ráfaga de 6\n");
    std::mt19937 rng(3);
    std::normal_distribution<float> N(0, 1);
    const int W = 640, H = 480;
    auto make = [&](float sx, float sy, float sigma, Plane* cleanY) {
        Plane y(W, H), u(W / 2, H / 2), v(W / 2, H / 2);
        for (int j = 0; j < H; j++)
            for (int i = 0; i < W; i++) {
                float rgb[3];
                scene((i + sx + 20) * 1.6f, (j + sy + 20) * 1.6f, rgb);
                float r = linToSrgb(rgb[0]), g = linToSrgb(rgb[1]), b = linToSrgb(rgb[2]);
                float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                if (cleanY) cleanY->at(i, j) = Y;
                y.at(i, j) = std::round(clamp01(Y + N(rng) * sigma) * 255.f) / 255.f;
                if ((i & 1) == 0 && (j & 1) == 0) {
                    u.at(i / 2, j / 2) = -0.168736f * r - 0.331264f * g + 0.5f * b;
                    v.at(i / 2, j / 2) = 0.5f * r - 0.418688f * g - 0.081312f * b;
                }
            }
        return Frame::makeYuv(y, u, v);
    };
    Plane clean(W, H);
    Frame ref = make(0, 0, 0.03f, &clean);
    NoiseModel nm = estimateNoise(ref.guide);
    std::printf("  ruido estimado %.4f (real 0.03)\n", nm.sigma(0.5f));
    CHECK(std::fabs(nm.sigma(0.5f) - 0.03f) < 0.01f, "estimación de ruido");
    Merger mg(ref, nm);
    std::uniform_real_distribution<float> U(-6, 6);
    for (int k = 1; k < 6; k++) mg.add(make(U(rng), U(rng), 0.03f, nullptr));
    // Frame corto (más oscuro) para recuperar luces también en YUV.
    {
        Frame sf = make(U(rng), U(rng), 0.03f, nullptr);
        for (auto& v : sf.planes[0].d) v = linToSrgb(srgbToLin(v) / 4.f);
        sf.guide = sf.planes[0];
        mg.setShort(sf, 4.f);
    }
    MergeResult m = mg.result();
    CHECK(!m.shortPlanes.empty(), "YUV: el frame corto debería quedar alineado");
    double a = psnr(ref.planes[0], clean), b = psnr(m.planes[0], clean);
    std::printf("  PSNR Y: %.2f -> %.2f dB\n", a, b);
    CHECK(b - a > 4.0, "la fusión YUV debería mejorar > 4 dB");
    std::vector<uint8_t> out(size_t(W) * H * 4);
    FinishParams fp; fp.style = Style::Vibrant;
    GainMapOut gm;
    finish(m, nullptr, fp, out.data(), W * 4, &gm);
    writePpm(std::string(outDir) + "/yuv_out.ppm", out.data(), W, H);
}

static void testSharpness() {
    std::printf("Nitidez (elección del frame de referencia)\n");
    const int W = 400, H = 300;
    std::vector<uint16_t> sharp(W * H), blurred(W * H);
    std::mt19937 rng(5);
    std::normal_distribution<float> N(0, 6);
    Plane img(W, H);
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) img.at(x, y) = 200 + 600 * vnoise(x / 3.f, y / 3.f);
    Plane bl = img;
    boxH(bl, 3);   // desenfoque de movimiento horizontal
    for (int i = 0; i < W * H; i++) {
        sharp[i] = uint16_t(std::max(0.f, img.d[i] + N(rng)));
        blurred[i] = uint16_t(std::max(0.f, bl.d[i] + N(rng)));
    }
    double a = gradientEnergy(sharp.data(), W * 2, 0, 0, W, H, 2), b = gradientEnergy(blurred.data(), W * 2, 0, 0, W, H, 2);
    std::printf("  nítido %.0f · movido %.0f\n", a, b);
    CHECK(a > b * 1.5, "el frame nítido debería puntuar claramente más");
}

static void testLanczos() {
    std::printf("Lanczos\n");
    Plane p(400, 300, 0.5f);
    Plane r = resizeLanczos(p, 133, 100);
    float mx = 0;
    for (float v : r.d) mx = std::max(mx, std::fabs(v - 0.5f));
    CHECK(mx < 1e-4f, "una imagen constante debe seguir constante (%g)", mx);

    // Ampliación (zoom digital): un borde debe seguir siendo un borde, sin halos fuertes.
    const int sw = 200, sh = 150, dw = 400, dh = 300;
    std::vector<uint8_t> src(sw * sh * 4, 255), dst(dw * dh * 4, 0);
    for (int y = 0; y < sh; y++)
        for (int x = 0; x < sw; x++) {
            uint8_t v = x < sw / 2 ? 60 : 180;
            uint8_t* px = &src[(y * sw + x) * 4];
            px[0] = px[1] = px[2] = v;
        }
    exportResize(src.data(), sw, sh, sw * 4, 0, 0, sw, sh, dst.data(), dw, dh, dw * 4, 0.2f);
    int lo = 255, hi = 0;
    for (int x = 0; x < dw; x++) { int v = dst[(150 * dw + x) * 4 + 1]; lo = std::min(lo, v); hi = std::max(hi, v); }
    int left = dst[(150 * dw + 50) * 4 + 1], right = dst[(150 * dw + 350) * 4 + 1];
    std::printf("  ampliación 2x: lados %d / %d, rango %d..%d\n", left, right, lo, hi);
    CHECK(std::abs(left - 60) <= 1 && std::abs(right - 180) <= 1, "la ampliación debe conservar los niveles");
    CHECK(lo >= 45 && hi <= 200, "la ampliación no debe crear halos fuertes (rango %d..%d)", lo, hi);

    // Reducción (exportar a Instagram): mismo borde, de 1200 a 300 px de ancho.
    const int bw = 1200, bh = 400, ow = 300, oh = 100;
    std::vector<uint8_t> big(bw * bh * 4, 255), small(ow * oh * 4, 0);
    for (int y = 0; y < bh; y++)
        for (int x = 0; x < bw; x++) {
            uint8_t v = x < bw / 2 + 2 ? 60 : 180;
            uint8_t* px = &big[(y * bw + x) * 4];
            px[0] = px[1] = px[2] = v;
        }
    exportResize(big.data(), bw, bh, bw * 4, 0, 0, bw, bh, small.data(), ow, oh, ow * 4, 0.35f);
    lo = 255; hi = 0;
    for (int x = 0; x < ow; x++) { int v = small[(50 * ow + x) * 4 + 1]; lo = std::min(lo, v); hi = std::max(hi, v); }
    std::printf("  reducción 4x: rango %d..%d\n", lo, hi);
    CHECK(lo >= 45 && hi <= 200, "la reducción no debe crear halos fuertes (rango %d..%d)", lo, hi);
}

int main(int argc, char** argv) {
    const char* outDir = argc > 1 ? argv[1] : ".";
    testLanczos();
    testSharpness();
    testYuv(outDir);
    testRaw(outDir);
    if (failures) { std::printf("%d fallos\n", failures); return 1; }
    std::printf("Todo OK\n");
    return 0;
}
