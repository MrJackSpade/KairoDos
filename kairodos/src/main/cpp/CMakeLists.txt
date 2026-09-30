cmake_minimum_required(VERSION 3.22.1)
project(kairodos_host LANGUAGES CXX)

add_library(kairodos_host SHARED native_host.cpp)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(kairodos_host PRIVATE gpu_fixture.cpp)
endif()
target_compile_features(kairodos_host PRIVATE cxx_std_17)
target_include_directories(kairodos_host PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../shared/frontend/src/main/cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dosbox-pure/libretro-common/include)
target_link_libraries(kairodos_host PRIVATE android log dl EGL GLESv3)
