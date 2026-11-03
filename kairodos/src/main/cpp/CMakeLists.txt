cmake_minimum_required(VERSION 3.22.1)
project(kairodos LANGUAGES C CXX)

get_filename_component(KAIRODOS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
include("${KAIRODOS_ROOT}/shared/cmake/KairoMachine.cmake")

# DOSBox Staging (backend-dos), which the core loads afresh for each run, on the shared machine host.
kairo_machine_library(kairodos dos_core.cpp)
# libkairo_chd.so: CDs kept as CHDs are hashed and prepared as the image they were made from (KairoDos#146).
include("${KAIRODOS_ROOT}/shared/cmake/KairoChd.cmake")
kairo_chd_jni_library()
target_include_directories(kairodos PRIVATE "${KAIRODOS_ROOT}/backend-dos/src/main/cpp")
if(NOT ANDROID)
    # The shared PC boot test (KairoDos#140) loads Staging as the app does, a library beside it, built
    # here with the dependencies tools/PrepareStagingDependencies.ps1 -Abi windows-host makes; the
    # arm64 profile does not apply.
    set(KAIRO_STAGING_DEPS "${KAIRODOS_ROOT}/.downloads/staging-deps/installed/x64-kairo-mingw" CACHE PATH "")
    set(KAIRO_PGO OFF CACHE BOOL "" FORCE)
    add_subdirectory("${KAIRODOS_ROOT}/backend-dos" backend-dos)
    # Copied whenever the library changes, not only when kairodos relinks.
    set(staging_beside_test "${CMAKE_CURRENT_BINARY_DIR}/${CMAKE_SHARED_LIBRARY_PREFIX}dosbox_staging${CMAKE_SHARED_LIBRARY_SUFFIX}")
    add_custom_command(OUTPUT "${staging_beside_test}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:dosbox> "${staging_beside_test}"
        DEPENDS dosbox)
    add_custom_target(dosbox_beside_test DEPENDS "${staging_beside_test}")
    add_dependencies(kairodos dosbox_beside_test)
endif()
# Preserve this host's existing non-Debug optimization level.
target_compile_options(kairodos PRIVATE $<$<NOT:$<CONFIG:Debug>>:-O2>)
