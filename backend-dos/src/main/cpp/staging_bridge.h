// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

// Versioned C boundary: the Android frontend never depends on SDL types.
struct KairoStagingCallbacks {
    uint32_t version;
    void (*video)(const uint32_t* pixels, int width, int height, int pitch, double aspect);
    // Emulation thread: 0 stops, 1 continues, 2 resumes after a pause.
    int (*poll)();
    void (*restart)();
    void (*telemetry)(uint64_t waits, uint64_t polls, uint64_t mouse_reads, int waiting);
};

extern "C" {
int kairo_staging_run(const char* config, const char* config_dir,
    const char* resources, const KairoStagingCallbacks* callbacks);
void kairo_staging_key(int code, bool pressed);
void kairo_staging_configure(bool absolute_mouse, int cycles_mode);
void kairo_staging_joypad(const uint8_t* buttons, size_t count);
void kairo_staging_mouse(int dx, int dy, bool left, bool right,
    int pointer_x, int pointer_y, bool pointer_pressed, bool absolute);
int kairo_staging_read_audio(int16_t* samples, int frames);
int kairo_staging_audio_rate();
}

bool KairoStagingPoll();
void KairoStagingVideo(const uint32_t* pixels, int width, int height, int pitch, double aspect);
void KairoStagingKeyboardWait(bool waiting);
void KairoStagingKeyboardPoll();
void KairoStagingMouseRead();
void KairoStagingRestart();

class RenderBackend;
RenderBackend* KairoCreateRenderer(int width, int height);
