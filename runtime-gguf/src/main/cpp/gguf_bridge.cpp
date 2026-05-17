#include "LLMInference.h"
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_loadModel(
JNIEnv* env,
jobject /*thiz*/,
jstring modelPath,
jfloat minP,
jfloat temperature,
jfloat topP,
jint topK,
jfloat repeatPenalty,
jfloat repeatPenaltyLastN,
jlong seed,
jboolean storeChats,
jlong contextSize,
jstring chatTemplate,
jint nThreads,
jint nGpuLayers,
jboolean useMmap,
jboolean useMlock,
jint nBatch,
jint nUbatch
) {
jboolean isCopy = true;
const char* modelPathCstr = env->GetStringUTFChars(modelPath, &isCopy);
auto* llmInference = new LLMInference();
const char* chatTemplateCstr = chatTemplate ? env->GetStringUTFChars(chatTemplate, &isCopy) : nullptr;

try {
llmInference->loadModel(
modelPathCstr,
minP,
temperature,
topP,
topK,
repeatPenalty,
repeatPenaltyLastN,
seed,
storeChats,
contextSize,
chatTemplateCstr,
nThreads,
nGpuLayers,
useMmap,
useMlock,
nBatch,
nUbatch
);
} catch (std::exception& error) {
env->ReleaseStringUTFChars(modelPath, modelPathCstr);
if (chatTemplateCstr) env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
delete llmInference;
env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
return 0;
}

env->ReleaseStringUTFChars(modelPath, modelPathCstr);
if (chatTemplateCstr) env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);

return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_addChatMessage(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr,
    jstring message,
    jstring role
) {
    jboolean isCopy = true;
    const char* messageCstr = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr = env->GetStringUTFChars(role, &isCopy);
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_getResponseGenerationSpeed(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_getPromptProcessingSpeed(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getPromptProcessingSpeed();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_getConfiguredThreadCount(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getConfiguredThreads();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_getConfiguredGpuLayers(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getConfiguredGpuLayers();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_getContextSizeUsed(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_closeNative(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_startCompletion(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr,
    jstring prompt
) {
    jboolean isCopy = true;
    const char* promptCstr = env->GetStringUTFChars(prompt, &isCopy);
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_completionLoop(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_stopCompletion(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_benchModel(
    JNIEnv* env,
    jobject /*unused*/,
    jlong modelPtr,
    jint pp,
    jint tg,
    jint pl,
    jint nr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    std::string result = llmInference->benchModel(pp, tg, pl, nr);
    return env->NewStringUTF(result.c_str());
}

// Multimodal support

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_loadMmproj(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr,
    jstring mmprojPath
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    jboolean isCopy = true;
    const char* mmprojPathCstr = env->GetStringUTFChars(mmprojPath, &isCopy);

    bool result = llmInference->loadMmproj(mmprojPathCstr);

    env->ReleaseStringUTFChars(mmprojPath, mmprojPathCstr);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_supportsVision(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->supportsVision();
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_gguf_GgufEngine_startCompletionWithImage(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr,
    jstring prompt,
    jbyteArray imageData,
    jint width,
    jint height
) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    jboolean isCopy = true;
    const char* promptCstr = env->GetStringUTFChars(prompt, &isCopy);

    jbyte* imageBytes = env->GetByteArrayElements(imageData, &isCopy);
    auto* imagePtr = reinterpret_cast<const unsigned char*>(imageBytes);

    try {
        llmInference->startCompletionWithImage(promptCstr, imagePtr, width, height);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ReleaseByteArrayElements(imageData, imageBytes, JNI_ABORT);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }

    env->ReleaseStringUTFChars(prompt, promptCstr);
    env->ReleaseByteArrayElements(imageData, imageBytes, JNI_ABORT);
}
