// SPDX-License-Identifier: GPL-2.0-or-later
// KairoDos's DOSBox Staging (backend-dos: libdosbox_staging.so) as the MachineCore the shared frame
// host runs (shared/frontend/src/main/cpp/kairo/machine_core.h). Staging runs its own loop on a
// thread of its own and takes turns with the host (kairo/emulator_turns.h): it hands the turn over
// at the start of every emulated frame, so the host runs it a frame at a time, and every call into
// it below is made while the host has the turn.

#include <dlfcn.h>

#include <array>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "kairo/emulator_turns.h"
#include "kairo/machine_core.h"
#include "kairo/thread_priority.h"
#include "staging_bridge.h"

namespace {

// The launch settings MainActivity.startGame passes: the configuration Staging runs and the folder
// of its own resources (DosStagingResources). The launch's home is the game's save folder, where
// Staging keeps its own configuration; its media are the host paths the configuration names by
// reference (KAIRO_HOST_PATH_PREFIX), in order.
constexpr const char *kConfig = "config";
constexpr const char *kResources = "resources";

// MainActivity's key codes (maxKeyCode) and Kairo's joystick controls (JoystickInputRouter).
constexpr int kKeyCodes = 342;
constexpr int kJoystickControls = 24;
// The BIOS keyboard flags' lock bits (kairo_staging_lock_flags), in the order MainActivity's
// NativeMachine lists the lock keys: Caps Lock, Num Lock, Scroll Lock.
constexpr std::array<int, 3> kLockFlags = {0x40, 0x20, 0x10};

// One run of Staging's library. Each run loads its own copy, and unloads it after, so a restart or
// the next game starts from Staging's initial globals (its shutdown flag among them).
class Staging {
public:
    static std::unique_ptr<Staging> open(std::string &error) {
        std::unique_ptr<Staging> staging(new Staging());
        staging->handle_ = dlopen("libdosbox_staging.so", RTLD_NOW | RTLD_LOCAL);
        if (!staging->handle_) {
            error = std::string("Could not load DOSBox Staging: ") + dlerror();
            return nullptr;
        }
        if (!staging->bind(staging->run, "kairo_staging_run", error) ||
            !staging->bind(staging->key, "kairo_staging_key", error) ||
            !staging->bind(staging->joypad, "kairo_staging_joypad", error) ||
            !staging->bind(staging->mouse_move, "kairo_staging_mouse_move", error) ||
            !staging->bind(staging->mouse_point, "kairo_staging_mouse_point", error) ||
            !staging->bind(staging->mouse_button, "kairo_staging_mouse_button", error) ||
            !staging->bind(staging->read_audio, "kairo_staging_read_audio", error) ||
            !staging->bind(staging->audio_rate, "kairo_staging_audio_rate", error) ||
            !staging->bind(staging->guest_frames, "kairo_staging_guest_frames", error) ||
            !staging->bind(staging->guest_input, "kairo_staging_guest_input", error) ||
            !staging->bind(staging->cpu, "kairo_staging_cpu", error) ||
            !staging->bind(staging->lock_flags, "kairo_staging_lock_flags", error) ||
            !staging->bind(staging->fast_forward, "kairo_staging_fast_forward", error) ||
            !staging->bind(staging->list_directory, "kairo_staging_list_directory", error))
            return nullptr;
        return staging;
    }

    ~Staging() {
        // Staging joins its own threads before kairo_staging_run returns.
        if (handle_) dlclose(handle_);
    }

    decltype(&kairo_staging_run) run = nullptr;
    decltype(&kairo_staging_key) key = nullptr;
    decltype(&kairo_staging_joypad) joypad = nullptr;
    decltype(&kairo_staging_mouse_move) mouse_move = nullptr;
    decltype(&kairo_staging_mouse_point) mouse_point = nullptr;
    decltype(&kairo_staging_mouse_button) mouse_button = nullptr;
    decltype(&kairo_staging_read_audio) read_audio = nullptr;
    decltype(&kairo_staging_audio_rate) audio_rate = nullptr;
    decltype(&kairo_staging_guest_frames) guest_frames = nullptr;
    decltype(&kairo_staging_guest_input) guest_input = nullptr;
    decltype(&kairo_staging_cpu) cpu = nullptr;
    decltype(&kairo_staging_lock_flags) lock_flags = nullptr;
    decltype(&kairo_staging_fast_forward) fast_forward = nullptr;
    decltype(&kairo_staging_list_directory) list_directory = nullptr;

private:
    Staging() = default;

    template <typename T> bool bind(T &target, const char *name, std::string &error) {
        target = reinterpret_cast<T>(dlsym(handle_, name));
        if (!target) error = std::string("Missing DOSBox Staging symbol: ") + name;
        return target != nullptr;
    }

    void *handle_ = nullptr;
};

class DosCore;
// The running core, for Staging's callbacks, which carry no context: one machine runs at a time.
DosCore *running = nullptr;

class DosCore : public kairo::MachineCore {
public:
    DosCore(std::string config, std::string config_dir, std::string resources, std::vector<std::string> host_paths)
        : config_(std::move(config)), config_dir_(std::move(config_dir)), resources_(std::move(resources)),
          host_paths_(std::move(host_paths)) {}

    ~DosCore() override {
        // close() ended Staging, or it ended itself.
        running = nullptr;
    }

    // Starts Staging and waits for its first frame; false with error() when it ends first.
    bool start() {
        running = this;
        if (turns_.start([this] { run_sessions(); })) return true;
        turns_.finish();
        return false;
    }

    bool run_frame() override { return turns_.run_frame(); }

    // "" when the game ended DOSBox itself (EXIT at its prompt).
    std::string error() override { return error_; }

    const uint8_t *frame(uint32_t &width, uint32_t &height) override {
        if (picture_.empty()) return nullptr;
        width = width_;
        height = height_;
        return picture_.data();
    }
    // Staging's pixels are 0x00RRGGBB words.
    bool frame_bgra() override { return true; }
    uint32_t frame_stride() override { return stride_; }
    double frame_seconds() override { return 1.0 / refresh_hz_; }
    int32_t audio_rate() override { return staging_->audio_rate(); }
    uint32_t read_audio(int16_t *stereo, uint32_t max_frames) override {
        return uint32_t(staging_->read_audio(stereo, int(max_frames)));
    }

    void flat_out(bool enabled) override {
        flat_out_ = enabled;
        staging_->fast_forward(enabled);
    }

    void release_inputs() override {
        for (int code = 0; code < kKeyCodes; ++code)
            if (keys_[code]) key(code, false);
        joypad_.fill(0);
        staging_->joypad(joypad_.data(), joypad_.size());
        staging_->mouse_button(1, false);
        staging_->mouse_button(2, false);
    }

    void key(int code, bool down) override {
        if (code < 0 || code >= kKeyCodes || keys_[code] == down) return;
        keys_[code] = down;
        staging_->key(code, down);
    }

    void joystick(int control, bool down) override {
        if (control < 0 || control >= kJoystickControls) return;
        joypad_[control] = down ? 1 : 0;
        staging_->joypad(joypad_.data(), joypad_.size());
    }

    void mouse_move(int dx, int dy) override { staging_->mouse_move(dx, dy); }
    void mouse_button(int button, bool down) override { staging_->mouse_button(button, down); }
    // Staging places its pointer at once (seamless capture).
    void mouse_warp(int x, int y) override {
        uint32_t width = 0, height = 0;
        frame(width, height);
        staging_->mouse_point(int(int64_t(x) * 65536 / width), int(int64_t(y) * 65536 / height));
    }
    bool mouse_warp_done() override { return true; }

    int locks() override {
        const int flags = staging_->lock_flags();
        int locks = 0;
        for (size_t bit = 0; bit < kLockFlags.size(); ++bit)
            if (flags & kLockFlags[bit]) locks |= 1 << bit;
        return locks;
    }

    bool guest_input(GuestInput &input) override {
        KairoStagingGuestInput guest{};
        staging_->guest_input(&guest);
        input.keyboard_waits = guest.keyboard_waits;
        input.keyboard_polls = guest.keyboard_polls;
        input.mouse_reads = guest.mouse_reads;
        input.keyboard_waiting = guest.keyboard_waiting != 0;
        // DOSBox sees the guest's mouse reads themselves.
        input.mouse_enabled = false;
        return true;
    }

    // The picture's shape for the view (MainActivity.videoSize) and the CPU for Diagnostics.
    std::string details() override {
        KairoStagingCpu cpu{};
        staging_->cpu(&cpu);
        return "video=" + std::to_string(width_) + "x" + std::to_string(height_) + ";aspect=" + std::to_string(aspect_) +
               ";dynamic=" + std::to_string(cpu.dynamic_active) + ";translated=" + std::to_string(cpu.translated_blocks) +
               ";executed=" + std::to_string(cpu.executed_blocks) + ";frames=" + std::to_string(staging_->guest_frames());
    }

    // "list <path>": the guest's directory, as kairo_staging_list_directory gives it (the program
    // picker, MainActivity).
    std::string command(const std::string &line) override {
        static const std::string kList = "list ";
        if (line.compare(0, kList.size(), kList) != 0) throw std::invalid_argument("Unknown DOS command: " + line);
        return staging_->list_directory(line.c_str() + kList.size());
    }

    // Staging starts again from its configuration.
    void reset() override { restart_ = true; }

    std::string close() override {
        stopping_ = true;
        turns_.finish();
        return "";
    }

private:
    // Staging's thread: runs it, and again after each restart, until it ends.
    void run_sessions() {
        kairo::name_thread("kairodos-dosbox");
        kairo::raise_emulation_priority("staging");
        std::vector<const char *> paths;
        for (const auto &path : host_paths_) paths.push_back(path.c_str());
        do {
            restart_ = false;
            polled_ = false;
            staging_ = Staging::open(error_);
            if (!staging_) return;
            const KairoStagingCallbacks callbacks{3, on_video, on_poll, on_restart, on_frame};
            int result;
            try {
                result = staging_->run(config_.c_str(), config_dir_.c_str(), resources_.c_str(), paths.data(), paths.size(),
                                       &callbacks);
            } catch (const std::exception &failure) {
                // Escaping the thread would end the app; the session ends with why instead.
                error_ = failure.what();
                result = 1;
            }
            staging_.reset();
            if (result != 0) {
                if (error_.empty())
                    error_ = "DOSBox Staging could not start or continue this game. Check the launch configuration.";
                return;
            }
        } while (restart_ && !stopping_);
    }

    static void on_video(const uint32_t *pixels, int width, int height, int pitch, double aspect) {
        running->video(pixels, width, height, pitch, aspect);
    }
    void video(const uint32_t *pixels, int width, int height, int pitch, double aspect) {
        if (!pixels || width <= 0 || height <= 0 || pitch < width * 4)
            throw std::invalid_argument("DOSBox Staging gave a frame of " + std::to_string(width) + "x" +
                                        std::to_string(height) + " with rows of " + std::to_string(pitch) + " bytes");
        picture_.resize(size_t(pitch) * size_t(height));
        std::memcpy(picture_.data(), pixels, picture_.size());
        width_ = uint32_t(width);
        height_ = uint32_t(height);
        stride_ = uint32_t(pitch);
        if (aspect > 0) aspect_ = aspect;
    }

    // Each run's first poll gives it what the host set before: a restart begins at Staging's own speed.
    static int on_poll() { return running->poll(); }
    int poll() {
        if (stopping_ || restart_) return 0;
        if (!polled_ && flat_out_) staging_->fast_forward(true);
        polled_ = true;
        return 1;
    }

    static void on_restart() { running->restart_ = true; }

    static void on_frame(double refresh_hz) { running->frame_end(refresh_hz); }
    void frame_end(double refresh_hz) {
        if (refresh_hz > 0) refresh_hz_ = refresh_hz;
        turns_.frame_end();
    }

    const std::string config_, config_dir_, resources_;
    const std::vector<std::string> host_paths_;
    kairo::EmulatorTurns turns_;
    // The run's library; replaced on Staging's thread only during its turn.
    std::unique_ptr<Staging> staging_;
    std::string error_;
    // Set by the host during its turn, read by Staging in its own.
    bool stopping_ = false;
    bool restart_ = false;
    bool flat_out_ = false;
    bool polled_ = false;

    std::vector<uint8_t> picture_;
    uint32_t width_ = 0, height_ = 0, stride_ = 0;
    double aspect_ = 4.0 / 3.0;
    double refresh_hz_ = 70.0;

    std::array<bool, kKeyCodes> keys_{};
    std::array<uint8_t, kJoystickControls> joypad_{};
};

} // namespace

std::unique_ptr<kairo::MachineCore> kairo::create_core(const kairo::Launch &launch, std::string &error) {
    auto core = std::make_unique<DosCore>(launch.settings.at(kConfig), launch.home + "/staging/config",
                                          launch.settings.at(kResources), launch.media);
    if (!core->start()) {
        error = core->error();
        return nullptr;
    }
    return core;
}
