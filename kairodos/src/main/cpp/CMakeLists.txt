cmake_minimum_required(VERSION 3.22.1)
project(kairodos_host LANGUAGES CXX)

add_library(kairodos_host SHARED native_host.cpp)
target_compile_features(kairodos_host PRIVATE cxx_std_17)
target_include_directories(kairodos_host PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dosbox-pure/libretro-common/include)
target_link_libraries(kairodos_host PRIVATE android log dl)
