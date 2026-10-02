cmake_minimum_required(VERSION 3.22.1)
project("hyllm" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp source checkout (see scripts/fetch-llama.sh). Pinned in llama-version.txt.
set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run scripts/fetch-llama.sh first.")
endif()

# Lean static build: CPU backend only, no tools/tests/network.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Cortex-A78/A55 class cores (e.g. Dimensity 7300 in Nothing Phone 3a Pro).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} build-llama)

add_library(hyllm SHARED llm_core.cpp jni_bridge.cpp)
target_link_libraries(hyllm llama android log)
# 16 KB page alignment: required on Android 15+ devices that use 16 KB memory pages.
target_link_options(hyllm PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
