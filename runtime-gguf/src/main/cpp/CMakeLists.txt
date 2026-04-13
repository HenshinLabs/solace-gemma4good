cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG c08d28d08871715fd68accffaeeb76ddcaede658
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

set(LLAMA_DIR ${llama_SOURCE_DIR})
set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)
set(VENDOR_DIR ${LLAMA_DIR}/vendor)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(VULKAN_LIB vulkan)

function(configure_target target_name)
    target_include_directories(
        ${target_name}
        PRIVATE
        ${COMMON_DIR}
        ${GGML_DIR}/include
        ${GGML_DIR}/src
        ${GGML_DIR}/src/ggml-cpu
        ${LLAMA_DIR}/include
        ${VENDOR_DIR}
    )

    target_compile_options(
        ${target_name}
        PRIVATE
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
        -Wl,--exclude-libs,ALL
    )
endfunction()

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        LLMInference.cpp
        gguf_bridge.cpp
    )
    configure_target(${target_name})
endfunction()

function(build_library_arm64 target_name cpu_flags)
    build_library(${target_name})
    target_compile_options(
        ${target_name}
        PRIVATE
        -DGGML_USE_CPU
        -DGGML_USE_CPU_AARCH64
        ${cpu_flags}
        -O3
    )
endfunction()

build_library(llama_android)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library_arm64(llama_android_v8_2_fp16 "-march=armv8.2-a+fp16")
    build_library_arm64(llama_android_v8_2_fp16_dotprod "-march=armv8.2-a+fp16+dotprod")
    build_library_arm64(llama_android_v8_4_fp16_dotprod "-march=armv8.4-a+fp16+dotprod")
    build_library_arm64(llama_android_v8_4_fp16_dotprod_sve "-march=armv8.4-a+fp16+dotprod+sve")
    build_library_arm64(llama_android_v8_4_fp16_dotprod_i8mm "-march=armv8.4-a+fp16+dotprod+i8mm")
    build_library_arm64(llama_android_v8_4_fp16_dotprod_i8mm_sve "-march=armv8.4-a+fp16+dotprod+i8mm+sve")
endif()
