# SPDX-License-Identifier: GPL-2.0-or-later
"""Summarize capture_dos_demo output without treating submissions as unique FPS."""
import argparse
import json
from pathlib import Path
import re
import statistics

p = argparse.ArgumentParser()
p.add_argument("capture", type=Path)
p.add_argument("--clock-ticks", type=int, required=True,
               help="Device getconf CLK_TCK result, not CPU frequency")
a = p.parse_args()
capture = json.loads((a.capture / "capture.json").read_text())
assert capture["recordExit"] == 0 and capture["statExit"] == 0, capture
rows = [json.loads(x) for x in (a.capture / "samples.jsonl").read_text().splitlines()]
assert len(rows) > 2 and a.clock_ticks > 0
first, last = rows[0], rows[-1]
duration = last["elapsed"] - first["elapsed"]
assert duration > 0

def cpu(raw):
    records = []
    for line in raw.splitlines():
        m = re.match(r"^(\d+) \((.*)\) (.*)$", line)
        if m:
            fields = m[3].split()
            records.append((int(m[1]), m[2], int(fields[11]) + int(fields[12])))
    # The capture writes /proc/PID/stat first, then all /proc/PID/task/TID/stat.
    assert records and records[0][0] == int(capture["pid"])
    return records[0][2], {tid: (name, ticks) for tid, name, ticks in records[1:]}

proc0, threads0 = cpu(first["raw"])
proc1, threads1 = cpu(last["raw"])
scale = 100.0 / (a.clock_ticks * duration)
thread_cpu = sorted([(tid, threads1[tid][0], (threads1[tid][1] - value[1]) * scale)
                     for tid, value in threads0.items() if tid in threads1], key=lambda x: -x[2])

def presentations(raw):
    result = set()
    for line in raw.split("FRAMES\n", 1)[1].splitlines()[1:]:
        fields = line.split()
        if len(fields) == 3 and all(x.isdigit() for x in fields):
            actual = int(fields[1])
            if 0 < actual < 2**63 - 1:
                result.add(actual)
    return result

frame_rows = rows
frame_source = "host polling (legacy)"
if (a.capture / "surface-history.txt").exists():
    assert capture.get("frameSamplerExit") == 0
    frame_source = "device-local-v1"
    frame_rows = []
    for block in (a.capture / "surface-history.txt").read_text().split("SAMPLE ")[1:]:
        stamp, body = block.split("\n", 1)
        assert body.endswith("END\n"), "Incomplete surface sample"
        frame_rows.append({"elapsed": float(stamp), "raw": "FRAMES\n" + body[:-4]})
    assert len(frame_rows) > 2
    frame_rows = [r for r in frame_rows if r["elapsed"] <= frame_rows[0]["elapsed"] + 120]
frame_duration = frame_rows[-1]["elapsed"] - frame_rows[0]["elapsed"]
initial = presentations(frame_rows[0]["raw"])
assert initial, "No initial SurfaceFlinger timestamps"
cutoff = max(initial)
times = sorted({t for row in frame_rows for t in presentations(row["raw"]) if t > cutoff})
assert len(times) > 1
intervals = sorted((b - b0) / 1e6 for b0, b in zip(times, times[1:]))
result = {
    "intervalSeconds": duration, "clockTicksPerSecond": a.clock_ticks,
    "processCpuPercent": (proc1 - proc0) * scale,
    "threadCpuPercent": thread_cpu,
    "threadLimits": "Only threads present at both endpoints; process CPU includes other threads.",
    "presentations": {"count": len(times), "rate": len(times) / frame_duration,
        "intervalSeconds": frame_duration, "source": frame_source,
        "medianIntervalMs": statistics.median(intervals),
        "p95IntervalMs": intervals[int(len(intervals) * .95)],
        "note": "Surface submissions, not necessarily unique guest frames"},
    "hardwareFirstLines": {str(i): sorted({row["raw"].splitlines()[i] for row in rows}) for i in range(1, 6)},
}
result["maxSamplingGapSeconds"] = max(b["elapsed"] - b0["elapsed"] for b0, b in zip(rows, rows[1:]))
result["maxFrameSamplingGapSeconds"] = max(b["elapsed"] - b0["elapsed"] for b0, b in zip(frame_rows, frame_rows[1:]))
history = [presentations(row["raw"]) for row in frame_rows]
result["disjointPresentationHistories"] = sum(not (old & new) for old, new in zip(history, history[1:]))
result["presentationHistoryComplete"] = result["disjointPresentationHistories"] == 0
if not result["presentationHistoryComplete"]:
    result["presentations"]["observedRateLowerBound"] = result["presentations"].pop("rate")
    result["presentations"]["note"] += "; history gaps, throughput and intervals are incomplete"
thermal = {}
for row in rows:
    lines = row["raw"].splitlines()[6:]
    for i in range(0, len(lines) - 1, 2):
        if re.match(r"^\d+ \(", lines[i]):
            break
        if re.fullmatch(r"-?\d+", lines[i + 1]):
            thermal.setdefault(lines[i], []).append(int(lines[i + 1]))
result["thermalAndCoolingRawRanges"] = {k: [min(v), max(v)] for k, v in thermal.items()}
counter_text = (a.capture / "counters.txt").read_text()
task_clock = re.search(r"([\d.,]+)\(ms\)\s+task-clock", counter_text)
counter_duration = re.search(r"Total test time:\s+([\d.]+) seconds", counter_text)
if task_clock and counter_duration:
    cpu_seconds = float(task_clock[1].replace(",", "")) / 1000
    result["perfStatTaskClock"] = {"cpuSeconds": cpu_seconds,
        "wallSeconds": float(counter_duration[1]),
        "cpuPercent": 100 * cpu_seconds / float(counter_duration[1]),
        "note": "Separate high-resolution counter/window from proc tick accounting; retain both."}

def audio_tracks(path):
    found = {}
    # Android 14 normal-track dump: Type is blank, then Id/Active/Client.
    # Fail on unfamiliar layout rather than silently indexing other data.
    text = path.read_text()
    assert "F Underruns  Flushed BitPerfect" in text
    for line in text.splitlines():
        if not re.match(r"^\s+\d+\s+(yes|no)\s+\d+\s+", line):
            continue
        f = line.split()
        assert len(f) >= 24 and f[23] in ("true", "false"), line
        if int(f[2]) == int(capture["pid"]):
            found[f[0]] = int(f[21])
    assert found, "No app AudioFlinger track found"
    return found

if (a.capture / "audio-start.txt").exists():
    start = audio_tracks(a.capture / "audio-start.txt")
    end = audio_tracks(a.capture / "audio-end.txt")
    assert start.keys() == end.keys(), "Audio track changed during measurement"
    assert all(end[k] >= start[k] for k in start)
    t0 = json.loads((a.capture / "audio-start-time.json").read_text())
    t1 = json.loads((a.capture / "audio-end-time.json").read_text())
    result["audio"] = {"snapshotSpanSeconds": t1["hostMonotonic"] - t0["hostMonotonic"],
        "trackUnderrunFieldStart": start, "trackUnderrunFieldEnd": end,
        "trackUnderrunFieldDelta": {k: end[k] - start[k] for k in start},
        "limits": "AudioFlinger dump field, not a test of emulated sample quality; snapshot window differs slightly from CPU window."}
(a.capture / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({k: v for k, v in result.items() if k != "threadCpuPercent"}, indent=2))
