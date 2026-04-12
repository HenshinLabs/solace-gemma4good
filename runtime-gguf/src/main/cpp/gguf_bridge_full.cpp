#include <jni.h>
#include <android/log.h>

#include <llama.h>
#include <common.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaAndroid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaAndroid", __VA_ARGS__)

namespace {

// Context wrapper for managing llama.cpp resources
struct LlamaContextWrapper {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    std::string model_path;
    int n_threads = 4;
    int n_gpu_layers = 0;
    int n_ctx = 4096;

    // KV cache management
    std::vector<llama_token> last_tokens;
    bool kv_cache_cleared = true;

    ~LlamaContextWrapper() {
        cleanup();
    }

    void cleanup() {
        if (sampler) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_free_model(model);
            model = nullptr;
        }
    }

    void clear_kv_cache() {
        if (ctx) {
            llama_kv_cache_clear(ctx);
            last_tokens.clear();
            kv_cache_cleared = true;
        }
    }
};

// Global state
std::atomic<jlong> g_next_handle{1};
std::mutex g_contexts_mutex;
std::unordered_map<jlong, std::unique_ptr<LlamaContextWrapper>> g_contexts;

// Helper functions
std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output(chars);
    env->ReleaseStringUTFChars(value, chars);
    return output;
}

jlong CreateHandle() {
    return g_next_handle.fetch_add(1);
}

LlamaContextWrapper* GetContext(jlong handle) {
    std::lock_guard<std::mutex> lock(g_contexts_mutex);
    auto it = g_contexts.find(handle);
    if (it != g_contexts.end()) {
        return it->second.get();
    }
    return nullptr;
}

void RemoveContext(jlong handle) {
    std::lock_guard<std::mutex> lock(g_contexts_mutex);
    g_contexts.erase(handle);
}

void AddContext(jlong handle, std::unique_ptr<LlamaContextWrapper> ctx) {
    std::lock_guard<std::mutex> lock(g_contexts_mutex);
    g_contexts[handle] = std::move(ctx);
}

// Create sampler with inference parameters
llama_sampler* CreateSampler(float temperature, float top_p, int top_k, float repeat_penalty) {
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());

    // Penalties
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties({
        .penalty_last_n = 64,
        .penalty_repeat = repeat_penalty,
        .penalty_freq = 0.0f,
        .penalty_present = 0.0f,
        .penalize_nl = false,
        .ignore_eos = false,
    }));

    // Temperature
    if (temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    }

    // Top-K
    if (top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    }

    // Top-P
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }

    // Softmax + distribution sampling
    llama_sampler_chain_add(smpl, llama_sampler_init_softmax());
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    return smpl;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeLoadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring model_path,
    jint thread_count,
    jint gpu_layers,
    jint context_size) {

    const std::string path = JStringToString(env, model_path);
    if (path.empty()) {
        LOGE("Model path is empty");
        return 0;
    }

    LOGI("Loading model: %s", path.c_str());

    // Check if backend is initialized
    llama_backend_init();

    auto wrapper = std::make_unique<LlamaContextWrapper>();
    wrapper->model_path = path;
    wrapper->n_threads = std::max(1, static_cast<int>(thread_count));
    wrapper->n_gpu_layers = std::max(0, static_cast<int>(gpu_layers));
    wrapper->n_ctx = std::max(512, static_cast<int>(context_size));

    // Load model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = wrapper->n_gpu_layers;
    model_params.main_gpu = 0;

    wrapper->model = llama_load_model_from_file(path.c_str(), model_params);
    if (!wrapper->model) {
        LOGE("Failed to load model from file: %s", path.c_str());
        return 0;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = wrapper->n_ctx;
    ctx_params.n_threads = wrapper->n_threads;
    ctx_params.n_threads_batch = wrapper->n_threads;

    wrapper->ctx = llama_new_context_with_model(wrapper->model, ctx_params);
    if (!wrapper->ctx) {
        LOGE("Failed to create context");
        wrapper->cleanup();
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(wrapper->model);
    LOGI("Model loaded: vocab=%d, ctx=%d, threads=%d, gpu_layers=%d",
         llama_vocab_n_tokens(vocab), wrapper->n_ctx, wrapper->n_threads, wrapper->n_gpu_layers);

    jlong handle = CreateHandle();
    AddContext(handle, std::move(wrapper));

    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeUnloadModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {

    LOGI("Unloading model: handle=%ld", context_ptr);
    RemoveContext(context_ptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint max_tokens) {

    LlamaContextWrapper* ctx_wrapper = GetContext(context_ptr);
    if (!ctx_wrapper || !ctx_wrapper->ctx || !ctx_wrapper->model) {
        LOGE("Invalid context in nativeGenerate");
        return env->NewStringUTF("");
    }

    const std::string prompt_text = JStringToString(env, prompt);
    if (prompt_text.empty()) {
        return env->NewStringUTF("");
    }

    LOGI("Generating: max_tokens=%d, temp=%.2f, top_p=%.2f, top_k=%d",
         max_tokens, temperature, top_p, top_k);

    const llama_vocab* vocab = llama_model_get_vocab(ctx_wrapper->model);

    // Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(prompt_text.size() + 16);

    int n_tokens = llama_tokenize(
        vocab,
        prompt_text.c_str(),
        prompt_text.size(),
        tokens.data(),
        tokens.size(),
        true,  // add_bos
        false   // add special tokens
    );

    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }

    tokens.resize(n_tokens);

    // Check context size
    if (tokens.size() > ctx_wrapper->n_ctx) {
        LOGE("Prompt too long: %zu tokens, context size: %d", tokens.size(), ctx_wrapper->n_ctx);
        return env->NewStringUTF("");
    }

    // Create sampler
    llama_sampler* sampler = CreateSampler(temperature, top_p, top_k, repeat_penalty);
    if (!sampler) {
        LOGE("Failed to create sampler");
        return env->NewStringUTF("");
    }

    // Evaluate prompt
    llama_batch batch_prompt = llama_batch_init(tokens.size(), 0, 1);
    batch_prompt.n_tokens = tokens.size();
    for (size_t i = 0; i < tokens.size(); i++) {
        batch_prompt.token[i] = tokens[i];
        batch_prompt.pos[i] = i;
        batch_prompt.n_seq_id[i] = 1;
        batch_prompt.seq_id[i][0] = 0;
    }
    if (llama_decode(ctx_wrapper->ctx, batch_prompt) != 0) {
        LOGE("Failed to decode prompt");
        llama_batch_free(batch_prompt);
        llama_sampler_free(sampler);
        return env->NewStringUTF("");
    }
    llama_batch_free(batch_prompt);

    // Generate tokens
    std::string response;
    response.reserve(max_tokens * 4);

    int n_gen = 0;
    const int n_max_gen = std::max(1, static_cast<int>(max_tokens));

    llama_batch batch = llama_batch_init(1, 0, 1);

    while (n_gen < n_max_gen) {
        // Sample next token
        llama_token token = llama_sampler_sample(sampler, ctx_wrapper->ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        // Convert token to string
        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);
        }

        // Prepare batch for next token
        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = tokens.size() + n_gen;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        // Decode this token for next iteration
        if (llama_decode(ctx_wrapper->ctx, batch) != 0) {
            LOGE("Failed to decode token");
            break;
        }

        n_gen++;
    }

    llama_batch_free(batch);

    llama_sampler_free(sampler);

    LOGI("Generated %d tokens, response length: %zu chars", n_gen, response.length());

    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerateTokens(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint max_tokens,
    jobject callback) {

    LlamaContextWrapper* ctx_wrapper = GetContext(context_ptr);
    if (!ctx_wrapper || !ctx_wrapper->ctx || !ctx_wrapper->model || !callback) {
        LOGE("Invalid parameters in nativeGenerateTokens");
        return JNI_FALSE;
    }

    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        return JNI_FALSE;
    }

    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    const std::string prompt_text = JStringToString(env, prompt);
    if (prompt_text.empty()) {
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    const llama_vocab* vocab = llama_model_get_vocab(ctx_wrapper->model);

    // Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(prompt_text.size() + 16);

    int n_tokens = llama_tokenize(
        vocab,
        prompt_text.c_str(),
        prompt_text.size(),
        tokens.data(),
        tokens.size(),
        true,
        false
    );

    if (n_tokens < 0) {
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    tokens.resize(n_tokens);

    if (tokens.size() > ctx_wrapper->n_ctx) {
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    // Create sampler
    llama_sampler* sampler = CreateSampler(temperature, top_p, top_k, repeat_penalty);
    if (!sampler) {
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    // Evaluate prompt
    if (llama_decode(ctx_wrapper->ctx, llama_batch_get_one(tokens.data(), tokens.size(), 0, 0)) != 0) {
        llama_sampler_free(sampler);
        env->DeleteLocalRef(callback_class);
        return JNI_FALSE;
    }

    // Generate and stream tokens
    int n_gen = 0;
    const int n_max_gen = std::max(1, static_cast<int>(max_tokens));
    bool emitted_any = false;

    while (n_gen < n_max_gen) {
        llama_token token = llama_sampler_sample(sampler, ctx_wrapper->ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            jstring java_token = env->NewStringUTF(std::string(buf, n).c_str());
            env->CallVoidMethod(callback, on_token, java_token);
            env->DeleteLocalRef(java_token);
            emitted_any = true;

            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }

        if (llama_decode(ctx_wrapper->ctx, llama_batch_get_one(&token, 1, tokens.size() + n_gen, 0)) != 0) {
            break;
        }

        n_gen++;
    }

    llama_sampler_free(sampler);
    env->DeleteLocalRef(callback_class);

    return emitted_any ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGetVocabSize(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {

    LlamaContextWrapper* ctx_wrapper = GetContext(context_ptr);
    if (!ctx_wrapper || !ctx_wrapper->model) {
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(ctx_wrapper->model);
    return llama_vocab_get_n_tokens(vocab);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeTokenize(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring text,
    jintArray tokens_out) {

    LlamaContextWrapper* ctx_wrapper = GetContext(context_ptr);
    if (!ctx_wrapper || !ctx_wrapper->model) {
        return 0;
    }

    const std::string text_str = JStringToString(env, text);
    if (text_str.empty()) {
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(ctx_wrapper->model);

    // Tokenize
    std::vector<llama_token> tokens;
    tokens.resize(text_str.size() + 16);

    int n_tokens = llama_tokenize(
        vocab,
        text_str.c_str(),
        text_str.size(),
        tokens.data(),
        tokens.size(),
        true,
        false
    );

    if (n_tokens < 0) {
        return 0;
    }

    tokens.resize(n_tokens);

    // Copy tokens to output array if provided
    if (tokens_out != nullptr) {
        jsize out_len = env->GetArrayLength(tokens_out);
        jsize copy_len = std::min(static_cast<jsize>(tokens.size()), out_len);

        jint* out = env->GetIntArrayElements(tokens_out, nullptr);
        for (jsize i = 0; i < copy_len; i++) {
            out[i] = static_cast<jint>(tokens[i]);
        }
        env->ReleaseIntArrayElements(tokens_out, out, 0);
    }

    return n_tokens;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeClearKVCache(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {

    LlamaContextWrapper* ctx_wrapper = GetContext(context_ptr);
    if (ctx_wrapper) {
        ctx_wrapper->clear_kv_cache();
        LOGI("KV cache cleared for handle=%ld", context_ptr);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeSetEnv(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring name,
    jstring value) {

    const std::string key = JStringToString(env, name);
    const std::string env_value = JStringToString(env, value);

    if (key.empty() || env_value.empty()) {
        return JNI_FALSE;
    }

    int status = setenv(key.c_str(), env_value.c_str(), 1);
    LOGI("Set env: %s=%s (status=%d)", key.c_str(), env_value.c_str(), status);

    return status == 0 ? JNI_TRUE : JNI_FALSE;
}

// Initialization
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("llama_android library loaded");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("llama_android library unloading");
    // Cleanup all contexts
    std::lock_guard<std::mutex> lock(g_contexts_mutex);
    g_contexts.clear();
}
