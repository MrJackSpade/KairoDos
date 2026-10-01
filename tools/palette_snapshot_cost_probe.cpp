// SPDX-License-Identifier: GPL-2.0-or-later
// Feasibility only: CPU index/palette capture, conservative history comparison,
// and a scalar conversion control. No emulator or app integration.
#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <vector>
static uint64_t ns(clockid_t id=CLOCK_MONOTONIC) {
    timespec t{}; clock_gettime(id,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;
}
struct Frame {
    std::vector<uint8_t> indices;
    std::vector<uint16_t> rows;
    std::vector<uint32_t> palettes;
    int count=0;
    Frame(int w,int h):indices(w*h),rows(h),palettes(256*h){}
};
static bool capture(Frame& out,const Frame& old,const std::vector<uint8_t>& indices,
                    const std::vector<uint32_t>& palettes,int w,int h,bool perRow) {
    out.count=0;
    for(int y=0;y<h;++y) {
        const auto* pal=palettes.data()+(perRow?y*256:0);
        if(!out.count || std::memcmp(pal,out.palettes.data()+(out.count-1)*256,1024)) {
            std::memcpy(out.palettes.data()+out.count*256,pal,1024);++out.count;
        }
        out.rows[y]=out.count-1;
        std::memcpy(out.indices.data()+y*w,indices.data()+y*w,w);
    }
    return out.count!=old.count || out.indices!=old.indices || out.rows!=old.rows ||
           std::memcmp(out.palettes.data(),old.palettes.data(),out.count*1024);
}
int main() {
    puts("{\"iterations\":120,\"warmupIterations\":20,\"runs\":[");int run=0;
    for(int w:{320,640}) for(int mode=0;mode<5;++mode) {
        const int h=w==320?200:480;
        const bool perRow=mode==3;
        const char* name[]={"static","indices","frame_palette","row_palettes","unused_palette"};
        std::vector<uint8_t> indices(w*h);
        std::vector<uint32_t> palettes((perRow?h:1)*256),rgb(w*h),oldRgb(w*h);
        Frame current(w,h),previous(w,h);
        uint64_t captureCpu=0,captureWall=0,scalarCpu=0,scalarWall=0,dirty=0,rgbDirty=0,versions=0,verified=0;
        for(int frame=0;frame<140;++frame) {
            for(int y=0;y<h;++y)for(int x=0;x<w;++x)
                indices[y*w+x]=mode==4?0:uint8_t(x+y+(mode==1?frame:0));
            for(int y=0;y<(perRow?h:1);++y)for(int i=0;i<256;++i) {
                unsigned phase=mode==2?frame:mode==3?frame+y:mode==4&&i==255?frame:0;
                palettes[y*256+i]=0xff000000u|unsigned(i)|((phase&255)<<8)|((i^phase)&255)<<16;
            }
            const auto begin=ns(),c0=ns(CLOCK_THREAD_CPUTIME_ID);
            const bool changed=capture(current,previous,indices,palettes,w,h,perRow);
            const auto c1=ns(CLOCK_THREAD_CPUTIME_ID),end=ns();
            const auto scalarBegin=ns(),s0=ns(CLOCK_THREAD_CPUTIME_ID);
            for(int y=0;y<h;++y)for(int x=0;x<w;++x)rgb[y*w+x]=palettes[(perRow?y*256:0)+indices[y*w+x]];
            const auto s1=ns(CLOCK_THREAD_CPUTIME_ID),scalarEnd=ns();
            // Consume every output, outside timing, and verify sampled palette
            // versions remain correct across successive palette/frame changes.
            for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
                if(current.palettes[current.rows[y]*256+current.indices[y*w+x]]!=rgb[y*w+x])return 2;
            }
            if(frame>=20) {
                captureCpu+=c1-c0;captureWall+=end-begin;scalarCpu+=s1-s0;scalarWall+=scalarEnd-scalarBegin;
                dirty+=changed;rgbDirty+=(rgb!=oldRgb);versions+=current.count;verified+=w*h;
            }
            std::swap(current,previous);std::swap(rgb,oldRgb);
        }
        printf("%s{\"width\":%d,\"height\":%d,\"case\":\"%s\",\"captureCpuNs\":%llu,\"captureWallNs\":%llu,\"scalarCpuNs\":%llu,\"scalarWallNs\":%llu,\"conservativeDirtyFrames\":%llu,\"rgbChangedFrames\":%llu,\"paletteVersions\":%llu,\"verifiedPixels\":%llu}\n",
            run++?",":"",w,h,name[mode],(unsigned long long)captureCpu,(unsigned long long)captureWall,(unsigned long long)scalarCpu,(unsigned long long)scalarWall,(unsigned long long)dirty,(unsigned long long)rgbDirty,(unsigned long long)versions,(unsigned long long)verified);
    }
    uint64_t cpu=0,wall=0;
    for(int i=0;i<120;++i){auto a=ns(),c=ns(CLOCK_THREAD_CPUTIME_ID),d=ns(CLOCK_THREAD_CPUTIME_ID),b=ns();cpu+=d-c;wall+=b-a;}
    printf("],\"clockOnly\":{\"cpuNs\":%llu,\"wallNs\":%llu}}\n",(unsigned long long)cpu,(unsigned long long)wall);
}
