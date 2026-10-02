# SPDX-License-Identifier: GPL-2.0-or-later
"""Check direct-BL reach for recognizable absolute ARM64 calls in captured blocks."""
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description=__doc__);p.add_argument('metadata',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
import json,struct
counts=dict(callSites=0,withinBlRange=0);minimum=None;maximum=0;targets={};trailing=0
stream=args.metadata.open()
for line in stream:
 try:row=json.loads(line)
 except json.JSONDecodeError:
  assert stream.read()=='', 'Only an incomplete final snapshot record may be excluded'
  trailing+=1;break
 if row.get('event')!='block' or 'hostCodeHex' not in row:continue
 code=bytes.fromhex(row['hostCodeHex']);words=struct.unpack('<'+'I'*(len(code)//4),code[:len(code)//4*4])
 for i in range(len(words)-4):
  w=words[i];reg=w&31
  if w&0xffe00000!=0xd2800000:continue
  if any(words[i+j]&0xffe0001f!=(0xf2800000|(j<<21)|reg) for j in (1,2,3)):continue
  if words[i+4]!=(0xd63f0000|(reg<<5)):continue
  target=sum(((words[i+j]>>5)&0xffff)<<(16*j) for j in range(4));pc=row['host']+i*4;delta=target-pc
  counts['callSites']+=1;counts['withinBlRange']+=(-0x8000000<=delta<0x8000000 and delta%4==0)
  minimum=abs(delta) if minimum is None else min(minimum,abs(delta));maximum=max(maximum,abs(delta))
  targets[hex(target)]=targets.get(hex(target),0)+1
result=dict(**counts,minAbsoluteDisplacementBytes=minimum,maxAbsoluteDisplacementBytes=maximum,uniqueTargets=len(targets),incompleteTrailingRecordExcluded=trailing,limits='Translation-site count from existing diagnostic capture, not execution counts or a normal-PGO measurement. ASLR/layout may differ by process; no relocation or memory-layout change is proposed.')
args.output.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
