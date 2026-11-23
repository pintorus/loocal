cmake_minimum_required(VERSION 3.22.1)
project(loocal_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build llama.cpp as static libraries linked into one libloocal_llama.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Default: armv8.2 dot-product + fp16, present on practically every 64-bit phone from 2018 on.
    # LOOCAL_ARM_ARCH overrides it for experiments (gradle -Ploocal.armArch=...).
    if(NOT LOOCAL_ARM_ARCH)
        set(LOOCAL_ARM_ARCH "armv8.2-a+dotprod+fp16")
    endif()
    set(GGML_CPU_ARM_ARCH "${LOOCAL_ARM_ARCH}" CACHE STRING "" FORCE)
endif()

# Image understanding (Qwen3-VL) through libmtmd, built on its own without the rest of tools/.
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

# Stable Diffusion checkpoints have tensor names past ggml's default 64 characters (sd.cpp's own
# ggml is patched to 128). Set for everything, so llama.cpp and sd.cpp agree on ggml_tensor's layout.
add_compile_definitions(GGML_MAX_NAME=128)
add_subdirectory(llama.cpp build-llama)

# Painting (Stable Diffusion) with stable-diffusion.cpp, built on llama.cpp's own ggml so the two
# share one copy of it in libloocal_llama.so.
set(SD_USE_UPSTREAM_GGML ON CACHE BOOL "" FORCE)
set(SD_GGML_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml" CACHE PATH "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(stable-diffusion.cpp build-sd)

add_library(loocal_llama SHARED loocal_llama.cpp loocal_sd.cpp)
target_link_libraries(loocal_llama PRIVATE llama mtmd stable-diffusion android log)
