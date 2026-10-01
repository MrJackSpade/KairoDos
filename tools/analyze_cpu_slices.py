# SPDX-License-Identifier: GPL-2.0-or-later
import json,sys
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description="Validate and summarize the ticket #61 diagnostic slice stream within its perf capture.")
parser.add_argument("profile_directory", type=Path)
parser.add_argument("--simpleperf-dir", required=True, type=Path)
parser.add_argument("--tid", required=True, type=int, help="Actual emulation thread ID from this recording")
args=parser.parse_args()
sys.path.insert(0,str(args.simpleperf_dir))
from simpleperf_report_lib import ReportLib
p=args.profile_directory;tid=args.tid
lib=ReportLib();lib.SetRecordFile(str(p/'perf.data'));lib.SetTraceOffCpuMode('on-cpu');times=[]
while (sample:=lib.GetNextSample()):
    if sample.tid==tid:times.append(sample.time)
lib.Close();assert times
rows=[json.loads(s) for s in (p/'slice-counts.jsonl').read_text().splitlines()]
unsigned=['slices','blocks','flags','entry','remaining','consumed']
for row in rows:
    assert len(row['slices'])==160 and len(row['blocks'])==128 and len(row['flags'])==192
    for kind in range(2):
        count=sum(row['slices'][kind*80:(kind+1)*80])
        for name in ['entry','remaining','consumed']:assert count==sum(row[name][kind*10:(kind+1)*10])
for a,b in zip(rows,rows[1:]):
    assert a['time']<b['time']
    for name in unsigned:assert all(y>=x for x,y in zip(a[name],b[name]))
first=next(row for row in rows if row['time']>=min(times))
last=next(row for row in reversed(rows) if row['time']<=max(times))
seconds=(last['time']-first['time'])/1e9;assert seconds>115
delta={k:[b-a for a,b in zip(first[k],last[k])] for k in unsigned+['budgetSum','remainingSum','cycleLeftDelta']}
reasons=['unclassified','cycles','iretOrNormalFlagPendingIrq','trap','callback','opcodeFallback','selfModifyingFallback','specialPageNormalFallback','invalidationNormalReturn','debug']
buckets=['<=0','1','2-4','5-16','17-64','65-256','257-1024','1025-4096','4097-16384','>16384']
blocks=['normal','cycles','link1','link2','opcode','iret','callback','selfModifying']
def states(values):
    return {f'IF={s&1},IRQ={bool(s&2)},TF={bool(s&4)}':v for s,v in enumerate(values) if v}
cores={}
for kind,name in enumerate(['dynrec','normal']):
    byreason={r:states(delta['slices'][(kind*10+i)*8:(kind*10+i+1)*8]) for i,r in enumerate(reasons)}
    cores[name]={'calls':sum(sum(v.values()) for v in byreason.values()),'reasons':{k:v for k,v in byreason.items() if v},'budgetSum':delta['budgetSum'][kind],'remainingSum':delta['remainingSum'][kind],'cycleLeftDelta':delta['cycleLeftDelta'][kind]}
    for field in ['entry','remaining','consumed']:cores[name][('cycleRegisterDelta' if field=='consumed' else field)+'Histogram']=dict(zip(buckets,delta[field][kind*10:(kind+1)*10]))
flagcalls={}
for op,name in enumerate(['cli','sti','popf']):
    events=delta['flags'][op*64:(op+1)*64]
    flagcalls[name]={'calls':sum(events),'transitions':{f'{before}->{after}':events[before*8+after] for before in range(8) for after in range(8) if events[before*8+after]}}
result={'intervalSeconds':seconds,'firstMonotonicNs':first['time'],'lastMonotonicNs':last['time'],'cores':cores,'blockReturns':{name:states(delta['blocks'][i*8:(i+1)*8]) for i,name in enumerate(blocks)},'flagHelpers':flagcalls,'stateEncoding':'IF bit 0, pending IRQ bit 1, TF bit 2; helper call counts include unsuccessful calls; budgets are scheduler cycles, not retired instructions','rawDeltas':delta}
(p/'slice-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='rawDeltas'},indent=2))
