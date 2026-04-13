# Third-Party Notices

## llama.cpp

This application uses `llama.cpp` for GGUF model inference in the native runtime bridge.

- Project: https://github.com/ggerganov/llama.cpp
- Copyright: Contributors to `llama.cpp`
- License: MIT License

`llama.cpp` is integrated as a git submodule at `llama.cpp` and linked from `runtime-gguf/src/main/cpp/CMakeLists.txt`.

## SmolChat-Android

Parts of the GGUF model loading metadata reader and native build strategy are adapted from
`SmolChat-Android`.

- Project: https://github.com/shubham0204/SmolChat-Android
- Copyright: Shubham Panchal
- License: Apache License 2.0

Adapted files include:

- `runtime-gguf/src/main/kotlin/com/masterllm/runtime/gguf/GGUFReader.kt`
- `runtime-gguf/src/main/cpp/GGUFReader.cpp`
- `runtime-gguf/src/main/cpp/CMakeLists.txt`
