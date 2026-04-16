#include "LLMInference.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <iostream>

#define TAG "[MasterLLM-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

LLMInference::~LLMInference() {
    // Free memory held by messages
    for (llama_chat_message &message : _messages) {
        free(const_cast<char *>(message.role));
        free(const_cast<char *>(message.content));
    }
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
    if (_batch) delete _batch;
    if (_sampler) llama_sampler_free(_sampler);
}

void LLMInference::loadModel(
const char *model_path,
float minP,
float temperature,
float topP,
int topK,
float repeatPenalty,
float repeatPenaltyLastN,
int64_t seed,
bool storeChats,
long contextSize,
const char *chatTemplate,
int nThreads,
int nGpuLayers,
bool useMmap,
bool useMlock,
int nBatch,
int nUbatch
) {
LOGi("Loading model: %s", model_path);
LOGi("Context size: %ld, nBatch: %d, nUbatch: %d, threads: %d",
contextSize, nBatch, nUbatch, nThreads);

// Load dynamic backends
ggml_backend_load_all();

// Create model
llama_model_params model_params = llama_model_default_params();
model_params.use_mmap = useMmap;
model_params.use_mlock = useMlock;
model_params.n_gpu_layers = nGpuLayers;

_model = llama_model_load_from_file(model_path, model_params);
if (!_model) {
LOGe("Failed to load model from %s", model_path);
throw std::runtime_error("loadModel() failed");
}

// Create context with optimized batch settings (from SmolChat pattern)
llama_context_params ctx_params = llama_context_default_params();
ctx_params.n_ctx = contextSize;
ctx_params.n_batch = nBatch > 0 ? nBatch : static_cast<int>(contextSize);
ctx_params.n_ubatch = nUbatch > 0 ? nUbatch : ctx_params.n_batch;
ctx_params.n_threads = nThreads;
ctx_params.n_threads_batch = std::min(nThreads * 2, 8);  // Batch uses more threads
ctx_params.no_perf = true;

LOGi("llama_context: n_ctx=%d, n_batch=%d, n_ubatch=%d, n_threads=%d, n_threads_batch=%d",
ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_ubatch,
ctx_params.n_threads, ctx_params.n_threads_batch);

_ctx = llama_init_from_model(_model, ctx_params);
if (!_ctx) {
LOGe("Failed to create context");
throw std::runtime_error("llama_init_from_model() failed");
}

// Create sampler
llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
sampler_params.no_perf = true;
_sampler = llama_sampler_chain_init(sampler_params);

// Add samplers (filtering -> penalties -> temperature -> final chooser)
const int32_t topKClamped = std::max<int32_t>(1, topK);
const float topPClamped = std::clamp(topP, 0.0f, 1.0f);
const float minPClamped = std::clamp(minP, 0.0f, 1.0f);
const float repeatPenaltyClamped = std::max(1.0f, repeatPenalty);
const float temperatureClamped = std::max(0.0f, temperature);
const int32_t repeatLastN = std::max(64, static_cast<int32_t>(repeatPenaltyLastN));
const int64_t resolvedSeed = seed >= 0 ? seed : LLAMA_DEFAULT_SEED;

llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(topKClamped));
llama_sampler_chain_add(
_sampler,
llama_sampler_init_top_p(topPClamped > 0.0f ? topPClamped : 1.0f, 1)
);

if (minPClamped > 0.0f) {
llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minPClamped, 1));
}

if (repeatPenaltyClamped > 1.0f) {
llama_sampler_chain_add(
_sampler,
llama_sampler_init_penalties(repeatLastN, repeatPenaltyClamped, 0.0f, 0.0f)
);
}

if (temperatureClamped <= 0.0f) {
llama_sampler_chain_add(_sampler, llama_sampler_init_greedy());
} else {
llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperatureClamped));
llama_sampler_chain_add(_sampler, llama_sampler_init_dist(resolvedSeed));
}

_formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
_messages.clear();

if (chatTemplate == nullptr || std::strlen(chatTemplate) == 0) {
const char *modelTemplate = llama_model_chat_template(_model, nullptr);
_chatTemplate = modelTemplate != nullptr ? modelTemplate : "";
} else {
_chatTemplate = chatTemplate;
}

_storeChats = storeChats;
_configuredThreads = nThreads;
_configuredGpuLayers = nGpuLayers;

LOGi("Model loaded successfully");
}

void LLMInference::addChatMessage(const char *message, const char *role) {
    llama_chat_message msg;
    msg.role = strdup(role);
    msg.content = strdup(message);
    _messages.push_back(msg);
}

float LLMInference::getResponseGenerationTime() const {
    if (_responseGenerationTimeUs <= 0 || _responseNumTokens <= 0) {
        return 0.0f;
    }
    return (float)_responseNumTokens / (_responseGenerationTimeUs / 1e6f);
}

float LLMInference::getPromptProcessingSpeed() const {
    if (_promptProcessingTimeUs <= 0 || _promptProcessingTokens <= 0) {
        return 0.0f;
    }
    return (float)_promptProcessingTokens / (_promptProcessingTimeUs / 1e6f);
}

void LLMInference::startCompletion(const char *query) {
    if (!_storeChats) {
        _formattedMessages.clear();
        _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
    }
    
    _generationReachedEog = false;
    _promptProcessingTimeUs = 0;
    _promptProcessingTokens = 0;
    _responseGenerationTimeUs = 0;
    _responseNumTokens = 0;
    _response.clear();
    _cacheResponseTokens.clear();
    
    addChatMessage(query, "user");
    
    // Apply chat template
    std::vector<common_chat_msg> messages;
    for (const llama_chat_message& message : _messages) {
        common_chat_msg msg;
        msg.role = message.role;
        msg.content = message.content;
        messages.push_back(msg);
    }
    
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.messages = messages;
    
    auto templates = common_chat_templates_init(_model, _chatTemplate);
    std::string prompt = common_chat_templates_apply(templates.get(), inputs).prompt;
    
    // Tokenize
    _promptTokens = common_tokenize(llama_model_get_vocab(_model), prompt, true, true);
    
    // Create batch
    if (_batch != nullptr) {
        delete _batch;
        _batch = nullptr;
    }
    _batch = new llama_batch();
    _batch->token = _promptTokens.data();
    _batch->n_tokens = _promptTokens.size();
}

bool LLMInference::_isValidUtf8(const char *response) {
    if (!response) return true;
    
    const unsigned char *bytes = (const unsigned char *)response;
    while (*bytes != 0x00) {
        int num;
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

std::string LLMInference::completionLoop() {
    // Check context size
    uint32_t contextSize = llama_n_ctx(_ctx);
    _nCtxUsed = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
    
    if (_nCtxUsed + _batch->n_tokens > contextSize) {
        throw std::runtime_error("Context size reached");
    }
    
    const bool isPromptPass = _batch->n_tokens > 1;
    auto start = ggml_time_us();
    
    // Run model
    if (llama_decode(_ctx, *_batch) < 0) {
        throw std::runtime_error("llama_decode() failed");
    }
    
    // Sample token
    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    
    // Check end of generation
    if (llama_vocab_is_eog(llama_model_get_vocab(_model), _currToken)) {
        _generationReachedEog = true;
        return "[EOG]";
    }
    
    // Convert token to piece
    std::string piece = common_token_to_piece(_ctx, _currToken, true);
    
    auto end = ggml_time_us();
    if (isPromptPass) {
        _promptProcessingTimeUs += (end - start);
        _promptProcessingTokens += _batch->n_tokens;
    } else {
        _responseGenerationTimeUs += (end - start);
        _responseNumTokens += 1;
    }
    _cacheResponseTokens += piece;
    
    // Update batch with new token
    _batch->token = &_currToken;
    _batch->n_tokens = 1;
    
    if (_isValidUtf8(_cacheResponseTokens.c_str())) {
        _response += _cacheResponseTokens;
        std::string valid_piece = _cacheResponseTokens;
        _cacheResponseTokens.clear();
        return valid_piece;
    }
    
    return "";
}

void LLMInference::stopCompletion() {
    if (_storeChats && _generationReachedEog && !_response.empty()) {
        addChatMessage(_response.c_str(), "assistant");
    }
    _generationReachedEog = false;
    _response.clear();
    _cacheResponseTokens.clear();
}

std::string LLMInference::benchModel(int pp, int tg, int pl, int nr) {
// Full PP+TG benchmark (from SmolChat pattern)
g_batch = llama_batch_init(pp, 0, pl);

auto pp_avg = 0.0;
auto tg_avg = 0.0;
auto pp_std = 0.0;
auto tg_std = 0.0;

const uint32_t n_ctx = llama_n_ctx(this->_ctx);
LOGi("Benchmark: n_ctx = %d, pp = %d, tg = %d, pl = %d, nr = %d", n_ctx, pp, tg, pl, nr);

int i, j;
int nri;
for (nri = 0; nri < nr; nri++) {
LOGi("Benchmark run %d/%d", nri + 1, nr);

// Prompt processing benchmark
common_batch_clear(g_batch);
for (i = 0; i < pp; i++) {
common_batch_add(g_batch, 1, i, {0}, false);
}
g_batch.logits[g_batch.n_tokens - 1] = true;
llama_memory_clear(llama_get_memory(this->_ctx), false);

const auto t_pp_start = ggml_time_us();
if (llama_decode(this->_ctx, g_batch) != 0) {
LOGe("llama_decode() failed during prompt processing");
}
const auto t_pp_end = ggml_time_us();

// Token generation benchmark
llama_memory_clear(llama_get_memory(this->_ctx), false);
const auto t_tg_start = ggml_time_us();
for (i = 0; i < tg; i++) {
common_batch_clear(g_batch);
for (j = 0; j < pl; j++) {
common_batch_add(g_batch, 0, i, {j}, true);
}
if (llama_decode(this->_ctx, g_batch) != 0) {
LOGe("llama_decode() failed during text generation");
}
}
const auto t_tg_end = ggml_time_us();

llama_memory_clear(llama_get_memory(this->_ctx), false);

const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

const auto speed_pp = double(pp) / t_pp;
const auto speed_tg = double(pl * tg) / t_tg;

pp_avg += speed_pp;
tg_avg += speed_tg;
pp_std += speed_pp * speed_pp;
tg_std += speed_tg * speed_tg;

LOGi("Run %d: pp %.2f t/s, tg %.2f t/s", nri + 1, speed_pp, speed_tg);
}

llama_batch_free(g_batch);

pp_avg /= double(nr);
tg_avg /= double(nr);

if (nr > 1) {
pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
} else {
pp_std = 0;
tg_std = 0;
}

char model_desc[128];
llama_model_desc(this->_model, model_desc, sizeof(model_desc));

const auto model_size = double(llama_model_size(this->_model)) / 1024.0 / 1024.0 / 1024.0;
const auto model_n_params = double(llama_model_n_params(this->_model)) / 1e9;

std::stringstream result;
result << std::setprecision(3);
result << "| Model | Size | Params | Test | t/s |\n";
result << "| --- | --- | --- | --- | --- |\n";
result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |";

return result.str();
}
