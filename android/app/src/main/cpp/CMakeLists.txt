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

add_subdirectory(llama.cpp build-llama)

add_library(loocal_llama SHARED loocal_llama.cpp)
target_link_libraries(loocal_llama PRIVATE llama android log)
