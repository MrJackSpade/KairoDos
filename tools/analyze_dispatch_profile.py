# SPDX-License-Identifier: GPL-2.0-or-later
import argparse,json,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--simpleperf-dir',required=True);p.add_argument('--tid',required=True,type=int);a=p.parse_args()
sys.path.insert(0,a.simpleperf_dir)
from simpleperf_report_lib import ReportLib
lib=ReportLib();lib.SetRecordFile(str(a.directory/'perf.data'));lib.SetTraceOffCpuMode('on-cpu');times=[]
while (s:=lib.GetNextSample()):
 if s.tid==a.tid:times.append(s.time)
lib.Close();assert times
rows=[json.loads(s) for s in (a.directory/'dispatch-counts.jsonl').read_text().splitlines()]
sizes={'counts':18,'returns':16,'normalCalls':5,'opcodes':5120,'clears':8}
for r in rows:
 for k,n in sizes.items():assert len(r[k])==n
for x,y in zip(rows,rows[1:]):
 assert x['time']<y['time'] and x['entries']<=y['entries']
 for k in sizes:assert all(i<=j for i,j in zip(x[k],y[k]))
f=next(r for r in rows if r['time']>=min(times));l=next(r for r in reversed(rows) if r['time']<=max(times));seconds=(l['time']-f['time'])/1e9;assert seconds>115
d={k:[j-i for i,j in zip(f[k],l[k])] for k in sizes}
labels=['coreCalls','dispatchIterations','makeCodePageException','specialPageNormal','cacheHit','cacheMiss','translated','invalidationNormal','hostRuns','linkAttempts','linkNoHandler','linkNoCode','linkNoBlock','linkSuccess','unused14','unused15','unused16','unused17']
reasons=['other','opcodeFallback','selfModifyingFallback','invalidationFallback','specialPageFallback']
r={'intervalSeconds':seconds,'firstMonotonicNs':f['time'],'lastMonotonicNs':l['time'],'entries':l['entries']-f['entries'],'counts':dict(zip(labels,d['counts'])),'returns':dict(zip(['normal','cycles','link1','link2','opcode','iret','callback','selfModifying']+[f'unused{i}' for i in range(8,16)],d['returns'])),'normalCalls':dict(zip(reasons,d['normalCalls'])),'opcodeTokens':{name:{hex(i):v for i,v in enumerate(d['opcodes'][n*1024:(n+1)*1024]) if v} for n,name in enumerate(reasons)},'clears':dict(zip(['otherPrimary','otherCrossPage','writePrimary','writeCrossPage','releasePrimary','releaseCrossPage','reusePrimary','reuseCrossPage'],d['clears']))}
c=r['counts'];returns=r['returns']
assert c['dispatchIterations']==c['cacheHit']+c['cacheMiss']+c['makeCodePageException']+c['specialPageNormal']
assert c['cacheMiss']==c['translated']+c['invalidationNormal']
assert c['linkAttempts']==c['linkNoHandler']+c['linkNoCode']+c['linkNoBlock']+c['linkSuccess']
# Flush occurs at poll boundaries, so an invocation can straddle either snapshot.
assert abs(c['hostRuns']-sum(returns.values()))<=2
assert abs(returns['opcode']-r['normalCalls']['opcodeFallback'])<=2
r['setccFallbacks']=sum(v for k,v in r['opcodeTokens']['opcodeFallback'].items() if (int(k,16)&0x1ff) in range(0x190,0x1a0))
r['setccFallbackPercent']=100*r['setccFallbacks']/r['normalCalls']['opcodeFallback']
r['internalEntryTransitions']=r['entries']-r['counts']['hostRuns']
r['internalEntryPercent']=100*r['internalEntryTransitions']/r['entries']
(a.directory/'dispatch-summary.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2))
