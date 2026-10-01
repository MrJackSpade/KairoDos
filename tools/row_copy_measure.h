// SPDX-License-Identifier: GPL-2.0-or-later
// Temporary ticket #79 observer: not a production optimization.
#pragma once
#include <android/log.h>
#include <sys/system_properties.h>
#include <time.h>
#include <atomic>
#include <array>
#include <cstring>
#include <sstream>
#include <vector>
namespace row_measure {
using U = unsigned long long;
enum Stage { Convert, WindowCopy, WindowLock, WindowPost, Observer, Count };
inline std::array<std::atomic<U>,Count> wall{}, cpu{}, calls{};
inline U frames=0, rows=0, changedRows=0, duplicates=0, resized=0, bytes=0;
inline std::array<U,5> buckets{};
inline unsigned oldWidth=0,oldHeight=0;
inline std::vector<unsigned char> previous;
inline bool compare=false;
inline U lastLog=0;
inline U clock(clockid_t kind) { timespec t{}; clock_gettime(kind,&t); return U(t.tv_sec)*1000000000ULL+t.tv_nsec; }
struct Stamp { U wall,cpu; };
inline Stamp stamp(){ return {clock(CLOCK_MONOTONIC),clock(CLOCK_THREAD_CPUTIME_ID)}; }
inline void finish(Stage s, Stamp start) {
    const auto end=stamp(); wall[s].fetch_add(end.wall-start.wall,std::memory_order_relaxed);
    cpu[s].fetch_add(end.cpu-start.cpu,std::memory_order_relaxed); calls[s].fetch_add(1,std::memory_order_relaxed);
}
inline void reset(){
    for(int i=0;i<Count;++i){wall[i]=0;cpu[i]=0;calls[i]=0;}
    frames=rows=changedRows=duplicates=resized=bytes=0;buckets={};oldWidth=oldHeight=0;previous.clear();
    char value[PROP_VALUE_MAX]{};__system_property_get("debug.kairo.rows",value);compare=value[0]=='1';lastLog=clock(CLOCK_MONOTONIC);
}
inline void frame(const uint32_t* data,unsigned width,unsigned height,unsigned pitch){
    ++frames;rows+=height;bytes+=U(width)*height*4;
    if(compare){
        const auto begin=stamp();const size_t rowBytes=size_t(width)*4;
        const bool sameSize=width==oldWidth&&height==oldHeight;U changed=0;
        if(!sameSize){++resized;changed=height;previous.resize(rowBytes*height);}
        const auto* source=reinterpret_cast<const unsigned char*>(data);
        for(unsigned y=0;y<height;++y){
            auto* dest=previous.data()+y*rowBytes;
            if(sameSize&&std::memcmp(dest,source+y*pitch,rowBytes))++changed;
            std::memcpy(dest,source+y*pitch,rowBytes);
        }
        changedRows+=changed;if(!changed)++duplicates;
        ++buckets[changed==0?0:changed*4<=height?1:changed*2<=height?2:changed*4<=height*3?3:4];
        oldWidth=width;oldHeight=height;finish(Observer,begin);
    }
    const auto now=clock(CLOCK_MONOTONIC);if(now-lastLog<2000000000ULL)return;lastLog=now;
    std::ostringstream out;out<<"{\"rowsCompared\":"<<(compare?"true":"false")<<",\"width\":"<<width<<",\"height\":"<<height
       <<",\"frames\":"<<frames<<",\"rows\":"<<rows<<",\"changedRows\":"<<changedRows<<",\"duplicates\":"<<duplicates
       <<",\"resized\":"<<resized<<",\"bytes\":"<<bytes<<",\"buckets\":[";
    for(int i=0;i<5;++i){if(i)out<<',';out<<buckets[i];}out<<"],\"stages\":[";
    for(int i=0;i<Count;++i){if(i)out<<',';out<<"{\"wallNs\":"<<wall[i].load()<<",\"cpuNs\":"<<cpu[i].load()<<",\"calls\":"<<calls[i].load()<<'}';}
    out<<"]}";__android_log_print(ANDROID_LOG_INFO,"KairoRowMeasure","%s",out.str().c_str());
}
}
