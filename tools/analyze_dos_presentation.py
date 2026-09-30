"""Join debug host measurements to observed Android presentation/thermal data."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(__doc__)
parser.add_argument("profile", type=Path)
parser.add_argument("observation", type=Path)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
profile = json.loads(args.profile.read_text(encoding="utf-8-sig"))
observations = [json.loads(line) for line in args.observation.read_text(encoding="utf-8-sig").splitlines() if line]
frames = set()
for item in observations:
    for row in (item["frameTimings"] or [])[1:]:
        columns = row.split()
        if len(columns) == 3 and 0 < int(columns[1]) < 2**63-1:
            frames.add(tuple(map(int, columns)))
output = {"game": profile["game"], "configuration": profile.get("configuration", "Debug host (-O0)"), "runs": []}
for run in profile["runs"]:
    start, end = run["startNs"], run["endNs"]
    durations = run["durationNs"]/1e9
    selected = sorted({present for _, present, _ in frames if start <= present <= end})
    if len(selected) < durations * 10:
        raise ValueError(f"Run {run['run']} has too few real presentations; reject this comparison")
    # SurfaceFlinger FrameTracker fields: desiredPresent, actualPresent, frameReady.
    # This is Android buffer latency, not input-to-photon or guest-to-display latency.
    ready_delays = sorted((present-ready)/1e6 for _, present, ready in frames
                          if start <= present <= end and 0 < ready <= present)
    intervals = [(b-a)/1e6 for a, b in zip(selected, selected[1:])]
    intervals.sort()
    percentile = lambda values, p: values[int((len(values)-1)*p)] if values else None
    telemetry = []
    for item in observations:
        raw = item["telemetry"]
        stamp = float(raw[0].split()[0])*1e9
        if start <= stamp <= end:
            # sysfs order is recorded by ObserveDosPresentation.ps1, not inferred.
            entry = {"stamp": stamp, "socC": int(raw[2])/1000,
                     "gpuC": int(raw[4])/1000, "cooling": [int(raw[i]) for i in (8, 10, 12)],
                     "batteryStatus": raw[13], "batteryCurrentUa": int(raw[14])}
            process = next((line for line in raw if "(loxifi.kairodos)" in line), None)
            if process:
                fields = process.split()
                entry["cpuTicks"] = int(fields[13])+int(fields[14])
            telemetry.append(entry)
    observation_gap = max(((b["stamp"]-a["stamp"])/1e9 for a, b in zip(telemetry, telemetry[1:])), default=0)
    if intervals and observation_gap > 127 * percentile(intervals, .5) / 1000:
        raise ValueError(f"Run {run['run']} observation gap exceeds SurfaceFlinger history; recollect")
    metrics = {key: {"count": value["count"], "meanMs": value["sumNs"]/max(value["count"],1)/1e6,
                     "p50Ms": value["p50Ns"]/1e6, "p95Ms": value["p95Ns"]/1e6,
                     "maxMs": value["maxNs"]/1e6} for key, value in run["metrics"].items()}
    first, last = telemetry[0] if telemetry else {}, telemetry[-1] if telemetry else {}
    # Android /proc uses 100 Hz ticks on this verified RGDS. Percentage of one CPU.
    cpu = None
    if "cpuTicks" in first and "cpuTicks" in last and last["stamp"] > first["stamp"]:
        cpu = (last["cpuTicks"]-first["cpuTicks"])/(last["stamp"]-first["stamp"])*1e9
    submitted = metrics["produced"]["count"] or metrics["hardwareFrame"]["count"]
    output["runs"].append({"run": run["run"], "mode": "software" if run["mode"] == 1 else "gles",
        "seconds": durations, "submittedPerSecond": submitted/durations,
        "completedPresentations": len(selected),
        "observedPresentationSpanSeconds": (selected[-1]-selected[0])/1e9,
        "maxObservationGapSeconds": observation_gap,
        "presentationsPerSecond": (len(selected)-1)*1e9/(selected[-1]-selected[0]) if len(selected)>1 else None,
        "presentationIntervalP50Ms": percentile(intervals,.5), "presentationIntervalP95Ms": percentile(intervals,.95),
        "presentationIntervalMaxMs": max(intervals, default=None),
        "readyToPresentP50Ms": percentile(ready_delays,.5),
        "readyToPresentP95Ms": percentile(ready_delays,.95),
        "pendingReplacements": metrics["replaced"]["count"] if run["mode"] == 1 else None,
        "pendingReplacementPercent": metrics["replaced"]["count"]*100/max(submitted,1) if run["mode"] == 1 else None,
        "coreRunMeanMs": metrics["coreRun"]["meanMs"], "approxProcessCpuPercent": cpu,
        "socCMin": min((t["socC"] for t in telemetry), default=None),
        "socCMax": max((t["socC"] for t in telemetry), default=None),
        "gpuCMax": max((t["gpuC"] for t in telemetry), default=None),
        "thermalCoolingActive": any(any(t["cooling"]) for t in telemetry),
        "batteryStatuses": sorted({t["batteryStatus"] for t in telemetry}), "stages": metrics})
body = json.dumps(output, indent=2)
if args.output:
    args.output.write_text(body+"\n", encoding="utf-8")
else:
    print(body)
