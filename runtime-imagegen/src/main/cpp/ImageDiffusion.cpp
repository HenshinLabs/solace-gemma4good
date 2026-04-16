#include "ImageDiffusion.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <random>
#include <vector>

#define TAG "[MasterLLM-ImageDiffusion]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

ImageDiffusion::~ImageDiffusion() {
    if (_sampler) llama_sampler_free(_sampler);
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
}

bool ImageDiffusion::_isValidUtf8(const char* text) {
    if (!text) return true;
    const unsigned char* bytes = (const unsigned char*)text;
    while (*bytes != 0x00) {
        int num;
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

std::string ImageDiffusion::_formatPrompt(const std::string& prompt, const std::string& systemPrompt) {
    std::string result;
    if (!systemPrompt.empty()) {
        result += "<|system|>\n" + systemPrompt + "<|end|>\n";
    }
    result += "<|user|>\n" + prompt + "<|end|>\n<|assistant|>";
    return result;
}

void ImageDiffusion::loadModel(const char* modelPath, const DiffusionParams& params) {
    LOGi("Loading diffusion model: %s", modelPath);
    LOGi("Params: steps=%d, cfg=%.2f, seed=%d, threads=%d, gpuLayers=%d",
         params.steps, params.cfg_scale, params.seed, params.n_threads, params.n_gpu_layers);

    ggml_backend_load_all();

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = params.use_mmap;
    model_params.use_mlock = params.use_mlock;
    model_params.n_gpu_layers = params.n_gpu_layers;

    _model = llama_model_load_from_file(modelPath, model_params);
    if (!_model) {
        LOGe("Failed to load model from %s", modelPath);
        throw std::runtime_error("loadModel() failed");
    }

    if (!llama_model_is_diffusion(_model)) {
        LOGe("Model is not a diffusion model");
        llama_model_free(_model);
        _model = nullptr;
        throw std::runtime_error("Model is not a diffusion model");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = params.width * params.height / 16;
    ctx_params.n_batch = ctx_params.n_ctx;
    ctx_params.n_ubatch = ctx_params.n_batch;
    ctx_params.n_threads = params.n_threads;
    ctx_params.n_threads_batch = std::min(params.n_threads * 2, 8);
    ctx_params.no_perf = true;

    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("Failed to create context");
        llama_model_free(_model);
        _model = nullptr;
        throw std::runtime_error("llama_init_from_model() failed");
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    _sampler = llama_sampler_chain_init(sampler_params);

    if (params.top_k > 0) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(params.top_k));
    }
    if (params.top_p < 1.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(params.top_p, 1));
    }
    if (params.temperature > 0.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_temp(params.temperature));
    }
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(params.seed >= 0 ? params.seed : LLAMA_DEFAULT_SEED));

    _params = params;
    LOGi("Diffusion model loaded successfully");
}

void ImageDiffusion::setProgressCallback(std::function<void(int32_t, int32_t, const std::string&)> cb) {
    _progressCallback = cb;
}

bool ImageDiffusion::generate(
    const char* prompt,
    const char* outputPath,
    std::function<void(int32_t step, int32_t total, const std::string& preview)> progressCb
) {
    if (!_model || !_ctx) {
        LOGe("Model not loaded");
        return false;
    }

    const llama_vocab* vocab = llama_model_get_vocab(_model);
    llama_token mask_token = llama_vocab_mask(vocab);
    if (mask_token == LLAMA_TOKEN_NULL) {
        LOGe("No mask token found in vocab");
        return false;
    }

    std::string formatted = _formatPrompt(prompt, "");
    std::vector<llama_token> input_tokens = common_tokenize(vocab, formatted, true, true);
    int32_t n_input = static_cast<int32_t>(input_tokens.size());
    int32_t max_length = _params.width * _params.height / 16;

    if (n_input >= max_length) {
        LOGe("Input too long: %d tokens, max %d", n_input, max_length);
        return false;
    }

    std::vector<llama_token> output_tokens(max_length, mask_token);
    std::copy(input_tokens.begin(), input_tokens.end(), output_tokens.begin());

    std::mt19937 rng(_params.seed >= 0 ? _params.seed : std::random_device{}());
    llama_set_causal_attn(_ctx, false);

    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> candidates(n_vocab);

    llama_batch batch = llama_batch_init(max_length, 0, 1);
    batch.n_tokens = max_length;

    std::vector<float> cond_logits_buffer;
    std::vector<llama_token> uncond_tokens(max_length);
    if (_params.cfg_scale > 0.0f) {
        cond_logits_buffer.resize(n_vocab * max_length);
    }

    LOGi("Starting diffusion: %d steps, %dx%d", _params.steps, _params.width, _params.height);

    for (int32_t step = 0; step < _params.steps; ++step) {
        for (int32_t i = 0; i < max_length; ++i) {
            batch.token[i] = output_tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 1;
        }

        float* logits = nullptr;
        if (_params.cfg_scale > 0.0f) {
            if (llama_decode(_ctx, batch) != 0) {
                LOGe("Failed to decode conditional");
                break;
            }
            float* cond_logits = llama_get_logits(_ctx);
            std::memcpy(cond_logits_buffer.data(), cond_logits, n_vocab * max_length * sizeof(float));

            for (int32_t i = 0; i < n_input; ++i) uncond_tokens[i] = mask_token;
            for (int32_t i = n_input; i < max_length; ++i) uncond_tokens[i] = output_tokens[i];
            for (int32_t i = 0; i < max_length; ++i) batch.token[i] = uncond_tokens[i];

            if (llama_decode(_ctx, batch) != 0) {
                LOGe("Failed to decode unconditional");
                break;
            }
            float* uncond_logits = llama_get_logits(_ctx);

            for (int32_t i = 0; i < n_vocab * max_length; ++i) {
                cond_logits_buffer[i] = uncond_logits[i] +
                    (_params.cfg_scale + 1.0f) * (cond_logits_buffer[i] - uncond_logits[i]);
            }
            logits = cond_logits_buffer.data();
        } else {
            if (llama_decode(_ctx, batch) != 0) {
                LOGe("Failed to decode");
                break;
            }
            logits = llama_get_logits(_ctx);
        }

        if (!logits) break;

        std::vector<int32_t> mask_positions;
        for (int32_t i = 0; i < max_length; ++i) {
            if (output_tokens[i] == mask_token) mask_positions.push_back(i);
        }
        if (mask_positions.empty()) break;

        int32_t transfer_count = std::max(1, static_cast<int32_t>(mask_positions.size() * 0.1f));
        std::vector<std::pair<float, int32_t>> confidences;
        confidences.reserve(mask_positions.size());

        for (size_t idx = 0; idx < mask_positions.size(); ++idx) {
            int32_t pos = mask_positions[idx];
            const float* pos_logits = logits + pos * n_vocab;

            for (int32_t tid = 0; tid < n_vocab; ++tid) {
                candidates[tid].id = tid;
                candidates[tid].logit = pos_logits[tid];
                candidates[tid].p = 0.0f;
            }

            llama_token_data_array cur_p = { candidates.data(), static_cast<size_t>(n_vocab), -1, false };
            llama_sampler_apply(_sampler, &cur_p);

            float conf = cur_p.data[cur_p.selected].p;
            confidences.emplace_back(conf, static_cast<int32_t>(idx));
        }

        std::partial_sort(confidences.begin(),
            confidences.begin() + std::min(transfer_count, static_cast<int32_t>(confidences.size())),
            confidences.end(),
            [](const auto& a, const auto& b) { return a.first > b.first; });

        for (int32_t i = 0; i < std::min(transfer_count, static_cast<int32_t>(confidences.size())); ++i) {
            int32_t mask_idx = confidences[i].second;
            int32_t pos = mask_positions[mask_idx];

            const float* pos_logits = logits + pos * n_vocab;
            for (int32_t tid = 0; tid < n_vocab; ++tid) {
                candidates[tid].id = tid;
                candidates[tid].logit = pos_logits[tid];
                candidates[tid].p = 0.0f;
            }
            llama_token_data_array cur_p = { candidates.data(), static_cast<size_t>(n_vocab), -1, false };
            llama_sampler_apply(_sampler, &cur_p);
            output_tokens[pos] = cur_p.data[cur_p.selected].id;
        }

        if (progressCb) {
            std::string preview;
            for (int32_t i = n_input; i < std::min(n_input + 50, max_length); ++i) {
                if (output_tokens[i] != mask_token) {
                    char piece[64];
                    int n = llama_token_to_piece(vocab, output_tokens[i], piece, sizeof(piece), 0, false);
                    if (n > 0) preview.append(piece, n);
                }
            }
            progressCb(step + 1, _params.steps, preview);
        }
    }

    llama_batch_free(batch);

    std::string result;
    for (int32_t i = n_input; i < max_length; ++i) {
        if (output_tokens[i] != mask_token) {
            char piece[64];
            int n = llama_token_to_piece(vocab, output_tokens[i], piece, sizeof(piece), 0, false);
            if (n > 0) result.append(piece, n);
        }
    }

    LOGi("Generation complete: %zu chars", result.size());
    return !result.empty();
}
