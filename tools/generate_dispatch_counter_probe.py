# SPDX-License-Identifier: GPL-2.0-or-later
import argparse
from pathlib import Path
parser=argparse.ArgumentParser(description='Generate a device test from the applied ticket62 diagnostic patch')
parser.add_argument('source_root',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args();root=args.source_root
arm=(root/'third_party/dosbox-staging/src/cpu/core_dynrec/risc_armv8le.h').read_text()
decoder=(root/'third_party/dosbox-staging/src/cpu/core_dynrec/decoder.h').read_text()
macros=arm[arm.index('// register mapping'):arm.index('// move a full register')]
imm=arm[arm.index('static void gen_mov_qword_to_reg_imm'):arm.index('// helper function for gen_mov_word_to_reg')]
start=decoder.index('static void gen_profile_dispatch_entry()');end=decoder.index('#endif',start)
helper=decoder[start:end]
source=r'''// SPDX-FileCopyrightText: 2002-2021 The DOSBox Team
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <sys/mman.h>
struct {uint8_t* pos;} cache;
void cache_addd(uint32_t value){memcpy(cache.pos,&value,4);cache.pos+=4;}
namespace kairo_dispatch_profile { FILE* output=nullptr;uint64_t entries=0; }
'''+macros+imm+helper+r'''
int main(){
 auto code=static_cast<uint8_t*>(mmap(nullptr,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));
 if(code==MAP_FAILED)return 2;
 unsigned checks=0;
 for(bool enabled:{false,true}){
  if(mprotect(code,4096,PROT_READ|PROT_WRITE))return 3;
  cache.pos=code;kairo_dispatch_profile::output=enabled?reinterpret_cast<FILE*>(1):nullptr;
  cache_addd(0xa9bf7bf3u); // stp x19, x30, [sp, #-16]!
  cache_addd(0xaa0003f3u); // mov x19, x0
  cache_addd(0xd51b4203u); // msr nzcv, x3
  gen_profile_dispatch_entry();
  cache_addd(0xd53b420au); // mrs x10, nzcv
  cache_addd(STR64_IMM(10,2,0));
  cache_addd(STR64_IMM(19,2,8));
  cache_addd(STR64_IMM(1,2,16));
  cache_addd(0xa8c17bf3u); // ldp x19, x30, [sp], #16
  cache_addd(RET);
  __builtin___clear_cache(reinterpret_cast<char*>(code),reinterpret_cast<char*>(cache.pos));
  if(mprotect(code,4096,PROT_READ|PROT_EXEC))return 4;
  auto run=reinterpret_cast<uint64_t(*)(uint64_t,uint64_t,uint64_t*,uint64_t)>(code);
  for(uint64_t initial:{0ull,0xfffffffeull,0xfffffffffffffffeull}){
   kairo_dispatch_profile::entries=initial;
   for(unsigned i=0;i<16000;++i){
    uint64_t state[3]={};const uint64_t flags=uint64_t(i&15)<<28;
    const auto result=run(0x123456789abcdef0ull,0xfedcba9876543210ull,state,flags);
    const auto expected=initial+(enabled?uint64_t(i)+1:0);
    if(result!=0x123456789abcdef0ull||state[0]!=flags||state[1]!=result||state[2]!=0xfedcba9876543210ull||kairo_dispatch_profile::entries!=expected){
     printf("FAIL enabled=%d i=%u\n",enabled,i);return 1;
    }
    ++checks;
   }
  }
 }
 printf("PASS %u actual ARM64 counter checks: enabled/disabled, 32/64-bit carry and wrap, NZCV, x0/x1/x19 preservation\n",checks);
 return 0;
}
'''
args.output.write_text(source)
