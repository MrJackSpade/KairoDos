// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cassert>
#include <cstdio>
#include "row_copy_measure.h"
int main(){
 using namespace row_measure;
 reset();compare=true;
 uint32_t pixels[]={1,2,999,3,4,888};
 frame(pixels,2,2,12);
 pixels[2]=123;pixels[5]=456;frame(pixels,2,2,12);
 pixels[4]=5;frame(pixels,2,2,12);
 frame(pixels,1,2,12);
 assert(frames==4&&rows==8&&changedRows==5&&duplicates==1&&resized==2&&bytes==56);
 assert(buckets[0]==1&&buckets[2]==1&&buckets[4]==2&&calls[Observer]==4);
 reset();compare=false;frame(pixels,2,2,12);
 assert(frames==1&&rows==2&&previous.empty()&&calls[Observer]==0);
 puts("PASS: visible-row changes, ignored padding, resize invalidation, reset, comparison-disabled path");
}
