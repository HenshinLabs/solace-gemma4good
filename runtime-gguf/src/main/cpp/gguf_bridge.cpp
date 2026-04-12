#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>

namespace {

struct StubContext {
    std::string model_path;
    int thread_count;
    int gpu_layers;
    int context_size;
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

std::string BuildNativeResponse(const std::string& prompt, int max_tokens) {
    std::ostringstream stream;
    stream << "native gguf response ";

    const int budget = std::clamp(max_tokens / 2, 16, 160);
    if (prompt.empty()) {
        for (int i = 0; i < budget; ++i) {
            stream << "token" << (i % 10) << ' ';
        }
        return stream.str();
    }

    std::istringstream words(prompt);
    std::string word;
    int emitted = 0;
    while (words >> word && emitted < budget) {
        stream << word << ' ';
        emitted++;
    }

    while (emitted < budget) {
        stream << "context" << (emitted % 6) << ' ';
        emitted++;
    }

    return stream.str();
}

bool ContextExists(jlong handle) {
    std::lock_guard<std::mutex> lock(g_context_mutex);
    return g_contexts.find(handle) != g_contexts.end();
}

}  // namespace

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
        return 0;
    }

    const jlong handle = g_next_handle.fetch_add(1);
    StubContext context{
        path,
        std::max(1, static_cast<int>(thread_count)),
        std::max(0, static_cast<int>(gpu_layers)),
        std::max(1024, static_cast<int>(context_size)),
    };

    {
        std::lock_guard<std::mutex> lock(g_context_mutex);
        g_contexts.emplace(handle, std::move(context));
    }

    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeUnloadModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong context_ptr) {
    std::lock_guard<std::mutex> lock(g_context_mutex);
    g_contexts.erase(context_ptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat /*temperature*/,
    jfloat /*top_p*/,
    jint /*top_k*/,
    jfloat /*repeat_penalty*/,
    jint max_tokens) {
    if (!ContextExists(context_ptr)) {
        return env->NewStringUTF("");
    }

    const std::string prompt_text = JStringToString(env, prompt);
    const std::string response = BuildNativeResponse(prompt_text, static_cast<int>(max_tokens));
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_nativeGenerateTokens(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong context_ptr,
    jstring prompt,
    jfloat /*temperature*/,
    jfloat /*top_p*/,
    jint /*top_k*/,
    jfloat /*repeat_penalty*/,
    jint max_tokens,
    jobject callback) {
    if (!ContextExists(context_ptr) || callback == nullptr) {
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
    const std::string response = BuildNativeResponse(prompt_text, static_cast<int>(max_tokens));

    std::istringstream words(response);
    std::string token;
    bool emitted = false;
    while (words >> token) {
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

    const int status = setenv(key.c_str(), env_value.c_str(), 1);
    return status == 0 ? JNI_TRUE : JNI_FALSE;
}
