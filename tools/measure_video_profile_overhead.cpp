// SPDX-License-Identifier: GPL-2.0-or-later
// Standalone observer-cost measurement, never run alongside a game capture.
#define KAIRO_VIDEO_PROFILE
#include "video_profile.h"
#include <cstdio>
#include <thread>
using Clock=std::chrono::steady_clock;
static constexpr int frames=20000;
static void producer() {
    for (int i=0;i<frames;++i) {
        auto t=video_profile::now(); video_profile::elapsed(video_profile::FrameMutex,t);
        t=video_profile::now(); video_profile::elapsed(video_profile::Convert,t);
        video_profile::record(video_profile::Produced);
    }
}
static void presenter() {
    for (int i=0;i<frames;++i) {
        for (auto stage : {video_profile::WindowMutex,video_profile::WindowLock,
                           video_profile::WindowCopy,video_profile::WindowPost,
                           video_profile::AgeAtPost}) {
            auto t=video_profile::now(); video_profile::elapsed(stage,t);
        }
        video_profile::record(video_profile::Presented);
    }
}
int main() {
    puts("{\"framesPerThread\":20000,\"eventsPerFramePair\":9,\"trials\":[");
    for(int trial=0;trial<6;++trial) {
        const bool active=trial%3==1;
        video_profile::reset(active);
        const auto start=Clock::now();
        std::thread a(producer),b(presenter); a.join(); b.join();
        const auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now()-start).count();
        video_profile::enabled=false;
        if(active && (video_profile::samples[video_profile::Produced].size()!=frames ||
                      video_profile::samples[video_profile::Presented].size()!=frames)) return 2;
        printf("%s{\"trial\":%d,\"enabled\":%s,\"wallNs\":%lld}",
               trial?",\n":"",trial,active?"true":"false",static_cast<long long>(ns));
    }
    puts("\n]}");
}
