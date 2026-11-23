// JNI bridge between app.loocal.inference.Painter and stable-diffusion.cpp (submodule), which runs
// on llama.cpp's ggml. One model per handle; a picture is painted on the calling thread.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <thread>

#include "stable-diffusion.h"

#define LOG_TAG "loocal_sd"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string g_error;

struct Progress {
    int expected = 0; // painting steps; loading reports its own counts, which aren't shown
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID step = nullptr;
};
thread_local Progress t_progress;

void on_progress(int step, int steps, float, void *) {
    auto &p = t_progress;
    if (p.env && p.listener && steps == p.expected) p.env->CallVoidMethod(p.listener, p.step, step, steps);
}

void on_log(enum sd_log_level_t level, const char *text, void *) {
    if (level >= SD_LOG_WARN) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text);
}

bool ok(sd_status_t status, const char *what) {
    if (status == SD_STATUS_OK) return true;
    const char *last = sd_get_last_error();
    g_error = std::string(what) + (last && *last ? ": " + std::string(last) : "");
    LOGE("%s", g_error.c_str());
    return false;
}

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_loocal_inference_Painter_nativeLoad(JNIEnv *env, jclass, jstring path, jint threads) {
    sd_set_log_callback(on_log, nullptr);
    sd_ctx_opt_t *opt = sd_ctx_opt_create();
    std::string model = str(env, path);
    sd_ctx_t *ctx = nullptr;
    bool good = ok(sd_opt_set_string((sd_opt_t *) opt, "model_path", model.c_str()), "model_path")
        && ok(sd_opt_set_int((sd_opt_t *) opt, "n_threads", std::max(1, (int) threads)), "n_threads")
        && ok(sd_opt_set_bool((sd_opt_t *) opt, "enable_mmap", true), "enable_mmap")
        && ok(sd_opt_set_bool((sd_opt_t *) opt, "flash_attn", true), "flash_attn")
        && ok(sd_ctx_create(opt, &ctx), "The picture model couldn't be loaded");
    sd_opt_free((sd_opt_t *) opt);
    return good ? reinterpret_cast<jlong>(ctx) : 0;
}

JNIEXPORT void JNICALL
Java_app_loocal_inference_Painter_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) sd_ctx_free(reinterpret_cast<sd_ctx_t *>(handle));
}

JNIEXPORT void JNICALL
Java_app_loocal_inference_Painter_nativeCancel(JNIEnv *, jclass, jlong handle) {
    if (handle) sd_cancel_generation(reinterpret_cast<sd_ctx_t *>(handle), SD_CANCEL_ALL);
}

JNIEXPORT jstring JNICALL
Java_app_loocal_inference_Painter_nativeError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_error.c_str());
}

/** Paints one picture; returns its pixels as ARGB ints (width x height), or null. */
JNIEXPORT jintArray JNICALL
Java_app_loocal_inference_Painter_nativePaint(JNIEnv *env, jclass, jlong handle, jstring prompt, jstring negative,
                                              jint width, jint height, jint steps, jfloat cfg, jlong seed, jobject listener) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (!ctx) { g_error = "No picture model is loaded."; return nullptr; }
    std::string p = str(env, prompt), n = str(env, negative);
    sd_img_gen_opt_t *opt = sd_img_gen_opt_create();
    auto *o = (sd_opt_t *) opt;
    bool good = ok(sd_opt_set_string(o, "prompt", p.c_str()), "prompt")
        && ok(sd_opt_set_string(o, "negative_prompt", n.c_str()), "negative_prompt")
        && ok(sd_opt_set_int(o, "width", width), "width")
        && ok(sd_opt_set_int(o, "height", height), "height")
        && ok(sd_opt_set_int(o, "sample.steps", steps), "steps")
        && ok(sd_opt_set_float(o, "sample.guidance.txt_cfg", cfg), "cfg")
        && ok(sd_opt_set_int(o, "seed", seed), "seed");
    t_progress = {steps, env, listener, env->GetMethodID(env->GetObjectClass(listener), "step", "(II)V")};
    sd_set_progress_callback(on_progress, nullptr);
    sd_result_t *result = nullptr;
    if (good) good = ok(sd_generate_image(ctx, opt, &result), "The picture couldn't be painted");
    sd_set_progress_callback(nullptr, nullptr);
    t_progress = {};
    sd_opt_free(o);
    jintArray out = nullptr;
    if (good && result && sd_result_get_image_count(result) > 0) {
        const sd_tensor_t *image = sd_result_get_image(result, 0);
        // U8, channels innermost: [C, W, H].
        const int c = (int) sd_tensor_dim(image, 0), w = (int) sd_tensor_dim(image, 1), h = (int) sd_tensor_dim(image, 2);
        const auto *px = static_cast<const uint8_t *>(sd_tensor_data(image));
        if (sd_tensor_type(image) == SD_TENSOR_U8 && c >= 3 && w == width && h == height) {
            out = env->NewIntArray(w * h);
            jint *dst = env->GetIntArrayElements(out, nullptr);
            for (int i = 0; i < w * h; i++) {
                const uint8_t *s = px + (size_t) i * c;
                dst[i] = (jint) (0xFF000000u | (s[0] << 16) | (s[1] << 8) | s[2]);
            }
            env->ReleaseIntArrayElements(out, dst, 0);
        } else g_error = "The picture came back in a shape Loocal can't read (" + std::to_string(c) + "x" + std::to_string(w) + "x" + std::to_string(h) + ").";
    } else if (good) g_error = "The picture model returned nothing.";
    if (result) sd_result_free(result);
    return out;
}

}
