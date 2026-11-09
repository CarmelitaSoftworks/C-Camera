cmake_minimum_required(VERSION 3.22.1)
project(ccamera CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ccamera SHARED
    jni_bridge.cpp
    core/image.cpp
    core/burst.cpp
    core/finish.cpp
    core/outputs.cpp)

target_compile_options(ccamera PRIVATE -O3 -fvisibility=hidden -Wall)
target_link_libraries(ccamera jnigraphics log)
