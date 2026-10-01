# SPDX-License-Identifier: GPL-2.0-or-later
import argparse,struct,hashlib,json
from pathlib import Path
q=argparse.ArgumentParser();q.add_argument('directory',type=Path);q.add_argument('--user',action='store_true');args=q.parse_args();p=args.directory
a=(p/'normal.bin').read_bytes();b=(p/'dynamic.bin').read_bytes();assert a==b and len(b)==96*28
cond=[True,False,False,True,True,False,True,False,False,True,True,False,True,False,True,False]
for i in range(96):
 c=i//6;kind=(i%6)//2;error,ip,cr2,flags,value,expected_ip,before=struct.unpack_from('<7I',b,i*28)
 assert error==([6,7,0] if args.user else [2,3,0])[kind],(i,'error',error)
 assert ip==(expected_ip if kind<2 else 0),(i,'restart EIP')
 assert cr2==(0x300000 if kind<2 else 0),(i,'CR2')
 assert flags&0x8d5==0x844,(i,'arithmetic flags',hex(flags))
 assert before==0x7f,(i,'write before fault handled')
 assert value==int(cond[c]),(i,'retried/successful value')
r={'result':'PASS','cases':96,'faults':64,'successfulWrites':32,'sha256':hashlib.sha256(b).hexdigest(),'coverage':['all 16 conditions','absent page: user write error 6' if args.user else 'absent page: supervisor write error 2','read-only page: user write error 7' if args.user else 'read-only page with CR0.WP: supervisor write error 3','writable page: no exception','register-indirect and scaled SIB+displacement addresses','saved fault EIP and CR2','flags preserved','alias reads target unchanged before repair','handler repairs PTE and retries instruction'],'limitations':['Does not validate abandoning a page fault without repairing its mapping.','Does not certify segment-limit faults or every possible guest/CPU mode.']}
(p/'fault-summary.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2))
