#pragma once

#include <llama.h>
#include <common.h>
#include <chat.h>
#include <string>
#include <vector>

class LLMInference {
private:
    // llama.cpp-specific types
    llama_context* _ctx = nullptr;
    llama_model* _model = nullptr;
    llama_sampler* _sampler = nullptr;
    llama_token _currToken;
    llama_batch* _batch = nullptr;
    llama_batch g_batch;
    
    // Stores messages in the conversation
    std::vector<llama_chat_message> _messages;
    
    // Formatted messages after applying chat template
    std::vector<char> _formattedMessages;
    
    // Prompt tokens
    std::vector<llama_token> _promptTokens;
    
    std::string _chatTemplate;
    std::string _response;
    std::string _cacheResponseTokens;
    
    bool _storeChats = true;
    int64_t _responseGenerationTime = 0;
    long _responseNumTokens = 0;
    int _nCtxUsed = 0;
    
    bool _isValidUtf8(const char* response);
    
public:
    LLMInference() = default;
    ~LLMInference();
    
    // Prevent copying
    LLMInference(const LLMInference&) = delete;
    LLMInference& operator=(const LLMInference&) = delete;
    
    void loadModel(
        const char* model_path,
        float minP,
        float temperature,
        bool storeChats,
        long contextSize,
        const char* chatTemplate,
        int nThreads,
        bool useMmap,
        bool useMlock
    );
    
    void addChatMessage(const char* message, const char* role);
    float getResponseGenerationTime() const;
    int getContextSizeUsed() const { return _nCtxUsed; }
    
    void startCompletion(const char* query);
    std::string completionLoop();
    void stopCompletion();
    
    std::string benchModel(int pp, int tg, int pl, int nr);
};
