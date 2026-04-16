cmake_minimum_required(VERSION 3.22.1)
project(image_diffusion)

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

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ImageDiffusion.cpp
        image_bridge.cpp
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
    )
endfunction()

build_library_universal("image_diffusion")

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library_arm64("image_diffusion_v8_2_fp16" "-march=armv8.2-a+fp16")
    build_library_arm64("image_diffusion_v8_2_fp16_dotprod" "-march=armv8.2-a+fp16+dotprod")
    build_library_arm64("image_diffusion_v8_4_fp16_dotprod" "-march=armv8.4-a+fp16+dotprod")
    build_library_arm64("image_diffusion_v8_4_fp16_dotprod_sve" "-march=armv8.4-a+fp16+dotprod+sve")
    build_library_arm64("image_diffusion_v8_4_fp16_dotprod_i8mm" "-march=armv8.4-a+fp16+dotprod+i8mm")
    build_library_arm64("image_diffusion_v8_4_fp16_dotprod_i8mm_sve" "-march=armv8.4-a+fp16+dotprod+i8mm+sve")
endif()
