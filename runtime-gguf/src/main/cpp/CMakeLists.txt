cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# For now, use minimal implementation
# Full llama.cpp integration with FetchContent requires network

add_library(
    llama_android
    SHARED
    gguf_bridge_stub.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    llama_android
    ${log-lib}
    ${android-lib}
)
