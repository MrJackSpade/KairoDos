// KairoDos bridge for guest input activity. This local patch is GPL-2.0-or-later.
#include "kairo_input_telemetry.h"
#include <atomic>

namespace {
std::atomic<uint64_t> keyboard_waits{0};
std::atomic<uint64_t> keyboard_polls{0};
std::atomic<uint64_t> mouse_reads{0};
std::atomic<bool> keyboard_waiting{false};
}

extern "C" void kairo_dos_keyboard_wait(bool waiting) {
    keyboard_waits.fetch_add(1, std::memory_order_relaxed);
    keyboard_waiting.store(waiting, std::memory_order_relaxed);
}

extern "C" void kairo_dos_keyboard_poll() {
    keyboard_polls.fetch_add(1, std::memory_order_relaxed);
    keyboard_waiting.store(false, std::memory_order_relaxed);
}

extern "C" void kairo_dos_mouse_read() {
    mouse_reads.fetch_add(1, std::memory_order_relaxed);
}

extern "C" void kairo_dos_input_telemetry_reset() {
    keyboard_waits.store(0, std::memory_order_relaxed);
    keyboard_polls.store(0, std::memory_order_relaxed);
    mouse_reads.store(0, std::memory_order_relaxed);
    keyboard_waiting.store(false, std::memory_order_relaxed);
}

extern "C" void kairo_dos_input_telemetry_snapshot(
    uint64_t* waits, uint64_t* polls, uint64_t* reads, int* waiting) {
    *waits = keyboard_waits.load(std::memory_order_relaxed);
    *polls = keyboard_polls.load(std::memory_order_relaxed);
    *reads = mouse_reads.load(std::memory_order_relaxed);
    *waiting = keyboard_waiting.load(std::memory_order_relaxed) ? 1 : 0;
}
