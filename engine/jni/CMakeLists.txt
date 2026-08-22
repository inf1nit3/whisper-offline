cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# GPU-Beschleunigung über Vulkan (Android-GPUs); fällt automatisch auf CPU
# zurück, wenn das Gerät kein Vulkan bereitstellt.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.c)
target_include_directories(whisper_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp/include)
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})
