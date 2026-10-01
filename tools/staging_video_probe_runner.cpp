// SPDX-License-Identifier: GPL-2.0-or-later
// Standalone observation of the actual Staging video callback. This deliberately
// does not model Android window copies or substitute for app-side measurements.
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <vector>
#include "staging_bridge.h"

using Clock = std::chrono::steady_clock;
static Clock::time_point started;
static std::vector<unsigned char> previous;
static int old_width, old_height;
static unsigned long long callbacks, changed, duplicate, bytes, observation_ns;
static double seconds() { return std::chrono::duration<double>(Clock::now()-started).count(); }
static int poll() { return seconds() < 8; }
static void video(const uint32_t* pixels, int width, int height, int pitch, double) {
    const auto begin = Clock::now();
    if (!pixels || width <= 0 || height <= 0 || pitch < width*4) return;
    const size_t row_bytes = static_cast<size_t>(width)*4;
    const bool same_size = old_width == width && old_height == height;
    bool same = same_size;
    const auto* source = reinterpret_cast<const unsigned char*>(pixels);
    if (same_size) for (int y=0; y<height; ++y)
        if (std::memcmp(previous.data()+y*row_bytes, source+y*pitch, row_bytes)) { same=false; break; }
    previous.resize(row_bytes*height);
    for (int y=0; y<height; ++y)
        std::memcpy(previous.data()+y*row_bytes, source+y*pitch, row_bytes);
    old_width=width; old_height=height;
    if (seconds() >= 2) {
        ++callbacks; same ? ++duplicate : ++changed; bytes += row_bytes*height;
        observation_ns += std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now()-begin).count();
    }
}
int main(int argc, char** argv) {
    if (argc != 5) return 2;
    auto* lib=dlopen(argv[1], RTLD_NOW|RTLD_LOCAL);
    if (!lib) { puts(dlerror()); return 3; }
    auto run=reinterpret_cast<decltype(&kairo_staging_run)>(dlsym(lib,"kairo_staging_run"));
    if (!run) return 4;
    KairoStagingCallbacks cb{}; cb.version=2; cb.poll=poll; cb.video=video;
    started=Clock::now();
    const int result=run(argv[2],argv[3],argv[4],&cb);
    printf("{\"core_result\":%d,\"elapsed_seconds\":%.6f,\"callbacks_after_2s\":%llu,"
           "\"changed\":%llu,\"duplicate\":%llu,\"callback_bytes\":%llu,"
           "\"observer_ns\":%llu,\"last_width\":%d,\"last_height\":%d}\n",
           result,seconds(),callbacks,changed,duplicate,bytes,observation_ns,old_width,old_height);
    return result;
}
