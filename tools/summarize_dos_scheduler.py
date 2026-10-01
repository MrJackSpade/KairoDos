# SPDX-License-Identifier: GPL-2.0-or-later
"""Summarize post-warmup counters from capture_dos_scheduler.py."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('capture', type=Path)
args = parser.parse_args()
root = args.capture
snapshots = [json.loads(line) for line in (root / 'scheduler.jsonl').read_text().splitlines()]

def parse(snapshot):
    threads = {}
    for block in snapshot['raw'].split('TASK ')[1:]:
        lines = block.splitlines()
        tid = int(lines[0])
        stat = lines[2].rsplit(')', 1)[1].split()
        runtime, wait, slices = map(int, lines[3].split())
        threads[tid] = dict(name=lines[1], nice=int(stat[16]), startTicks=int(stat[19]),
                            runtime=runtime, wait=wait, slices=slices)
    return threads

# Exclude launch and warmup work; report this window separately from frame capture.
launch = json.loads((root / 'start.json').read_text())['launchUtc']
snapshots = [sample for sample in snapshots if sample['time'] >= launch + 60]
assert len(snapshots) >= 2, 'Not enough post-warmup samples'
first, last = parse(snapshots[0]), parse(snapshots[-1])
wall = snapshots[-1]['time'] - snapshots[0]['time']
assert wall > 0
rows = []
for tid, end in last.items():
    if tid not in first or end['startTicks'] != first[tid]['startTicks']:
        continue
    begin = first[tid]
    assert end['nice'] == begin['nice'], 'Priority changed inside measurement window'
    assert all(end[key] >= begin[key] for key in ('runtime', 'wait', 'slices'))
    rows.append(dict(tid=tid, name=end['name'], nice=end['nice'],
                     runtimeSeconds=(end['runtime'] - begin['runtime']) / 1e9,
                     runqueueSeconds=(end['wait'] - begin['wait']) / 1e9,
                     slices=end['slices'] - begin['slices']))
rows.sort(key=lambda row: row['runtimeSeconds'], reverse=True)
result = dict(wallSeconds=wall, threads=rows)
(root / 'scheduler-summary.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(dict(wallSeconds=wall, threads=rows[:8]), indent=2))
