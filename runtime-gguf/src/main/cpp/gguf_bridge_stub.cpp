#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LlamaAndroid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaAndroid", __VA_ARGS__)

namespace {

struct StubContext {
    std::string model_path;
    int thread_count;
    int gpu_layers;
    int context_size;
    int vocab_size;
};

std::atomic<jlong> g_next_handle{1};
std::mutex g_context_mutex;
std::unordered_map<jlong, StubContext> g_contexts;

std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output(chars);
    env->ReleaseStringUTFChars(value, chars);
    return output;
}

// Simple tokenizer simulation for estimation
int CountTokens(const std::string& text) {
    if (text.empty()) return 0;
    int tokens = 0;
    bool inToken = false;
    for (char c : text) {
        if (std::isspace(c)) {
            if (inToken) {
                tokens++;
                inToken = false;
            }
        } else {
            inToken = true;
        }
    }
    if (inToken) tokens++;
    // Add tokens for punctuation and special chars
    return std::max(1, tokens + (int)(text.length() / 10));
}

std::string GenerateResponse(const std::string& prompt, int max_tokens) {
    // Simulate streaming tokens with varied responses based on prompt
    std::stringstream response;

    if (prompt.find("hello") != std::string::npos || prompt.find("hi") != std::string::npos) {
        response << "Hello there! How can I help you today?";
    } else if (prompt.find("help") != std::string::npos) {
        response << "I'm here to help! What would you like to know?";
    } else if (prompt.find("name") != std::string::npos) {
        response << "I am Master LLM, an AI assistant running locally on your device.";
    } else if (prompt.find("time") != std::string::npos) {
        response << "I don't have real-time access, but I can help you with other things.";
    } else {
        // Generate a response based on the prompt
        response << "I understand you're asking about \"";
        // Extract key words from prompt
        std::istringstream words(prompt);
        std::string word;
        int count = 0;
        while (words >> word && count < 3) {
            if (word.length() > 3) {
                if (count > 0) response << " ";
                response << word;
                count++;
            }
        }
        response << "\". ";
        response << "As an AI running locally on your device, I can help answer questions, ";
        response << "assist with writing, explain concepts, and have conversations. ";
        response << "Since I'm running entirely on-device using the GGUF model you downloaded, ";
        response << "your conversations stay private and work offline. ";
        response << "How else can I assist you?";
    }

    return response.str();
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

    LOGI("Loading model: %s (threads=%d, gpu=%d, ctx=%d)",
         path.c_str(), thread_count, gpu_layers, context_size);

    jlong handle = g_next_handle.fetch_add(1);

    StubContext ctx;
    ctx.model_path = path;
    ctx.thread_count = std::max(1, (int)thread_count);
    ctx.gpu_layers = std::max(0, (int)gpu_layers);
    ctx.context_size = std::max(512, (int)context_size);
    ctx.vocab_size = 32000; // Typical vocab size

    {
        std::lock_guard<std::mutex> lock(g_context_mutex);
        g_contexts[handle] = ctx;
    }

    LOGI("Model loaded with handle=%ld", handle);
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeUnloadModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {

    std::lock_guard<std::mutex> lock(g_context_mutex);
    g_contexts.erase(context_ptr);
    LOGI("Model unloaded: handle=%ld", context_ptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat /*temperature*/,
    jfloat /*topP*/,
    jint /*topK*/,
    jfloat /*repeatPenalty*/,
    jint max_tokens) {

    std::lock_guard<std::mutex> lock(g_context_mutex);
    auto it = g_contexts.find(context_ptr);
    if (it == g_contexts.end()) {
        LOGE("Invalid context handle: %ld", context_ptr);
        return env->NewStringUTF("");
    }

    const std::string prompt_text = JStringToString(env, prompt);
    if (prompt_text.empty()) {
        return env->NewStringUTF("");
    }

    LOGI("Generating response (max_tokens=%d)", max_tokens);

    std::string response = GenerateResponse(prompt_text, max_tokens);
    return env->NewStringUTF(response.c_str());
}

// Token callback interface
class TokenCallbackWrapper {
public:
    JNIEnv* env;
    jobject callback;
    jmethodID onToken;
};

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerateTokens(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jfloat repeatPenalty,
    jint max_tokens,
    jobject callback) {

    std::lock_guard<std::mutex> lock(g_context_mutex);
    auto it = g_contexts.find(context_ptr);
    if (it == g_contexts.end() || callback == nullptr) {
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

    std::string response = GenerateResponse(prompt_text, max_tokens);

    // Stream response word by word
    std::istringstream words(response);
    std::string word;
    bool emitted = false;

    while (words >> word) {
        // Add space before word (except first)
        std::string token = emitted ? " " + word : word;

        jstring java_token = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(callback, on_token, java_token);
        env->DeleteLocalRef(java_token);
        emitted = true;

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            emitted = false;
            break;
        }
    }

    env->DeleteLocalRef(callback_class);
    return emitted ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGetVocabSize(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {

    std::lock_guard<std::mutex> lock(g_context_mutex);
    auto it = g_contexts.find(context_ptr);
    if (it != g_contexts.end()) {
        return it->second.vocab_size;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeTokenize(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring text,
    jintArray tokens_out) {

    std::lock_guard<std::mutex> lock(g_context_mutex);
    auto it = g_contexts.find(context_ptr);
    if (it == g_contexts.end()) {
        return 0;
    }

    const std::string text_str = JStringToString(env, text);
    if (text_str.empty()) {
        return 0;
    }

    // Simple tokenization
    int n_tokens = CountTokens(text_str);

    if (tokens_out != nullptr) {
        jsize out_len = env->GetArrayLength(tokens_out);
        jint* out = env->GetIntArrayElements(tokens_out, nullptr);

        // Fill with dummy token IDs
        for (jsize i = 0; i < std::min(out_len, (jsize)n_tokens); i++) {
            out[i] = i + 1;
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

    LOGI("KV cache cleared for handle=%ld", context_ptr);
    // In stub implementation, nothing to clear
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
