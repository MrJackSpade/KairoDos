# SPDX-License-Identifier: GPL-2.0-or-later
# This profile describes the ordinary core, not diagnostic/alternative builds.
if(KAIRO_GUEST_PROFILE OR KAIRO_OPL_VERIFY OR KAIRO_OPL_STRESS OR NOT KAIRO_OPL_WORKER)
    message(FATAL_ERROR "PGO requires the ordinary OPL-worker core; disable PGO for diagnostic/alternative builds")
endif()
# The shared helper checks the manifest and compiler, relocates and indexes the profile, and makes
# out-of-date function profiles build errors.
include("${CMAKE_CURRENT_LIST_DIR}/../../shared/cmake/KairoPgo.cmake")
kairo_use_profile("${CMAKE_CURRENT_LIST_DIR}/arm64-v1.proftext" "${CMAKE_CURRENT_LIST_DIR}/profile.json"
    "${CMAKE_CURRENT_LIST_DIR}/../..")
