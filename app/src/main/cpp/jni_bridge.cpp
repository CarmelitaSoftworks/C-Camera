// Puente JNI entre Kotlin (com.carmelita.ccamera.processing.NativeEngine) y el motor C++.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <memory>
#include <mutex>

#include "core/finish.h"

#define LOG_TAG "CCameraNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace foton;

namespace {

struct Session {
    std::unique_ptr<Merger> merger;
    NoiseModel noise;
    bool hasNoise = false;
    std::unique_ptr<Frame> pendingShort;
    float pendingRatio = 1.f;
    GainMapOut gain;
    std::mutex mu;
};

Session* S(jlong h) { return reinterpret_cast<Session*>(h); }

/** Mapea la posición dentro del 2x2 del CFA al plano canónico (0 R, 1 G fila R, 2 G fila B, 3 B). */
void cfaMap(int cfa, int map[4]) {
    // posiciones: 0 arriba-izq, 1 arriba-der, 2 abajo-izq, 3 abajo-der
    switch (cfa) {
        case 1: map[0] = 1; map[1] = 0; map[2] = 3; map[3] = 2; break;  // GRBG
        case 2: map[0] = 2; map[1] = 3; map[2] = 0; map[3] = 1; break;  // GBRG
        case 3: map[0] = 3; map[1] = 2; map[2] = 1; map[3] = 0; break;  // BGGR
        default: map[0] = 0; map[1] = 1; map[2] = 2; map[3] = 3; break; // RGGB
    }
}

void addFrame(Session* s, Frame&& f, bool isShort, float ratio) {
    std::lock_guard<std::mutex> lock(s->mu);
    if (isShort) {
        if (s->merger) s->merger->setShort(f, ratio);
        else { s->pendingShort = std::make_unique<Frame>(std::move(f)); s->pendingRatio = ratio; }
        return;
    }
    if (!s->merger) {
        NoiseModel nm = s->hasNoise ? s->noise : estimateNoise(f.kind == FrameKind::Bayer ? f.planes[1] : f.planes[0]);
        s->merger = std::make_unique<Merger>(std::move(f), nm);
        if (s->pendingShort) { s->merger->setShort(*s->pendingShort, s->pendingRatio); s->pendingShort.reset(); }
    } else {
        s->merger->add(f);
    }
}

struct LockedBitmap {
    JNIEnv* env; jobject bmp; AndroidBitmapInfo info{}; void* px = nullptr;
    LockedBitmap(JNIEnv* e, jobject b) : env(e), bmp(b) {
        if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS) px = nullptr;
    }
    ~LockedBitmap() { if (px) AndroidBitmap_unlockPixels(env, bmp); }
    uint8_t* data() const { return static_cast<uint8_t*>(px); }
};

void throwJava(JNIEnv* env, const char* msg) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, msg);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_createSession(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Session());
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_release(JNIEnv*, jobject, jlong h) {
    delete S(h);
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_setNoiseProfile(JNIEnv*, jobject, jlong h, jfloat s, jfloat o) {
    S(h)->noise = NoiseModel{s, o};
    S(h)->hasNoise = true;
}

/** RAW16: buffer directo con uint16 por píxel. */
JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_addRaw(
        JNIEnv* env, jobject, jlong h, jobject buffer, jint rowStride, jint width, jint height,
        jint cfa, jfloatArray blackArr, jfloat white, jboolean isShort, jfloat ratio) {
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base) { throwJava(env, "El buffer RAW no es directo"); return; }
    float black[4];
    env->GetFloatArrayRegion(blackArr, 0, 4, black);
    int map[4];
    cfaMap(cfa, map);
    int pw = width / 2, ph = height / 2;
    std::vector<Plane> P(4, Plane(pw, ph));
    parallelFor(ph, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++)
            for (int dy = 0; dy < 2; dy++) {
                const uint16_t* row = reinterpret_cast<const uint16_t*>(base + size_t(2 * y + dy) * rowStride);
                for (int x = 0; x < pw; x++)
                    for (int dx = 0; dx < 2; dx++) {
                        int pos = dy * 2 + dx;
                        float v = (float(row[2 * x + dx]) - black[pos]) / (white - black[pos]);
                        P[map[pos]].at(x, y) = std::min(std::max(v, 0.f), 1.f);
                    }
            }
    });
    addFrame(S(h), Frame::makeBayer(std::move(P[0]), std::move(P[1]), std::move(P[2]), std::move(P[3])),
             isShort, ratio);
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_addYuv(
        JNIEnv* env, jobject, jlong h, jobject yBuf, jint yRowStride, jobject uBuf, jobject vBuf,
        jint uvRowStride, jint uvPixelStride, jint width, jint height, jboolean isShort, jfloat ratio) {
    auto* yb = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* ub = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vb = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yb || !ub || !vb) { throwJava(env, "Buffers YUV no directos"); return; }
    int w = width & ~1, hh = height & ~1;
    Plane Y(w, hh), U(w / 2, hh / 2), V(w / 2, hh / 2);
    parallelFor(hh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* r = yb + size_t(y) * yRowStride;
            float* o = Y.row(y);
            for (int x = 0; x < w; x++) o[x] = r[x] / 255.f;
        }
    });
    parallelFor(hh / 2, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* ur = ub + size_t(y) * uvRowStride;
            const uint8_t* vr = vb + size_t(y) * uvRowStride;
            for (int x = 0; x < w / 2; x++) {
                U.at(x, y) = (ur[x * uvPixelStride] - 128) / 255.f;
                V.at(x, y) = (vr[x * uvPixelStride] - 128) / 255.f;
            }
        }
    });
    addFrame(S(h), Frame::makeYuv(std::move(Y), std::move(U), std::move(V)), isShort, ratio);
}

JNIEXPORT jint JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_framesAdded(JNIEnv*, jobject, jlong h) {
    return S(h)->merger ? S(h)->merger->framesAdded() : 0;
}

JNIEXPORT jintArray JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_outputSize(JNIEnv* env, jobject, jlong h) {
    Session* s = S(h);
    jintArray r = env->NewIntArray(2);
    jint wh[2] = {0, 0};
    if (s->merger) { wh[0] = s->merger->outputWidth(); wh[1] = s->merger->outputHeight(); }
    env->SetIntArrayRegion(r, 0, 2, wh);
    return r;
}

/**
 * Revela en `bitmap` (RGBA_8888 del tamaño de outputSize). Devuelve el mapa de ganancia
 * (bytes) o null; sus dimensiones se consultan con gainMapSize().
 * meta: [wb0..3, ccm0..8] ; lsc: (lscW*lscH*4) o null.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_process(
        JNIEnv* env, jobject, jlong h, jobject bitmap, jfloatArray meta, jfloatArray lscArr, jint lscW, jint lscH,
        jfloat exposure, jboolean smartHdr, jint style, jfloat sharpen, jboolean wantGainMap, jfloat headroom,
        jboolean autoBrightness) {
    Session* s = S(h);
    std::lock_guard<std::mutex> lock(s->mu);
    if (!s->merger) { throwJava(env, "No hay frames"); return nullptr; }
    LockedBitmap lb(env, bitmap);
    if (!lb.data()) { throwJava(env, "Bitmap no válido"); return nullptr; }
    MergeResult m = s->merger->result();
    s->merger.reset();
    int W, H;
    outputSize(m, W, H);
    if (int(lb.info.width) != W || int(lb.info.height) != H) { throwJava(env, "Tamaño de bitmap incorrecto"); return nullptr; }

    RawMetadata md;
    if (meta) {
        float mv[13];
        env->GetFloatArrayRegion(meta, 0, 13, mv);
        for (int i = 0; i < 4; i++) md.wb[i] = mv[i];
        for (int i = 0; i < 9; i++) md.ccm[i] = mv[4 + i];
    }
    if (lscArr && lscW > 1 && lscH > 1) {
        md.lscW = lscW; md.lscH = lscH;
        md.lsc.resize(size_t(lscW) * lscH * 4);
        env->GetFloatArrayRegion(lscArr, 0, jsize(md.lsc.size()), md.lsc.data());
    }
    FinishParams p;
    p.exposure = exposure;
    p.smartHdr = smartHdr;
    p.style = static_cast<Style>(std::min(std::max(int(style), 0), 4));
    p.sharpen = sharpen;
    p.gainMap = wantGainMap;
    p.hdrHeadroom = headroom;
    p.autoBrightness = autoBrightness;
    s->gain = GainMapOut();
    finish(m, m.kind == FrameKind::Bayer ? &md : nullptr, p, lb.data(), int(lb.info.stride), wantGainMap ? &s->gain : nullptr);
    if (!wantGainMap || s->gain.data.empty()) return nullptr;
    jbyteArray out = env->NewByteArray(jsize(s->gain.data.size()));
    env->SetByteArrayRegion(out, 0, jsize(s->gain.data.size()), reinterpret_cast<const jbyte*>(s->gain.data.data()));
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_gainMapSize(JNIEnv* env, jobject, jlong h) {
    jint v[2] = {S(h)->gain.w, S(h)->gain.h};
    jintArray r = env->NewIntArray(2);
    env->SetIntArrayRegion(r, 0, 2, v);
    return r;
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_bokeh(
        JNIEnv* env, jobject, jobject bitmap, jfloatArray maskArr, jint mw, jint mh, jfloat strength) {
    LockedBitmap lb(env, bitmap);
    if (!lb.data()) { throwJava(env, "Bitmap no válido"); return; }
    std::vector<float> mask(size_t(mw) * mh);
    env->GetFloatArrayRegion(maskArr, 0, jsize(mask.size()), mask.data());
    portraitBokeh(lb.data(), int(lb.info.width), int(lb.info.height), int(lb.info.stride), mask.data(), mw, mh, strength);
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_exportResize(
        JNIEnv* env, jobject, jobject src, jint cx, jint cy, jint cw, jint ch, jobject dst, jfloat sharpen) {
    LockedBitmap s(env, src);
    LockedBitmap d(env, dst);
    if (!s.data() || !d.data()) { throwJava(env, "Bitmap no válido"); return; }
    exportResize(s.data(), int(s.info.width), int(s.info.height), int(s.info.stride), cx, cy, cw, ch,
                 d.data(), int(d.info.width), int(d.info.height), int(d.info.stride), sharpen);
}

JNIEXPORT void JNICALL
Java_com_carmelita_ccamera_processing_NativeEngine_composeStory(JNIEnv* env, jobject, jobject src, jobject dst) {
    LockedBitmap s(env, src);
    LockedBitmap d(env, dst);
    if (!s.data() || !d.data()) { throwJava(env, "Bitmap no válido"); return; }
    composeStory(s.data(), int(s.info.width), int(s.info.height), int(s.info.stride),
                 d.data(), int(d.info.width), int(d.info.height), int(d.info.stride));
}

}  // extern "C"
