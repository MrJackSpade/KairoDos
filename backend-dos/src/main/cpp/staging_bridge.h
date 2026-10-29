// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

// Versioned C boundary: the Android frontend never depends on SDL types.
//
// DOSBox Staging runs on its own thread, taking turns with the host (KairoDos#124): it hands the
// turn over at the start of every emulated frame (frame) and waits there until the host gives it
// back. Every kairo_staging_ function but kairo_staging_run is called while the host has the turn.
struct KairoStagingCallbacks {
    uint32_t version;  // 3
    // A frame's picture, when it changed.
    void (*video)(const uint32_t* pixels, int width, int height, int pitch, double aspect);
    // Staging's event poll: 0 ends the run (the host stops or restarts it), 1 continues.
    int (*poll)();
    // Staging restarts itself (its own config change): the host runs it again once this run ends.
    void (*restart)();
    // An emulated frame begins, whether or not its picture changed, at the guest's refresh rate in
    // hertz: the host takes its turn, and this returns when the host gives it back.
    void (*frame)(double refresh_hz);
};

// Owned and updated by the emulation thread.
struct KairoStagingCpuCounters {
    uint64_t translated_blocks = 0;
    uint64_t executed_blocks = 0;
};
extern KairoStagingCpuCounters kairo_staging_cpu_counters;

// How the guest reads its input since the run started (MachineCore::GuestInput).
struct KairoStagingGuestInput {
    uint64_t keyboard_waits;
    uint64_t keyboard_polls;
    uint64_t mouse_reads;
    int keyboard_waiting;
};

// What the CPU runs on (Diagnostics): whether the dynamic recompiler runs now, and its blocks.
struct KairoStagingCpu {
    int dynamic_active;
    uint64_t translated_blocks;
    uint64_t executed_blocks;
};

// A host path the launch configuration names by reference: "kairo-host:<n>" is host_paths[n].
// AUTOEXEC.BAT is read in the DOS code page, so a host path written into it loses every
// character outside that code page; MOUNT, IMGMOUNT and BOOT resolve a reference instead.
#define KAIRO_HOST_PATH_PREFIX "kairo-host:"

extern "C" {
// Runs DOSBox Staging on the calling thread until it ends: 0 when it ended cleanly.
// [host_paths]: UTF-8 host paths the configuration's references name, [host_path_count] of them.
int kairo_staging_run(const char* config, const char* config_dir,
    const char* resources, const char* const* host_paths, size_t host_path_count,
    const KairoStagingCallbacks* callbacks);
void kairo_staging_key(int code, bool pressed);
// Kairo's 24 joystick controls (JoystickInputRouter's order), 1 for each held.
void kairo_staging_joypad(const uint8_t* buttons, size_t count);
// Relative movement, by mouse counts.
void kairo_staging_mouse_move(int dx, int dy);
// Moves the pointer to (x, y) in 1/65536ths of the picture's width and height (direct touch).
void kairo_staging_mouse_point(int x, int y);
// Button 1 (left) or 2 (right).
void kairo_staging_mouse_button(int button, bool down);
int kairo_staging_read_audio(int16_t* samples, int frames);
int kairo_staging_audio_rate();
// Emulated frames since the run started, whether or not the picture changed: the
// video callback sees only frames whose picture changed.
uint64_t kairo_staging_guest_frames();
void kairo_staging_guest_input(KairoStagingGuestInput* input);
void kairo_staging_cpu(KairoStagingCpu* cpu);
// The BIOS keyboard flags at 0040:0017: bit 4 Scroll Lock, 5 Num Lock, 6 Caps Lock.
int kairo_staging_lock_flags();
/** Runs the guest as fast as it can while [enabled], as Staging's fast-forward hotkey does. */
void kairo_staging_fast_forward(bool enabled);
// Empty path lists mounted user drives; otherwise an absolute UTF-8 DOS directory. Result lives
// until the next call: OK or ERROR on the first line, followed by D/F, a tab, and an absolute
// UTF-8 DOS path.
const char* kairo_staging_list_directory(const char* path);
}

// The host path [reference] names, or null when it is not a reference. Emulation thread.
const char* KairoStagingHostPath(const char* reference);

bool KairoStagingPoll();
// Called by VGA_VerticalTimer at the start of every emulated frame.
void KairoStagingFrameEnd();
void KairoStagingVideo(const uint32_t* pixels, int width, int height, int pitch, double aspect);
void KairoStagingKeyboardWait(bool waiting);
void KairoStagingKeyboardPoll();
void KairoStagingMouseRead();
void KairoStagingRestart();

class RenderBackend;
RenderBackend* KairoCreateRenderer(int width, int height);
