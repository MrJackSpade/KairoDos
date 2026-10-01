# SPDX-License-Identifier: GPL-2.0-or-later
"""Generate an Android correctness probe from the archived compact-call experiment.
Apply docs/benchmarks/arm64-compact-calls-prototype.patch in an isolated checkout.
The extracted instruction definitions retain DOSBox's GPL-2.0-or-later license.
"""
from pathlib import Path
import argparse
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('source_root', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
s = (a.source_root / 'third_party/dosbox-staging/src/cpu/core_dynrec/risc_armv8le.h').read_text()
macros = s[s.index('// register mapping'):s.index('// move a full register')]
calls = s[s.index('static void inline gen_call_function_raw'):s.index('// generate a call to a function with paramcount')]
head = r'''// SPDX-FileCopyrightText: 2002-2021 The DOSBox Team
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <initializer_list>
#include <sys/mman.h>
using Bitu=uintptr_t; using Bits=intptr_t;
struct {uint8_t* pos;} cache;
void cache_addd(uint32_t w){memcpy(cache.pos,&w,4);cache.pos+=4;}
'''
tail = r'''int main(){
 auto p=(uint8_t*)mmap(nullptr,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(p==MAP_FAILED)return 1;
 unsigned checks=0;
 for(int64_t pages: {-(1ll<<20)-1,-(1ll<<20),-1ll,0ll,1ll,(1ll<<20)-1,(1ll<<20)})
 for(unsigned offset: {0u,4u,2048u,4092u}){
  cache.pos=p; auto base=(uintptr_t)p & ~uintptr_t(4095);
  auto target=base+pages*4096+offset;
  gen_call_memory_function((void*)target);
  uint32_t w[5]={};memcpy(w,p,cache.pos-p);
  bool compact=pages>=-(1ll<<20)&&pages<(1ll<<20);
  if(cache.pos-p!=(compact?12:20))return 2;
  uintptr_t decoded=0;
  if(compact){
   auto imm=((w[0]>>29)&3)|(((w[0]>>5)&0x7ffff)<<2);
   int64_t signed_imm=(imm&(1<<20))?int64_t(imm)-(1<<21):imm;
   decoded=base+signed_imm*4096+((w[1]>>10)&4095);
  }else{for(unsigned i=0;i<4;++i)decoded|=uint64_t((w[i]>>5)&65535)<<(16*i);}
  if(decoded!=target)return 3; ++checks;
 }
 for(bool fixed:{false,true}){
  if(mprotect(p,4096,PROT_READ|PROT_WRITE))return 4;
  cache.pos=p;
  cache_addd(0xa9bf7bf3); // stp x19,x30,[sp,#-16]!
  cache_addd(0xd51b4203); // msr nzcv,x3
  if(fixed)gen_call_function_raw(p+1024);else gen_call_memory_function(p+1024);
  cache_addd(0xd53b420a); // mrs x10,nzcv
  cache_addd(0xf900004a); // str x10,[x2]
  cache_addd(0xa8c17bf3); // ldp x19,x30,[sp],#16
  cache_addd(0xd65f03c0); // ret
  cache.pos=p+1024;
  cache_addd(0x8b010000); // add x0,x0,x1 (no flags)
  cache_addd(0xd65f03c0);
  __builtin___clear_cache((char*)p,(char*)p+4096);
  if(mprotect(p,4096,PROT_READ|PROT_EXEC))return 5;
  auto fn=(uint64_t(*)(uint64_t,uint64_t,uint64_t*,uint64_t))p;
  for(unsigned f=0;f<16;++f)for(uint64_t a:{0ull,1ull,0xffffffffull,0xffffffffffffffffull}){
   uint64_t flags=0;auto result=fn(a,17,&flags,uint64_t(f)<<28);
   if(result!=a+17||flags!=(uint64_t(f)<<28))return 6;++checks;
  }
 }
 printf("%u checks passed: encoding boundaries, fallback, native compact/fixed calls, NZCV\n",checks);
 return 0;
}
'''
a.output.write_text(head + macros + calls + tail)
