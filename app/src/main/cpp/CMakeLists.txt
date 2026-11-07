cmake_minimum_required(VERSION 3.22.1)
project(jarvisbrain)

# llama.cpp source ko yahan clone karein:
#   git clone https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Android ke liye lean build: no curl, no tests/examples/server
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod + fp16 = Q4 models par ~2x speed (Snapdragon 8xx / Dimensity 8xxx+)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(jarvisbrain SHARED llama_jni.cpp)
target_include_directories(jarvisbrain PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
target_compile_features(jarvisbrain PRIVATE cxx_std_17)
target_compile_options(jarvisbrain PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(jarvisbrain llama ggml android log)

add_subdirectory(whisper)
