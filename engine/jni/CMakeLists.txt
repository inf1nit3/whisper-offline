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

# ARM-Erweiterungen des Zielgeräts fest einkompilieren. Ohne das baut der NDK
# auf der armv8-a-Baseline — ohne dotprod und fp16-Arithmetik laufen die
# quantisierten Matmuls um ein Vielfaches langsamer.
# Baseline armv8.2+dotprod+fp16: läuft auf Cortex-A75/A55-Klasse und neuer
# (~2018, z. B. auch Redmi-Note-Geräte ohne i8mm). whisper_engine.c prüft
# dotprod beim Laden über HWCAP und meldet sauber statt mit SIGILL abzustürzen.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
# GPU-Beschleunigung über Vulkan (Android-GPUs); fällt automatisch auf CPU
# zurück, wenn das Gerät kein Vulkan bereitstellt.
# GPU-Pfad standardmäßig aus. Gemessen auf einem Vivo X300 Pro (Dimensity 9500,
# Whisper small q5_1, gleiches 30-s-Fenster): CPU 6,6 s gegen Vulkan 14,1 s.
# Der Vulkan-Backend kostet außerdem ~65 MB SPIR-V-Shader in der .so.
# Zum Gegentesten auf anderer Hardware: -DGGML_VULKAN=ON (braucht dann
# ANDROID_PLATFORM=android-28, siehe README).
set(GGML_VULKAN OFF CACHE BOOL "")
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "" FORCE)

# ggml-vulkan braucht außerdem SPIRV-Headers und Vulkan-Headers. Beide liegen
# vorgebaut in tools/ (siehe README); der NDK liefert nur libvulkan.so.
# CMAKE_FIND_ROOT_PATH muss mit erweitert werden, weil die Android-Toolchain
# find_package sonst auf den Sysroot einschränkt.
if (GGML_VULKAN)
    foreach(_prefix spirv-install vk-install)
        set(_dir "${CMAKE_CURRENT_SOURCE_DIR}/../../tools/${_prefix}")
        if (EXISTS "${_dir}")
            list(APPEND CMAKE_PREFIX_PATH    "${_dir}")
            list(APPEND CMAKE_FIND_ROOT_PATH "${_dir}")
            # ggml-vulkan sucht SPIRV-Headers zwar per find_package, verlinkt das
            # Target aber nie — der Include-Pfad muss von außen kommen.
            include_directories(SYSTEM "${_dir}/include")
        endif()
    endforeach()
endif()

# ggml-vulkan übersetzt seine Shader mit glslc. Der NDK bringt das Werkzeug mit,
# legt es aber nicht in den PATH — sonst scheitert schon das Konfigurieren.
if (GGML_VULKAN AND NOT Vulkan_GLSLC_EXECUTABLE)
    file(GLOB _ndk_glslc "${ANDROID_NDK}/shader-tools/*/glslc")
    if (_ndk_glslc)
        list(GET _ndk_glslc 0 _glslc)
        set(Vulkan_GLSLC_EXECUTABLE "${_glslc}" CACHE FILEPATH "" FORCE)
        message(STATUS "glslc aus dem NDK: ${_glslc}")
    endif()
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED
    whisper_jni.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../core/whisper_engine.c)
target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../core
    ${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp/ggml/include)
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper parakeet ${log-lib})
