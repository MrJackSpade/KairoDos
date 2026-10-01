# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import argparse
q=argparse.ArgumentParser();q.add_argument('output',type=Path);q.add_argument('--lazy',action='store_true');q.add_argument('--prefix',default='none',choices=['none','66','67','66-67']);args=q.parse_args();args.output.mkdir(parents=True,exist_ok=True)
a=['.intel_syntax noprefix','.code16','.global _start','_start:','push cs','pop ds','mov ax,cs','mov word ptr [real_segment],ax','movzx eax,ax','shl eax,4','mov ebx,eax','add eax,offset gdt','mov dword ptr [gdtr+2],eax']
for n in [8,16,24]:a += [f'mov word ptr [gdt+{n+2}],bx','mov eax,ebx','shr eax,16',f'mov byte ptr [gdt+{n+4}],al',f'mov byte ptr [gdt+{n+7}],ah']
a += ['mov dx,offset filename','xor cx,cx','mov ah,0x3c','int 0x21','mov word ptr [handle],ax','round:','mov bx,word ptr [idx]','mov ax,word ptr [flagtable+bx]','mov word ptr [flagword],ax','movzx ebx,bx','shl ebx,1','mov eax,dword ptr [operands+ebx]','mov dword ptr [operand],eax','cli','lgdt [gdtr]','mov eax,cr0','or eax,1','mov cr0,eax','.byte 0xea','.word pm32','.word 8','.code32','pm32:','mov ax,16','mov ds,ax','mov es,ax','mov ss,ax','mov esp,0xff00','mov dword ptr [outptr],offset buffer']
for c in range(16):
 for d in range(10):a += [f'call test_{c}_{d}']
a += ['.byte 0xea','.long pm16','.word 24','.code16','pm16:','mov eax,cr0','and eax,0xfffffffe','mov cr0,eax','.byte 0xea','.word real_return','real_segment: .word 0','real_return:','mov ax,cs','mov ds,ax','mov es,ax','mov ss,ax','mov sp,0xff00','sti','mov bx,word ptr [handle]','mov dx,offset buffer','mov cx,6400','mov ah,0x40','int 0x21','add word ptr [idx],2','cmp word ptr [idx],128','jne round','mov bx,word ptr [handle]','mov ah,0x3e','int 0x21','mov ax,0x4c00','int 0x21','.code32']
for c in range(16):
 for d in range(10):
  a += [f'test_{c}_{d}:']
  for reg,val in [('eax',0x12345678),('ecx',0x23456789),('edx',0x3456789a),('ebx',0x456789ab),('ebp',0x56789abc),('esi',0x6789abcd),('edi',0x789abcde)]:a += [f'mov {reg},{val}']
  a += ['mov dword ptr [target],0xaabbccdd','movzx edi,word ptr [flagword]','push edi','popfd','mov edi,0x789abcde']
  if args.lazy:a += ['cmp eax,dword ptr [operand]']
  if args.prefix!='none':a += ['.byte '+','.join('0x'+v for v in args.prefix.split('-'))]
  if d<8:a += [f'.byte 0x0f,{0x90+c},{0xc0+d}']
  else:a += [f'.byte 0x0f,{0x90+c},'+('6' if '67' in args.prefix else '5'),('.word ' if '67' in args.prefix else '.long ')+f'target+{d-8}']
  a += ['pushfd','pushad','mov esi,esp','mov edi,dword ptr [outptr]','mov ecx,9','rep movsd','mov eax,dword ptr [target]','stosd','mov dword ptr [outptr],edi','add esp,36','ret']
a += ['.code16','.balign 8','gdt:','.quad 0','.word 0xffff,0','.byte 0,0x9a,0x40,0','.word 0xffff,0','.byte 0,0x92,0x40,0','.word 0xffff,0','.byte 0,0x9a,0,0','gdtr: .word 31','.long 0','handle: .word 0','idx: .word 0','flagword: .word 0','operand: .long 0','target: .long 0','outptr: .long 0','filename: .asciz "RESULT.BIN"','flagtable:']
for i in range(64):a += ['.word '+str(2|sum(bit for n,bit in enumerate([1,4,16,64,128,2048]) if i&(1<<n)))]
a += ['operands:']
for v in [0,1,0xffffffff,0x12345678,0x12345679,0x12345677,0x80000000,0x7fffffff]*8:a += [f'.long {v}']
a += ['buffer: .space 6400']
(args.output/'setcc.s').write_text('\n'.join(a)+'\n')
