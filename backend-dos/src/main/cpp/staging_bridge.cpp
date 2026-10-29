// SPDX-License-Identifier: GPL-2.0-or-later
#include "staging_bridge.h"
#include "kairo_guest_profile.h"
#include "dosbox.h"
#include "SDL.h"
#include "gui/common.h"
#include "config/setup.h"
#include "cpu/cpu.h"
#include "dos/dos.h"
#include "shell/shell.h"
#include "misc/unicode.h"
#include "hardware/input/joystick.h"
#include "hardware/input/mouse.h"
#include "hardware/memory.h"
#include "hardware/timer.h"
#include "hardware/video/vga.h"
#include "libs/loguru/loguru.hpp"
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

int KairoStagingMain(int argc, char** argv);
void KairoSetFastForward(bool enabled);  // dosbox.cpp (Kairo)
void KairoHostHeld(int64_t micros);       // dosbox.cpp (Kairo)

static_assert(C_DYNREC && C_TARGET_CPU_ARM,
    "KairoDos requires the ARM64 dynamic recompiler");
KairoStagingCpuCounters kairo_staging_cpu_counters{};

// Read the guest filesystem, rather than the archive's host paths: this also
// enumerates FAT/ISO mounts and returns the exact DOS short names to execute.
extern "C" const char* kairo_staging_list_directory(const char* input) {
    static std::string result;
    result = "ERROR\nDOS filesystem is not ready";
    if (!DOS_GetFirstShell() || !dos.tables.tempdta) return result.c_str();
    const std::string utf8 = input ? input : "";
    result = "OK\n";
    if (utf8.empty()) {
        // Z: contains emulator utilities, not a game's files.
        for (size_t index = 0; index < 25 && index < Drives.size(); ++index)
            if (Drives[index]) result += std::string("D\t") + char('A' + index) + ":\\\n";
        return result.c_str();
    }
    const auto path = utf8_to_dos(utf8, DosStringConvertMode::NoSpecialCharacters,
                                 UnicodeFallback::EmptyString);
    if (path.size() < 3 || path.size() + 4 >= DOS_PATHLENGTH ||
        path[0] < 'A' || path[0] > 'Y' || path[1] != ':' || path[2] != '\\' ||
        path.find_first_of("\r\n\t\"*?<>|/") != std::string::npos ||
        path.find(':', 2) != std::string::npos || path.find("..") != std::string::npos ||
        !Drives[path[0] - 'A']) {
        result = "ERROR\nInvalid DOS directory";
        return result.c_str();
    }
    // Preserve both the caller's DTA pointer and the shell's temporary search
    // record, including error state. All accesses occur on the core thread.
    struct SearchState {
        RealPt dta = dos.dta();
        uint16_t error = dos.errorcode;
        std::array<uint8_t, sizeof(sDTA)> bytes{};
        SearchState() {
            MEM_BlockRead(RealToPhysical(dos.tables.tempdta), bytes.data(), bytes.size());
            dos.dta(dos.tables.tempdta);
        }
        ~SearchState() {
            MEM_BlockWrite(RealToPhysical(dos.tables.tempdta), bytes.data(), bytes.size());
            dos.dta(dta);
            dos.errorcode = error;
        }
    } saved;
    FatAttributeFlags attributes{};
    if (path.size() > 3 && (!DOS_GetFileAttr(path.c_str(), &attributes) || !attributes.directory)) {
        result = "ERROR\nDOS directory does not exist";
        return result.c_str();
    }
    const std::string prefix = path.back() == '\\' ? path : path + "\\";
    DOS_DTA dta(dos.dta());
    bool found = DOS_FindFirst((prefix + "*.*").c_str(), FatAttributeFlags::NotVolume);
    while (found) {
        DOS_DTA::Result entry{};
        dta.GetResult(entry);
        if (!entry.IsDummyDirectory() && (entry.IsDirectory() || entry.IsFile())) {
            result += entry.IsDirectory() ? "D\t" : "F\t";
            result += dos_to_utf8(prefix + entry.name, DosStringConvertMode::NoSpecialCharacters) + "\n";
        }
        found = DOS_FindNext();
    }
    return result.c_str();
}

namespace {
KairoStagingCallbacks callbacks{};
// The launch's host paths, which its configuration names by reference (KairoStagingHostPath).
std::vector<std::string> host_paths;
SDL_Keymod modifiers = KMOD_NONE;
uint64_t keyboard_waits = 0, keyboard_polls = 0, mouse_reads = 0;
// Written on the emulation thread, read by the host's probe.
std::atomic<uint64_t> guest_frames{0};
int keyboard_waiting = 0;
// Whether the mouse follows direct touch (seamless capture), once input chose: the launch
// configuration sets the first mode.
int mouse_absolute = -1;
float cursor_x = 400.0f, cursor_y = 300.0f;

void set_mouse_absolute(bool absolute) {
    if (int(absolute) == mouse_absolute) return;
    set_section_property_value("mouse", "mouse_capture", absolute ? "seamless" : "onstart");
    mouse_absolute = absolute;
}

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
    const char* resources, const char* const* paths, size_t path_count, const KairoStagingCallbacks* cb) {
    if (!cb || cb->version != 3 || !cb->video || !cb->poll || !cb->restart || !cb->frame || !config || !config_dir || !resources || (path_count && !paths)) return 1;
    host_paths.assign(paths, paths + path_count);
#ifdef KAIRO_GUEST_PROFILE
    kairo_guest_profile::open(config_dir);
#endif
    callbacks = *cb;
    kairo_staging_cpu_counters = {};
    guest_frames = 0;
    keyboard_waits = keyboard_polls = mouse_reads = 0;
    keyboard_waiting = 0;
    mouse_absolute = -1;
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
#ifdef KAIRO_GUEST_PROFILE
    kairo_guest_profile::close();
#endif
    loguru::shutdown();
    host_paths.clear();
    return result;
}

const char* KairoStagingHostPath(const char* reference) {
    const size_t prefix = std::strlen(KAIRO_HOST_PATH_PREFIX);
    if (!reference || std::strncmp(reference, KAIRO_HOST_PATH_PREFIX, prefix) != 0) return nullptr;
    char* end = nullptr;
    const unsigned long index = std::strtoul(reference + prefix, &end, 10);
    if (end == reference + prefix || *end != '\0' || index >= host_paths.size()) {
        LOG_ERR("KAIRO: The launch configuration names an unknown host path: %s", reference);
        return nullptr;
    }
    return host_paths[index].c_str();
}

CPU_Decoder* KairoRunningDecoder();  // cpu/cpu.cpp (Kairo)

bool KairoStagingPoll() {
#ifdef KAIRO_GUEST_PROFILE
    kairo_guest_profile::flush();
#endif
    if (callbacks.poll()) return true;
    DOSBOX_RequestShutdown();
    return false;
}
void KairoStagingFrameEnd() {
    guest_frames.fetch_add(1, std::memory_order_relaxed);
    const auto held = GetTicksUs();
    callbacks.frame(VGA_GetRefreshRate());
    // The host's turn is not the emulator's work: auto cycles must not count it.
    KairoHostHeld(GetTicksUsSince(held));
}
extern "C" void kairo_staging_fast_forward(bool enabled) { KairoSetFastForward(enabled); }
extern "C" uint64_t kairo_staging_guest_frames() { return guest_frames.load(std::memory_order_relaxed); }
extern "C" void kairo_staging_guest_input(KairoStagingGuestInput* input) {
    *input = {keyboard_waits, keyboard_polls, mouse_reads, keyboard_waiting};
}
extern "C" void kairo_staging_cpu(KairoStagingCpu* cpu) {
    // A game waiting for a key spends most of its time halted, in DOSBox's halt decoder.
    CPU_Decoder* const decoder = KairoRunningDecoder();
    *cpu = {decoder == &CPU_Core_Dynrec_Run || decoder == &CPU_Core_Dynrec_Trap_Run,
        kairo_staging_cpu_counters.translated_blocks, kairo_staging_cpu_counters.executed_blocks};
}
extern "C" int kairo_staging_lock_flags() { return mem_readb(0x417); }
void KairoStagingVideo(const uint32_t* pixels, int w, int h, int pitch, double aspect) {
    callbacks.video(pixels, w, h, pitch, aspect);
}
void KairoStagingKeyboardWait(bool waiting) {
    if (waiting) ++keyboard_waits;
    keyboard_waiting = waiting ? 1 : 0;
}
void KairoStagingKeyboardPoll() { ++keyboard_polls; }
void KairoStagingMouseRead() { ++mouse_reads; }
void KairoStagingRestart() {
    callbacks.restart();
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

extern "C" void kairo_staging_mouse_move(int dx, int dy) {
    set_mouse_absolute(false);
    const auto viewport = GFX_GetViewportSizeInPixels();
    const auto width = viewport.w - 1;
    const auto height = viewport.h - 1;
    if (width <= 0 || height <= 0 || (!dx && !dy)) return;
    cursor_x = std::clamp(cursor_x + dx, 0.0f, float(width));
    cursor_y = std::clamp(cursor_y + dy, 0.0f, float(height));
    MOUSE_EventMoved(float(dx), float(dy), viewport.x + cursor_x, viewport.y + cursor_y);
}

extern "C" void kairo_staging_mouse_point(int x, int y) {
    set_mouse_absolute(true);
    const auto viewport = GFX_GetViewportSizeInPixels();
    const auto width = viewport.w - 1;
    const auto height = viewport.h - 1;
    if (width <= 0 || height <= 0) return;
    const auto next_x = std::clamp(float(x) * width / 65536.0f, 0.0f, float(width));
    const auto next_y = std::clamp(float(y) * height / 65536.0f, 0.0f, float(height));
    MOUSE_EventMoved(next_x - cursor_x, next_y - cursor_y, viewport.x + next_x, viewport.y + next_y);
    cursor_x = next_x; cursor_y = next_y;
}

extern "C" void kairo_staging_mouse_button(int button, bool down) {
    if (button == 1) MOUSE_EventButton(MouseButtonId::Left, down);
    else if (button == 2) MOUSE_EventButton(MouseButtonId::Right, down);
}
