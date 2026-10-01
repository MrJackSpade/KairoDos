// SPDX-License-Identifier: GPL-2.0-or-later
#define KAIRO_VIDEO_PROFILE
#include "frame_copy_profile.h"
#include <cassert>
#include <cstdio>
int main() {
    using namespace frame_copy_profile;
    uint32_t pixels[]={1,2,999,3,4,888}; // Two visible pixels and padding per row.
    reset(false);
    callback(pixels,2,2,12); window_copy(16);
    assert(read().callbacks==0 && read().window_copies==0);
    reset(true);
    callback(pixels,2,2,12);
    pixels[2]=123; pixels[5]=456;
    callback(pixels,2,2,12); // Padding is not visible content.
    pixels[4]=5;
    callback(pixels,2,2,12);
    callback(pixels,1,2,12); // New dimensions, not a claimed content difference.
    callback(nullptr,1,2,12); callback(pixels,4,2,12);
    window_copy(16); window_copy(8);
    auto c=read();
    assert(c.callbacks==4 && c.changed==1 && c.duplicates==1 && c.first_or_resized==2);
    assert(c.callback_bytes==56 && c.window_copies==2 && c.window_bytes==24);
    stop(); callback(pixels,1,2,12); window_copy(16);
    assert(read().callbacks==4 && read().window_bytes==24);
    puts(snapshot().c_str());
    reset(true); callback(pixels,1,2,12);
    assert(read().callbacks==1 && read().first_or_resized==1 && read().window_copies==0);
    puts("PASS: padding, changed pixels, resize, invalid input, stop, reset, disabled counts");
}
