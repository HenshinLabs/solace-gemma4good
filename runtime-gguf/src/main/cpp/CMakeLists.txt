cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# For production, use the full llama.cpp implementation
# For development/testing, use the stub
option(USE_LLAMA_CPP "Build with full llama.cpp support (requires network)" OFF)

if(USE_LLAMA_CPP)
    # Include llama.cpp via FetchContent
    include(FetchContent)
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG b4505
        GIT_SHALLOW TRUE
    )
    
    # Configure llama.cpp
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
    set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
    set(LLAMA_AVX OFF CACHE BOOL "" FORCE)
    set(LLAMA_AVX2 OFF CACHE BOOL "" FORCE)
    set(LLAMA_AVX512 OFF CACHE BOOL "" FORCE)
    set(LLAMA_FMA OFF CACHE BOOL "" FORCE)
    set(LLAMA_F16C OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_AARCH64 ON CACHE BOOL "" FORCE)
    
    FetchContent_MakeAvailable(llama)
    
    # Full implementation sources
    set(SOURCES
        LLMInference.cpp
        gguf_bridge.cpp
    )
    
    set(INCLUDES
        ${llama_SOURCE_DIR}
        ${llama_SOURCE_DIR}/common
        ${llama_SOURCE_DIR}/ggml/include
    )
    
    set(LIBRARIES
        llama
        common
        ggml
    )
else()
    # Stub implementation for development/testing
    set(SOURCES
        gguf_bridge_stub.cpp
    )
    
    set(INCLUDES "")
    set(LIBRARIES "")
endif()

add_library(llama_android SHARED ${SOURCES})

if(INCLUDES)
    target_include_directories(llama_android PRIVATE ${INCLUDES})
endif()

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(llama_android
    ${LIBRARIES}
    ${log-lib}
    ${android-lib}
)

target_compile_options(llama_android PRIVATE
    -O3
    -ffast-math
    -funroll-loops
)
