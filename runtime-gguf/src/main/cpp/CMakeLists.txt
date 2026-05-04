cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Enable Vulkan backend only when Vulkan-Hpp headers are available.
set(_VULKAN_HPP_HINTS
    "${CMAKE_ANDROID_NDK}/sources/third_party/vulkan/src/include"
    "$ENV{ANDROID_NDK_HOME}/sources/third_party/vulkan/src/include"
    "${CMAKE_SYSROOT}/usr/include"
)

# Avoid stale cache values and host include paths when cross-compiling for Android.
unset(VULKAN_HPP_INCLUDE_DIR CACHE)
find_path(VULKAN_HPP_INCLUDE_DIR
    NAMES vulkan/vulkan.hpp
    HINTS ${_VULKAN_HPP_HINTS}
    NO_CMAKE_PATH
    NO_CMAKE_ENVIRONMENT_PATH
    NO_SYSTEM_ENVIRONMENT_PATH
    NO_CMAKE_SYSTEM_PATH
)

if(VULKAN_HPP_INCLUDE_DIR)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    include_directories(SYSTEM ${VULKAN_HPP_INCLUDE_DIR})
else()
    message(WARNING "Vulkan-Hpp header not found; building runtime-gguf without Vulkan GPU backend")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(../../../../llama.cpp llama.cpp)

set(LLAMA_DIR_RELATIVE "../../../../llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)

set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)
set(VENDOR_DIR ${LLAMA_DIR}/vendor)

target_compile_options(
    llama
    PUBLIC
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(
    llama
    PRIVATE
    -Wl,--gc-sections
    -flto
    -Wl,--exclude-libs,ALL
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(VULKAN_LIB vulkan)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        LLMInference.cpp
        gguf_bridge.cpp
    )
    target_include_directories(
        ${target_name}
        PUBLIC
        ${COMMON_DIR}
        ${GGML_DIR}/include
        ${GGML_DIR}/src
        ${GGML_DIR}/src/ggml-cpu
        ${LLAMA_DIR}/include
        ${VENDOR_DIR}
    )
    target_compile_options(
        ${target_name}
        PUBLIC
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
    )
    target_link_libraries(
        ${target_name}
        PRIVATE
        ${ANDROID_LIB}
        ${LOG_LIB}
        llama
        common
    )
    if(VULKAN_LIB)
        target_link_libraries(${target_name} PRIVATE ${VULKAN_LIB})
    endif()
    target_link_options(
        ${target_name}
        PRIVATE
        -Wl,--gc-sections
        -flto
        -Wl,--exclude-libs,ALL
    )
endfunction()

function(build_library_universal target_name)
    build_library(${target_name})
    target_compile_options(
        ${target_name}
        PUBLIC
        -DGGML_USE_CPU
        -O3
        -ffast-math
    )
endfunction()

function(build_library_arm64 target_name cpu_flags)
    build_library(${target_name})
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
    target_compile_definitions(
        ${target_name}
        PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
    )
    target_compile_options(
        ${target_name}
        PUBLIC
        -DGGML_USE_CPU
        -DGGML_USE_CPU_AARCH64
        ${cpu_flags}
        -O3
        -ffast-math
    )
endfunction()

build_library_universal("llama_android")

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library_arm64("llama_android_v8_2_fp16" "-march=armv8.2-a+fp16")
    build_library_arm64("llama_android_v8_2_fp16_dotprod" "-march=armv8.2-a+fp16+dotprod")
    build_library_arm64("llama_android_v8_4_fp16_dotprod" "-march=armv8.4-a+fp16+dotprod")
    build_library_arm64("llama_android_v8_4_fp16_dotprod_sve" "-march=armv8.4-a+fp16+dotprod+sve")
    build_library_arm64("llama_android_v8_4_fp16_dotprod_i8mm" "-march=armv8.4-a+fp16+dotprod+i8mm")
    build_library_arm64("llama_android_v8_4_fp16_dotprod_i8mm_sve" "-march=armv8.4-a+fp16+dotprod+i8mm+sve")
endif()

set(TARGET_NAME_GGUF_READER ggufreader)
add_library(${TARGET_NAME_GGUF_READER} SHARED GGUFReader.cpp)
target_include_directories(
    ${TARGET_NAME_GGUF_READER}
    PUBLIC
    ${GGML_DIR}/include
)
target_compile_options(
    ${TARGET_NAME_GGUF_READER}
    PUBLIC
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_libraries(${TARGET_NAME_GGUF_READER} ggml)
target_link_options(
    ${TARGET_NAME_GGUF_READER}
    PRIVATE
    -Wl,--gc-sections
    -flto
    -Wl,--exclude-libs,ALL
)
