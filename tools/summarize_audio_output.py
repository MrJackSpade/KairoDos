# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import json,re,statistics,sys
p=Path(sys.argv[1]);start=json.loads((p/'audio-start-time.json').read_text())['hostUtc'];end=json.loads((p/'audio-end-time.json').read_text())['hostUtc'];pid=json.loads((p/'capture.json').read_text())['pid']
rows=[];configs=[]
for line in (p/'audio-log.txt').read_text().splitlines():
 m=re.search(r'^\s*([0-9.]+)\s+(\d+)\s+\d+\s+I KairoAudioMeasure: (.*)',line)
 if not m or m[2]!=pid:continue
 fields=dict(re.findall(r'(\w+)=(-?\d+)',m[3]));fields={k:int(v) for k,v in fields.items()}
 if 'windowNs' not in fields:configs.append(fields);continue
 t=float(m[1]);begin=t-fields['windowNs']/1e9
 if begin>=start and t<=end:rows.append(fields)
assert rows
window=sum(r['windowNs'] for r in rows)
result=dict(pid=pid,config=configs,windows=len(rows),observedSeconds=window/1e9,readWallPercent=100*sum(r['readNs'] for r in rows)/window,writeWallPercent=100*sum(r['writeNs'] for r in rows)/window,readCallsPerSecond=sum(r['reads'] for r in rows)/(window/1e9),emptyReadsPerSecond=sum(r['empty'] for r in rows)/(window/1e9),maxReadMs=max(r['readMax'] for r in rows)/1e6,maxWriteMs=max(r['writeMax'] for r in rows)/1e6,partialWrites=sum(r['partial'] for r in rows),underrunRange=[min(r['underruns'] for r in rows),max(r['underruns'] for r in rows)],softwarePendingFramesRange=[min(r['pending'] for r in rows),max(r['pending'] for r in rows)],softwarePendingFramesMedian=statistics.median(r['pending'] for r in rows),submittedFramesPerSecond=(rows[-1]['submitted']-rows[0]['submitted'])/(sum(r['windowNs'] for r in rows[1:])/1e9))
(p/'audio-summary.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
