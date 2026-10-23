cmake_minimum_required(VERSION 3.22.1)
project(kairodos LANGUAGES CXX)

include("${CMAKE_CURRENT_SOURCE_DIR}/../../../../shared/cmake/KairoNativeDefaults.cmake")
add_library(kairodos SHARED native_host.cpp)
kairo_native_debug_defaults(kairodos)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(kairodos PRIVATE KAIRO_VIDEO_PROFILE)
    # Optional measurement build, never changes ordinary debug/release settings.
    if(KAIRO_OPTIMIZED_PROFILE)
        target_compile_definitions(kairodos PRIVATE NDEBUG KAIRO_OPTIMIZED_PROFILE)
    endif()
endif()
target_compile_features(kairodos PRIVATE cxx_std_17)
target_include_directories(kairodos PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../shared/frontend/src/main/cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../shared/third_party/librashader/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../backend-dos/src/main/cpp)
# Preserve this host's existing non-Debug optimization level.
target_compile_options(kairodos PRIVATE $<$<NOT:$<CONFIG:Debug>>:-O2>)
target_link_libraries(kairodos PRIVATE android log dl EGL GLESv3)
