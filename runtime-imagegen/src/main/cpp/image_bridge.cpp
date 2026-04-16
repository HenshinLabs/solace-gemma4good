#include "ImageDiffusion.h"
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterllm_runtime_imagegen_ImageGenEngine_loadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath,
    jint steps,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jint seed,
    jfloat cfgScale,
    jint width,
    jint height,
    jint nThreads,
    jint nGpuLayers,
    jboolean useMmap,
    jboolean useMlock,
    jstring negativePrompt
) {
    jboolean isCopy = true;
    const char* modelPathCstr = env->GetStringUTFChars(modelPath, &isCopy);
    const char* negativePromptCstr = negativePrompt ? env->GetStringUTFChars(negativePrompt, &isCopy) : nullptr;

    auto* diffusion = new ImageDiffusion();

    try {
        DiffusionParams params;
        params.steps = steps;
        params.temperature = temperature;
        params.top_p = topP;
        params.top_k = topK;
        params.seed = seed;
        params.cfg_scale = cfgScale;
        params.width = width;
        params.height = height;
        params.n_threads = nThreads;
        params.n_gpu_layers = nGpuLayers;
        params.use_mmap = useMmap;
        params.use_mlock = useMlock;
        if (negativePromptCstr) params.negative_prompt = negativePromptCstr;

        diffusion->loadModel(modelPathCstr, params);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        if (negativePromptCstr) env->ReleaseStringUTFChars(negativePrompt, negativePromptCstr);
        delete diffusion;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    if (negativePromptCstr) env->ReleaseStringUTFChars(negativePrompt, negativePromptCstr);

    return reinterpret_cast<jlong>(diffusion);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_imagegen_ImageGenEngine_generate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong modelPtr,
    jstring prompt,
    jstring outputPath
) {
    auto* diffusion = reinterpret_cast<ImageDiffusion*>(modelPtr);
    jboolean isCopy = true;
    const char* promptCstr = env->GetStringUTFChars(prompt, &isCopy);
    const char* outputPathCstr = env->GetStringUTFChars(outputPath, &isCopy);

    bool result = diffusion->generate(promptCstr, outputPathCstr, nullptr);

    env->ReleaseStringUTFChars(prompt, promptCstr);
    env->ReleaseStringUTFChars(outputPath, outputPathCstr);

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterllm_runtime_imagegen_ImageGenEngine_close(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* diffusion = reinterpret_cast<ImageDiffusion*>(modelPtr);
    delete diffusion;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterllm_runtime_imagegen_ImageGenEngine_isModelLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong modelPtr
) {
    auto* diffusion = reinterpret_cast<ImageDiffusion*>(modelPtr);
    return diffusion->isModelLoaded();
}
