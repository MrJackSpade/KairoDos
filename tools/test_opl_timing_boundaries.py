# SPDX-License-Identifier: GPL-2.0-or-later
"""Compile the real current/legacy catch-up methods with deterministic clock and sinks."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
path = 'third_party/dosbox-staging/src/hardware/audio/opl.cpp'
reference_revision = '9dcfd7f6'
legacy = subprocess.check_output(['git', 'show', reference_revision + ':' + path]).decode()
current = Path(path).read_text()

def method(text):
    start = text.index('void Opl::RenderUpToNow()')
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

old, new = method(legacy), method(current)
assert 'QueueFrames' not in old and 'QueueFrames' in new
prefix = r'''
// SPDX-License-Identifier: GPL-2.0-or-later
// Catch-up methods below are extracted from DOSBox Staging, copyright DOSBox Team.
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <initializer_list>
#include <stdexcept>
#define KAIRO_OPL_WORKER
static double observed_now;
static double PIC_FullIndex() { return observed_now; }
struct Channel {
    bool woke = false;
    unsigned calls = 0;
    bool WakeUp() { ++calls; return woke; }
};
struct Sink { uint64_t count = 0; void emplace(int) { ++count; } };
struct State {
    struct WorkerState { static constexpr uint32_t BlockFrames = 64; } storage;
    WorkerState* worker = nullptr;
    Channel channel_storage;
    Channel* channel = &channel_storage;
    Sink fifo;
    double last_rendered_ms = 0, ms_per_frame = 1000.0 / 49716;
    uint64_t frames = 0, blocks = 0;
    int RenderFrame() { ++frames; return 0; }
    void QueueFrames(uint32_t n) {
        if (n > WorkerState::BlockFrames) throw std::runtime_error("oversized batch");
        frames += n;
        blocks += n != 0;
    }
};
'''
source = prefix
for namespace, body in [('legacy', old), ('candidate', new)]:
    source += '\nnamespace ' + namespace + ' {\nstruct Opl : State { void RenderUpToNow(); };\n' + body + '\n}\n'
source += r'''
static uint64_t bits(double x) { uint64_t n; std::memcpy(&n, &x, sizeof(n)); return n; }
static uint64_t cases = 0, frames_checked = 0, blocks_checked = 0, wake_cases = 0;
static void check(double start, double now, bool wake, bool worker) {
    legacy::Opl before;
    candidate::Opl after;
    before.last_rendered_ms = after.last_rendered_ms = start;
    before.channel_storage.woke = after.channel_storage.woke = wake;
    after.worker = worker ? &after.storage : nullptr;
    observed_now = now;
    before.RenderUpToNow();
    after.RenderUpToNow();
    if (bits(before.last_rendered_ms) != bits(after.last_rendered_ms) ||
        before.frames != after.frames || before.channel_storage.calls != 1 ||
        after.channel_storage.calls != 1 || before.fifo.count != before.frames ||
        (!worker && after.fifo.count != after.frames) || (worker && after.fifo.count))
        throw std::runtime_error("catch-up differs from pre-worker method");
    ++cases; frames_checked += before.frames; blocks_checked += after.blocks; wake_cases += wake;
}
int main() {
    const double step = 1000.0 / 49716;
    for (double start : {0.0, 0.000001, 123.456, 1000000.125, 4294967296.0}) {
        for (unsigned n : {0u, 1u, 2u, 63u, 64u, 65u, 127u, 128u, 1024u, 8192u}) {
            double boundary = start;
            for (unsigned i = 0; i < n; ++i) boundary += step;
            for (double now : {std::nextafter(boundary, -std::numeric_limits<double>::infinity()),
                               boundary, std::nextafter(boundary, std::numeric_limits<double>::infinity())})
                for (bool wake : {false, true}) for (bool worker : {false, true}) check(start, now, wake, worker);
        }
    }
    // Repeated times, fractional steps, and externally supplied callback time resets.
    uint64_t seed = 0x74;
    double last = 0;
    for (unsigned i = 0; i < 10000; ++i) {
        seed = seed * 6364136223846793005ULL + 1;
        const double now = last + double(seed % 1025) * step / 8;
        check(last, now, i % 37 == 0, true);
        check(last, last, false, true);
        last = now;
        if (i % 101 == 0) last = double(i); // callback timestamp input, not callback implementation
    }
    std::printf("{\"cases\":%llu,\"referenceFrames\":%llu,\"workerBlocks\":%llu,\"wakeCases\":%llu,\"mismatches\":0}\n",
        (unsigned long long)cases, (unsigned long long)frames_checked,
        (unsigned long long)blocks_checked, (unsigned long long)wake_cases);
}
'''
cpp = a.output / 'opl-timing.cpp'
cpp.write_text(source)
compiler = a.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
subprocess.run([str(compiler), '--target=aarch64-linux-android26', '-std=c++17', '-O3',
                '-static-libstdc++', str(cpp), '-o', str(a.output/'opl-timing')], check=True)
(a.output/'provenance.json').write_text(json.dumps({
    'referenceRevision': reference_revision,
    'currentMethodSha256': hashlib.sha256(new.encode()).hexdigest(),
    'referenceMethodSha256': hashlib.sha256(old.encode()).hexdigest(),
    'generatedSourceSha256': hashlib.sha256(cpp.read_bytes()).hexdigest(),
    'binarySha256': hashlib.sha256((a.output/'opl-timing').read_bytes()).hexdigest(),
    'limits': 'Actual extracted catch-up methods with deterministic PIC time, WakeUp result and counting sinks. Does not model real mixer/thread scheduling, synthesis, or timer/status/routing.'}, indent=2)+'\n')
print('Built', a.output/'opl-timing')
