"""Attribute existing on-CPU samples to DOS video families; no device changes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys

p = argparse.ArgumentParser()
p.add_argument("captures", nargs="+", type=Path)
p.add_argument("--report-lib", required=True, type=Path)
p.add_argument("--output", required=True, type=Path)
a = p.parse_args()
sys.path.insert(0, str(a.report_lib))
from simpleperf_report_lib import ReportLib

patterns = {
    "text": r"VGA_TEXT_|draw_text_line",
    "hardwareCursor": r"HWMouse",
    "reelMagicMix": r"RMR_DrawLine_(?!Passthrough)|MixPixel",
    "egaMemoryHandlers": r"VGA_(?:Unchained|Chained)EGA_Handler",
    "vgaMemoryHandlers": r"VGA_(?:Unchained|Chained)VGA_Handler",
    "palette": r"draw_.*dac_palette|draw_cached_vesa_palette_line",
}
results = []
for directory in a.captures:
    tid = json.loads((directory / "summary.json").read_text())["threadCpuPercent"][0][0]
    lib = ReportLib()
    lib.SetRecordFile(str(directory / "perf.data"))
    lib.SetSymfs(str(directory / "symbols"))
    lib.SetTraceOffCpuMode("on-cpu")
    total = samples = 0
    self_cost = Counter()
    inclusive = Counter()
    symbols = Counter()
    while (sample := lib.GetNextSample()):
        if sample.tid != tid:
            continue
        total += sample.period
        samples += 1
        name = lib.GetSymbolOfCurrentSample().symbol_name
        chain = lib.GetCallChainOfCurrentSample()
        names = [name] + [chain.entries[i].symbol.symbol_name for i in range(chain.nr)]
        if re.search(r"VGA_|draw_.*(?:palette|text)|RMR_|MixPixel", name):
            symbols[name] += sample.period
        for family, pattern in patterns.items():
            if re.search(pattern, name):
                self_cost[family] += sample.period
            if any(re.search(pattern, n) for n in names):
                inclusive[family] += sample.period
    lib.Close()
    results.append({
        "capture": directory.name, "threadId": tid, "samples": samples,
        "recordSeconds": float(re.search(r"Recorded for ([\d.]+) seconds", (directory / "record.log").read_text())[1]),
        "elfSha256": hashlib.sha256((directory / "symbols/libdosbox_staging.so").read_bytes()).hexdigest(),
        "emulationCpuSeconds": total / 1e9,
        "selfCpuSeconds": {k: self_cost[k] / 1e9 for k in patterns},
        "inclusiveCpuSeconds": {k: inclusive[k] / 1e9 for k in patterns},
        "videoSelfSymbols": {k: v / 1e9 for k, v in symbols.most_common()},
    })
    print(directory.name, results[-1]["inclusiveCpuSeconds"], flush=True)
a.output.write_text(json.dumps({"patterns": patterns, "captures": results,
    "limitations": "Sampled symbol attribution, not exact call counts. Families may overlap. Inlined or unresolved code can be missed; zero samples alone does not prove inactivity."}, indent=2) + "\n")
