"""Read existing simpleperf captures; no new device instrumentation or timing changes."""
import argparse
from collections import Counter
import hashlib
import json
import re
from pathlib import Path
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
    summary = json.loads((directory / "summary.json").read_text())
    tid = summary["threadCpuPercent"][0][0]
    lib = ReportLib()
    lib.SetRecordFile(str(directory / "perf.data"))
    lib.SetSymfs(str(directory / "symbols"))
    lib.SetTraceOffCpuMode("on-cpu")
    total = 0
    functions = Counter()
    addresses = Counter()
    sample_counts = Counter()
    while (sample := lib.GetNextSample()):
        if sample.tid != tid:
            continue
        total += sample.period
        symbol = lib.GetSymbolOfCurrentSample()
        if "dac_palette" in symbol.symbol_name:
            functions[symbol.symbol_name] += sample.period
            sample_counts[symbol.symbol_name] += 1
            addresses[(symbol.symbol_name, symbol.vaddr_in_file-symbol.symbol_addr)] += sample.period
    lib.Close()
    record_text = (directory / "record.log").read_text()
    duration = float(re.search(r"Recorded for ([\d.]+) seconds", record_text)[1])
    elf_hash = hashlib.sha256((directory / "symbols/libdosbox_staging.so").read_bytes()).hexdigest()
    # Both binaries' ARM64 loops were checked with llvm-objdump. Do not silently
    # reuse relative PC ranges with an unreviewed binary layout.
    audited = elf_hash in {
        "1d631cbc5c0da0fe846bd0d1edaab38a116735b621a814a20eb494d81a649524",
        "86e3260ff8e0fbc1f6faa92d6b7e5d7e45cad6c85026fc83a70773cd00b1d56e",
    }
    loop_ns = sum(v for (name,offset),v in addresses.items()
                  if name.startswith("draw_linear_line_from_dac_palette(")
                  and any(lo <= offset < hi for lo,hi in [(0x74,0x8c),(0xc4,0xdc),(0x100,0x118)])) if audited else None
    results.append({
        "capture": str(directory), "threadId": tid,
        "elfSha256": elf_hash, "recordSeconds": duration,
        "auditedConversionLoopCpuSeconds": loop_ns/1e9 if loop_ns is not None else None,
        "emulationCpuSeconds": total / 1e9,
        "paletteFunctionSelfCpuSeconds": {k: v/1e9 for k,v in functions.items()},
        "paletteFunctionSamples": dict(sample_counts),
        "relativePcCpuSeconds": [{"function": k[0], "offset": hex(k[1]), "seconds": v/1e9}
                                 for k,v in sorted(addresses.items())],
    })
    print(directory.name, total/1e9, {k:round(v/1e9,6) for k,v in functions.items()}, flush=True)
a.output.write_text(json.dumps(results, indent=2)+"\n")
