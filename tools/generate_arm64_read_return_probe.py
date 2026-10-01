# SPDX-License-Identifier: GPL-2.0-or-later
"""Probe the archived register-return read experiment after applying its patch."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('source_root',type=Path)
p.add_argument('output',type=Path)
args=p.parse_args()
root=args.source_root/'third_party/dosbox-staging/src/cpu/core_dynrec'
a=(root/'risc_armv8le.h').read_text();d=(root/'decoder_basic.h').read_text()
def function(s,name):
 start=s.index(name);start=s.rfind('\n',0,start)+1;brace=s.index('{',start);level=1;end=brace+1
 while level:
  level+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]+'\n'
macros=a[a.index('// register mapping'):a.index('// move a full register')]
original=d[d.index('bool DRC_CALL_CONV mem_readb_checked_drc'):d.index('bool DRC_CALL_CONV mem_writeb_checked_drc')]
new=d[d.index('struct DynrecReadResult'):d.index('#endif',d.index('struct DynrecReadResult'))]
head=r'''// SPDX-FileCopyrightText: 2002-2021 The DOSBox Team
// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <sys/mman.h>
#include <initializer_list>
#define C_TARGET_CPU_ARM 1
#define DRC_CALL_CONV
#define DRC_FC
using Bitu=uintptr_t;using Bits=intptr_t;using PhysPt=uint32_t;using HostPt=uint8_t*;
struct {uint8_t* pos;} cache;
void cache_addd(uint32_t x){memcpy(cache.pos,&x,4);cache.pos+=4;}
void cache_addd(uint32_t x,const uint8_t* p){memcpy(const_cast<uint8_t*>(p),&x,4);}
struct {Bitu readdata;} core_dynrec;
uint8_t ram[8192];bool cached[2],readable[2];unsigned handlers,crossings,bytes_seen;uint32_t fault_address;
uint8_t host_readb(const uint8_t*p){return *p;}
uint16_t host_readw(const uint8_t*p){uint16_t v;memcpy(&v,p,2);return v;}
uint32_t host_readd(const uint8_t*p){uint32_t v;memcpy(&v,p,4);return v;}
HostPt get_tlb_read(PhysPt a){return a<8192 && cached[a>>12] && readable[a>>12]?ram:nullptr;}
bool checked(PhysPt a,void*out,unsigned size){
 ++handlers;
 for(unsigned i=0;i<size;++i){uint32_t address=a+i;
  if(address>=8192 || !readable[address>>12]){fault_address=address;return true;}
  ((uint8_t*)out)[i]=ram[address];++bytes_seen;
 }return false;
}
struct Handler{
 bool readb_checked(PhysPt a,uint8_t*p){return checked(a,p,1);}
 bool readw_checked(PhysPt a,uint16_t*p){return checked(a,p,2);}
 bool readd_checked(PhysPt a,uint32_t*p){return checked(a,p,4);}
}handler;
Handler*get_tlb_readhandler(PhysPt){return &handler;}
bool mem_unalignedreadw_checked(PhysPt a,uint16_t*p){++crossings;return checked(a,p,2);}
bool mem_unalignedreadd_checked(PhysPt a,uint32_t*p){++crossings;return checked(a,p,4);}
'''
emitter=function(a,'static void gen_mov_regs(')+function(a,'static void inline gen_call_function_raw(')+function(a,'static const uint8_t* gen_create_branch_long_nonzero(')
emitter+='const uint8_t* fault_branch;\nstatic void dyn_check_exception(HostReg r){fault_branch=gen_create_branch_long_nonzero(r,false);}\n'
for name in ('dyn_read_byte','dyn_read_byte_canuseword','dyn_read_word'):
 emitter+=function(d,'static void '+name+'(')
tail=r'''
int main(){
 for(unsigned i=0;i<sizeof(ram);++i)ram[i]=(i*173+97)^(i>>5);
 auto code=(uint8_t*)mmap(nullptr,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 if(code==MAP_FAILED)return 1;
 uint64_t checks=0;
 for(unsigned test=0;test<8;++test){
  unsigned variant=test%4;bool same_register=test>=4;
  if(mprotect(code,4096,PROT_READ|PROT_WRITE))return 2;
  cache.pos=code;
  cache_addd(0xa9bf7bf3); // stp x19,x30,[sp,#-16]!
  cache_addd(MOVZ64(HOST_r19,0x5678,0));cache_addd(MOVK64(HOST_r19,0x1234,16));
  auto destination=same_register?HOST_r0:HOST_r19;
  if(variant==0)dyn_read_byte(FC_OP1,destination);
  else if(variant==1)dyn_read_byte_canuseword(FC_OP1,destination);
  else dyn_read_word(FC_OP1,destination,variant==3);
  cache_addd(B_FWD(cache.pos-fault_branch),fault_branch);
  gen_mov_regs(HOST_r0,destination);
  cache_addd(0xa8c17bf3);cache_addd(0xd65f03c0);
  __builtin___clear_cache((char*)code,(char*)cache.pos);
  if(mprotect(code,4096,PROT_READ|PROT_EXEC))return 3;
  auto fn=(DynrecReadResult(*)(uint32_t))code;
  unsigned size=variant<2?1:variant==2?2:4;
  uint32_t mask=size==4?0xffffffffu:(1u<<(8*size))-1;
  for(unsigned mode=0;mode<16;++mode){
   cached[0]=mode&1;cached[1]=mode&2;readable[0]=mode&4;readable[1]=mode&8;
   for(unsigned index=0;index<8200;++index){
    uint32_t address=index<8196?index:0xffffffffu-(index-8196);
    handlers=crossings=bytes_seen=fault_address=0;core_dynrec.readdata=0xaabbccdd11223344ull;
    bool fail=size==1?mem_readb_checked_drc(address):size==2?mem_readw_checked_drc(address):mem_readd_checked_drc(address);
    uint32_t value=core_dynrec.readdata&mask;auto h=handlers,c=crossings,b=bytes_seen,f=fault_address;
    handlers=crossings=bytes_seen=fault_address=0;core_dynrec.readdata=0xaabbccdd11223344ull;
    auto result=fn(address);
    if(result.fault!=uint64_t(fail)||(!fail && result.value!=value)||(fail && !same_register && result.value!=0x12345678u)||h!=handlers||c!=crossings||b!=bytes_seen||f!=fault_address){
     printf("FAIL variant=%u mode=%u addr=%x value=%llx fault=%llu expected=%x/%u handlers=%u/%u cross=%u/%u\n",variant,mode,address,(unsigned long long)result.value,(unsigned long long)result.fault,value,fail,handlers,h,crossings,c);return 4;
    }++checks;
   }
  }
 }
 printf("%llu generated-read checks passed: widths, cached/handler paths, page crossings, partial faults, address wrap, fault destination preservation\n",(unsigned long long)checks);
}
'''
args.output.write_text(head+macros+original+new+emitter+tail)
