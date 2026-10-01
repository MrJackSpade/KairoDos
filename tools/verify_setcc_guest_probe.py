# SPDX-License-Identifier: GPL-2.0-or-later
import argparse
from pathlib import Path
import struct,json,hashlib,difflib
parser=argparse.ArgumentParser();parser.add_argument('capture_directory',type=Path);args=parser.parse_args();p=args.capture_directory;a=(p/'normal.bin').read_bytes();b=(p/'dynamic.bin').read_bytes();assert a==b and len(b)==10240*40
base=[0x789abcde,0x6789abcd,0x56789abc,None,0x456789ab,0x3456789a,0x23456789,0x12345678]
for i in range(64):
 f=0x202|sum(bit for n,bit in enumerate([1,4,16,64,128,2048]) if i&(1<<n));cf,pf,af,zf,sf,of=[bool(f&bit) for bit in [1,4,16,64,128,2048]]
 cond=[of,not of,cf,not cf,zf,not zf,cf or zf,not(cf or zf),sf,not sf,pf,not pf,sf!=of,sf==of,zf or sf!=of,not zf and sf==of]
 for c in range(16):
  for d in range(10):
   n=(i*160+c*10+d);v=struct.unpack_from('<10I',b,n*40);expected=base.copy();mem=0xaabbccdd;truth=int(cond[c])
   if d<8:
    r=[7,6,5,4][d%4];shift=8*(d//4);expected[r]=(expected[r]&~(255<<shift))|(truth<<shift)
   else:shift=8*(d-8);mem=(mem&~(255<<shift))|(truth<<shift)
   assert all(e is None or e==v[j] for j,e in enumerate(expected)),(n,'registers')
   assert v[8]&0x8d5==f&0x8d5,(n,'flags');assert v[9]==mem,(n,'memory')
r={'cases':10240,'result':'PASS','normalVsDynamic':'byte-identical full snapshots','independentOracle':'all non-stack registers, condition result, arithmetic flags and destination memory','snapshotSha256':hashlib.sha256(b).hexdigest(),'coverage':'16-bit real mode; materialized flags; every condition, all 64 arithmetic flag combinations, eight byte registers and two direct memory destinations','remaining':['lazy flags','16/32-bit prefixes/address forms','protected-mode fault and SMC checks','normal-build A/B/A benchmark']}
(p/'correctness-initial.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2))
