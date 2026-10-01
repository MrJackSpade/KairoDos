# SPDX-License-Identifier: GPL-2.0-or-later
"""Verify actual Clang profile matching, including a wrong-root negative control."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--compile-commands', type=Path)
parser.add_argument('--build-root', type=Path, default=Path('backend-dos/.cxx'))
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
if args.compile_commands:
    database = args.compile_commands
else:
    candidates = []
    for path in args.build_root.rglob('compile_commands.json'):
        # AGP also publishes an IDE convenience copy under .cxx/tools. Only
        # native build directories identify actual, distinct configurations.
        if path.relative_to(args.build_root).parts[0] == 'tools':
            continue
        commands = json.loads(path.read_text())
        if any('-fprofile-use=' in c['command'] for c in commands):
            candidates.append(path)
    if len(candidates) != 1:
        raise SystemExit(f'Expected one PGO compilation database; supply --compile-commands: {candidates}')
    database = candidates[0]
commands = json.loads(database.read_text())
args.output.mkdir(parents=True, exist_ok=True)
output = args.output.resolve()
manifest = json.loads((root / 'backend-dos/pgo/profile.json').read_text())
profile_text = root / 'backend-dos/pgo/arm64-v1.proftext'
if hashlib.sha256(profile_text.read_bytes()).hexdigest() != manifest['portableTextSha256']:
    raise SystemExit('Source profile differs from its manifest')
# These counts came from the frozen #70 profile's optimized IR. Each selected
# function lost its count in the experimentally verified wrong-root control.
expected = {
    '/cpu/cpu.cpp': {
        '_ZL15cpu_switch_taskm11TSwitchTypem': 34773,
        '_ZL18cpu_check_segmentsv': 4,
        '_ZL10hlt_decodev': 2726198,
    },
    '/video/vga_draw.cpp': {
        '_ZL17VGA_VerticalTimerj': 74233,
        '_ZL18VGA_DrawSingleLinej': 12934812,
    },
}

def tokens(command):
    parts = shlex.split(command, posix=os.name != 'nt')
    return [p[1:-1] if len(p) > 1 and p[0] == p[-1] == '"' else p for p in parts]

first = next(c for c in commands if '-fprofile-use=' in c['command'])
compiler = Path(tokens(first['command'])[0])
profdata = compiler.parent / ('llvm-profdata.exe' if os.name == 'nt' else 'llvm-profdata')
wrong_text = output / 'wrong-root.proftext'
wrong_text.write_text((root / 'backend-dos/pgo/arm64-v1.proftext').read_text().replace(
    '@KAIRO_SOURCE@/', '/__kairo_deliberately_wrong_profile_root__/'), encoding='utf-8', newline='\n')
wrong_profile = output / 'wrong-root.profdata'
subprocess.run([str(profdata), 'merge', str(wrong_text), '-o', str(wrong_profile)], check=True)

def entry_counts(text):
    metadata = dict(re.findall(r'!(\d+) = !\{!"function_entry_count", i64 (\d+)', text))
    return {m[1]: int(metadata[m[2]]) for m in re.finditer(
        r'^define[^\n]*?@([^ (]+)[^\n]*? !prof !(\d+)', text, re.M) if m[2] in metadata}

records = {}
for suffix, required in expected.items():
    command = next(c for c in commands if c['file'].replace('\\', '/').endswith(suffix))
    if '-fprofile-generate' in command['command'] or '-fprofile-use=' not in command['command']:
        raise SystemExit('Expected a profile-use build without generation instrumentation')
    if manifest['portableTextSha256'] not in command['command']:
        raise SystemExit('Compilation database uses an older or different profile artifact')
    unit = Path(suffix).stem
    results = {}
    for label in ('matching', 'wrong-root'):
        argv = tokens(command['command'])
        # Match actual Ninja spelling, not CMake's Windows database spelling.
        argv = [a.replace('\\', '/') if a == command['file'] else a for a in argv]
        index = argv.index('-o')
        del argv[index:index + 2]
        if label == 'wrong-root':
            argv = ['-fprofile-use=' + wrong_profile.as_posix() if a.startswith('-fprofile-use=') else a for a in argv]
        ir = output / f'{unit}-{label}.ll'
        argv += ['-emit-llvm', '-S', '-o', str(ir)]
        with (output / f'{unit}-{label}.log').open('w', encoding='utf-8') as log:
            subprocess.run(argv, cwd=command['directory'], stdout=log, stderr=subprocess.STDOUT, check=True)
        counts = entry_counts(ir.read_text())
        selected = {symbol: counts.get(symbol) for symbol in required}
        if label == 'matching' and selected != required:
            raise SystemExit(f'{unit}: profile matching failed: {selected}, expected {required}')
        if label == 'wrong-root' and any(selected[symbol] == count for symbol, count in required.items()):
            raise SystemExit(f'{unit}: negative control did not detect missing profile matches: {selected}')
        results[label] = selected
    records[unit] = results
    print(f'{unit}: matching counts verified; wrong-root control detected', flush=True)
report = {'compiler': str(compiler), 'database': str(database),
          'portableTextSha256': manifest['portableTextSha256'], 'checks': records}
(output / 'profile-matching.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
