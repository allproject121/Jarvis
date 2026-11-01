# Jarvis native build root (AGP externalNativeBuild entry point).
#
# Each native component lives in its own subdirectory so several features can
# share the single `externalNativeBuild.cmake.path` an Android module allows.
#
#   whisper/  -> libwhisper.so (whisper.cpp + ggml, JNI for
#                com.jarvis.assistant.androllm.voice.whisper.WhisperLibKt)
#
# Pinned toolchain: NDK 26.1.10909125, CMake 3.22.1, ABI arm64-v8a, minSdk 28.
cmake_minimum_required(VERSION 3.22)
project(jarvis_native C CXX)

add_subdirectory(whisper)
