cmake_minimum_required(VERSION 3.22.1)

project("stronghold_android")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# JNI Bridge
add_library(
        node_bridge
        SHARED
        node_bridge.cpp
)

# 导入已经编译好的 Node.js Android 动态库
add_library(
        node
        SHARED
        IMPORTED
)

set_target_properties(
        node
        PROPERTIES
        IMPORTED_LOCATION
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so"
)

# Node.js headers
target_include_directories(
        node_bridge
        PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include/node"
)

# Android 日志库
find_library(
        log-lib
        log
)

# 链接
target_link_libraries(
        node_bridge
        node
        ${log-lib}
)