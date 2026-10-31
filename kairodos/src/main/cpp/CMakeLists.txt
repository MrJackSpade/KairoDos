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
# Preserve this host's existing non-Debug optimization level.
target_compile_options(kairodos PRIVATE $<$<NOT:$<CONFIG:Debug>>:-O2>)
