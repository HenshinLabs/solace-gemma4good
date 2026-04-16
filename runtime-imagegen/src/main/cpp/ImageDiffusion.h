#pragma once

#include <llama.h>
#include <common.h>
#include <string>
#include <vector>
#include <functional>

struct DiffusionParams {
    int32_t steps = 20;
    float temperature = 0.0f;
    float top_p = 0.95f;
    int32_t top_k = 40;
    int32_t seed = -1;
    float cfg_scale = 7.5f;
    int32_t width = 512;
    int32_t height = 512;
    int32_t n_threads = 4;
    int32_t n_gpu_layers = 0;
    bool use_mmap = true;
    bool use_mlock = false;
    std::string negative_prompt;
};

class ImageDiffusion {
private:
    llama_context* _ctx = nullptr;
    llama_model* _model = nullptr;
    llama_sampler* _sampler = nullptr;
    DiffusionParams _params;
    std::function<void(int32_t, int32_t, const std::string&)> _progressCallback;

    bool _isValidUtf8(const char* text);
    std::string _formatPrompt(const std::string& prompt, const std::string& systemPrompt);

public:
    ImageDiffusion() = default;
    ~ImageDiffusion();

    ImageDiffusion(const ImageDiffusion&) = delete;
    ImageDiffusion& operator=(const ImageDiffusion&) = delete;

    void loadModel(
        const char* modelPath,
        const DiffusionParams& params
    );

    bool generate(
        const char* prompt,
        const char* outputPath,
        std::function<void(int32_t step, int32_t total, const std::string& preview)> progressCb
    );

    void setProgressCallback(std::function<void(int32_t, int32_t, const std::string&)> cb);
    bool isModelLoaded() const { return _model != nullptr; }
};
