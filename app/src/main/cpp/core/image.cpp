#include "image.h"

#include <atomic>

namespace foton {

int numThreads() {
    static int n = std::max(1u, std::thread::hardware_concurrency());
    return n;
}

void parallelFor(int n, const std::function<void(int, int)>& fn) {
    if (n <= 0) return;
    int t = std::min(numThreads(), n);
    if (t <= 1 || n < 8) { fn(0, n); return; }
    int chunks = std::min(n, t * 4);
    std::vector<std::thread> th;
    th.reserve(t);
    std::atomic_int next{0};
    for (int i = 0; i < t; i++) {
        th.emplace_back([&] {
            for (;;) {
                int c = next.fetch_add(1);
                if (c >= chunks) break;
                int y0 = int(int64_t(n) * c / chunks), y1 = int(int64_t(n) * (c + 1) / chunks);
                if (y1 > y0) fn(y0, y1);
            }
        });
    }
    for (auto& x : th) x.join();
}

Plane down2(const Plane& s) {
    Plane o(s.w / 2, s.h / 2);
    parallelFor(o.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* a = s.row(2 * y);
            const float* b = s.row(2 * y + 1);
            float* r = o.row(y);
            for (int x = 0; x < o.w; x++) r[x] = 0.25f * (a[2 * x] + a[2 * x + 1] + b[2 * x] + b[2 * x + 1]);
        }
    });
    return o;
}

Plane box3(const Plane& s) {
    Plane o(s.w, s.h);
    parallelFor(s.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = 0; x < s.w; x++) {
                float acc = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) acc += s.atc(x + dx, y + dy);
                o.at(x, y) = acc / 9.f;
            }
    });
    return o;
}

static const float K5[5] = {1.f / 16, 4.f / 16, 6.f / 16, 4.f / 16, 1.f / 16};

Plane gauss5(const Plane& s) {
    Plane t(s.w, s.h), o(s.w, s.h);
    parallelFor(s.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* r = s.row(y);
            float* q = t.row(y);
            for (int x = 0; x < s.w; x++) {
                float acc = 0;
                for (int k = -2; k <= 2; k++) acc += K5[k + 2] * r[clampi(x + k, 0, s.w - 1)];
                q[x] = acc;
            }
        }
    });
    parallelFor(s.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* rr[5];
            for (int k = -2; k <= 2; k++) rr[k + 2] = t.row(clampi(y + k, 0, s.h - 1));
            float* q = o.row(y);
            for (int x = 0; x < s.w; x++)
                q[x] = K5[0] * rr[0][x] + K5[1] * rr[1][x] + K5[2] * rr[2][x] + K5[3] * rr[3][x] + K5[4] * rr[4][x];
        }
    });
    return o;
}

Plane blur(const Plane& s, int passes) {
    if (passes <= 0) return s;
    Plane o = gauss5(s);
    for (int i = 1; i < passes; i++) o = gauss5(o);
    return o;
}

Plane resizeBilinear(const Plane& s, int w, int h) {
    Plane o(w, h);
    float sx = float(s.w) / w, sy = float(s.h) / h;
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float yy = (y + 0.5f) * sy - 0.5f;
            for (int x = 0; x < w; x++) o.at(x, y) = s.sample((x + 0.5f) * sx - 0.5f, yy);
        }
    });
    return o;
}

Plane up2(const Plane& s, int w, int h) {
    Plane o(w, h);
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            float sy = (y + 0.5f) / 2.f - 0.5f;
            for (int x = 0; x < w; x++) o.at(x, y) = s.sample((x + 0.5f) / 2.f - 0.5f, sy);
        }
    });
    return o;
}

static float sinc(float x) {
    if (std::fabs(x) < 1e-6f) return 1.f;
    float px = float(M_PI) * x;
    return std::sin(px) / px;
}

static float lanczos3(float x) { return std::fabs(x) < 3.f ? sinc(x) * sinc(x / 3.f) : 0.f; }

// Pesos de un remuestreo 1D (Lanczos-3 escalado para reducir sin aliasing).
struct Taps { std::vector<int> start; std::vector<int> count; std::vector<float> w; int maxTaps = 0; };

static Taps makeTaps(int src, int dst) {
    Taps t;
    float scale = float(src) / dst;
    float support = 3.f * std::max(1.f, scale);
    float fscale = std::max(1.f, scale);
    t.maxTaps = int(std::ceil(support * 2)) + 2;
    t.start.resize(dst); t.count.resize(dst); t.w.assign(size_t(dst) * t.maxTaps, 0.f);
    for (int i = 0; i < dst; i++) {
        float c = (i + 0.5f) * scale - 0.5f;
        int a = int(std::floor(c - support)) + 1, b = int(std::floor(c + support));
        a = std::max(a, 0); b = std::min(b, src - 1);
        int n = std::min(b - a + 1, t.maxTaps);
        float sum = 0;
        for (int k = 0; k < n; k++) {
            float v = lanczos3((a + k - c) / fscale);
            t.w[size_t(i) * t.maxTaps + k] = v; sum += v;
        }
        for (int k = 0; k < n; k++) t.w[size_t(i) * t.maxTaps + k] /= sum;
        t.start[i] = a; t.count[i] = n;
    }
    return t;
}

Plane resizeLanczos(const Plane& s, int w, int h) {
    Taps tx = makeTaps(s.w, w), ty = makeTaps(s.h, h);
    Plane tmp(w, s.h), o(w, h);
    parallelFor(s.h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* r = s.row(y);
            float* q = tmp.row(y);
            for (int x = 0; x < w; x++) {
                const float* wt = &tx.w[size_t(x) * tx.maxTaps];
                int st = tx.start[x], n = tx.count[x];
                float acc = 0;
                for (int k = 0; k < n; k++) acc += wt[k] * r[st + k];
                q[x] = acc;
            }
        }
    });
    parallelFor(h, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const float* wt = &ty.w[size_t(y) * ty.maxTaps];
            int st = ty.start[y], n = ty.count[y];
            float* q = o.row(y);
            for (int x = 0; x < w; x++) q[x] = 0;
            for (int k = 0; k < n; k++) {
                const float* r = tmp.row(st + k);
                float c = wt[k];
                for (int x = 0; x < w; x++) q[x] += c * r[x];
            }
        }
    });
    return o;
}

/** Desenfoque de caja deslizante (O(1) por píxel) en una dirección. */
void boxH(Plane& p, int r) {
    if (r <= 0) return;
    parallelFor(p.h, [&](int a, int b) {
        std::vector<float> tmp(p.w);
        for (int y = a; y < b; y++) {
            float* row = p.row(y);
            float s = 0;
            for (int i = -r; i <= r; i++) s += row[clampi(i, 0, p.w - 1)];
            for (int x = 0; x < p.w; x++) {
                tmp[x] = s / (2 * r + 1);
                s += row[clampi(x + r + 1, 0, p.w - 1)] - row[clampi(x - r, 0, p.w - 1)];
            }
            std::copy(tmp.begin(), tmp.end(), row);
        }
    });
}

void boxV(Plane& p, int r) {
    if (r <= 0) return;
    parallelFor(p.w, [&](int a, int b) {
        std::vector<float> tmp(p.h);
        for (int x = a; x < b; x++) {
            float s = 0;
            for (int i = -r; i <= r; i++) s += p.at(x, clampi(i, 0, p.h - 1));
            for (int y = 0; y < p.h; y++) {
                tmp[y] = s / (2 * r + 1);
                s += p.at(x, clampi(y + r + 1, 0, p.h - 1)) - p.at(x, clampi(y - r, 0, p.h - 1));
            }
            for (int y = 0; y < p.h; y++) p.at(x, y) = tmp[y];
        }
    });
}

/** Tres pasadas de caja ~ gaussiana de sigma ≈ r. */
void softBlur(Plane& p, int r) {
    int rr = std::max(1, int(r * 0.58f));
    for (int i = 0; i < 3; i++) { boxH(p, rr); boxV(p, rr); }
}

/** Filtro guiado (He et al.): refina la máscara pegándola a los bordes de la imagen. */
Plane guidedFilter(const Plane& I, const Plane& pIn, int r, float eps) {
    auto mean = [&](const Plane& x) { Plane m = x; boxH(m, r); boxV(m, r); return m; };
    Plane Ip(I.w, I.h), II(I.w, I.h);
    for (size_t i = 0; i < I.d.size(); i++) { Ip.d[i] = I.d[i] * pIn.d[i]; II.d[i] = I.d[i] * I.d[i]; }
    Plane mI = mean(I), mp = mean(pIn), mIp = mean(Ip), mII = mean(II);
    Plane a(I.w, I.h), b(I.w, I.h);
    for (size_t i = 0; i < a.d.size(); i++) {
        float cov = mIp.d[i] - mI.d[i] * mp.d[i];
        float var = mII.d[i] - mI.d[i] * mI.d[i];
        a.d[i] = cov / (var + eps);
        b.d[i] = mp.d[i] - a.d[i] * mI.d[i];
    }
    a = mean(a); b = mean(b);
    Plane q(I.w, I.h);
    for (size_t i = 0; i < q.d.size(); i++) q.d[i] = a.d[i] * I.d[i] + b.d[i];
    return q;
}


float srgbToLin(float x) { return x <= 0.04045f ? x / 12.92f : std::pow((x + 0.055f) / 1.055f, 2.4f); }
float linToSrgb(float x) {
    x = clamp01(x);
    return x <= 0.0031308f ? x * 12.92f : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
}

namespace {
constexpr int LUT_N = 4096;
struct Luts {
    float toLin[LUT_N + 1];
    float toSrgb[LUT_N + 1];
    Luts() {
        for (int i = 0; i <= LUT_N; i++) {
            float s = float(i) / LUT_N;
            toLin[i] = srgbToLin(s);
            toSrgb[i] = linToSrgb(s * s);
        }
    }
};
const Luts& luts() { static Luts l; return l; }
}  // namespace

float srgbToLinFast(float x) {
    const Luts& L = luts();
    float f = clamp01(x) * LUT_N;
    int i = std::min(int(f), LUT_N - 1);
    float t = f - i;
    return L.toLin[i] + (L.toLin[i + 1] - L.toLin[i]) * t;
}

float linToSrgbFast(float x) {
    const Luts& L = luts();
    float f = std::sqrt(clamp01(x)) * LUT_N;
    int i = std::min(int(f), LUT_N - 1);
    float t = f - i;
    return L.toSrgb[i] + (L.toSrgb[i + 1] - L.toSrgb[i]) * t;
}

}  // namespace foton
