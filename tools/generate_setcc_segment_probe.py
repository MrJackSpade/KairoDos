# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import argparse
q=argparse.ArgumentParser();q.add_argument('template',type=Path);q.add_argument('output',type=Path);args=q.parse_args();p=args.output;p.mkdir(parents=True,exist_ok=True)
s=args.template.read_text();head=s[:s.index('call test_0_0')];head=head.replace('mov ebx,eax','mov ebx,eax\nmov dword ptr [csbase],eax',1)
head=head.replace('mov dx,offset filename','mov eax,dword ptr [csbase]\nadd eax,offset target\nmov word ptr [gdt+34],ax\nshr eax,16\nmov byte ptr [gdt+36],al\nmov byte ptr [gdt+39],ah\nmov dx,offset filename')
head=head.replace('cli\nlgdt','cli\nmov eax,dword ptr [csbase]\nadd eax,offset idt\nmov dword ptr [idtr+2],eax\nlidt [idtr]\nlgdt',1)
tail=s[s.index('.byte 0xea\n.long pm16'):s.index('.code32\ntest_0_0')];tail=tail.replace('sti\n','lidt [real_idtr]\nsti\n').replace('mov cx,6400','mov cx,1152')
data=s[s.index('.code16\n.balign 8\ngdt:'):].replace('gdtr: .word 31','.word 0,0\n.byte 0,0x92,0x40,0\ngdtr: .word 39').replace('buffer: .space 6400','buffer: .space 1152')
a=[head]
for c in range(16):
 for kind in range(3):
  i=c*3+kind;o=i*24;off=1 if kind==1 else 0
  a += [f'mov byte ptr [gdt+37],{0x90 if kind==2 else 0x92}','mov ax,32','mov fs,ax','mov dword ptr [target],0xaabbccdd',f'mov dword ptr [current_output],offset buffer+{o}',f'mov dword ptr [resume_ip],offset resume_{i}',f'mov dword ptr [buffer+{o+16}],offset fault_{i}',f'mov dword ptr [buffer+{o+20}],{off}','push 0x846','popfd',f'fault_{i}:',f'.byte 0x64,0x0f,{0x90+c},5',f'.long {off}',f'resume_{i}:','pushfd','pop eax','mov ebx,dword ptr [current_output]','cmp dword ptr [ebx],0','jne saved_'+str(i),'mov dword ptr [ebx+8],eax',f'saved_{i}:','mov eax,dword ptr [target]','mov dword ptr [ebx+12],eax']
a += [tail,'.code32','gp_handler:','pushad','mov ebx,dword ptr [current_output]','mov eax,dword ptr [esp+32]','or eax,0x80000000','mov dword ptr [ebx],eax','mov eax,dword ptr [esp+36]','mov dword ptr [ebx+4],eax','mov eax,dword ptr [esp+44]','mov dword ptr [ebx+8],eax','mov eax,dword ptr [resume_ip]','mov dword ptr [esp+36],eax','popad','add esp,4','iretd',data,'csbase: .long 0','resume_ip: .long 0','current_output: .long 0','idtr: .word 111','.long 0','real_idtr: .word 1023','.long 0','idt: .space 104','.word gp_handler,8','.byte 0,0x8e','.word 0']
(p/'setcc.s').write_text('\n'.join(a)+'\n')

