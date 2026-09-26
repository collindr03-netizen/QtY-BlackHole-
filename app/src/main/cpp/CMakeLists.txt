cmake_minimum_required(VERSION 3.22.1)

project("qty_telemetry_jni")

# Set C++20 standard
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define the shared library
add_library(
    qty_telemetry_jni
    SHARED
    qty_telemetry.cpp
)

# Find Android Log Library
find_library(
    log-lib
    log
)

# Link libraries
target_link_libraries(
    qty_telemetry_jni
    ${log-lib}
)
