# SPDX-License-Identifier: GPL-2.0-or-later
"""Read existing simpleperf captures to bound synchronous OPL work and waits."""
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

results = []
for directory in a.captures:
    tid = json.loads((directory / "summary.json").read_text())["threadCpuPercent"][0][0]
    row = {"capture": directory.name, "emulationTid": tid,
           "recordSeconds": float(re.search(r"Recorded for ([\d.]+) seconds", (directory / "record.log").read_text())[1]),
           "elfSha256": hashlib.sha256((directory / "symbols/libdosbox_staging.so").read_bytes()).hexdigest()}
    for mode in ("on-cpu", "off-cpu"):
        lib = ReportLib()
        lib.SetRecordFile(str(directory / "perf.data"))
        lib.SetSymfs(str(directory / "symbols"))
        lib.SetTraceOffCpuMode(mode)
        costs = Counter()
        while (sample := lib.GetNextSample()):
            if sample.tid != tid:
                continue
            name = lib.GetSymbolOfCurrentSample().symbol_name
            chain = lib.GetCallChainOfCurrentSample()
            names = [name] + [chain.entries[i].symbol.symbol_name for i in range(chain.nr)]
            costs["total"] += sample.period
            if any(n.startswith("Opl::PortWrite(") for n in names):
                costs["portWriteInclusive"] += sample.period
                if any(n.startswith("Opl::RenderFrame(") or n.startswith("OPL3_") for n in names):
                    costs["generationUnderPortWrite"] += sample.period
                if any("MutexLock" in n or "mutex::lock" in n for n in names):
                    costs["mutexUnderPortWrite"] += sample.period
        lib.Close()
        row[mode + "Seconds"] = {k: v / 1e9 for k, v in costs.items()}
    results.append(row)
    print(json.dumps(row), flush=True)
a.output.write_text(json.dumps({"captures": results,
    "limitations": "Inclusive categories overlap. Off-CPU includes scheduling delay, not necessarily lock ownership alone. Attribution can miss inlined/unwound frames. Not an optimization benchmark."}, indent=2) + "\n")
