# SPDX-License-Identifier: GPL-2.0-or-later
"""Observe per-thread CPU/runqueue counters alongside a verified DOS demo capture."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
assert not (args.output / 'start.json').exists(), 'Use a new capture directory'

def shell(command):
    return subprocess.check_output([args.adb, '-s', args.serial, 'shell', command], text=True)

pid = shell('pidof com.loxifi.kairodos').strip()
assert pid.isdigit(), 'Expected one running KairoDos process'
child = subprocess.Popen([sys.executable, str(Path(__file__).with_name('capture_dos_demo.py')),
    '--adb', args.adb, '--serial', args.serial, '--output', str(args.output), '--observation', 'light'])
# Caller verifies the sound-card prompt before invocation, as required by the child tool.
try:
    with (args.output / 'scheduler.jsonl').open('w') as output:
        while child.poll() is None:
            raw = shell(f'cat /proc/uptime; for t in /proc/{pid}/task/*; do echo TASK ${{t##*/}}; '
                        'cat "$t/comm" "$t/stat" "$t/schedstat" "$t/cgroup"; done')
            output.write(json.dumps({'time': time.time(), 'raw': raw}) + '\n')
            output.flush()
            time.sleep(10)
finally:
    if child.poll() is None:
        child.terminate()
        child.wait()
sys.exit(child.returncode)
