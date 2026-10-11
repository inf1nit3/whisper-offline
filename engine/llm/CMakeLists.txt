cmake_minimum_required(VERSION 3.22)
project(llm_engine LANGUAGES C CXX)

# Lokales Sprachmodell für „Enhance“ (llama.cpp, Quellcode in engine/llama.cpp).
#
#   Android  → libllm_jni.so: statisch gegen llama.cpp + dessen ggml gelinkt,
#              exportiert nur die JNI-Funktionen. Liegt neben libwhisper_jni.so,
#              dessen ggml (aus whisper.cpp) davon nichts sieht.
#   sonst    → llm-runner: eigener Prozess (Windows-App, Tests unter macOS).
#              Unter Windows mit eigenen ggml-DLLs in engine/llm/ — getrennt von
#              den whisper-DLLs in engine/.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)  # Jinja-Chatvorlagen (Gemma 4 u. a.)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)  # kein Download aus der App heraus
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)

if (ANDROID)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # Gleiche Baseline wie die Whisper-Engine (siehe engine/jni/CMakeLists.txt)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif()
elseif (WIN32)
    # Wie bei whisper: alle CPU-Varianten als Module, Auswahl zur Laufzeit.
    set(BUILD_SHARED_LIBS     ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_METAL        OFF CACHE BOOL "" FORCE)  # gleiche CPU-Pfade wie auf den Zielgeräten
    set(GGML_BLAS         OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp llama.cpp EXCLUDE_FROM_ALL)

add_library(llm_core STATIC llm_engine.cpp)
target_include_directories(llm_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(llm_core PUBLIC llama llama-common)
set_target_properties(llm_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if (ANDROID)
    add_library(llm_jni SHARED llm_jni.c)
    find_library(log-lib log)
    target_link_libraries(llm_jni PRIVATE llm_core ${log-lib})
    target_link_options(llm_jni PRIVATE "-Wl,--exclude-libs,ALL")
else()
    add_executable(llm-runner llm_runner.cpp)
    target_link_libraries(llm-runner PRIVATE llm_core)
    if (MINGW)
        # MinGW-Laufzeit fest einbinden, damit die EXE ohne libstdc++-6.dll
        # startet (die ggml-DLLs brauchen sie trotzdem, sie liegt daneben).
        target_link_options(llm-runner PRIVATE -static-libgcc -static-libstdc++)
    endif()
endif()
