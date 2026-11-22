// JNI bridge between app.loocal.inference.LlamaEngine and llama.cpp (pinned submodule, tag b12634).
// One model + one context per handle, plus an optional vision projector (mmproj) for photos.
// CPU backend.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "loocal_llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBatch = 512;

struct Engine {
    llama_model *model = nullptr;
    mtmd_context *vision = nullptr; // null for text-only models
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    llama_batch_ext *batch = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string tmpl;      // chat template from the GGUF metadata
    llama_pos n_past = 0;  // next position in the KV cache
    int remaining = 0;     // tokens left for this reply
    bool pending = false;  // logits of the last decode are waiting to be sampled
    std::string utf8_tail; // incomplete UTF-8 bytes carried to the next piece
};

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<std::string> strings(JNIEnv *env, jobjectArray array) {
    const jsize n = env->GetArrayLength(array);
    std::vector<std::string> out(n);
    for (jsize i = 0; i < n; ++i) out[i] = to_string(env, (jstring) env->GetObjectArrayElement(array, i));
    return out;
}

// Applies the model's own chat template, ending with the assistant turn opener.
std::string format_chat(const Engine &e, const std::vector<std::string> &r, const std::vector<std::string> &t) {
    std::vector<llama_chat_message> messages(r.size());
    size_t total = 0;
    for (size_t i = 0; i < r.size(); ++i) { messages[i] = {r[i].c_str(), t[i].c_str()}; total += t[i].size(); }

    const char *tmpl = e.tmpl.empty() ? nullptr : e.tmpl.c_str();
    std::vector<char> buf(total * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), (int32_t) buf.size());
    }
    return len < 0 ? std::string() : std::string(buf.data(), len);
}

std::vector<llama_token> tokenize(const Engine &e, const std::string &text) {
    // The template already contains the special tokens, so parse them; add BOS only if the model wants one.
    int n = -llama_tokenize(e.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0) llama_tokenize(e.vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true);
    return tokens;
}

// Decodes tokens from position e.n_past, requesting logits for the last one only.
bool decode(Engine &e, const llama_token *tokens, int count) {
    for (int start = 0; start < count; start += kBatch) {
        const int size = std::min(kBatch, count - start);
        llama_batch_ext_clear(e.batch);
        for (int i = 0; i < size; ++i) {
            const int idx = llama_batch_ext_add_token(e.batch, 0, tokens[start + i]);
            if (idx < 0) { LOGE("batch add failed: %d", idx); return false; }
            const llama_pos pos = e.n_past + start + i;
            llama_batch_ext_set_pos(e.batch, idx, &pos);
            llama_batch_ext_set_output_logits(e.batch, idx, start + i == count - 1);
        }
        const int rc = llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, e.batch);
        if (rc != 0) { LOGE("llama_process failed: %d", rc); return false; }
    }
    e.n_past += count;
    return true;
}

// Length of the longest prefix of s made of complete UTF-8 sequences.
size_t complete_utf8_prefix(const std::string &s) {
    size_t i = s.size();
    // Look back at most 3 bytes for an unfinished lead byte.
    for (size_t back = 1; back <= 3 && back <= s.size(); ++back) {
        const unsigned char c = s[s.size() - back];
        if ((c & 0xC0) == 0x80) continue;            // continuation byte
        size_t need = (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        if (need > back) i = s.size() - back;        // sequence not finished yet
        break;
    }
    return i;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(out, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

Engine *from(jlong handle) { return reinterpret_cast<Engine *>(handle); }

/** A photo, as RGB bytes already resized on the Kotlin side, attached to one message. */
struct Photo {
    int index = -1; // message index in the texts array, -1 for none
    int width = 0, height = 0;
    std::vector<unsigned char> rgb; // empty: placeholder, only to count tokens
};

Photo photo(JNIEnv *env, jint index, jint width, jint height, jbyteArray rgb) {
    Photo p;
    p.index = index; p.width = width; p.height = height;
    if (rgb) {
        p.rgb.resize(env->GetArrayLength(rgb));
        env->GetByteArrayRegion(rgb, 0, (jsize) p.rgb.size(), reinterpret_cast<jbyte *>(p.rgb.data()));
    }
    return p;
}

/** Tokenizes the templated chat with the photo in place of the media marker. Caller frees the chunks. */
mtmd_input_chunks *chunks_with_photo(const Engine &e, std::vector<std::string> roles, std::vector<std::string> texts, const Photo &p) {
    texts[p.index] = std::string(mtmd_default_marker()) + "\n" + texts[p.index];
    const std::string prompt = format_chat(e, roles, texts);
    mtmd_bitmap *bitmap = mtmd_bitmap_init(p.width, p.height, p.rgb.empty() ? nullptr : p.rgb.data());
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[] = {bitmap};
    const int rc = mtmd_tokenize(e.vision, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (rc != 0) { LOGE("mtmd_tokenize failed: %d", rc); mtmd_input_chunks_free(chunks); return nullptr; }
    return chunks;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_loocal_inference_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring jpath, jstring jmmproj, jint context_tokens,
                                                 jint threads, jint threads_batch, jint load_mode, jboolean repack) {
    static bool initialized = false;
    if (!initialized) { llama_backend_init(); initialized = true; }

    auto *e = new Engine();
    const std::string path = to_string(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only in the MVP
    mp.load_mode = (llama_load_mode) load_mode;
    mp.use_extra_bufts = repack;
    e->model = llama_model_load_from_file(path.c_str(), mp);
    if (!e->model) { LOGE("cannot load %s", path.c_str()); delete e; return 0; }

    const int cores = std::max(1, (int) std::thread::hardware_concurrency());
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) context_tokens;
    cp.n_batch = kBatch;
    cp.n_ubatch = kBatch;
    cp.n_threads = std::clamp((int) threads, 1, cores);
    cp.n_threads_batch = std::clamp((int) threads_batch, 1, cores);
    cp.no_perf = true;
    // KV cache in 8 bits (iOS keeps it in 8 bits past 1,024 tokens): half the memory of f16 for the
    // whole 2,560-token context, at no visible cost to the replies. Quantized V needs flash attention.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { LOGE("cannot create context"); llama_model_free(e->model); delete e; return 0; }

    e->vocab = llama_model_get_vocab(e->model);
    if (const char *t = llama_model_chat_template(e->model, nullptr)) e->tmpl = t;
    e->batch = llama_batch_ext_init(e->ctx);

    if (jmmproj) {
        const std::string mmproj = to_string(env, jmmproj);
        mtmd_context_params vp = mtmd_context_params_default();
        vp.use_gpu = false;
        vp.print_timings = false;
        vp.warmup = false;
        vp.n_threads = cp.n_threads_batch;
        e->vision = mtmd_init_from_file(mmproj.c_str(), e->model, vp);
        if (!e->vision) LOGE("cannot load vision projector %s", mmproj.c_str());
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    e->sampler = llama_sampler_chain_init(sp);
    // Same sampling as iOS (GenerateParameters: temperature 0.7, top-p 0.9).
    llama_sampler_chain_add(e->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_app_loocal_inference_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine *e = from(handle);
    if (!e) return;
    if (e->vision) mtmd_free(e->vision);
    if (e->sampler) llama_sampler_free(e->sampler);
    if (e->batch) llama_batch_ext_free(e->batch);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jint JNICALL
Java_app_loocal_inference_LlamaEngine_nativeCountTokens(JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray texts,
                                                        jint image_index, jint width, jint height) {
    Engine *e = from(handle);
    const auto r = strings(env, roles), t = strings(env, texts);
    if (e->vision && image_index >= 0) {
        mtmd_input_chunks *chunks = chunks_with_photo(*e, r, t, photo(env, image_index, width, height, nullptr));
        if (!chunks) return -1;
        const auto n = (jint) mtmd_helper_get_n_tokens(chunks);
        mtmd_input_chunks_free(chunks);
        return n;
    }
    return (jint) tokenize(*e, format_chat(*e, r, t)).size();
}

JNIEXPORT jboolean JNICALL
Java_app_loocal_inference_LlamaEngine_nativeHasVision(JNIEnv *, jobject, jlong handle) {
    Engine *e = from(handle);
    return e->vision != nullptr && mtmd_support_vision(e->vision);
}

JNIEXPORT jboolean JNICALL
Java_app_loocal_inference_LlamaEngine_nativeStart(JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray texts,
                                                  jint image_index, jint width, jint height, jbyteArray rgb, jint max_tokens) {
    Engine *e = from(handle);
    llama_memory_clear(llama_get_memory(e->ctx), true); // fresh KV cache per reply
    llama_sampler_reset(e->sampler);
    e->n_past = 0;
    e->utf8_tail.clear();
    e->remaining = max_tokens;
    const auto r = strings(env, roles), t = strings(env, texts);
    if (e->vision && image_index >= 0 && rgb) {
        mtmd_input_chunks *chunks = chunks_with_photo(*e, r, t, photo(env, image_index, width, height, rgb));
        if (!chunks) return false;
        llama_pos new_past = 0;
        const int rc = mtmd_helper_eval_chunks(e->vision, e->ctx, chunks, 0, 0, kBatch, true, &new_past);
        mtmd_input_chunks_free(chunks);
        if (rc != 0) { LOGE("mtmd_helper_eval_chunks failed: %d", rc); return false; }
        e->n_past = new_past;
        e->pending = true;
        return true;
    }
    const auto tokens = tokenize(*e, format_chat(*e, r, t));
    e->pending = !tokens.empty() && decode(*e, tokens.data(), (int) tokens.size());
    return e->pending;
}

// Returns the next piece as UTF-8 bytes (possibly empty while a character is incomplete), or null at the end.
JNIEXPORT jbyteArray JNICALL
Java_app_loocal_inference_LlamaEngine_nativeNext(JNIEnv *env, jobject, jlong handle) {
    Engine *e = from(handle);
    if (!e->pending || e->remaining <= 0) return nullptr;

    llama_token token = llama_sampler_sample(e->sampler, e->ctx, -1);
    e->pending = false;
    if (llama_vocab_is_eog(e->vocab, token)) return nullptr;

    char buf[256];
    int n = llama_token_to_piece(e->vocab, token, buf, sizeof(buf), 0, false);
    if (n > 0) e->utf8_tail.append(buf, n);

    --e->remaining;
    if (e->remaining > 0 && (int) e->n_past < (int) llama_n_ctx(e->ctx)) {
        e->pending = decode(*e, &token, 1);
    }

    const size_t complete = complete_utf8_prefix(e->utf8_tail);
    std::string out = e->utf8_tail.substr(0, complete);
    e->utf8_tail.erase(0, complete);
    return to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_app_loocal_inference_LlamaEngine_nativeReset(JNIEnv *, jobject, jlong handle) {
    Engine *e = from(handle);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->pending = false;
    e->remaining = 0;
    e->n_past = 0;
    e->utf8_tail.clear();
}

} // extern "C"
