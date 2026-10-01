# SPDX-License-Identifier: GPL-2.0-or-later
"""Generate exact CMP/TEST condition comparisons from an isolated patched tree."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description=__doc__);p.add_argument('source_root',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
import re
b=args.source_root/'third_party/dosbox-staging/src/cpu';s=(b/'flags.cpp').read_text();l=(b/'lazyflags.h').read_text()
def body(text,token):
 start=text.rfind('\n',0,text.index(token))+1;end=text.index('{',start)+1;depth=1
 while depth:depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
enum=l[l.index('enum {'):l.index('};',l.index('enum {'))+2]
head=r'''// SPDX-FileCopyrightText: 2002-2021 The DOSBox Team
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>
#include <initializer_list>
union GenReg32{uint8_t byte[4];uint16_t word[2];uint32_t dword[1];};
'''+l[l.index('struct LazyFlags {'):l.index('extern LazyFlags')]+r'''
LazyFlags lflags;
uint32_t flags=0;unsigned logs=0;
#define BL_INDEX 0
#define W_INDEX 0
#define DW_INDEX 0
#define GETFLAG(f) (flags & FLAG_##f)
#define FLAG_ZF 0x40u
#define FLAG_SF 0x80u
void log_error(const char*,...){++logs;}
#define LOG(...) log_error
'''+l[l.index('inline constexpr uint8_t &lf_var1b'):l.index('// many places')]+enum+'\n'

head+='''
#define FLAG_CF 1u
#define FLAG_OF 0x800u
#define DRC_CALL_CONV
#define DRC_FLAGS_INVALIDATION
using Bitu=uintptr_t;
inline uint8_t lf_var2b_minus_one(){return lf_var2b-1;}
'''
d=(b/'core_dynrec/decoder_basic.h').read_text();start=d.index('enum BranchTypes');head+=d[start:d.index('};',start)+2]+'\n'
for f in ('CF','OF','ZF','SF'):head+=body(s,'uint32_t get_'+f)+'\n'
head+='''
unsigned mf_functions_num=0;
struct {Bitu ftype;} mf_functions[64];
void* selected=nullptr;bool fallback=false;
void gen_call_function_raw(void* p){selected=p;}
void dyn_branchflag_to_reg(BranchTypes){fallback=true;}
'''
ops=(b/'core_dynrec/operators.h').read_text()
head+=ops[ops.index('// The pending flag-optimization queue'):ops.index('static void DRC_CALL_CONV dynrec_mul_byte')]
tail=r'''
int main(){
 uint64_t checks=0;uint32_t rng=0x159abedf;
 for(unsigned count=0;count<3;++count)for(unsigned type=0;type<=t_LASTFLAG;++type)for(unsigned cond=0;cond<16;++cond){
  mf_functions_num=count;mf_functions[0].ftype=type;selected=nullptr;fallback=false;
  dyn_branchflag_from_producer(BranchTypes(cond));
  bool eligible=count==1 && (type==t_CMPb||type==t_CMPw||type==t_CMPd||type==t_TESTb||type==t_TESTw||type==t_TESTd) && cond!=BR_P && cond!=BR_NP;
  if(bool(selected)!=eligible||fallback==eligible||mf_functions_num!=count)return 1;
  if(!eligible)continue;
  auto fn=(uint32_t(*)())selected;
  for(unsigned n=0;n<32768;++n){
   auto next=[&](){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return rng;};
   lflags.type=type;lflags.var1.dword[0]=next();lflags.var2.dword[0]=next();lflags.res.dword[0]=next();flags=next();
   if(n<32)lflags.res.dword[0]=uint32_t(1)<<n;
   if(n==32)lflags.res.dword[0]=0;
   if(n==33)lflags.res.dword[0]=~uint32_t(0);
   uint32_t cf=get_CF(),of=get_OF(),zf=get_ZF(),sf=get_SF();
   uint32_t expected[]={of,!of,cf,!cf,zf,!zf,cf||zf,!cf&&!zf,sf,!sf,0,0,(sf!=0)!=(of!=0),(sf!=0)==(of!=0),zf||((sf!=0)!=(of!=0)),!zf&&((sf!=0)==(of!=0))};
   auto actual=fn();if(actual!=expected[cond]){printf("FAIL type=%u cond=%u actual=%x expected=%x\n",type,cond,actual,expected[cond]);return 2;}++checks;
  }
 }
 printf("%llu exact known-producer condition comparisons passed; all queue sizes/types/conditions checked for fallback selection\n",(unsigned long long)checks);
}
'''
args.output.write_text(head+tail)
