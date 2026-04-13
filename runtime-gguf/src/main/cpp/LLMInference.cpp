#include "LLMInference.h"
#include <android/log.h>
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
    bool storeChats,
    long contextSize,
    const char *chatTemplate,
    int nThreads,
    int nGpuLayers,
    bool useMmap,
    bool useMlock
) {
    LOGi("Loading model: %s", model_path);
    
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
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.no_perf = true;
    
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("Failed to create context");
        throw std::runtime_error("llama_init_from_model() failed");
    }
    
    // Create sampler
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    _sampler = llama_sampler_chain_init(sampler_params);
    
    // Add samplers
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    
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
    // Simplified benchmark
    g_batch = llama_batch_init(pp, 0, pl);
    
    auto start = ggml_time_us();
    
    // Prompt processing
    common_batch_clear(g_batch);
    for (int i = 0; i < pp; i++) {
        common_batch_add(g_batch, 1, i, {0}, false);
    }
    g_batch.logits[g_batch.n_tokens - 1] = true;
    
    llama_memory_clear(llama_get_memory(_ctx), false);
    
    if (llama_decode(_ctx, g_batch) != 0) {
        LOGe("llama_decode() failed during prompt processing");
    }
    
    auto end = ggml_time_us();
    auto t_pp = double(end - start) / 1000000.0;
    double speed_pp = double(pp) / t_pp;
    
    llama_batch_free(g_batch);
    
    std::stringstream result;
    result << std::setprecision(3);
    result << "Prompt processing: " << speed_pp << " t/s\n";
    result << "Prompt tokens: " << pp << "\n";
    result << "Time: " << t_pp << " s";
    
    return result.str();
}
