cmake_minimum_required(VERSION 3.22.1)
project(llmhotspot_native LANGUAGES C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_STANDALONE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp llama-build EXCLUDE_FROM_ALL)

add_library(llmhotspot SHARED llmhotspot_jni.cpp)
target_include_directories(llmhotspot PRIVATE third_party/llama.cpp/include)
target_link_libraries(llmhotspot PRIVATE llama android log)
target_compile_features(llmhotspot PRIVATE cxx_std_17)
