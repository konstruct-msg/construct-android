# libconstruct_whisper.so: whisper.cpp (static, CPU only) behind a three-call JNI bridge.
# Built by scripts/build_whisper.sh for each ABI with the NDK's toolchain; the source is fetched
# there at a pinned version and checked by SHA-256 (TODO 116). Not built by Gradle.
cmake_minimum_required(VERSION 3.18)
project(construct_whisper C CXX)

if(NOT WHISPER_SRC)
    message(FATAL_ERROR "WHISPER_SRC: the whisper.cpp source tree")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# No libomp to ship beside it; ggml's own thread pool does the work.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Cross-compiled for every phone of the ABI, not this machine: an armv8.2 build would stop with an
# illegal instruction on the Cortex-A53 phones still about.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_SRC} whisper EXCLUDE_FROM_ALL)

add_library(construct_whisper SHARED whisper_jni.c)
target_link_libraries(construct_whisper PRIVATE whisper log)
target_compile_options(construct_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(construct_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL "-Wl,-z,max-page-size=16384")
