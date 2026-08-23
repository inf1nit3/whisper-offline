cmake_minimum_required(VERSION 3.22)
project(whisper_shim LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Gleiche Konfiguration wie der bisherige Windows-Engine-Build: gemeinsame
# DLLs, Backends als eigene Module, alle CPU-Varianten. ggml wählt zur Laufzeit
# die passende (SSE4.2 bis AVX-512/AMX), die EXE läuft damit auf jedem x64.
set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE              OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   ON  CACHE BOOL "" FORCE)  # whisper-cli bleibt als Rückfallweg
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_shim SHARED
    whisper_shim.c
    ${CMAKE_CURRENT_SOURCE_DIR}/../core/whisper_engine.c)

target_include_directories(whisper_shim PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../core
    ${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../whisper.cpp/ggml/include)

target_link_libraries(whisper_shim PRIVATE whisper parakeet)

# Ohne Präfix, damit die Datei unter Windows whisper_shim.dll heißt und der
# DllImport-Name in WhisperNative.cs direkt passt.
set_target_properties(whisper_shim PROPERTIES PREFIX "")
