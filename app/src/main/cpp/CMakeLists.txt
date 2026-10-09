# app/src/main/cpp/CMakeLists.txt
#
# Сборка нативной библиотеки stockfish-lib (Stockfish sf_19 + JNI-обёртка).
# Stockfish распространяется под GPLv3, см. stockfish/COPYING.txt.
#
# Флаги соответствуют ARCH=armv8 из Makefile Stockfish:
#   USE_NEON=8, USE_POPCNT, USE_PREFETCH, IS_64BIT, -fno-exceptions.

cmake_minimum_required(VERSION 3.22)
project(stockfish_lib LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(SF_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/stockfish/src")

# Исходники из SRCS в Makefile Stockfish, кроме main.cpp (у нас свой вход через JNI).
set(SF_SOURCES
    ${SF_SRC_DIR}/attacks.cpp
    ${SF_SRC_DIR}/benchmark.cpp
    ${SF_SRC_DIR}/bitboard.cpp
    ${SF_SRC_DIR}/engine.cpp
    ${SF_SRC_DIR}/evaluate.cpp
    ${SF_SRC_DIR}/memory.cpp
    ${SF_SRC_DIR}/misc.cpp
    ${SF_SRC_DIR}/movegen.cpp
    ${SF_SRC_DIR}/movepick.cpp
    ${SF_SRC_DIR}/position.cpp
    ${SF_SRC_DIR}/score.cpp
    ${SF_SRC_DIR}/search.cpp
    ${SF_SRC_DIR}/thread.cpp
    ${SF_SRC_DIR}/timeman.cpp
    ${SF_SRC_DIR}/tt.cpp
    ${SF_SRC_DIR}/tune.cpp
    ${SF_SRC_DIR}/uci.cpp
    ${SF_SRC_DIR}/ucioption.cpp
    ${SF_SRC_DIR}/syzygy/tbprobe.cpp
    ${SF_SRC_DIR}/nnue/nnue_accumulator.cpp
    ${SF_SRC_DIR}/nnue/nnue_misc.cpp
    ${SF_SRC_DIR}/nnue/network.cpp
    ${SF_SRC_DIR}/nnue/features/half_ka_v2_hm.cpp
    ${SF_SRC_DIR}/nnue/features/full_threats.cpp
    ${SF_SRC_DIR}/nnue/features/pp_3wide.cpp
)

add_library(stockfish-lib SHARED
    stockfish_jni.cpp
    stockfish_bridge.cpp
    ${SF_SOURCES}
)

target_include_directories(stockfish-lib PRIVATE ${SF_SRC_DIR})

# NNUE_EMBEDDING_OFF: сеть НЕ встраивается в библиотеку (она весит ~100 МБ).
# Файл сети скачивается при первом запуске (NnueDownloader.kt) и передаётся через EvalFile.
target_compile_definitions(stockfish-lib PRIVATE
    NDEBUG
    IS_64BIT
    USE_POPCNT
    USE_PREFETCH
    USE_NEON=8
    NNUE_EMBEDDING_OFF
)

target_compile_options(stockfish-lib PRIVATE
    -O3
    -fno-exceptions
    -Wall
    -Wcast-qual
)

find_library(log-lib log)
target_link_libraries(stockfish-lib ${log-lib})
