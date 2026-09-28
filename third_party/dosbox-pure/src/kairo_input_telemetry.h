// KairoDos bridge for guest input activity. This local patch is GPL-2.0-or-later.
#pragma once

#include <cstdint>

extern "C" void kairo_dos_keyboard_wait(bool waiting);
extern "C" void kairo_dos_keyboard_poll();
extern "C" void kairo_dos_mouse_read();
extern "C" __attribute__((visibility("default"))) void kairo_dos_input_telemetry_reset();
extern "C" __attribute__((visibility("default"))) void kairo_dos_input_telemetry_snapshot(
    uint64_t* waits, uint64_t* polls, uint64_t* mouse_reads, int* waiting);
