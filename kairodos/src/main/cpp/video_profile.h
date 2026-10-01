// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstdint>
#include "frame_copy_profile.h"

// Debug-only observation of the existing host; disabled until instrumentation
// enables it. Release builds contain neither storage nor JNI profiling entrypoints.
namespace video_profile {
enum Stage { Convert, FrameMutex, WindowMutex, WindowLock, WindowCopy, WindowPost,
             HardwarePublish, CoreRun, AgeAtPost, Produced, Replaced, Presented,
             NoSurface, WindowFailure, HardwareFrame, Count };
}
#ifdef KAIRO_VIDEO_PROFILE
#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <mutex>
#include <sstream>
#include <vector>
namespace video_profile {
inline std::atomic<bool> enabled{false};
inline std::mutex mutex;
inline std::array<std::vector<uint64_t>, Count> samples;
inline uint64_t now() {
    return enabled ? std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count() : 0;
}
inline void record(Stage stage, uint64_t ns = 0) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mutex);
    samples[stage].push_back(ns);
}
inline void elapsed(Stage stage, uint64_t start) { if (start) record(stage, now() - start); }
inline void reset(bool active) {
    enabled = false;
    // Full-frame comparison is opt-in independently of timing collection.
    frame_copy_profile::reset(false);
    std::lock_guard<std::mutex> lock(mutex);
    for (auto& list : samples) list.clear();
    enabled = active;
}
inline std::string snapshot() {
    static const char* names[] = {"convert", "frameMutex", "windowMutex", "windowLock",
        "windowCopy", "windowPost", "hardwarePublish", "coreRun", "ageAtPost",
        "produced", "replaced", "presented", "noSurface", "windowFailure", "hardwareFrame"};
    std::lock_guard<std::mutex> lock(mutex);
    std::ostringstream result;
    result << '{';
    for (int stage = 0; stage < Count; ++stage) {
        auto list = samples[stage];
        std::sort(list.begin(), list.end());
        uint64_t sum = 0;
        for (auto value : list) sum += value;
        auto percentile = [&](double p) { return list.empty() ? uint64_t{0} : list[static_cast<size_t>((list.size()-1)*p)]; };
        if (stage) result << ',';
        result << '"' << names[stage] << "\":{\"count\":" << list.size()
               << ",\"sumNs\":" << sum << ",\"p50Ns\":" << percentile(.50)
               << ",\"p95Ns\":" << percentile(.95) << ",\"maxNs\":" << percentile(1) << '}';
    }
    result << ",\"frameCopies\":" << frame_copy_profile::snapshot() << '}';
    return result.str();
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_VideoPresentationFixture_nativeProfileReset(JNIEnv*, jclass, jboolean active) {
    video_profile::reset(active);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_mrjackspade_kairodos_VideoPresentationFixture_nativeProfileSnapshot(JNIEnv* env, jclass) {
    return env->NewStringUTF(video_profile::snapshot().c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_VideoPresentationFixture_nativeProfileFrameCopies(JNIEnv*, jclass, jboolean active) {
    frame_copy_profile::reset(active);
}
extern "C" JNIEXPORT void JNICALL
Java_com_mrjackspade_kairodos_VideoPresentationFixture_nativeProfileResetEnabledOff(JNIEnv*, jclass) {
    video_profile::enabled = false;
    frame_copy_profile::stop();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_mrjackspade_kairodos_VideoPresentationFixture_nativeProfileConfiguration(JNIEnv* env, jclass) {
#ifdef KAIRO_OPTIMIZED_PROFILE
    return env->NewStringUTF("Release-equivalent host (-O2 -DNDEBUG)");
#else
    return env->NewStringUTF("Optimized Android host (-O2), Staging CPU presenter");
#endif
}
#else
namespace video_profile {
inline uint64_t now() { return 0; }
inline void record(Stage, uint64_t = 0) {}
inline void elapsed(Stage, uint64_t) {}
}
#endif
