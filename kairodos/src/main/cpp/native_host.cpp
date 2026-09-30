// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
#include <vector>
#include "staging_bridge.h"
#include "video_profile.h"

namespace {
std::mutex surface_mutex, frame_mutex, input_mutex, control_mutex, error_mutex;
ANativeWindow* surface = nullptr;
int surface_width = 0, surface_height = 0;
std::condition_variable frame_ready, control_changed;
std::vector<uint8_t> pending_frame;
unsigned pending_width = 0, pending_height = 0;
uint64_t pending_frame_time = 0;
bool has_pending_frame = false, renderer_stopping = false;
struct KeyChange { int code; bool down; };
std::deque<KeyChange> key_changes;
// Stable frontend key codes; SDL2 translation belongs to the core adapter.
std::array<uint8_t, 512> keys{};
std::array<uint8_t, 24> joypad{};
std::atomic<int> mouse_x{0}, mouse_y{0}, pointer_x{0}, pointer_y{0};
std::atomic<bool> mouse_left{false}, mouse_right{false}, pointer_pressed{false};
std::atomic<int> mouse_mode{0}, cycles_mode{0};
std::atomic<int> sample_rate{48000}, video_width{640}, video_height{400};
std::atomic<double> aspect{4.0 / 3.0};
std::atomic<bool> stop_requested{false}, paused{false}, reset_requested{false};
std::atomic<int> status{0}; // idle, loading, running, failed
std::atomic<uint64_t> guest_keyboard_waits{0}, guest_keyboard_polls{0}, guest_mouse_reads{0};
std::atomic<int> guest_keyboard_waiting{0};
std::string last_error;

// The worker owns input and the event loop. The audio thread reads concurrently.
// An exclusive lock before dlclose waits for all audio API calls to complete.
std::shared_mutex core_api_mutex;
void* core_handle = nullptr;
struct Core {
    decltype(&kairo_staging_run) run = nullptr;
    decltype(&kairo_staging_key) key = nullptr;
    decltype(&kairo_staging_configure) configure = nullptr;
    decltype(&kairo_staging_joypad) joypad = nullptr;
    decltype(&kairo_staging_mouse) mouse = nullptr;
    decltype(&kairo_staging_read_audio) read_audio = nullptr;
    decltype(&kairo_staging_audio_rate) audio_rate = nullptr;
} core;
void set_error(const std::string& message) {
    std::lock_guard lock(error_mutex);
    last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "KairoDos", "%s", message.c_str());
}
template<typename T> bool symbol(T& target, const char* name) {
    target = reinterpret_cast<T>(dlsym(core_handle, name));
    if (!target) set_error(std::string("Missing DOSBox Staging symbol: ") + name);
    return target != nullptr;
}
bool load_core() {
    std::unique_lock lock(core_api_mutex);
    core_handle = dlopen("libdosbox_staging.so", RTLD_NOW | RTLD_LOCAL);
    if (!core_handle) { set_error(std::string("Could not load DOSBox Staging: ") + dlerror()); return false; }
    return symbol(core.run, "kairo_staging_run") && symbol(core.key, "kairo_staging_key") &&
        symbol(core.configure, "kairo_staging_configure") &&
        symbol(core.joypad, "kairo_staging_joypad") && symbol(core.mouse, "kairo_staging_mouse") &&
        symbol(core.read_audio, "kairo_staging_read_audio") && symbol(core.audio_rate, "kairo_staging_audio_rate");
}
void unload_core() {
    std::unique_lock lock(core_api_mutex);
    core = {};
    // Staging joins its worker threads before returning. All static SDL/core
    // dependencies belong to this session DSO. Unload it to reset engine globals
    // (including the shutdown flag) before launching another game.
    if (core_handle) dlclose(core_handle);
    core_handle = nullptr;
}
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void video(const uint32_t* data, int width, int height, int pitch, double ratio) {
    if (!data || width <= 0 || height <= 0 || pitch < width * 4) return;
    video_width = width; video_height = height;
    if (ratio > 0) aspect = ratio;
    sample_rate = core.audio_rate(); status = 2;
    // Posting a frame may wait for the panel. Keep that wait off Staging's
    // emulation thread: its guest video/audio clocks run independently.
    const auto arrival = video_profile::now();
    std::lock_guard lock(frame_mutex);
    video_profile::elapsed(video_profile::FrameMutex, arrival);
    const auto conversion = video_profile::now();
    pending_frame.resize(static_cast<size_t>(width) * height * 4);
    for (int y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint32_t*>(reinterpret_cast<const uint8_t*>(data) + y * pitch);
        auto* out = reinterpret_cast<uint32_t*>(pending_frame.data()) + y * width;
        for (int x = 0; x < width; ++x) {
            const uint32_t rgb = row[x];
            out[x] = 0xff000000u | ((rgb & 0xffu) << 16) | (rgb & 0xff00u) | ((rgb >> 16) & 0xffu);
        }
    }
    pending_width = width; pending_height = height;
    video_profile::elapsed(video_profile::Convert, conversion);
    video_profile::record(video_profile::Produced);
    if (has_pending_frame) video_profile::record(video_profile::Replaced);
    pending_frame_time = arrival; has_pending_frame = true;
    frame_ready.notify_one();
}
void render_frames() {
    std::vector<uint8_t> frame;
    while (true) {
        unsigned width, height;
        uint64_t arrival;
        {
            std::unique_lock lock(frame_mutex);
            frame_ready.wait(lock, [] { return renderer_stopping || has_pending_frame; });
            if (renderer_stopping) return;
            frame.swap(pending_frame);
            width = pending_width; height = pending_height; arrival = pending_frame_time;
            has_pending_frame = false;
        }
        const auto wait = video_profile::now();
        std::lock_guard lock(surface_mutex);
        video_profile::elapsed(video_profile::WindowMutex, wait);
        if (!surface) { video_profile::record(video_profile::NoSurface); continue; }
        if (surface_width != int(width) || surface_height != int(height)) {
            if (ANativeWindow_setBuffersGeometry(surface, width, height, WINDOW_FORMAT_RGBA_8888) != 0) continue;
            surface_width = width; surface_height = height;
        }
        ANativeWindow_Buffer buffer{};
        const auto start = video_profile::now();
        const int locked = ANativeWindow_lock(surface, &buffer, nullptr);
        video_profile::elapsed(video_profile::WindowLock, start);
        if (locked != 0) { video_profile::record(video_profile::WindowFailure); continue; }
        const auto copy = video_profile::now();
        for (unsigned y = 0; y < height && y < unsigned(buffer.height); ++y) {
            auto* out = static_cast<uint32_t*>(buffer.bits) + y * buffer.stride;
            const auto* row = reinterpret_cast<const uint32_t*>(frame.data()) + y * width;
            std::memcpy(out, row, std::min(width, unsigned(buffer.width)) * 4);
        }
        video_profile::elapsed(video_profile::WindowCopy, copy);
        const auto post = video_profile::now();
        const int posted = ANativeWindow_unlockAndPost(surface);
        video_profile::elapsed(video_profile::WindowPost, post);
        video_profile::record(posted == 0 ? video_profile::Presented : video_profile::WindowFailure);
        video_profile::elapsed(video_profile::AgeAtPost, arrival);
    }
}
int poll() {
    bool resumed = false;
    {
        std::unique_lock lock(control_mutex);
        if (paused && !stop_requested && !reset_requested) {
            resumed = true;
            control_changed.wait(lock, [] { return !paused || stop_requested || reset_requested; });
        }
    }
    if (stop_requested || reset_requested) return 0;
    std::deque<KeyChange> changes;
    std::array<uint8_t, 24> buttons;
    { std::lock_guard lock(input_mutex); changes.swap(key_changes); buttons = joypad; }
    for (const auto& key : changes) core.key(key.code, key.down);
    core.configure(mouse_mode == 1, cycles_mode);
    core.joypad(buttons.data(), buttons.size());
    core.mouse(std::clamp(mouse_x.exchange(0), -32768, 32767), std::clamp(mouse_y.exchange(0), -32768, 32767),
        mouse_left, mouse_right, pointer_x, pointer_y, pointer_pressed, mouse_mode == 1);
    return resumed ? 2 : 1;
}
void telemetry(uint64_t waits, uint64_t polls, uint64_t reads, int waiting) {
    guest_keyboard_waits = waits; guest_keyboard_polls = polls;
    guest_mouse_reads = reads; guest_keyboard_waiting = waiting;
}
void restart() {
    { std::lock_guard lock(control_mutex); reset_requested = true; }
    control_changed.notify_all();
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeRun(JNIEnv* env, jobject,
    jstring config_j, jstring save_j, jstring resources_j) {
    int expected = status.load();
    while (expected == 0 || expected == 3) { if (status.compare_exchange_weak(expected, 1)) break; }
    if (expected == 1 || expected == 2) { set_error("DOS session already active"); return false; }
    const std::string config = string(env, config_j), config_dir = string(env, save_j) + "/staging/config";
    const std::string resources = string(env, resources_j);
    { std::lock_guard lock(error_mutex); last_error.clear(); }
    { std::lock_guard lock(control_mutex); stop_requested = false; paused = false; reset_requested = false; }
    mouse_x = 0; mouse_y = 0; mouse_left = false; mouse_right = false; pointer_pressed = false;
    { std::lock_guard lock(frame_mutex); renderer_stopping = false; has_pending_frame = false; }
    std::thread renderer(render_frames);
    int result = 0;
    do {
        reset_requested = false;
        { std::lock_guard lock(input_mutex); key_changes.clear(); keys.fill(0); joypad.fill(0); }
        telemetry(0, 0, 0, 0); status = 1;
        if (!load_core()) { result = 1; unload_core(); break; }
        const KairoStagingCallbacks callbacks{1, video, poll, restart, telemetry};
        try {
            result = core.run(config.c_str(), config_dir.c_str(), resources.c_str(), &callbacks);
        } catch (const std::exception& failure) {
            result = 1; set_error(failure.what());
        } catch (...) {
            result = 1; set_error("DOSBox Staging session failed");
        }
        status = 1; unload_core();
        if (result != 0 && !stop_requested) {
            std::lock_guard lock(error_mutex);
            if (last_error.empty()) last_error = "DOSBox Staging could not start or continue this game. Check the launch configuration.";
        }
    } while (reset_requested && !stop_requested && result == 0);
    { std::lock_guard lock(frame_mutex); renderer_stopping = true; }
    frame_ready.notify_all(); renderer.join();
    const bool ok = result == 0 || stop_requested;
    status = ok ? 0 : 3;
    return ok;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeStop(JNIEnv*, jobject) {
    { std::lock_guard lock(control_mutex); stop_requested = true; }
    control_changed.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativePause(JNIEnv*, jobject, jboolean value) {
    { std::lock_guard lock(control_mutex); paused = value; }
    control_changed.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeReset(JNIEnv*, jobject) { restart(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeStatus(JNIEnv*, jobject) { return status; }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeAudioRate(JNIEnv*, jobject) { return sample_rate; }
extern "C" JNIEXPORT jdouble JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeAspect(JNIEnv*, jobject) { return aspect; }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeVideoWidth(JNIEnv*, jobject) { return video_width; }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeVideoHeight(JNIEnv*, jobject) { return video_height; }
extern "C" JNIEXPORT jstring JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard lock(error_mutex); return env->NewStringUTF(last_error.c_str());
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeInputTelemetry(JNIEnv* env, jobject) {
    jlong values[]{jlong(guest_keyboard_waits), jlong(guest_keyboard_polls), jlong(guest_mouse_reads), jlong(guest_keyboard_waiting)};
    jlongArray result = env->NewLongArray(4); if (result) env->SetLongArrayRegion(result, 0, 4, values); return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeSetSurface(JNIEnv* env, jobject, jobject value) {
    ANativeWindow* next = value ? ANativeWindow_fromSurface(env, value) : nullptr;
    std::lock_guard lock(surface_mutex); if (surface) ANativeWindow_release(surface);
    surface = next; surface_width = surface_height = 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeKey(JNIEnv*, jobject, jint code, jboolean down) {
    if (code < 0 || code >= int(keys.size())) return;
    std::lock_guard lock(input_mutex); if (keys[code] == uint8_t(down)) return;
    keys[code] = uint8_t(down); key_changes.push_back({code, bool(down)});
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeJoypad(JNIEnv*, jobject, jint id, jboolean down) {
    if (id < 0 || id >= int(joypad.size())) return;
    std::lock_guard lock(input_mutex); joypad[id] = uint8_t(down);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeMouseMove(JNIEnv*, jobject, jint dx, jint dy) { mouse_x.fetch_add(dx); mouse_y.fetch_add(dy); }
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeMouseButton(JNIEnv*, jobject, jint button, jboolean down) {
    if (button == 1) mouse_left = down; if (button == 2) mouse_right = down;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativePointer(JNIEnv*, jobject, jint x, jint y, jboolean down) {
    pointer_x = std::clamp(int(x), -32767, 32767); pointer_y = std::clamp(int(y), -32767, 32767); pointer_pressed = down;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeConfigure(JNIEnv*, jobject, jint mouse, jint cycles, jint) {
    mouse_mode = mouse == 1 ? 1 : 0; cycles_mode = cycles == 1 ? 1 : 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeReadAudio(JNIEnv* env, jobject, jshortArray output, jint requested) {
    if (!output || requested <= 0 || paused || status != 2) return 0;
    std::shared_lock lock(core_api_mutex); if (!core.read_audio) return 0;
    const int frames = std::min(int(requested), int(env->GetArrayLength(output) / 2));
    jshort* dest = env->GetShortArrayElements(output, nullptr); if (!dest) return 0;
    const int count = core.read_audio(dest, frames);
    env->ReleaseShortArrayElements(output, dest, 0); return count;
}
