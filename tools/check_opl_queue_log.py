# SPDX-License-Identifier: GPL-2.0-or-later
"""Check actual OPL integration queue/sample diagnostics from a fixture log."""
import argparse
import json
from pathlib import Path
import re

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('log', type=Path)
p.add_argument('--mode', required=True, choices=('OPL2', 'DualOPL2', 'OPL3'))
p.add_argument('--capacity', required=True, type=int)
p.add_argument('--instances', type=int, default=3)
p.add_argument('--require-pressure', action='store_true')
a = p.parse_args()
assert a.capacity > 0 and a.instances > 0
text = a.log.read_text()
assert text.count('KAIRO OPL worker enabled: ' + a.mode + '\n') == a.instances
samples = [tuple(map(int, row)) for row in re.findall(
    r'KAIRO OPL verify: samples=(\d+) mismatches=(\d+)', text)]
assert len(samples) == a.instances and all(n > 0 and errors == 0 for n, errors in samples)
pattern = (r'KAIRO OPL queue: capacity=(\d+) commands=(\d+) results=(\d+) '
           r'current_commands=(\d+) current_results=(\d+) submit_drains=(\d+) '
           r'submit_waits=(\d+) fifo_frames=(\d+) issued=(\d+) consumed=(\d+)')
fields = ('capacity', 'maxCommands', 'maxResults', 'currentCommands',
          'currentResults', 'submitDrains', 'submitWaits', 'maxFifoFrames',
          'issued', 'consumed')
queues = [dict(zip(fields, map(int, row))) for row in re.findall(pattern, text)]
assert len(queues) == a.instances
for q in queues:
    assert q['capacity'] == a.capacity
    assert 0 < q['maxCommands'] <= a.capacity and 0 < q['maxResults'] <= a.capacity
    assert q['currentCommands'] == q['currentResults'] == 0
    assert q['issued'] == q['consumed'] and q['issued'] > 0
    if a.require_pressure:
        assert q['maxCommands'] == q['maxResults'] == a.capacity
        assert q['submitDrains'] > 0 and q['submitWaits'] > 0
print(json.dumps({'mode': a.mode, 'instances': a.instances,
    'samplesCompared': sum(n for n, errors in samples), 'mismatches': 0,
    'pressureRequired': a.require_pressure, 'queues': queues,
    'limits': 'Queue/sample evidence only. Inspect fixture lifecycle result and rendered image separately; FIFO is the existing mixer queue, not a new bounded ring.'}, indent=2))
