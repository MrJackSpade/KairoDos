# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import argparse
q=argparse.ArgumentParser();q.add_argument('template',type=Path);q.add_argument('output',type=Path);args=q.parse_args();p=args.output;p.mkdir(parents=True,exist_ok=True)
s=args.template.read_text();head=s[:s.index('call test_0_0')];head=head.replace('mov ebx,eax','mov ebx,eax\nmov dword ptr [csbase],eax',1).replace('cli\nlgdt','cli\nmov eax,dword ptr [csbase]\nadd eax,offset idt\nmov dword ptr [idtr+2],eax\nlidt [idtr]\nlgdt',1)
tail=s[s.index('.byte 0xea\n.long pm16'):s.index('.code32\ntest_0_0')];tail=tail.replace('sti\n','lidt [real_idtr]\nsti\n').replace('mov cx,6400','mov cx,2688').replace('cmp word ptr [idx],128','cmp word ptr [idx],2')
data=s[s.index('.code16\n.balign 8\ngdt:'):];data=data.replace('buffer: .space 6400','buffer: .space 2688')
a=[head,'cld','mov eax,dword ptr [csbase]','add eax,offset page_space+4095','and eax,0xfffff000','mov dword ptr [pd_phys],eax','mov edi,eax','sub edi,dword ptr [csbase]','mov dword ptr [pd_off],edi','xor eax,eax','mov ecx,2048','rep stosd','mov edi,dword ptr [pd_off]','mov eax,dword ptr [pd_phys]','add eax,4096','or eax,3','mov dword ptr [edi],eax','add edi,4096','mov eax,3','mov ecx,1024','pte_loop:','stosd','add eax,4096','loop pte_loop','mov edi,dword ptr [pd_off]','mov dword ptr [edi+4096+3076],0x300003','mov eax,dword ptr [pd_phys]','mov cr3,eax']
index=0
for condition in range(16):
 for kind in range(3):
  for sib in [False,True]:
   o=index*28
   a += ['mov edi,0x300000','sub edi,dword ptr [csbase]','mov byte ptr [edi],0x7f','mov ebx,dword ptr [pd_off]',f'mov dword ptr [ebx+4096+3072],{0x300000|[0,1,3][kind]}',f'mov dword ptr [current_output],offset buffer+{o}',f'mov dword ptr [resume_ip],offset resume_{index}',f'mov dword ptr [buffer+{o+20}],offset fault_{index}',f'mov dword ptr [buffer+{o+24}],'+('0x7f' if kind==2 else '0xdeadbeef'),'mov eax,dword ptr [pd_phys]','mov cr3,eax','mov eax,cr0','or eax,0x80010000','mov cr0,eax']
   if sib:a += ['sub edi,15','mov ecx,2']
   a += ['push 0x846','popfd',f'fault_{index}:',f'.byte 0x0f,{0x90+condition},'+('0x44,0x8f,7' if sib else '7'),f'resume_{index}:','pushfd','pop eax','mov ebx,dword ptr [current_output]', 'cmp dword ptr [ebx],0','jne recorded_'+str(index),'mov dword ptr [ebx+12],eax',f'recorded_{index}:','mov eax,cr0','and eax,0x7ffeffff','mov cr0,eax','mov edi,0x300000','sub edi,dword ptr [csbase]','movzx eax,byte ptr [edi]','mov dword ptr [ebx+16],eax']
   index+=1
a += [tail,'.code32','pf_handler:','pushad','mov ebx,dword ptr [current_output]','mov eax,dword ptr [esp+32]','mov dword ptr [ebx],eax','mov eax,dword ptr [esp+36]','mov dword ptr [ebx+4],eax','mov eax,cr2','mov dword ptr [ebx+8],eax','mov eax,dword ptr [esp+44]','mov dword ptr [ebx+12],eax','mov edi,0x301000','sub edi,dword ptr [csbase]','movzx eax,byte ptr [edi]','mov dword ptr [ebx+24],eax','mov edi,dword ptr [pd_off]','mov dword ptr [edi+4096+3072],0x300003','mov eax,dword ptr [pd_phys]','mov cr3,eax','popad','add esp,4','iretd',data,'csbase: .long 0','pd_phys: .long 0','pd_off: .long 0','resume_ip: .long 0','current_output: .long 0','idtr: .word 119','.long 0','real_idtr: .word 1023','.long 0','idt: .space 112','.word pf_handler,8','.byte 0,0x8e','.word 0','page_space: .space 12288']
(p/'setcc.s').write_text('\n'.join(a)+'\n')
