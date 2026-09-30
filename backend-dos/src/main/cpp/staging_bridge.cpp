// SPDX-License-Identifier: GPL-2.0-or-later
#include "staging_bridge.h"
#include "dosbox.h"
#include "SDL.h"
#include "gui/common.h"
#include "config/setup.h"
#include "cpu/cpu.h"
#include "hardware/input/joystick.h"
#include "hardware/input/mouse.h"
#include "libs/loguru/loguru.hpp"
#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

int KairoStagingMain(int argc, char** argv);
void KairoResetHostTiming();

static_assert(C_DYNREC && C_TARGET_CPU_ARM,
    "KairoDos requires the ARM64 dynamic recompiler");
KairoStagingCpuCounters kairo_staging_cpu_counters{};

namespace {
KairoStagingCallbacks callbacks{};
SDL_Keymod modifiers = KMOD_NONE;
uint64_t keyboard_waits = 0, keyboard_polls = 0, mouse_reads = 0;
int keyboard_waiting = 0;
bool last_left = false, last_right = false, last_pointer = false;
int last_cycles = 0, last_mouse = -1;
bool settings_captured = false;
float cursor_x = 400.0f, cursor_y = 300.0f;
std::string original_cycles, original_real_cycles, original_protected_cycles;

SDL_Scancode scancode(int code) {
    if (code >= 282 && code <= 293) return SDL_Scancode(SDL_SCANCODE_F1 + code - 282);
    if (code >= 256 && code <= 265) {
        return code == 256 ? SDL_SCANCODE_KP_0 : SDL_Scancode(SDL_SCANCODE_KP_1 + code - 257);
    }
    switch (code) {
        case 266: return SDL_SCANCODE_KP_PERIOD;
        case 267: return SDL_SCANCODE_KP_DIVIDE;
        case 268: return SDL_SCANCODE_KP_MULTIPLY;
        case 269: return SDL_SCANCODE_KP_MINUS;
        case 270: return SDL_SCANCODE_KP_PLUS;
        case 271: return SDL_SCANCODE_KP_ENTER;
        case 272: return SDL_SCANCODE_KP_EQUALS;
        case 273: return SDL_SCANCODE_UP;
        case 274: return SDL_SCANCODE_DOWN;
        case 275: return SDL_SCANCODE_RIGHT;
        case 276: return SDL_SCANCODE_LEFT;
        case 277: return SDL_SCANCODE_INSERT;
        case 278: return SDL_SCANCODE_HOME;
        case 279: return SDL_SCANCODE_END;
        case 280: return SDL_SCANCODE_PAGEUP;
        case 281: return SDL_SCANCODE_PAGEDOWN;
        case 300: return SDL_SCANCODE_NUMLOCKCLEAR;
        case 301: return SDL_SCANCODE_CAPSLOCK;
        case 302: return SDL_SCANCODE_SCROLLLOCK;
        case 303: return SDL_SCANCODE_RSHIFT;
        case 304: return SDL_SCANCODE_LSHIFT;
        case 305: return SDL_SCANCODE_RCTRL;
        case 306: return SDL_SCANCODE_LCTRL;
        case 307: return SDL_SCANCODE_RALT;
        case 308: return SDL_SCANCODE_LALT;
        case 309: case 312: return SDL_SCANCODE_RGUI;
        case 310: case 311: return SDL_SCANCODE_LGUI;
        case 316: return SDL_SCANCODE_PRINTSCREEN;
        case 318: case 19: return SDL_SCANCODE_PAUSE;
        case 319: return SDL_SCANCODE_APPLICATION;
        default: return SDL_GetScancodeFromKey(code);
    }
}
}

extern "C" int kairo_staging_run(const char* config, const char* config_dir,
    const char* resources, const KairoStagingCallbacks* cb) {
    if (!cb || cb->version != 2 || !config || !config_dir || !resources) return 1;
    callbacks = *cb;
    kairo_staging_cpu_counters = {};
    std::filesystem::create_directories(config_dir);
    SDL_setenv("SDL_VIDEODRIVER", "dummy", 1);
    SDL_setenv("SDL_AUDIODRIVER", "dummy", 1);
    SDL_setenv("XDG_CONFIG_HOME", config_dir, 1);
    SDL_setenv("XDG_DATA_HOME", resources, 1);
    SDL_SetMainReady();
    SDL_SetHint(SDL_HINT_NO_SIGNAL_HANDLERS, "1");
    loguru::add_callback("android", [](void*, const loguru::Message& message) {
        __android_log_print(message.verbosity <= loguru::Verbosity_ERROR ? ANDROID_LOG_ERROR :
            message.verbosity == loguru::Verbosity_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
            "DOSBoxStaging", "%s%s", message.prefix, message.message);
    }, nullptr, loguru::Verbosity_INFO);
    std::vector<std::string> args{"dosbox-staging", "--noprimaryconf", "--nolocalconf",
        "--working-dir", resources, "--conf", config};
    std::vector<char*> argv;
    for (auto& arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);
    const auto original_directory = std::filesystem::current_path();
    const auto result = KairoStagingMain(static_cast<int>(args.size()), argv.data());
    std::error_code error;
    std::filesystem::current_path(original_directory, error);
    loguru::shutdown();
    return result;
}

bool KairoStagingPoll() {
    if (callbacks.telemetry)
        callbacks.telemetry(keyboard_waits, keyboard_polls, mouse_reads, keyboard_waiting);
    if (callbacks.cpu_telemetry)
        callbacks.cpu_telemetry(1,
            cpudecoder == &CPU_Core_Dynrec_Run || cpudecoder == &CPU_Core_Dynrec_Trap_Run,
            kairo_staging_cpu_counters.translated_blocks,
            kairo_staging_cpu_counters.executed_blocks);
    const int result = callbacks.poll ? callbacks.poll() : 1;
    if (result == 2) KairoResetHostTiming();
    if (result) return true;
    DOSBOX_RequestShutdown();
    return false;
}
void KairoStagingVideo(const uint32_t* pixels, int w, int h, int pitch, double aspect) {
    if (callbacks.video) callbacks.video(pixels, w, h, pitch, aspect);
}
void KairoStagingKeyboardWait(bool waiting) {
    if (waiting) ++keyboard_waits;
    keyboard_waiting = waiting ? 1 : 0;
}
void KairoStagingKeyboardPoll() { ++keyboard_polls; }
void KairoStagingMouseRead() { ++mouse_reads; }
void KairoStagingRestart() {
    if (callbacks.restart) callbacks.restart();
    DOSBOX_RequestShutdown();
}

extern "C" void kairo_staging_key(int code, bool pressed) {
    const auto scan = scancode(code);
    if (scan == SDL_SCANCODE_UNKNOWN) return;
    SDL_Keymod mask = KMOD_NONE;
    switch (scan) {
        case SDL_SCANCODE_LSHIFT: mask = KMOD_LSHIFT; break;
        case SDL_SCANCODE_RSHIFT: mask = KMOD_RSHIFT; break;
        case SDL_SCANCODE_LCTRL: mask = KMOD_LCTRL; break;
        case SDL_SCANCODE_RCTRL: mask = KMOD_RCTRL; break;
        case SDL_SCANCODE_LALT: mask = KMOD_LALT; break;
        case SDL_SCANCODE_RALT: mask = KMOD_RALT; break;
        default: break;
    }
    modifiers = SDL_Keymod(pressed ? modifiers | mask : modifiers & ~mask);
    SDL_Event event{};
    event.type = pressed ? SDL_KEYDOWN : SDL_KEYUP;
    event.key.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    event.key.keysym.scancode = scan;
    event.key.keysym.sym = SDL_GetKeyFromScancode(scan);
    event.key.keysym.mod = modifiers;
    SDL_PushEvent(&event);
}

extern "C" void kairo_staging_joypad(const uint8_t* buttons, size_t count) {
    if (!buttons || count < 24) return;
    for (int stick = 0; stick < 2; ++stick) {
        const int base = stick == 0 ? 16 : 20;
        JOYSTICK_Enable(stick, true);
        JOYSTICK_Move_X(stick, 32767 * (int(buttons[base + 3]) - int(buttons[base + 2])));
        JOYSTICK_Move_Y(stick, 32767 * (int(buttons[base + 1]) - int(buttons[base])));
        // Preserve Kairo's two-button DOS joysticks: Y/B, then L/R.
        JOYSTICK_Button(stick, 0, buttons[stick == 0 ? 1 : 10] != 0);
        JOYSTICK_Button(stick, 1, buttons[stick == 0 ? 0 : 11] != 0);
    }
}

extern "C" void kairo_staging_configure(bool absolute, int cycles) {
    if (!settings_captured) {
        auto* cpu = get_section("cpu");
        original_cycles = cpu->GetString("cycles");
        original_real_cycles = cpu->GetString("cpu_cycles");
        original_protected_cycles = cpu->GetString("cpu_cycles_protected");
        settings_captured = true;
    }
    if (cycles != last_cycles) {
        if (cycles == 1) set_section_property_value("cpu", "cycles", "max");
        else if (!original_cycles.empty()) set_section_property_value("cpu", "cycles", original_cycles);
        else {
            set_section_property_value("cpu", "cpu_cycles", original_real_cycles);
            set_section_property_value("cpu", "cpu_cycles_protected", original_protected_cycles);
        }
        last_cycles = cycles;
    }
    if (int(absolute) != last_mouse) {
        set_section_property_value("mouse", "mouse_capture", absolute ? "seamless" : "onstart");
        last_mouse = absolute;
    }
}

extern "C" void kairo_staging_mouse(int dx, int dy, bool left, bool right,
    int x, int y, bool pointer, bool absolute) {
    const auto viewport = GFX_GetViewportSizeInPixels();
    const auto width = viewport.w - 1;
    const auto height = viewport.h - 1;
    if (width <= 0 || height <= 0) return;
    if (absolute && pointer) {
        const auto next_x = float(x + 32767) * width / 65534.0f;
        const auto next_y = float(y + 32767) * height / 65534.0f;
        MOUSE_EventMoved(last_pointer ? next_x - cursor_x : 0, last_pointer ? next_y - cursor_y : 0,
            viewport.x + next_x, viewport.y + next_y);
        cursor_x = next_x; cursor_y = next_y;
    } else if (dx || dy) {
        cursor_x = std::clamp(cursor_x + dx, 0.0f, float(width));
        cursor_y = std::clamp(cursor_y + dy, 0.0f, float(height));
        MOUSE_EventMoved(float(dx), float(dy), viewport.x + cursor_x, viewport.y + cursor_y);
    }
    left = left || (absolute && pointer);
    if (left != last_left) MOUSE_EventButton(MouseButtonId::Left, left);
    if (right != last_right) MOUSE_EventButton(MouseButtonId::Right, right);
    last_left = left; last_right = right; last_pointer = pointer;
}
