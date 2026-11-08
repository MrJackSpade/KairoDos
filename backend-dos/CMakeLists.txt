cmake_minimum_required(VERSION 3.22.1)
project(kairo_staging VERSION 0.83.0 LANGUAGES C CXX)

# arm64-v8a ships; x86_64 is for the test emulator (shared tools/emulator.ps1, Kairo#114); a Windows
# host build is the shared PC boot test's (KairoDos#140), which kairodos's CMake adds.
if(ANDROID)
    if(NOT (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
        message(FATAL_ERROR "KairoDos builds for Android arm64-v8a, or x86_64 for the test emulator")
    endif()
elseif(NOT (WIN32 AND CMAKE_SYSTEM_PROCESSOR MATCHES "AMD64|x86_64"))
    message(FATAL_ERROR "KairoDos's host build is the shared PC boot test's, on x86-64 Windows")
endif()
option(KAIRO_GUEST_PROFILE "Record translated guest code metadata (Debug only)" OFF)
option(KAIRO_OPL_WORKER "Ordered catch-up synthesis for ordinary OPL modes" ON)
option(KAIRO_OPL_VERIFY "Compare worker OPL samples with synchronous synthesis" OFF)
option(KAIRO_OPL_STRESS "Delay OPL worker with a one-entry queue for verification" OFF)
if(KAIRO_OPL_STRESS AND NOT KAIRO_OPL_VERIFY)
    message(FATAL_ERROR "OPL stress requires sample verification")
endif()
if(KAIRO_OPL_VERIFY AND NOT KAIRO_OPL_WORKER)
    message(FATAL_ERROR "OPL verification requires the worker")
endif()
if(KAIRO_OPL_VERIFY OR KAIRO_OPL_STRESS)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        message(FATAL_ERROR "OPL verification and stress are restricted to Debug builds")
    endif()
endif()
if(KAIRO_OPL_WORKER)
    add_compile_definitions(KAIRO_OPL_WORKER)
    if(KAIRO_OPL_VERIFY)
        add_compile_definitions(KAIRO_OPL_VERIFY)
    endif()
    if(KAIRO_OPL_STRESS)
        add_compile_definitions(KAIRO_OPL_STRESS)
    endif()
endif()
if(KAIRO_GUEST_PROFILE)
    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        message(FATAL_ERROR "Guest profiling is restricted to Debug measurement builds")
    endif()
    add_compile_definitions(KAIRO_GUEST_PROFILE)
endif()
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# The emulation engine must be optimized even in a debuggable frontend APK.
add_compile_options(-O2)
option(KAIRO_PGO "Use the audited native ARM64 performance profile" ON)
if(KAIRO_PGO)
    include(pgo/PrepareProfile.cmake)
endif()
add_compile_definitions(NDEBUG SDL_MAIN_HANDLED KAIRO_STAGING)

set(STAGING_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/dosbox-staging")
set(DOSBOX_VERSION "0.83.0")
set(BUILD_GIT_HASH "7b40053-kairo")
# The device's recompiler, DYNREC, on either ABI: the emulator runs the same translation (with its
# x86-64 backend) and the same portable FPU and aligned memory paths as the device, not Staging's
# x86-only dynamic core, so what the emulator checks is what players run.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(C_TARGET_CPU_ARM ON)
else()
    set(C_TARGET_CPU_X86 ON)
endif()
set(C_DYNREC ON)
set(C_PER_PAGE_W_OR_X ON)
set(C_MT32EMU ON)
# Shader metadata in 0.83.0 requires its glad helpers even for texture output.
# The Android renderer is supplied by Kairo and never creates a desktop context.
set(C_OPENGL ON)
set(OPT_TESTS OFF)
set(OPT_MANYMOUSE OFF)
set(HAS_STACK_INCLUDES OFF CACHE BOOL "Android API 26 lacks execinfo backtrace" FORCE)
if(WIN32)
    # The shared PC boot test's Windows build (KairoDos#140): what Staging's own CMake sets for
    # Windows (dosbox_config.h), Windows 10's API, which cpp-httplib and its file calls need, and a
    # 64-bit off_t (misc/cross.h).
    set(DOSBOX_PLATFORM_WINDOWS ON)
    set(NOMINMAX ON)
    set(_USE_MATH_DEFINES ON)
    add_compile_definitions(_WIN32_WINNT=0x0A00 WINVER=0x0A00 _FILE_OFFSET_BITS=64)
endif()

include(CheckIncludeFile)
include(CheckIncludeFiles)
include(CheckSymbolExists)
include(CheckStructHasMember)
foreach(header libgen.h netinet/in.h stdlib.h strings.h sys/types.h sys/xattr.h)
    string(TOUPPER "${header}" variable)
    string(REPLACE "/" "_" variable "${variable}")
    string(REPLACE "." "_" variable "${variable}")
    check_include_file("${header}" "HAVE_${variable}")
endforeach()
set(HAVE_BUILTIN_CLEAR_CACHE ON)
set(HAVE_FD_ZERO ON)
set(HAVE_SYS_SOCKET_H ON)
set(CUSTOM_DATADIR "/nonexistent/kairo-staging")
check_symbol_exists(clock_gettime time.h HAVE_CLOCK_GETTIME)
check_symbol_exists(mprotect sys/mman.h HAVE_MPROTECT)
check_symbol_exists(mmap sys/mman.h HAVE_MMAP)
check_symbol_exists(setpriority sys/resource.h HAVE_SETPRIORITY)
check_symbol_exists(strnlen string.h HAVE_STRNLEN)
check_symbol_exists(pthread_setname_np pthread.h HAVE_PTHREAD_SETNAME_NP)
check_struct_has_member("struct dirent" d_type dirent.h HAVE_STRUCT_DIRENT_D_TYPE)
configure_file("${STAGING_SOURCE}/src/dosbox_config.h.in.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/include/dosbox_config.h")

# Dependency sources/build recipes are pinned with the imported engine.
# PrepareStagingDependencies.ps1 builds this prefix from the shipped sources.
if(NOT KAIRO_STAGING_DEPS)
    message(FATAL_ERROR "Run tools/PrepareStagingDependencies.ps1 before building")
endif()
list(PREPEND CMAKE_PREFIX_PATH "${KAIRO_STAGING_DEPS}")
list(PREPEND CMAKE_FIND_ROOT_PATH "${KAIRO_STAGING_DEPS}")
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_HIDAPI OFF CACHE BOOL "" FORCE)
set(SDL_HAPTIC OFF CACHE BOOL "" FORCE)
set(SDL_POWER OFF CACHE BOOL "" FORCE)
set(SDL_SENSOR OFF CACHE BOOL "" FORCE)
set(SDL_LOCALE OFF CACHE BOOL "" FORCE)
set(SDL_MISC OFF CACHE BOOL "" FORCE)
set(SDL_KAIRO_EMBEDDED ON)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../third_party/staging-deps/sdl2" sdl2)
target_compile_definitions(SDL2-static PRIVATE SDL_KAIRO_EMBEDDED)
if(ANDROID)
    target_sources(SDL2-static PRIVATE src/main/cpp/sdl_embedded_android.c)
endif()
find_package(SDL2_image CONFIG REQUIRED)
find_package(iir CONFIG REQUIRED)
find_package(PNG REQUIRED)
# MT32Emu::mt32emu and FluidSynth::libfluidsynth: the MIDI synths every Kairo core shares (KairoX86#306).
include("${CMAKE_CURRENT_SOURCE_DIR}/../shared/cmake/KairoMidi.cmake")
find_package(OpusFile CONFIG REQUIRED)

# The Android presenter supplies the render backend; desktop OpenGL is unused.
add_library(OpenGL::GL INTERFACE IMPORTED)
foreach(dep SPEEXDSP ZLIB_NG)
    if(dep STREQUAL "SPEEXDSP")
        set(lib speexdsp)
    else()
        set(lib z-ng)
    endif()
    find_library(${dep}_LIBRARY NAMES ${lib} PATHS "${KAIRO_STAGING_DEPS}/lib" NO_DEFAULT_PATH REQUIRED)
    add_library(PkgConfig::${dep} STATIC IMPORTED)
    set_target_properties(PkgConfig::${dep} PROPERTIES
        IMPORTED_LOCATION "${${dep}_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${KAIRO_STAGING_DEPS}/include")
endforeach()
# Upstream's bundled ZMBV requests zlib-ng via pkg-config. Supply its pinned
# imported target without depending on a host pkg-config installation.
function(pkg_check_modules prefix)
    if(NOT prefix STREQUAL "ZLIB_NG")
        message(FATAL_ERROR "Unexpected pkg-config dependency: ${prefix}")
    endif()
endfunction()

include_directories("${STAGING_SOURCE}/include" "${STAGING_SOURCE}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/../shared/frontend/src/main/cpp"
    "${STAGING_SOURCE}/src/libs" "${CMAKE_CURRENT_BINARY_DIR}/include"
    "${KAIRO_STAGING_DEPS}/include" "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
add_library(dosbox SHARED "${STAGING_SOURCE}/src/main.cpp"
    "${STAGING_SOURCE}/src/dosbox.cpp" src/main/cpp/staging_bridge.cpp
    src/main/cpp/kairo_renderer.cpp)
set_target_properties(dosbox PROPERTIES OUTPUT_NAME dosbox_staging)
# Keep strong core function calls local instead of routing them through PLT
# stubs. Retain weak/data binding for C++ object identity and shared dependencies
# except the explicitly core-owned paging object in paging.h;
# do not replace this with broad -Bsymbolic. See docs/symbol-binding-evaluation.md.
if(ANDROID)
    target_link_options(dosbox PRIVATE "-Wl,-Bsymbolic-non-weak-functions")
endif()
add_library(libdosboxcommon STATIC)
add_subdirectory("${STAGING_SOURCE}/src" staging)
if(WIN32)
    # Staging's Windows program resources (its icon and manifest, platform/windows): the shared PC
    # boot test loads this as a library, which takes neither.
    get_target_property(dosbox_sources dosbox SOURCES)
    list(FILTER dosbox_sources EXCLUDE REGEX "winres\\.rc$|dosbox\\.manifest$")
    set_property(TARGET dosbox PROPERTY SOURCES ${dosbox_sources})
    # The host API's exports, listed (staging_exports.def).
    target_sources(dosbox PRIVATE src/main/cpp/staging_exports.def)
endif()
target_link_libraries(libdosboxcommon PRIVATE
    $<IF:$<TARGET_EXISTS:iir::iir>,iir::iir,iir::iir_static>
    loguru libdecoders libglad PkgConfig::SPEEXDSP
    $<IF:$<TARGET_EXISTS:SDL2::SDL2>,SDL2::SDL2,SDL2::SDL2-static>)
target_link_libraries(dosbox PRIVATE libdosboxcommon libglad
    $<IF:$<TARGET_EXISTS:SDL2::SDL2>,SDL2::SDL2,SDL2::SDL2-static>)
if(ANDROID)
    target_link_libraries(dosbox PRIVATE dl log)
endif()
