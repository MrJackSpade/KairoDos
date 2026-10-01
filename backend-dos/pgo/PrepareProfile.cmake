# SPDX-License-Identifier: GPL-2.0-or-later
# This profile describes the ordinary core, not diagnostic/alternative builds.
if(KAIRO_GUEST_PROFILE OR KAIRO_OPL_VERIFY OR KAIRO_OPL_STRESS OR NOT KAIRO_OPL_WORKER)
    message(FATAL_ERROR "PGO requires the ordinary OPL-worker core; disable PGO for diagnostic/alternative builds")
endif()
set(_kairo_profile_text "${CMAKE_CURRENT_LIST_DIR}/arm64-v1.proftext")
set(_kairo_profile_manifest "${CMAKE_CURRENT_LIST_DIR}/profile.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${_kairo_profile_text}" "${_kairo_profile_manifest}")
file(READ "${_kairo_profile_manifest}" _kairo_manifest)
string(JSON _kairo_expected_hash GET "${_kairo_manifest}" portableTextSha256)
string(JSON _kairo_compiler_version GET "${_kairo_manifest}" compilerVersion)
if(NOT CMAKE_CXX_COMPILER_ID STREQUAL "Clang" OR
   NOT CMAKE_CXX_COMPILER_VERSION VERSION_EQUAL _kairo_compiler_version)
    message(FATAL_ERROR "PGO profile requires its recorded Clang ${_kairo_compiler_version}; use -DKAIRO_PGO=OFF for another compiler")
endif()
file(SHA256 "${_kairo_profile_text}" _kairo_actual_hash)
if(NOT _kairo_actual_hash STREQUAL _kairo_expected_hash)
    message(FATAL_ERROR "PGO profile does not match its audited manifest")
endif()
get_filename_component(_kairo_llvm_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
find_program(_kairo_profdata NAMES llvm-profdata llvm-profdata.exe
    PATHS "${_kairo_llvm_bin}" NO_DEFAULT_PATH NO_CACHE REQUIRED)
get_filename_component(_kairo_source_root "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
file(TO_CMAKE_PATH "${_kairo_source_root}" _kairo_source_root)
string(REGEX REPLACE "/+$" "" _kairo_source_root "${_kairo_source_root}")
string(SHA256 _kairo_root_hash "${_kairo_source_root}/")
# Both hashes are in the flag path: a profile or checkout change rebuilds users
# even when a compiler depfile does not list the profile as an input.
set(_kairo_profile_dir "${CMAKE_CURRENT_BINARY_DIR}/pgo/${_kairo_expected_hash}/${_kairo_root_hash}")
file(MAKE_DIRECTORY "${_kairo_profile_dir}")
set(_kairo_profile "${_kairo_profile_dir}/profile.profdata")
if(NOT EXISTS "${_kairo_profile}")
    file(READ "${_kairo_profile_text}" _kairo_profile_contents)
    string(REPLACE "@KAIRO_SOURCE@/" "${_kairo_source_root}/"
        _kairo_profile_contents "${_kairo_profile_contents}")
    file(WRITE "${_kairo_profile_dir}/profile.proftext" "${_kairo_profile_contents}")
    execute_process(COMMAND "${_kairo_profdata}" merge
        "${_kairo_profile_dir}/profile.proftext" -o "${_kairo_profile}.pending"
        RESULT_VARIABLE _kairo_profile_result ERROR_VARIABLE _kairo_profile_error)
    if(NOT _kairo_profile_result EQUAL 0)
        file(REMOVE "${_kairo_profile}.pending")
        message(FATAL_ERROR "Could not prepare PGO profile: ${_kairo_profile_error}")
    endif()
    file(RENAME "${_kairo_profile}.pending" "${_kairo_profile}")
endif()
add_compile_options("-fprofile-use=${_kairo_profile}" -Werror=profile-instr-out-of-date)
message(STATUS "Kairo PGO: audited profile ${_kairo_expected_hash}, root ${_kairo_source_root}/")
