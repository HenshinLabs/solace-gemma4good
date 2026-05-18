# Solace: On-Device Mental Health AI Powered by Gemma 4 E2B via llama.cpp on Android

**A private, offline, crisis-ready mental health companion that leverages Google's Gemma 4 E2B model through carefully engineered therapeutic personas to provide empathetic mental health support, where the model's inherent tendency toward agreeable and supportive language generation is redirected through specialized system prompts to prevent users from spiraling into despair, all running locally on Android phones at 10-12 tokens per second using ARM64-optimized llama.cpp inference with multimodal vision, offline voice I/O, and five evidence-based therapeutic session templates, ensuring that the most vulnerable conversations a person can have never leave their device.**

---

## The Problem Gemma 4 Solves

One billion people worldwide suffer from mental health conditions yet most never receive care, and the people who need help the most are precisely those least likely to seek it because the barriers are not just cost and geography but the paralyzing fear that their darkest thoughts will be recorded, judged, or shared, which is why Solace runs entirely on-device so that a person having a panic attack at 3 AM or contemplating self-harm can speak freely to an AI that will never log their words to a server, never flag their account, and never sell their data, because there is no server, no account, and no data transmission.

## How Gemma 4's Behavioral Characteristics Are Used for Good

The core insight that makes Solace work is not just that Gemma 4 E2B can run on a phone, but that large language models have a well-documented behavioral tendency toward **sycophancy**, where they agree with the user and mirror their emotional state, which is a liability in general-purpose chatbots but becomes a **therapeutic asset** when properly directed, because a person in crisis who says "everything is terrible" needs an AI that does not argue with their feelings but instead acknowledges them with warmth and then gently redirects toward grounding techniques and safety planning, and Gemma 4's architecture makes this particularly effective because its instruction-following capabilities allow the system prompt to precisely shape the model's response patterns without requiring fine-tuning.

## Therapeutic Persona Engineering

Each of the five therapeutic session templates in Solace engineers a distinct persona through carefully crafted system prompts that exploit Gemma 4's behavioral characteristics in different ways:

### Anxiety Relief
The system prompt instructs the model to validate the user's feelings without judgment before guiding them through box breathing and 5-4-3-2-1 grounding, using short sentences and an unhurried tone that mirrors the pacing a human therapist would adopt.

### Panic Attack Support
The system prompt tells the model to prioritize immediate stabilization over solutions, repeating reassurance frequently and keeping responses extremely brief because a person in panic cannot process long paragraphs.

### Sleep and Rest
The system prompt directs the model to use flowing, gentle language and to acknowledge the user's worries warmly before transitioning back to relaxation, exploiting the model's tendency to be accommodating by having it accommodate the user's need for calm rather than their spiral of anxious thoughts.

### Daily Check-in
The system prompt shapes the model into a conversational companion that helps users name their emotions, which is a clinically validated technique because many people struggle to identify exactly what they feel and the act of naming an emotion reduces its intensity.

### Crisis Support
The system prompt instructs the model to express that it hears the user's pain, to gently assess their safety, and to automatically surface crisis helpline numbers including the 988 Suicide and Crisis Lifeline, iCall India, the Vandrevala Foundation, and the Crisis Text Line, because in a crisis the barrier to help is often the effort of looking up a phone number and Solace removes that barrier entirely.

## Thinking Block Separation

The system prompt engineering goes beyond simple instruction setting because Gemma 4 uses a channel-based thinking architecture where the model can reason internally in thinking blocks before producing its visible response, and Solace leverages this by allowing the model to process the user's emotional state in its internal reasoning while ensuring that only the calm, supportive response reaches the user, with the `TtsTextFilter` class stripping thinking blocks from the output before text-to-speech playback so that a user listening through headphones hears only reassurance and not the model's analytical processing of their distress, which creates an experience that feels less like talking to a computer and more like speaking with someone who simply understands.

## Architecture

The technical architecture that enables this therapeutic experience is a seventeen-module Android application following Clean Architecture with MVVM, where the native inference layer wraps llama.cpp through a JNI bridge that supports text generation and multimodal image understanding, with the runtime selecting the optimal native library from seven ARM64-compiled variants based on CPU feature detection from `/proc/cpuinfo`, mapping CPU part IDs to core types and loading the best available SIMD configuration, which delivers the 10-12 tokens per second generation speed that makes conversation feel natural rather than stilted, because therapeutic rapport requires pacing and a model that takes five seconds per response breaks the emotional flow that makes the interaction effective.

```
Presentation Layer (Jetpack Compose)
    Home Screen / Chat Screen / Guided Sessions / Settings
            |
ViewModel Layer (Hilt, StateFlow)
    ChatViewModel (1,696 lines) / RoleplayViewModel (1,099 lines)
            |
Data Layer (Room, SharedPreferences)
    ConversationRepository / ModelRepository / Vosk ASR / KittenTTS
            |
Runtime Layer (Native C++)
    GgufEngine (JNI) -> LLMInference (C++) -> mtmd (multimodal)
            |
llama.cpp (submodule)
    ggml-cpu (ARM64 SIMD) / ggml-vulkan (GPU) / common (tokenization)
```

## Performance Journey

The performance journey from zero inference to production-ready speed involved four phases:

1. **Phase 1: Zero tokens/sec.** The GGUF header reported 128K context but we were initializing with 2048, causing `llama_decode` to return -1.
2. **Phase 2: 2 tokens/sec on Qwen 0.8B.** Fixed by tuning batch configuration from default 512/512 to auto-tuned 1024/512.
3. **Phase 3: 40 tokens/sec on Qwen 0.8B.** Adding ARM64-specific compilation with FP16, Dot Product, I8MM, and SVE instructions delivered a twenty-fold improvement.
4. **Phase 4: 10-12 tokens/sec on Gemma 4 E2B.** Expected performance given the model is four times larger, with Vulkan GPU offload pushing to 16-18 tokens/sec on Adreno 750.

| Metric | Value |
|--------|-------|
| Prompt processing | ~85 tokens/sec |
| Token generation | 10-12 tokens/sec |
| First token latency | ~1.2 seconds |
| Context window | 128K tokens |
| Model load time | ~3 seconds |
| GPU offload (Adreno 750) | 16-18 tokens/sec |

## Multimodal Vision

The multimodal vision capability adds another therapeutic dimension because Gemma 4's native image understanding through the mtmd pipeline allows users to share images as part of their conversation, which enables art therapy exercises where a user draws their emotional state and the AI responds to the visual content, journaling with photographs where the model helps the user find meaning in their daily experiences, and visual grounding techniques where during an anxiety episode the user can photograph their surroundings and the AI helps them engage with the present moment through describing what it sees, with the mmproj vision projector downloaded separately at 941 MB so that text-only users are not burdened with files they do not need.

## Offline Voice Pipeline

The offline voice pipeline ensures that users who cannot type during distress can still access support, using Vosk for speech-to-text with a 40 MB English model and KittenTTS for text-to-speech with a 23 MB ONNX model bundled in the application assets, so that the complete interaction loop from speaking your feelings to hearing a calming response happens entirely on the device without any network call, which is critical for a mental health application because the people who need it most are often in situations where connectivity is unavailable or undesirable.

## Web Search Agent

The web search agent capability allows the model to look up current health information through DuckDuckGo when the user asks about specific conditions, medications, or local resources, with the `ToolRegistry` class providing web search, URL fetching, and time tools that the model can invoke through structured output, expanding the assistant's usefulness beyond pure conversation into practical mental health information retrieval.

## Download Flow

The download flow presents a consent dialog explaining the model size and capabilities before downloading, uses SHA-256 verification for file integrity, supports HTTP resume for interrupted downloads, and automatically proceeds to download the vision projector after the main model completes, with the entire first-launch experience designed to be transparent and non-coercive because a mental health application must earn trust from the very first interaction.

## Built With Gemma

Solace was developed with the assistance of [Gemma 4 31B IT](https://huggingface.co/google/gemma-4-31B-it) running on an NVIDIA A100 80GB GPU as a coding assistant, with the same Gemma 4 family powering the on-device inference in the final application, demonstrating that the models designed for positive impact are themselves used to build the tools that deliver that impact.

## Links

- **Blog:** [https://henshinlabs.github.io/solace-gemma4good/blog.html](https://henshinlabs.github.io/solace-gemma4good/blog.html)
- **Repository:** [https://github.com/HenshinLabs/solace-gemma4good](https://github.com/HenshinLabs/solace-gemma4good)
- **Release downloads:** [https://github.com/HenshinLabs/solace-gemma4good/releases/tag/v2.0.5](https://github.com/HenshinLabs/solace-gemma4good/releases/tag/v2.0.5)
- **Website:** [https://henshinlabs.github.io/solace-gemma4good/](https://henshinlabs.github.io/solace-gemma4good/)
- **License:** GNU General Public License v3.0

---

*NOTE: HenshinLabs is our own organization for building our own open-source projects and applications that are still too small for the big world.*
