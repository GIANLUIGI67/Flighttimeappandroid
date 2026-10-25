# Aletheia Offline — llama.cpp JNI (GGUF on-device inference)
cmake_minimum_required(VERSION 3.22.1)
project(aletheia_llama)

set(LLAMA_BUILD_COMMON ON CACHE BOOL "build common utils" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Static llama/common must be PIC to link into libaletheia_llama.so
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Prefer a vendored tree (committed or scripts/sync-llama-cpp.sh). Fall back to FetchContent.
set(ALETHEIA_LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp")
if(EXISTS "${ALETHEIA_LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "Aletheia: using vendored llama.cpp at ${ALETHEIA_LLAMA_DIR}")
    add_subdirectory(${ALETHEIA_LLAMA_DIR} build-llama)
else()
    message(STATUS "Aletheia: FetchContent llama.cpp (tag b5215)")
    include(FetchContent)
    FetchContent_Declare(
            llama
            GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
            GIT_TAG        b5215
            GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(aletheia_llama SHARED aletheia_llama_jni.cpp)

# Headers come from the llama / common targets when available.
target_link_libraries(aletheia_llama
        llama
        common
        android
        log)
