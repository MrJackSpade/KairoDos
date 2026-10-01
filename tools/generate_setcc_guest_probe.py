# SPDX-License-Identifier: GPL-2.0-or-later
import argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('output_directory',type=Path);args=parser.parse_args();p=args.output_directory;p.mkdir(parents=True,exist_ok=True)
regs=['al','cl','dl','bl','ah','ch','dh','bh','byte ptr [target]','byte ptr [target+1]']
a=['.intel_syntax noprefix','.code16','.global _start','_start:','mov dx, offset filename','xor cx,cx','mov ah,0x3c','int 0x21','mov word ptr [handle],ax','round:','mov bx,word ptr [idx]','mov ax,word ptr [flagtable+bx]','mov word ptr [flagword],ax']
for c in range(16):
 for d in range(10):a += [f'call test_{c}_{d}']
a += ['add word ptr [idx],2','cmp word ptr [idx],128','jne round','mov bx,word ptr [handle]','mov ah,0x3e','int 0x21','mov ax,0x4c00','int 0x21']
for c in range(16):
 for d,reg in enumerate(regs):
  a += [f'test_{c}_{d}:']
  for r,v in [('eax',0x12345678),('ecx',0x23456789),('edx',0x3456789a),('ebx',0x456789ab),('ebp',0x56789abc),('esi',0x6789abcd),('edi',0x789abcde)]:a += [f'mov {r},{v}']
  a += ['mov dword ptr [target],0xaabbccdd','push word ptr [flagword]','popf']
  # Explicit encoding retains every condition, including aliases.
  if d<8:a += [f'.byte 0x0f,{0x90+c},{0xc0+d}']
  else:a += [f'.byte 0x0f,{0x90+c},0x06',f'.word target+{d-8}']
  a += ['pushfd','pushad','mov dx,sp','mov cx,36','call write','add sp,36','mov dx,offset target','mov cx,4','call write','ret']
a += ['write:','mov bx,word ptr [handle]','mov ah,0x40','int 0x21','ret','handle: .word 0','idx: .word 0','flagword: .word 0','target: .long 0','filename: .asciz "RESULT.BIN"','flagtable:']
for i in range(64):a += ['.word '+str(0x202|sum(bit for n,bit in enumerate([1,4,16,64,128,2048]) if i&(1<<n)))]
(p/'setcc.s').write_text('\n'.join(a)+'\n')
