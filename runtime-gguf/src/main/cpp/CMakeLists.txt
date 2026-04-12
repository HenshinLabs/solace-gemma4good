cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    llama_android
    SHARED
    gguf_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    llama_android
    ${log-lib}
)
