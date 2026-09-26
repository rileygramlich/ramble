# whisper.cpp for the keyboard: one shared library, arm64 phones only.
cmake_minimum_required(VERSION 3.22)
project(yapwhisper)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# armv8.2 half-precision maths: every phone from roughly 2018 on has it, and it
# roughly doubles whisper's speed over the baseline arm64 build.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_compile_options(-march=armv8.2-a+fp16+dotprod -O3)

add_subdirectory(${WHISPER_DIR}/ggml ggml)
add_library(yapwhisper SHARED yap_jni.cpp ${WHISPER_DIR}/src/whisper.cpp)
target_include_directories(yapwhisper PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_compile_definitions(yapwhisper PRIVATE WHISPER_VERSION="1.9.4")
find_library(LOG_LIB log)
target_link_libraries(yapwhisper ggml ${LOG_LIB} android)
