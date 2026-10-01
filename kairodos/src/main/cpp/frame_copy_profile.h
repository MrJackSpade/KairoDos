// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstddef>
#include <cstdint>

// Observation only. No pixel comparison or storage exists in release builds.
// Counts content changes at the host callback boundary, not guest VGA updates.
#ifdef KAIRO_VIDEO_PROFILE
#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <vector>
namespace frame_copy_profile {
struct Counts {
    uint64_t callbacks=0, changed=0, duplicates=0, first_or_resized=0;
    uint64_t callback_bytes=0, window_copies=0, window_bytes=0;
};
inline std::atomic<bool> enabled{false};
inline std::mutex mutex;
inline Counts counts;
inline std::vector<uint8_t> previous;
inline size_t old_width=0, old_height=0;
inline void reset(bool active) {
    std::lock_guard<std::mutex> lock(mutex);
    counts={}; previous.clear(); old_width=old_height=0;
    enabled=active;
}
inline void stop() {
    std::lock_guard<std::mutex> lock(mutex);
    enabled=false;
}
inline void callback(const void* data, size_t width, size_t height, size_t pitch) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (!enabled || !data || !width || !height || pitch/4 < width) return;
    const auto row_bytes=width*4;
    const auto* source=static_cast<const uint8_t*>(data);
    const bool same_size=width==old_width && height==old_height;
    bool same=same_size;
    if (same_size) for (size_t y=0; y<height; ++y) {
        if (std::memcmp(previous.data()+y*row_bytes,source+y*pitch,row_bytes)) {
            same=false; break;
        }
    }
    ++counts.callbacks;
    if (!same_size) ++counts.first_or_resized;
    else if (same) ++counts.duplicates;
    else ++counts.changed;
    counts.callback_bytes+=row_bytes*height;
    previous.resize(row_bytes*height);
    for (size_t y=0; y<height; ++y)
        std::memcpy(previous.data()+y*row_bytes,source+y*pitch,row_bytes);
    old_width=width; old_height=height;
}
inline void window_copy(size_t bytes) {
    if (!enabled) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (!enabled) return;
    ++counts.window_copies; counts.window_bytes+=bytes;
}
inline Counts read() {
    std::lock_guard<std::mutex> lock(mutex);
    return counts;
}
inline std::string snapshot() {
    const auto c=read();
    std::ostringstream out;
    out << "{\"callbacks\":" << c.callbacks << ",\"changed\":" << c.changed
        << ",\"duplicates\":" << c.duplicates << ",\"firstOrResized\":" << c.first_or_resized
        << ",\"callbackBytes\":" << c.callback_bytes << ",\"windowCopies\":" << c.window_copies
        << ",\"windowBytes\":" << c.window_bytes << '}';
    return out.str();
}
}
#else
namespace frame_copy_profile {
inline void callback(const void*, size_t, size_t, size_t) {}
inline void window_copy(size_t) {}
}
#endif
