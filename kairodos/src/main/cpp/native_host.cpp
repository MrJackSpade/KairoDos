#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "libretro.h"

namespace {
constexpr const char* TAG = "KairoDos";
constexpr size_t AUDIO_CAPACITY = 131072;
std::mutex surface_mutex;
ANativeWindow* surface = nullptr;
int surface_width = 0;
int surface_height = 0;
std::mutex frame_mutex;
std::condition_variable frame_ready;
std::vector<uint8_t> pending_frame;
unsigned pending_width = 0, pending_height = 0;
bool has_pending_frame = false, renderer_stopping = false;
std::mutex input_mutex;
struct KeyChange { unsigned code; bool down; };
std::deque<KeyChange> key_changes;
std::array<uint8_t, RETROK_LAST> keys{};
// Libretro buttons 0-15, followed by four directions for each DOS joystick.
std::array<uint8_t, 24> joypad{};
std::atomic<int> mouse_x{0}, mouse_y{0};
std::atomic<bool> mouse_left{false}, mouse_right{false};
std::atomic<int> pointer_x{0}, pointer_y{0};
std::atomic<bool> pointer_pressed{false};
std::atomic<int> mouse_mode{0}, cycles_mode{0};
std::atomic<bool> outside_conf{false};
std::atomic<bool> option_dirty{false};
std::mutex audio_mutex;
std::array<int16_t, AUDIO_CAPACITY> audio{};
size_t audio_read = 0, audio_write = 0, audio_count = 0;
std::atomic<int> sample_rate{44100};
std::atomic<double> fps{60.0};
std::atomic<double> aspect{4.0 / 3.0};
std::atomic<int> video_width{640}, video_height{400};
std::atomic<bool> stop_requested{false}, paused{false}, reset_requested{false};
std::atomic<bool> core_crashed{false};
std::atomic<int> status{0}; // 0 idle, 1 loading, 2 running, 3 failed
std::atomic<uint64_t> guest_keyboard_waits{0}, guest_keyboard_polls{0}, guest_mouse_reads{0};
std::atomic<int> guest_keyboard_waiting{0};
std::mutex error_mutex;
std::mutex core_execution_mutex;
std::string last_error;
std::string save_directory, system_directory, content_directory;
retro_keyboard_callback keyboard_callback{};
void* core_handle = nullptr;

struct Core {
    decltype(&retro_set_environment) set_environment = nullptr;
    decltype(&retro_set_video_refresh) set_video_refresh = nullptr;
    decltype(&retro_set_audio_sample_batch) set_audio_sample_batch = nullptr;
    decltype(&retro_set_input_poll) set_input_poll = nullptr;
    decltype(&retro_set_input_state) set_input_state = nullptr;
    decltype(&retro_set_controller_port_device) set_controller_port_device = nullptr;
    decltype(&retro_init) init = nullptr;
    decltype(&retro_deinit) deinit = nullptr;
    decltype(&retro_load_game) load_game = nullptr;
    decltype(&retro_unload_game) unload_game = nullptr;
    decltype(&retro_get_system_av_info) get_system_av_info = nullptr;
    decltype(&retro_run) run = nullptr;
    decltype(&retro_reset) reset = nullptr;
    decltype(&retro_serialize_size) serialize_size = nullptr;
    decltype(&retro_serialize) serialize = nullptr;
    decltype(&retro_unserialize) unserialize = nullptr;
    void (*set_zip_root)(bool) = nullptr;
    void (*reset_input_telemetry)() = nullptr;
    void (*input_telemetry_snapshot)(uint64_t*, uint64_t*, uint64_t*, int*) = nullptr;
} core;

void set_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(error_mutex);
    last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void core_log(retro_log_level level, const char* format, ...) {
    char message[1024];
    va_list args;
    va_start(args, format);
    std::vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_print(level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "DOSBoxPure", "%s", message);
    constexpr char crash_prefix[] = "[DOSBOX] Crash: ";
    if (std::strncmp(message, crash_prefix, sizeof(crash_prefix) - 1) == 0) {
        std::string detail(message + sizeof(crash_prefix) - 1);
        while (!detail.empty() && (detail.back() == '\n' || detail.back() == '\r'))
            detail.pop_back();
        set_error("DOS emulator crashed: " + detail);
        core_crashed.store(true);
        stop_requested.store(true);
    }
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = system_directory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = save_directory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            *static_cast<const char**>(data) = content_directory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = core_log; return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0; return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = option_dirty.exchange(false); return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* variable = static_cast<retro_variable*>(data);
            if (std::strcmp(variable->key, "dosbox_pure_mouse_input") == 0) {
                variable->value = mouse_mode.load() == 1 ? "direct" : "virtual";
                return true;
            }
            if (std::strcmp(variable->key, "dosbox_pure_cycles") == 0) {
                variable->value = cycles_mode.load() == 1 ? "max" : "auto";
                return true;
            }
            if (std::strcmp(variable->key, "dosbox_pure_conf") == 0) {
                variable->value = outside_conf.load() ? "outside" : "false";
                return true;
            }
            if (std::strcmp(variable->key, "dosbox_pure_menu_time") == 0) {
                variable->value = "0"; // Return to KairoDos when the game or its script exits.
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
            keyboard_callback = *static_cast<retro_keyboard_callback*>(data); return true;
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_NONE; return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER:
            return false; // Software renderer until an EGL libretro host is implemented.
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            *static_cast<bool*>(data) = false; return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const auto& info = *static_cast<retro_system_av_info*>(data);
            if (info.timing.fps > 1) fps.store(info.timing.fps);
            if (info.timing.sample_rate > 1000) sample_rate.store(static_cast<int>(info.timing.sample_rate));
            if (info.geometry.aspect_ratio > 0) aspect.store(info.geometry.aspect_ratio);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const auto& geometry = *static_cast<retro_game_geometry*>(data);
            if (geometry.aspect_ratio > 0) aspect.store(geometry.aspect_ratio);
            return true;
        }
        case RETRO_ENVIRONMENT_SHUTDOWN:
            stop_requested.store(true); return true;
        default:
            return false;
    }
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (core_crashed.load() || !data || data == RETRO_HW_FRAME_BUFFER_VALID ||
        width == 0 || height == 0) return;
    video_width.store(static_cast<int>(width));
    video_height.store(static_cast<int>(height));
    // Posting to a 60 Hz Surface can block for a full refresh. Keep that wait off
    // the emulation thread, which must run at the DOS video rate (often 70 Hz).
    std::lock_guard<std::mutex> lock(frame_mutex);
    pending_frame.resize(static_cast<size_t>(width) * height * 4);
    const auto* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; ++y) {
        const auto* row = reinterpret_cast<const uint32_t*>(src + y * pitch);
        auto* out = reinterpret_cast<uint32_t*>(pending_frame.data()) + y * width;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t rgb = row[x];
            out[x] = 0xff000000u | ((rgb & 0xffu) << 16) | (rgb & 0xff00u) |
                     ((rgb >> 16) & 0xffu);
        }
    }
    pending_width = width;
    pending_height = height;
    has_pending_frame = true;
    frame_ready.notify_one();
}

void render_frames() {
    std::vector<uint8_t> frame;
    while (true) {
        unsigned width, height;
        {
            std::unique_lock<std::mutex> lock(frame_mutex);
            frame_ready.wait(lock, [] { return renderer_stopping || has_pending_frame; });
            if (renderer_stopping) return;
            frame.swap(pending_frame);
            width = pending_width;
            height = pending_height;
            has_pending_frame = false;
        }
        std::lock_guard<std::mutex> lock(surface_mutex);
        if (!surface) continue;
        if (surface_width != static_cast<int>(width) || surface_height != static_cast<int>(height)) {
            if (ANativeWindow_setBuffersGeometry(surface, static_cast<int>(width),
                    static_cast<int>(height), WINDOW_FORMAT_RGBA_8888) != 0) continue;
            surface_width = static_cast<int>(width);
            surface_height = static_cast<int>(height);
        }
        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(surface, &buffer, nullptr) != 0) continue;
        for (unsigned y = 0; y < height && y < static_cast<unsigned>(buffer.height); ++y) {
            auto* out = static_cast<uint32_t*>(buffer.bits) + y * buffer.stride;
            const auto* row = reinterpret_cast<const uint32_t*>(frame.data()) + y * width;
            std::memcpy(out, row, std::min(width, static_cast<unsigned>(buffer.width)) * 4);
        }
        ANativeWindow_unlockAndPost(surface);
    }
}

size_t audio_batch(const int16_t* data, size_t frames) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    size_t samples = frames * 2;
    for (size_t i = 0; i < samples; ++i) {
        if (audio_count == AUDIO_CAPACITY) {
            audio_read = (audio_read + 1) % AUDIO_CAPACITY;
            --audio_count;
        }
        audio[audio_write] = data[i];
        audio_write = (audio_write + 1) % AUDIO_CAPACITY;
        ++audio_count;
    }
    return frames;
}

void input_poll() {}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_MOUSE) {
        if (id == RETRO_DEVICE_ID_MOUSE_X) return static_cast<int16_t>(
            std::clamp(mouse_x.exchange(0), -32768, 32767));
        if (id == RETRO_DEVICE_ID_MOUSE_Y) return static_cast<int16_t>(
            std::clamp(mouse_y.exchange(0), -32768, 32767));
        if (id == RETRO_DEVICE_ID_MOUSE_LEFT) return mouse_left.load() ? 1 : 0;
        if (id == RETRO_DEVICE_ID_MOUSE_RIGHT) return mouse_right.load() ? 1 : 0;
    }
    if (device == RETRO_DEVICE_POINTER) {
        if (id == RETRO_DEVICE_ID_POINTER_X) return static_cast<int16_t>(pointer_x.load());
        if (id == RETRO_DEVICE_ID_POINTER_Y) return static_cast<int16_t>(pointer_y.load());
        if (id == RETRO_DEVICE_ID_POINTER_PRESSED) return pointer_pressed.load() ? 1 : 0;
        if (id == RETRO_DEVICE_ID_POINTER_COUNT) return pointer_pressed.load() ? 1 : 0;
    }
    std::lock_guard<std::mutex> lock(input_mutex);
    if (device == RETRO_DEVICE_KEYBOARD && id < keys.size()) return keys[id] ? 1 : 0;
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD && id < 16) return joypad[id] ? 1 : 0;
    if (device == RETRO_DEVICE_ANALOG &&
        (index == RETRO_DEVICE_INDEX_ANALOG_LEFT ||
         index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) &&
        (id == RETRO_DEVICE_ID_ANALOG_X || id == RETRO_DEVICE_ID_ANALOG_Y)) {
        const size_t base = index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? 16 : 20;
        const size_t negative = base + (id == RETRO_DEVICE_ID_ANALOG_X ? 2 : 0);
        return static_cast<int16_t>(32767 * (int(joypad[negative + 1]) -
                                            int(joypad[negative])));
    }
    return 0;
}

template <typename T> bool symbol(T& target, const char* name) {
    target = reinterpret_cast<T>(dlsym(core_handle, name));
    if (!target) set_error(std::string("Missing DOSBox Pure symbol: ") + name);
    return target != nullptr;
}

bool load_core() {
    core_handle = dlopen("libretro.so", RTLD_NOW | RTLD_LOCAL);
    if (!core_handle) { set_error(std::string("Could not load DOSBox Pure: ") + dlerror()); return false; }
    return symbol(core.set_environment, "retro_set_environment") &&
        symbol(core.set_video_refresh, "retro_set_video_refresh") &&
        symbol(core.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
        symbol(core.set_input_poll, "retro_set_input_poll") &&
        symbol(core.set_input_state, "retro_set_input_state") &&
        symbol(core.set_controller_port_device, "retro_set_controller_port_device") &&
        symbol(core.init, "retro_init") && symbol(core.deinit, "retro_deinit") &&
        symbol(core.load_game, "retro_load_game") &&
        symbol(core.unload_game, "retro_unload_game") &&
        symbol(core.get_system_av_info, "retro_get_system_av_info") &&
        symbol(core.run, "retro_run") && symbol(core.reset, "retro_reset") &&
        symbol(core.serialize_size, "retro_serialize_size") &&
        symbol(core.serialize, "retro_serialize") &&
        symbol(core.unserialize, "retro_unserialize") &&
        symbol(core.set_zip_root, "kairo_set_enter_solo_root_dir") &&
        symbol(core.reset_input_telemetry, "kairo_dos_input_telemetry_reset") &&
        symbol(core.input_telemetry_snapshot, "kairo_dos_input_telemetry_snapshot");
}

std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeRun(JNIEnv* env, jobject,
    jstring path_j, jstring save_j, jstring system_j, jboolean enter_solo_root,
    jboolean use_outside_conf) {
    int expected = status.load();
    while (expected == 0 || expected == 3) {
        if (status.compare_exchange_weak(expected, 1)) break;
    }
    if (expected == 1 || expected == 2) {
        set_error("DOSBox Pure session already active");
        return false;
    }
    stop_requested.store(false);
    core_crashed.store(false);
    paused.store(false);
    reset_requested.store(false);
    const std::string path = string(env, path_j);
    save_directory = string(env, save_j);
    system_directory = string(env, system_j);
    auto separator = path.find_last_of('/');
    content_directory = separator == std::string::npos ? "" : path.substr(0, separator);
    { std::lock_guard<std::mutex> lock(error_mutex); last_error.clear(); }
    { std::lock_guard<std::mutex> lock(audio_mutex); audio_read = audio_write = audio_count = 0; }
    { std::lock_guard<std::mutex> lock(input_mutex); key_changes.clear(); keys.fill(0); joypad.fill(0); }
    guest_keyboard_waits.store(0); guest_keyboard_polls.store(0);
    guest_mouse_reads.store(0); guest_keyboard_waiting.store(0);
    keyboard_callback = {};
    outside_conf.store(use_outside_conf);
    if (!load_core()) { if (core_handle) dlclose(core_handle); core_handle = nullptr; status.store(3); return false; }
    core.set_zip_root(enter_solo_root);
    core.set_environment(environment);
    core.set_video_refresh(video);
    core.set_audio_sample_batch(audio_batch);
    core.set_input_poll(input_poll);
    core.set_input_state(input_state);
    core.init();
    retro_game_info game{path.c_str(), nullptr, 0, nullptr};
    if (!core.load_game(&game)) {
        set_error("DOSBox Pure could not open this game file");
        core.deinit(); dlclose(core_handle); core_handle = nullptr; status.store(3); return false;
    }
    core.reset_input_telemetry();
    // Two DOS joysticks: left/right analog axes and B/Y/A/X as button lines 1-4.
    // Frontend D-pad and Enter remain independent keyboard events.
    core.set_controller_port_device(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_JOYPAD, 7));
    retro_system_av_info av{};
    core.get_system_av_info(&av);
    if (av.timing.fps > 1) fps.store(av.timing.fps);
    if (av.timing.sample_rate > 1000) sample_rate.store(static_cast<int>(av.timing.sample_rate));
    if (av.geometry.aspect_ratio > 0) aspect.store(av.geometry.aspect_ratio);
    status.store(2);
    {
        std::lock_guard<std::mutex> lock(frame_mutex);
        renderer_stopping = false;
        has_pending_frame = false;
    }
    std::thread renderer(render_frames);
    auto next = std::chrono::steady_clock::now();
    while (!stop_requested.load()) {
        if (paused.load()) { std::this_thread::sleep_for(std::chrono::milliseconds(10)); next = std::chrono::steady_clock::now(); continue; }
        std::deque<KeyChange> changes;
        { std::lock_guard<std::mutex> lock(input_mutex); changes.swap(key_changes); }
        if (keyboard_callback.callback) for (const auto& change : changes)
            keyboard_callback.callback(change.down, change.code, change.code < 128 ? change.code : 0, 0);
        if (reset_requested.exchange(false)) {
            std::lock_guard<std::mutex> lock(core_execution_mutex);
            core.reset();
        }
        {
            std::lock_guard<std::mutex> lock(core_execution_mutex);
            core.run();
        }
        uint64_t waits = 0, polls = 0, reads = 0;
        int waiting = 0;
        core.input_telemetry_snapshot(&waits, &polls, &reads, &waiting);
        guest_keyboard_waits.store(waits); guest_keyboard_polls.store(polls);
        guest_mouse_reads.store(reads); guest_keyboard_waiting.store(waiting);
        double rate = std::clamp(fps.load(), 10.0, 240.0);
        next += std::chrono::nanoseconds(static_cast<long long>(1000000000.0 / rate));
        auto now = std::chrono::steady_clock::now();
        if (next > now) std::this_thread::sleep_until(next);
        else if (now - next > std::chrono::milliseconds(100)) next = now;
    }
    {
        std::lock_guard<std::mutex> lock(frame_mutex);
        renderer_stopping = true;
    }
    frame_ready.notify_one();
    renderer.join();
    {
        std::lock_guard<std::mutex> lock(core_execution_mutex);
        status.store(1);
        core.unload_game();
        core.deinit();
        dlclose(core_handle); core_handle = nullptr;
        status.store(0);
    }
    return !core_crashed.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeStop(JNIEnv*, jobject) { stop_requested.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativePause(JNIEnv*, jobject, jboolean value) { paused.store(value); }
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeReset(JNIEnv*, jobject) { reset_requested.store(true); }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeSaveState(JNIEnv* env, jobject, jstring path_j) {
    const std::string path = string(env, path_j);
    std::lock_guard<std::mutex> lock(core_execution_mutex);
    if (status.load() != 2 || !core_handle) return 1;
    const size_t size = core.serialize_size();
    if (!size || size > 512ull * 1024 * 1024) return 2;
    std::vector<uint8_t> state(size);
    if (!core.serialize(state.data(), state.size())) return 4;
    FILE* file = std::fopen(path.c_str(), "wb");
    if (!file) return 3;
    const bool written = std::fwrite(state.data(), 1, state.size(), file) == state.size();
    const bool closed = std::fclose(file) == 0;
    if (!written || !closed) { std::remove(path.c_str()); return 3; }
    return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeLoadState(JNIEnv* env, jobject, jstring path_j) {
    const std::string path = string(env, path_j);
    std::lock_guard<std::mutex> lock(core_execution_mutex);
    if (status.load() != 2 || !core_handle) return 1;
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return 3;
    if (std::fseek(file, 0, SEEK_END) != 0) { std::fclose(file); return 3; }
    const long stored_size = std::ftell(file);
    // Pure's sparse state length can change as guest memory changes. Load the recorded
    // length, then let the core validate its version, layout, and machine configuration.
    if (stored_size < 9 || stored_size > 512ll * 1024 * 1024 ||
        std::fseek(file, 0, SEEK_SET) != 0) {
        std::fclose(file);
        return 2;
    }
    const size_t size = static_cast<size_t>(stored_size);
    std::vector<uint8_t> state(size);
    const bool read = std::fread(state.data(), 1, size, file) == size;
    std::fclose(file);
    if (!read) return 3;
    if (!core.unserialize(state.data(), size)) return 4;
    { std::lock_guard<std::mutex> audio_lock(audio_mutex); audio_read = audio_write = audio_count = 0; }
    return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeStatus(JNIEnv*, jobject) { return status.load(); }
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeInputTelemetry(JNIEnv* env, jobject) {
    jlong values[4] = {static_cast<jlong>(guest_keyboard_waits.load()),
                       static_cast<jlong>(guest_keyboard_polls.load()),
                       static_cast<jlong>(guest_mouse_reads.load()),
                       static_cast<jlong>(guest_keyboard_waiting.load())};
    jlongArray result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeAudioRate(JNIEnv*, jobject) { return sample_rate.load(); }
extern "C" JNIEXPORT jdouble JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeAspect(JNIEnv*, jobject) { return aspect.load(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeVideoWidth(JNIEnv*, jobject) { return video_width.load(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeVideoHeight(JNIEnv*, jobject) { return video_height.load(); }
extern "C" JNIEXPORT jstring JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(error_mutex);
    return env->NewStringUTF(last_error.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeSetSurface(JNIEnv* env, jobject, jobject value) {
    ANativeWindow* next = value ? ANativeWindow_fromSurface(env, value) : nullptr;
    std::lock_guard<std::mutex> lock(surface_mutex);
    if (surface) ANativeWindow_release(surface);
    surface = next;
    surface_width = surface_height = 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeKey(JNIEnv*, jobject, jint code, jboolean down) {
    if (code < 0 || code >= static_cast<jint>(keys.size())) return;
    std::lock_guard<std::mutex> lock(input_mutex);
    if (keys[code] == static_cast<uint8_t>(down)) return;
    keys[code] = static_cast<uint8_t>(down);
    key_changes.push_back({static_cast<unsigned>(code), static_cast<bool>(down)});
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeJoypad(JNIEnv*, jobject, jint id, jboolean down) {
    if (id < 0 || id >= static_cast<jint>(joypad.size())) return;
    std::lock_guard<std::mutex> lock(input_mutex);
    joypad[id] = static_cast<uint8_t>(down);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeMouseMove(JNIEnv*, jobject, jint dx, jint dy) {
    mouse_x.fetch_add(dx); mouse_y.fetch_add(dy);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeMouseButton(JNIEnv*, jobject, jint button, jboolean down) {
    if (button == 1) mouse_left.store(down);
    if (button == 2) mouse_right.store(down);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativePointer(JNIEnv*, jobject, jint x, jint y, jboolean down) {
    pointer_x.store(std::clamp(static_cast<int>(x), -32767, 32767));
    pointer_y.store(std::clamp(static_cast<int>(y), -32767, 32767));
    pointer_pressed.store(down);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeConfigure(JNIEnv*, jobject, jint mouse, jint cycles) {
    mouse_mode.store(mouse == 1 ? 1 : 0);
    cycles_mode.store(cycles == 1 ? 1 : 0);
    option_dirty.store(true);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mrjackspade_kairodos_MainActivity_nativeReadAudio(JNIEnv* env, jobject, jshortArray output, jint max_frames) {
    if (!output || max_frames <= 0) return 0;
    const size_t frames = std::min(static_cast<size_t>(max_frames), static_cast<size_t>(env->GetArrayLength(output) / 2));
    std::lock_guard<std::mutex> lock(audio_mutex);
    const size_t count = std::min(frames * 2, audio_count) & ~static_cast<size_t>(1);
    jshort* dest = env->GetShortArrayElements(output, nullptr);
    if (!dest) return 0;
    for (size_t i = 0; i < count; ++i) {
        dest[i] = audio[audio_read];
        audio_read = (audio_read + 1) % AUDIO_CAPACITY;
    }
    audio_count -= count;
    env->ReleaseShortArrayElements(output, dest, 0);
    return static_cast<jint>(count / 2);
}
