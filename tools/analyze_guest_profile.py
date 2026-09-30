# SPDX-License-Identifier: GPL-2.0-or-later
"""Join CLOCK_MONOTONIC CPU samples to time-valid translated guest blocks.

Counts sampled CPU time, NOT executed instructions or block entry counts.
Raw metadata is local-only; do not publish guest-code dumps with results.
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import sys


class Timeline:
    def __init__(self):
        self.blocks = {}
        self.pages = defaultdict(set)
        self.last_time = 0

    def remove(self, identity):
        old = self.blocks.pop(identity, None)
        if old:
            for page in range(old['host'] // 4096, (old['host'] + old['size'] - 1) // 4096 + 1):
                self.pages[page].discard(identity)

    def apply(self, event):
        if event['time'] < self.last_time:
            raise ValueError('Metadata timestamps moved backwards')
        self.last_time = event['time']
        if event['event'] == 'begin':
            self.blocks.clear()
            self.pages.clear()
        elif event['event'] == 'clear':
            self.remove(event['block'])
        elif event['event'] == 'block':
            if event['size'] <= 0:
                raise ValueError('Invalid block size')
            self.remove(event['block'])
            self.blocks[event['block']] = event
            for page in range(event['host'] // 4096, (event['host'] + event['size'] - 1) // 4096 + 1):
                self.pages[page].add(event['block'])

    def lookup(self, ip):
        matches = [self.blocks[key] for key in self.pages.get(ip // 4096, ())
                   if self.blocks[key]['host'] <= ip < self.blocks[key]['host'] + self.blocks[key]['size']]
        if len(matches) > 1:
            raise ValueError('Overlapping live JIT mappings: attribution would be ambiguous')
        return matches[0] if matches else None


def summarize(events, samples):
    timeline = Timeline()
    pending = iter(events)
    event = next(pending, None)
    direct, via_helper = Counter(), Counter()
    helper_symbols = defaultdict(Counter)
    unattributed_symbols = Counter()
    definitions = {}
    total = unmatched = 0
    for sample in sorted(samples, key=lambda x: x['time']):
        while event is not None and event['time'] <= sample['time']:
            timeline.apply(event)
            event = next(pending, None)
        total += sample['period']
        block = timeline.lookup(sample['ip'])
        counter = direct
        if block is None:
            counter = via_helper
            # Nearest JIT caller. Do not count every parent or double-count helpers.
            for ip in sample.get('callchain', []):
                block = timeline.lookup(ip)
                if block is not None:
                    break
        if block is None:
            unmatched += sample['period']
            unattributed_symbols[sample.get('symbol', '[unknown]')] += sample['period']
            continue
        # Separate opcode/address forms; operand-only changes are not distinguished.
        identity = (block['guest'], block['csBase'], block['code32'],
                    tuple((i[1], i[2]) for i in block['instructions']))
        counter[identity] += sample['period']
        if counter is via_helper:
            helper_symbols[identity][sample.get('symbol', '[unknown]')] += sample['period']
        definitions[identity] = block
    combined = direct + via_helper
    return {
        'sampleCount': len(samples), 'sampledCpuSeconds': total / 1e9,
        'firstSampleMonotonicNs': min((s['time'] for s in samples), default=None),
        'lastSampleMonotonicNs': max((s['time'] for s in samples), default=None),
        'attributedGuestBlockForms': len(combined),
        'directJitSeconds': sum(direct.values()) / 1e9,
        'jitCallerSeconds': sum(via_helper.values()) / 1e9,
        'unattributedSeconds': unmatched / 1e9,
        'unattributedSymbols': [{'symbol': name, 'seconds': count / 1e9}
                               for name, count in unattributed_symbols.most_common(20)],
        'hotBlocks': [{
            'guestLinear': hex(key[0]), 'csBase': hex(key[1]), 'code32': key[2],
            'directSeconds': direct[key] / 1e9, 'callerSeconds': via_helper[key] / 1e9,
            'helperSymbols': [{'symbol': name, 'seconds': count / 1e9}
                              for name, count in helper_symbols[key].most_common(8)],
            'instructions': [{'guestLinear': hex(i[1]), 'opcodeByte': hex(i[2])}
                             for i in definitions[key]['instructions']]
        } for key, _ in combined.most_common(40)],
        'limitations': [
            'CPU-time sampling is not an instruction execution count.',
            'Opcode bytes include prefixes; operands are not recorded.',
            'Nearest mapped caller attribution depends on call-chain completeness.',
            'Kernel samples may inherit the interrupted user call chain; attribution is not proof of causality.',
            'Translation regions include generated guards and helper exits.',
            'Block identities include guest address and opcode form, not complete operands.'
        ]
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', required=True, type=Path)
    parser.add_argument('--perf', required=True, type=Path)
    parser.add_argument('--simpleperf-dir', required=True)
    parser.add_argument('--symbols')
    parser.add_argument('--tid', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    sys.path.insert(0, args.simpleperf_dir)
    from simpleperf_report_lib import ReportLib
    events = [json.loads(line) for line in args.metadata.read_text(encoding='utf-8').splitlines()]
    if not events or events[0].get('version') != 1:
        raise ValueError('Unsupported or missing metadata header')
    lib = ReportLib()
    samples = []
    try:
        lib.SetRecordFile(str(args.perf))
        if args.symbols:
            lib.SetSymfs(args.symbols)
        lib.SetTraceOffCpuMode('on-cpu')
        while (sample := lib.GetNextSample()):
            if sample.tid != args.tid:
                continue
            chain = lib.GetCallChainOfCurrentSample()
            samples.append({'time': sample.time, 'ip': sample.ip, 'period': sample.period,
                            'symbol': lib.GetSymbolOfCurrentSample().symbol_name,
                            'callchain': [chain.entries[i].ip for i in range(chain.nr)]})
    finally:
        lib.Close()
    if not samples:
        raise ValueError('No on-CPU samples for the requested emulation thread')
    result = summarize(events, samples)
    result['metadataEvents'] = len(events)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('hotBlocks', 'limitations')}, indent=2))


if __name__ == '__main__':
    main()
