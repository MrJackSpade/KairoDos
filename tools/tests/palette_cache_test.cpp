// SPDX-License-Identifier: GPL-2.0-or-later
#include "../../backend-dos/src/main/cpp/kairo_palette_cache.h"
#include <cassert>
#include <cstdio>
#include <random>
int main() {
    KairoPaletteCache cache;
    std::array<uint32_t,256> palette{};
    std::vector<uint8_t> pixels(1024*8);
    std::vector<uint32_t> rendered(1024*8);
    std::mt19937 random(12345);
    for(auto& p:palette) p=random();
    for(auto& p:pixels) p=random();
    size_t reused=0;
    for(uint64_t frame=1;frame<=100;++frame) {
        size_t width=frame<50?1024:640;
        if(frame%17==0) cache.Invalidate(); // reset/failed renderer update
        for(size_t row=0;row<8;++row) {
            if(frame%11==0 && row==4) { // generic blanking path bypasses cache
                for(size_t x=0;x<width;++x) rendered[row*width+x]=0;
                continue;
            }
            if(frame%3==0) pixels[row*width+random()%width]^=31;
            if(frame%7==0) palette[random()%256]^=0x00abcdef;
            bool same=cache.Remember(pixels.data()+row*width,palette.data(),width,8,row,frame);
            if(same) ++reused;
            for(size_t x=0;x<width;++x) {
                auto expected=palette[pixels[row*width+x]];
                if(same) assert(rendered[row*width+x]==expected);
                else rendered[row*width+x]=expected;
            }
        }
    }
    assert(reused>100);
    assert(!cache.Remember(pixels.data(),palette.data(),0,8,0,101));
    assert(!cache.Remember(pixels.data(),palette.data(),8,8,8,101));
    assert(!cache.Remember(pixels.data(),palette.data(),8192,8192,0,101));
    palette.fill(0); pixels.assign(pixels.size(),0);
    assert(!cache.Remember(pixels.data(),palette.data(),8,1,0,102));
    assert(cache.Remember(pixels.data(),palette.data(),8,1,0,103));
    palette[0]=0x123456;
    assert(!cache.Remember(pixels.data(),palette.data(),8,1,0,104));
    assert(!cache.Remember(pixels.data(),palette.data(),8,1,0,106));
    cache.Invalidate();
    assert(!cache.Remember(pixels.data(),palette.data(),8,1,0,107));
    std::printf("Indexed renderer history: OK (%zu verified reused lines)\n",reused);
}
