#include "burst.h"

#include <atomic>
#include <mutex>

namespace foton {

static constexpr int LEVELS = 4;

Frame Frame::makeYuv(Plane y, Plane u, Plane v) {
    Frame f;
    f.kind = FrameKind::Yuv;
    f.guide = y;
    f.planes = {std::move(y), std::move(u), std::move(v)};
    f.scale = {1, 2, 2};
    return f;
}

Frame Frame::makeBayer(Plane r, Plane g1, Plane g2, Plane b) {
    Frame f;
    f.kind = FrameKind::Bayer;
    f.guide = Plane(r.w, r.h);
    for (size_t i = 0; i < f.guide.d.size(); i++) f.guide.d[i] = 0.25f * (r.d[i] + g1.d[i] + g2.d[i] + b.d[i]);
    f.planes = {std::move(r), std::move(g1), std::move(g2), std::move(b)};
    f.scale = {1, 1, 1, 1};
    return f;
}

std::vector<Plane> pyramid(const Plane& p, int levels) {
    std::vector<Plane> v;
    v.push_back(p);
    for (int i = 1; i < levels; i++) v.push_back(down2(v.back()));
    return v;
}

NoiseModel estimateNoise(const Plane& p) {
    const int bs = 16;
    int bx = (p.w - 2) / bs, by = (p.h - 2) / bs;
    NoiseModel m;
    if (bx <= 0 || by <= 0) return m;
    std::vector<float> means(size_t(bx) * by);
    parallelFor(by, [&](int j0, int j1) {
        for (int j = j0; j < j1; j++)
            for (int i = 0; i < bx; i++) {
                double s = 0;
                for (int y = 1 + j * bs; y < 1 + (j + 1) * bs; y++)
                    for (int x = 1 + i * bs; x < 1 + (i + 1) * bs; x++) {
                        float b = 0;
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dx = -1; dx <= 1; dx++) b += p.at(x + dx, y + dy);
                        s += std::fabs(p.at(x, y) - b / 9.f);
                    }
                means[size_t(j) * bx + i] = float(s / (bs * bs));
            }
    });
    std::sort(means.begin(), means.end());
    float q = means[std::min(means.size() - 1, size_t(means.size() * 0.25f))];
    float sigma = std::max(0.5f / 255.f, q / (0.943f * 0.798f));
    m.s = 0; m.o = sigma * sigma;
    return m;
}

// ---------------- Alineación ----------------

static float sadInt(const Plane& r, const Plane& a, int x0, int y0, int x1, int y1, int ox, int oy, int step) {
    double s = 0; int n = 0;
    for (int y = y0; y < y1; y += step) {
        const float* rr = r.row(y);
        const float* ar = a.row(clampi(y + oy, 0, a.h - 1));
        for (int x = x0; x < x1; x += step) {
            s += std::fabs(rr[x] - ar[clampi(x + ox, 0, a.w - 1)]);
            n++;
        }
    }
    return float(s / std::max(1, n));
}

TileOffsets alignPyramids(const std::vector<Plane>& ref, const std::vector<Plane>& alt, int tile0) {
    TileOffsets prev;
    bool hasPrev = false;
    for (int lvl = LEVELS - 1; lvl >= 0; lvl--) {
        const Plane& R = ref[lvl];
        const Plane& A = alt[lvl];
        int w = R.w, h = R.h;
        int ts = lvl == 0 ? tile0 : 16;
        int radius = lvl == LEVELS - 1 ? 4 : (lvl == 0 ? 1 : 2);
        int step = lvl == 0 ? 2 : 1;
        TileOffsets cur;
        cur.ts = ts;
        cur.tx = (w + ts - 1) / ts; cur.ty = (h + ts - 1) / ts;
        cur.dx.assign(size_t(cur.tx) * cur.ty, 0.f);
        cur.dy.assign(size_t(cur.tx) * cur.ty, 0.f);
        parallelFor(cur.ty, [&](int j0, int j1) {
            for (int j = j0; j < j1; j++)
                for (int i = 0; i < cur.tx; i++) {
                    int x0 = i * ts, y0 = j * ts;
                    int x1 = std::min(x0 + ts, w), y1 = std::min(y0 + ts, h);
                    int gx = 0, gy = 0;
                    if (hasPrev) {
                        int cx = (x0 + x1) / 2 / 2, cy = (y0 + y1) / 2 / 2;
                        int pi = std::min(cx / prev.ts, prev.tx - 1), pj = std::min(cy / prev.ts, prev.ty - 1);
                        gx = int(std::lround(prev.dx[size_t(pj) * prev.tx + pi])) * 2;
                        gy = int(std::lround(prev.dy[size_t(pj) * prev.tx + pi])) * 2;
                    }
                    float best = 1e30f; int bx = gx, by = gy;
                    for (int oy = gy - radius; oy <= gy + radius; oy++)
                        for (int ox = gx - radius; ox <= gx + radius; ox++) {
                            float d = sadInt(R, A, x0, y0, x1, y1, ox, oy, step);
                            if (d < best) { best = d; bx = ox; by = oy; }
                        }
                    float fx = float(bx), fy = float(by);
                    if (lvl == 0) {
                        // Refinamiento subpíxel: parábola sobre el coste en x y en y.
                        float cxm = sadInt(R, A, x0, y0, x1, y1, bx - 1, by, step);
                        float cxp = sadInt(R, A, x0, y0, x1, y1, bx + 1, by, step);
                        float cym = sadInt(R, A, x0, y0, x1, y1, bx, by - 1, step);
                        float cyp = sadInt(R, A, x0, y0, x1, y1, bx, by + 1, step);
                        float denx = cxm - 2 * best + cxp, deny = cym - 2 * best + cyp;
                        if (denx > 1e-9f) fx += std::max(-0.5f, std::min(0.5f, 0.5f * (cxm - cxp) / denx));
                        if (deny > 1e-9f) fy += std::max(-0.5f, std::min(0.5f, 0.5f * (cym - cyp) / deny));
                    }
                    cur.dx[size_t(j) * cur.tx + i] = fx;
                    cur.dy[size_t(j) * cur.tx + i] = fy;
                }
        });
        prev = std::move(cur);
        hasPrev = true;
    }
    return prev;
}

// Interpolación bilineal de un campo definido en centros de tile.
struct FieldInterp {
    int tx, ty, ts;
    std::vector<int> x0, x1;
    std::vector<float> fx;
    FieldInterp(int w, int tx_, int ty_, int ts_) : tx(tx_), ty(ty_), ts(ts_), x0(w), x1(w), fx(w) {
        for (int x = 0; x < w; x++) {
            float s = std::min(std::max((x + 0.5f) / ts - 0.5f, 0.f), float(tx - 1));
            int i0 = std::min(int(s), tx > 1 ? tx - 2 : 0);
            x0[x] = i0; x1[x] = std::min(i0 + 1, tx - 1); fx[x] = s - i0;
        }
    }
    struct Row { int r0, r1; float fy; };
    Row row(float y) const {
        float s = std::min(std::max((y + 0.5f) / ts - 0.5f, 0.f), float(ty - 1));
        int j0 = std::min(int(s), ty > 1 ? ty - 2 : 0);
        return {j0 * tx, std::min(j0 + 1, ty - 1) * tx, s - j0};
    }
    float at(const std::vector<float>& v, const Row& r, int x) const {
        float a = v[r.r0 + x0[x]], b = v[r.r0 + x1[x]], c = v[r.r1 + x0[x]], d = v[r.r1 + x1[x]];
        float f = fx[x];
        return (a + (b - a) * f) * (1 - r.fy) + (c + (d - c) * f) * r.fy;
    }
};

// ---------------- Fusión ----------------

Merger::Merger(Frame ref, NoiseModel noise) : ref_(std::move(ref)), noise_(noise) {
    // La guía Bayer es media de 4 canales: varianza / 4.
    guideNoise_ = noise_;
    if (ref_.kind == FrameKind::Bayer) { guideNoise_.s /= 4; guideNoise_.o /= 4; }
    tile0_ = ref_.kind == FrameKind::Yuv ? 32 : 16;
    pyr_ = pyramid(ref_.guide, LEVELS);
    for (size_t p = 0; p < ref_.planes.size(); p++) {
        refBox_.push_back(box3(ref_.planes[p]));
        acc_.push_back(ref_.planes[p]);
        wsum_.emplace_back(ref_.planes[p].w, ref_.planes[p].h, 1.f);
    }
}

void Merger::warpWithOffsets(const Frame& alt, const TileOffsets& off, std::vector<Plane>& out) const {
    int gw = ref_.guide.w;
    FieldInterp fi(gw, off.tx, off.ty, off.ts);
    out.resize(alt.planes.size());
    for (size_t p = 0; p < alt.planes.size(); p++) {
        const Plane& src = alt.planes[p];
        int s = alt.scale[p];
        Plane o(src.w, src.h);
        parallelFor(src.h, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                auto r = fi.row(float(y * s));
                float* q = o.row(y);
                for (int x = 0; x < src.w; x++) {
                    int gx = std::min(x * s, gw - 1);
                    float ox = fi.at(off.dx, r, gx) / s, oy = fi.at(off.dy, r, gx) / s;
                    q[x] = src.sampleCubic(x + ox, y + oy);
                }
            }
        });
        out[p] = std::move(o);
    }
}

void Merger::add(const Frame& alt) {
    auto altPyr = pyramid(alt.guide, LEVELS);
    TileOffsets off = alignPyramids(pyr_, altPyr, tile0_);
    const Plane& G = ref_.guide;
    int gw = G.w, gh = G.h;

    // 1) Robustez por tile (con el desplazamiento subpíxel).
    std::vector<float> wt(size_t(off.tx) * off.ty);
    parallelFor(off.ty, [&](int j0, int j1) {
        for (int j = j0; j < j1; j++)
            for (int i = 0; i < off.tx; i++) {
                size_t k = size_t(j) * off.tx + i;
                int x0 = i * off.ts, y0 = j * off.ts;
                int x1 = std::min(x0 + off.ts, gw), y1 = std::min(y0 + off.ts, gh);
                double d = 0, m = 0; int n = 0;
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) {
                        float r = G.at(x, y);
                        d += std::fabs(r - alt.guide.sample(x + off.dx[k], y + off.dy[k]));
                        m += r; n++;
                    }
                d /= n; m /= n;
                float expD = 1.128f * guideNoise_.sigma(float(m));
                float e = std::max(0.f, float(d) - 1.5f * expD);
                wt[k] = std::exp(-e * e / (2.f * 4.f * expD * expD));
            }
    });

    // 2) Warp de todos los planos.
    std::vector<Plane> warped;
    warpWithOffsets(alt, off, warped);
    std::vector<Plane> wbox(warped.size());
    for (size_t p = 0; p < warped.size(); p++) wbox[p] = box3(warped[p]);

    // 3) Peso por píxel en la rejilla de la guía: robustez del tile x rechazo de fantasmas.
    FieldInterp fi(gw, off.tx, off.ty, off.ts);
    Plane wpix(gw, gh);
    std::atomic<long long> wtotal{0};
    const float sq2 = 1.41421356f;
    parallelFor(gh, [&](int y0, int y1) {
        double local = 0;
        for (int y = y0; y < y1; y++) {
            auto r = fi.row(float(y));
            for (int x = 0; x < gw; x++) {
                float dn = 0;
                for (size_t p = 0; p < warped.size(); p++) {
                    int s = ref_.scale[p];
                    int px = std::min(x / s, warped[p].w - 1), py = std::min(y / s, warped[p].h - 1);
                    float rb = refBox_[p].at(px, py);
                    float level = (ref_.kind == FrameKind::Yuv && p > 0) ? G.at(x, y) : rb;
                    float thr = sq2 * noise_.sigma(level);
                    dn = std::max(dn, std::fabs(wbox[p].at(px, py) - rb) / thr);
                }
                float w = fi.at(wt, r, x) * clamp01(2.f - dn);
                wpix.at(x, y) = w;
                local += w;
            }
        }
        wtotal.fetch_add((long long)(local * 1000.0));
    });
    weightSum_ += wtotal.load() / 1000.0;

    // 4) Acumulación.
    for (size_t p = 0; p < warped.size(); p++) {
        int s = ref_.scale[p];
        Plane& A = acc_[p];
        Plane& W = wsum_[p];
        const Plane& src = warped[p];
        parallelFor(src.h, [&](int y0, int y1) {
            for (int y = y0; y < y1; y++)
                for (int x = 0; x < src.w; x++) {
                    float w = wpix.at(std::min(x * s, gw - 1), std::min(y * s, gh - 1));
                    A.at(x, y) += w * src.at(x, y);
                    W.at(x, y) += w;
                }
        });
    }
    frames_++;
}

void Merger::setShort(const Frame& sf, float ratio) {
    // Igualar brillo para alinear: lineal en Bayer, aproximadamente gamma 2.2 en YUV.
    float k = ref_.kind == FrameKind::Bayer ? ratio : std::pow(ratio, 1.f / 2.2f);
    Plane g = sf.guide;
    for (auto& v : g.d) v = std::min(v * k, 1.f);
    Plane rg = ref_.guide;
    for (auto& v : rg.d) v = std::min(v, 1.f);
    TileOffsets off = alignPyramids(pyramid(rg, LEVELS), pyramid(g, LEVELS), tile0_);
    warpWithOffsets(sf, off, short_);
    shortRatio_ = ratio;
}

MergeResult Merger::result() {
    MergeResult r;
    r.kind = ref_.kind;
    r.scale = ref_.scale;
    r.noise = noise_;
    for (size_t p = 0; p < acc_.size(); p++) {
        Plane& A = acc_[p];
        const Plane& W = wsum_[p];
        for (size_t i = 0; i < A.d.size(); i++) A.d[i] /= W.d[i];
    }
    r.planes = std::move(acc_);
    r.shortPlanes = std::move(short_);
    r.shortRatio = shortRatio_;
    r.effectiveFrames = 1.f + float(weightSum_ / (double(ref_.guide.w) * ref_.guide.h));
    return r;
}

}  // namespace foton
