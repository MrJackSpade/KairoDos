# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import json,re,sys
root=Path(sys.argv[1]);capture=json.loads((root/'capture.json').read_text());samples=[json.loads(x) for x in (root/'samples.jsonl').read_text().splitlines()]
start,end=samples[0]['hostUtc'],samples[-1]['hostUtc'];pid=capture['pid'];rows=[]
for line in (root/'row-log.txt').read_text().splitlines():
 m=re.match(r'\s*([0-9.]+)\s+(\d+)\s+\d+\s+I KairoRowMeasure: (.*)',line)
 if m and m[2]==pid and start<=float(m[1])<=end:rows.append((float(m[1]),json.loads(m[3])))
assert len(rows)>=2
first,last=rows[0][1],rows[-1][1];duration=rows[-1][0]-rows[0][0]
assert first['rowsCompared']==last['rowsCompared']
result=dict(pid=pid,windowSeconds=duration,rowsCompared=last['rowsCompared'],firstSize=[first['width'],first['height']],lastSize=[last['width'],last['height']],logs=len(rows))
for key in ('frames','rows','changedRows','duplicates','resized','bytes'):result[key]=last[key]-first[key];assert result[key]>=0
result['changedRowFraction']=result['changedRows']/result['rows'] if result['rowsCompared'] and result['rows'] else None
result['buckets']=[b-a for a,b in zip(first['buckets'],last['buckets'])]
result['stages']={}
for name,a,b in zip(('convert','windowCopy','windowLock','windowPost','observer'),first['stages'],last['stages']):
 v={k:b[k]-a[k] for k in ('wallNs','cpuNs','calls')};assert all(x>=0 for x in v.values());v.update(cpuPercentOfOneCore=100*v['cpuNs']/1e9/duration,meanCpuUs=v['cpuNs']/v['calls']/1000 if v['calls'] else None,meanWallUs=v['wallNs']/v['calls']/1000 if v['calls'] else None);result['stages'][name]=v
(root/'row-summary.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
