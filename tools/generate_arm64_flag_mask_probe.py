# SPDX-License-Identifier: GPL-2.0-or-later
"""Generate exact ZF/SF comparisons from the isolated flag-mask candidate."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description=__doc__);p.add_argument('source_root',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
base=args.source_root/'third_party/dosbox-staging/src/cpu'
s=(base/'flags.cpp').read_text();l=(base/'lazyflags.h').read_text()
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
ops=(base/'core_dynrec/operators.h').read_text()
helpers=ops[ops.index('// Only result-derived ZF/SF cases'):ops.index('static uint32_t DRC_CALL_CONV dynrec_get_of')]
functions=body(s,'uint32_t get_ZF')+'\n'+body(s,'uint32_t get_SF')+'\n'+helpers
tail=r'''
int main(){
 uint32_t state=0x39acd582;unsigned checks=0;
 for(unsigned type=0;type<256;++type)for(unsigned n=0;n<4096;++n){
  state^=state<<13;state^=state>>17;state^=state<<5;
  lflags.type=type;lflags.res.dword[0]=state;
  if(n<32)lflags.res.dword[0]=uint32_t(1)<<n;
  if(n==32)lflags.res.dword[0]=0;
  if(n==33)lflags.res.dword[0]=0xffffffff;
  for(uint32_t f:{0u,0x40u,0x80u,0xc0u,0xffffffffu}){
   flags=f;logs=0;auto z=get_ZF(),sg=get_SF(),expected_logs=logs;
   logs=0;auto cz=dynrec_result_zf(),cs=dynrec_result_sf();
   if(z!=cz||sg!=cs||logs!=expected_logs){printf("FAIL type=%u result=%x flags=%x\n",type,lflags.res.dword[0],f);return 1;}++checks;
  }
 }
 printf("%u exact ZF/SF value and invalid-type diagnostic comparisons passed\n",checks);
}
'''
args.output.write_text(head+functions+tail)
