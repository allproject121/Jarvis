// Jarvis offline brain — llama.cpp JNI bridge.
// Ek model + ek context per handle. Grammar sampler output ko valid JSON tak force karta hai.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "llama.h"

#define TAG "JarvisBrainJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Engine {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx = 2048;
    std::mutex lock;
    std::atomic<bool> cancel{false};
};

static std::once_flag g_backend_once;

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Incomplete UTF-8 bytes ko Java ko dene se crash hota hai — sirf complete sequences bhejo.
static size_t valid_utf8_prefix(const std::string& s) {
    size_t i = 0, last_ok = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0 || i + len > s.size()) break;
        i += len; last_ok = i;
    }
    return last_ok;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvis_brain_LlamaNative_nativeLoad(JNIEnv* env, jobject, jstring jpath,
                                            jint nCtx, jint nThreads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    std::string path = jstr(env, jpath);

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;      // CPU only — har phone par chalta hai
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // RAM bachata hai: weights file se page hote hain

    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", path.c_str()); return 0; }

    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    auto* e = new Engine();
    e->model = model; e->ctx = ctx; e->vocab = llama_model_get_vocab(model); e->n_ctx = nCtx;
    LOGI("loaded %s ctx=%d threads=%d", path.c_str(), nCtx, nThreads);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_brain_LlamaNative_nativeGenerate(JNIEnv* env, jobject, jlong handle,
        jstring jprompt, jstring jgrammar, jint maxTokens, jfloat temperature) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return env->NewStringUTF("");
    std::lock_guard<std::mutex> g(e->lock);
    e->cancel = false;

    // Har command fresh — purana KV cache clear
    llama_memory_clear(llama_get_memory(e->ctx), true);

    std::string prompt = jstr(env, jprompt);
    std::string grammar = jstr(env, jgrammar);

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(), toks.data(), n, true, true);
    if ((int)toks.size() + maxTokens > e->n_ctx) {
        LOGE("prompt too long: %zu tokens", toks.size());
        return env->NewStringUTF("");
    }

    // Prompt ko batches me decode karo
    for (size_t i = 0; i < toks.size(); i += 512) {
        int cnt = (int)std::min<size_t>(512, toks.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, cnt)) != 0) {
            LOGE("prompt decode failed"); return env->NewStringUTF("");
        }
    }

    auto sp = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    if (!grammar.empty())
        llama_sampler_chain_add(smpl, llama_sampler_init_grammar(e->vocab, grammar.c_str(), "root"));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());   // deterministic
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    char buf[256];
    for (int i = 0; i < maxTokens && !e->cancel; ++i) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        int len = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) out.append(buf, len);
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(smpl);

    out.resize(valid_utf8_prefix(out));
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_brain_LlamaNative_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (auto* e = reinterpret_cast<Engine*>(handle)) e->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_brain_LlamaNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return;
    e->cancel = true;
    {
        std::lock_guard<std::mutex> g(e->lock);
        if (e->ctx) llama_free(e->ctx);
        if (e->model) llama_model_free(e->model);
    }
    delete e;
    LOGI("engine freed");
}
