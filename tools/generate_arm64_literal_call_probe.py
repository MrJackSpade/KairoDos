# SPDX-License-Identifier: GPL-2.0-or-later
"""Generate baseline/candidate ARM64 call and lazy-flags patch comparisons."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('baseline',type=Path);p.add_argument('candidate',type=Path);p.add_argument('output',type=Path)
args=p.parse_args()
import re
base=Path('third_party/dosbox-staging/src/cpu')
a=(args.candidate/base/'core_dynrec/risc_armv8le.h').read_text()
orig=(args.baseline/base/'core_dynrec/risc_armv8le.h').read_text()
def body(s,token):
 start=s.rfind('\n',0,s.index(token))+1; end=s.index('{',start)+1;depth=1
 while depth: depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
flags=(args.baseline/base/'lazyflags.h').read_text();start=flags.index('enum {');flags=flags[start:flags.index('};',start)+2]
macros=a[a.index('// register mapping'):a.index('// move a full register')]
header=r'''// SPDX-FileCopyrightText: 2002-2021 The DOSBox Team
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <initializer_list>
#include <sys/mman.h>
#define DRC_FLAGS_INVALIDATION_DCODE 1
using Bitu=uintptr_t;using Bits=intptr_t;
struct {uint8_t* pos;} cache;
void cache_addd(uint32_t w,const uint8_t* p){memcpy((void*)p,&w,4);}
void cache_addd(uint32_t w){cache_addd(w,cache.pos);cache.pos+=4;}
'''
functions=body(a,'static void gen_write_literal_call')+'\n'+body(a,'static void inline gen_call_function_raw')+'\n'+body(a,'static void gen_fill_function_ptr')+'\n'+body(orig,'static void inline gen_call_function_raw').replace('gen_call_function_raw','original_call')+'\n'+body(orig,'static void gen_fill_function_ptr').replace('gen_fill_function_ptr','original_fill')
tail=r'''
int main(){
 auto p=(uint8_t*)mmap(nullptr,8192,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(p==MAP_FAILED)return 1;
 unsigned checks=0;
 for(unsigned alignment:{0u,4u}) for(unsigned mode=0;mode<2;++mode)
 for(unsigned type=0;type<=t_LASTFLAG;++type){
  if(mprotect(p,8192,PROT_READ|PROT_WRITE))return 2;
  for(unsigned candidate=0;candidate<2;++candidate){
   auto start=p+candidate*4096+alignment;cache.pos=start;
   cache_addd(0xa9bf7bf3); // preserve x19,lr
   cache_addd(0xd51b4203); // msr nzcv,x3
   auto site=cache.pos;
   if(candidate)gen_call_function_raw(p+2048);else original_call(p+2048);
   if(cache.pos!=site+20)return 3;
   if(mode){if(candidate)gen_fill_function_ptr(site,p+2056,type);else original_fill(site,p+2056,type);}
   // x2/x3 can be scratch in arithmetic patches; use x4 for NZCV output.
   cache_addd(0xd53b420a); // mrs x10,nzcv
   cache_addd(0xf900008a); // str x10,[x4]
   cache_addd(0xa8c17bf3);cache_addd(0xd65f03c0);
  }
  cache.pos=p+2048;cache_addd(0x8b010000);cache_addd(0xd65f03c0); // add
  cache_addd(0xcb010000);cache_addd(0xd65f03c0); // subtract
  __builtin___clear_cache((char*)p,(char*)p+8192);
  if(mprotect(p,8192,PROT_READ|PROT_EXEC))return 4;
  using Fn=uint64_t(*)(uint64_t,uint64_t,uint64_t,uint64_t,uint64_t*);
  auto ref=(Fn)(p+alignment);auto cand=(Fn)(p+4096+alignment);
  for(unsigned f=0;f<16;++f)for(uint64_t v:{0ull,1ull,0x80ull,0x8000ull,0xffffffffull,0xffffffffffffffffull})
  for(unsigned shift:{0u,1u,7u,16u,31u}){
   uint64_t rf=0,cf=0;auto r=ref(v,shift,shift,uint64_t(f)<<28,&rf);auto c=cand(v,shift,shift,uint64_t(f)<<28,&cf);
   if(r!=c||rf!=cf){printf("FAIL align=%u mode=%u type=%u r=%llx c=%llx\n",alignment,mode,type,(unsigned long long)r,(unsigned long long)c);return 5;}++checks;
  }
 }
 printf("%u original/candidate native call and lazy-flags patch checks passed; both literal alignments and all NZCV patterns\n",checks);
}
'''
args.output.write_text(header+flags+'\n'+macros+functions+tail)
