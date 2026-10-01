# SPDX-License-Identifier: GPL-2.0-or-later
"""Attribute ARM64 PC samples using time-valid completed translation bytes.

Requires opt-in hostCodeHex metadata. Aggregates instruction families, not
instruction latency or dynamic execution counts. Raw guest/JIT bytes stay local.
"""
import argparse
from collections import Counter
import json
import struct
import re
from pathlib import Path
import sys

from analyze_guest_profile import Timeline


def family(mnemonic):
    if mnemonic == 'nop':
        return 'nop'
    if mnemonic in ('bl', 'blr'):
        return 'call'
    if mnemonic in ('br', 'ret'):
        return 'indirect branch/return'
    if mnemonic == 'b' or mnemonic.startswith(('b.', 'cb', 'tb')):
        return 'direct/conditional branch'
    if mnemonic.startswith(('ld', 'st')):
        return 'load' if mnemonic.startswith('ld') else 'store'
    if mnemonic in ('adr', 'adrp', 'mov', 'movk', 'movz', 'movn'):
        return 'address/immediate/register move'
    return 'arithmetic/other'


def call_sequence(code, offset):
    """Recognize the backend's exact five-word x10 call, not nearby guesses."""
    fixed = (0xd280000a, 0xf2a0000a, 0xf2c0000a, 0xf2e0000a)
    for slot in range(5):
        start = offset - slot * 4
        if start < 0 or start + 20 > len(code):
            continue
        words = struct.unpack_from('<5I', code, start)
        if words[4] != 0xd63f0140:
            continue
        if all((word & 0xffe0001f) == opcode for word,opcode in zip(words[:4],fixed)):
            target = sum(((word >> 5) & 65535) << (16*i) for i,word in enumerate(words[:4]))
            return slot, target
    return None


def block_link_sequence(code, offset):
    """Recognize the baseline x10 -> x12 -> x10 -> branch dependency."""
    for distance in range(7):
        branch = offset + distance*4
        if branch < 8 or branch+4 > len(code):
            continue
        first, second, jump = struct.unpack_from('<3I',code,branch-8)
        if jump != 0xd61f0140 or first != 0xf940014c or (second & 0xffc003ff) != 0xf940018a:
            continue
        if offset >= branch-8:
            return {branch-8:'load target block',branch-4:'load target code',branch:'branch'}[offset]
        start = branch-8
        for _ in range(4):
            if start < 4:
                break
            word = struct.unpack_from('<I',code,start-4)[0]
            opcode = word & 0xff80001f
            if opcode not in (0xd280000a,0xf280000a):
                break
            start -= 4
            if opcode == 0xd280000a:
                if offset >= start:
                    return 'link address setup'
                break
    return None


def summarize(events, samples, targets=None):
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    disassembler = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    timeline = Timeline()
    pending = iter(events)
    event = next(pending, None)
    counts, mnemonics, helper_context = Counter(), Counter(), Counter()
    calls = Counter()
    links = Counter()
    load_bases = Counter()
    adjacent_register_forwarding = 0
    total = mapped = missing = undecoded = 0
    for sample in sorted(samples, key=lambda s: s['time']):
        while event is not None and event['time'] <= sample['time']:
            timeline.apply(event)
            event = next(pending, None)
        period = sample['period']
        total += period
        block = timeline.lookup(sample['ip'])
        if block is None:
            continue
        mapped += period
        if 'hostCodeHex' not in block:
            missing += period
            continue
        if '_code' not in block:
            block['_code'] = bytes.fromhex(block['hostCodeHex'])
            if len(block['_code']) != block['size']:
                raise ValueError('Recorded code size does not match block range')
        offset = sample['ip'] - block['host']
        if offset % 4:
            raise ValueError('Unaligned ARM64 sampled PC')
        instruction = next(disassembler.disasm(block['_code'][offset:offset+4], sample['ip']), None)
        if instruction is None:
            undecoded += period
            continue
        counts[family(instruction.mnemonic)] += period
        mnemonics[instruction.mnemonic] += period
        if instruction.mnemonic.startswith('ld'):
            match = re.search(r'\[(x\d+|sp)(?:,|\])', instruction.op_str)
            load_bases[match[1] if match else 'other'] += period
        if instruction.mnemonic == 'ldr' and offset >= 4 and '[x20' in instruction.op_str:
            previous = next(disassembler.disasm(block['_code'][offset-4:offset], sample['ip']-4), None)
            if previous and previous.mnemonic == 'str':
                dest, address = instruction.op_str.split(',',1)
                source, prior_address = previous.op_str.split(',',1)
                if address == prior_address and dest.startswith('w') and source.startswith('w'):
                    adjacent_register_forwarding += period
        sequence = call_sequence(block['_code'], offset)
        if sequence:
            slot, target = sequence
            name = (targets or {}).get(target, 'unresolved call target')
            calls[(name, 'target setup' if slot < 4 else 'indirect call')] += period
        link = block_link_sequence(block['_code'],offset)
        if link:
            links[link] += period
        # A proximity label, not a claim of dependency or causality. Decode only
        # within the same live block; never cross a reused translation boundary.
        for distance in range(1, 9):
            pos = offset - distance * 4
            if pos < 0:
                break
            previous = next(disassembler.disasm(block['_code'][pos:pos+4], block['host']+pos), None)
            if previous and previous.mnemonic in ('bl', 'blr'):
                helper_context[(distance, instruction.mnemonic)] += period
                break
    return {
        'sampleCount': len(samples), 'totalSampledSeconds': total / 1e9,
        'directJitSeconds': mapped / 1e9, 'missingCodeSeconds': missing / 1e9,
        'undecodedSeconds': undecoded / 1e9,
        'familiesSeconds': {k: v/1e9 for k,v in counts.most_common()},
        'mnemonicsSeconds': {k: v/1e9 for k,v in mnemonics.most_common()},
        'loadBaseRegisterSeconds': {k:v/1e9 for k,v in load_bases.most_common()},
        'adjacentCpuRegisterStoreLoadSampledSeconds': adjacent_register_forwarding/1e9,
        'fixedCallSequenceSeconds': [
            {'target': k[0], 'part': k[1], 'seconds': v/1e9}
            for k,v in calls.most_common()],
        'twoLoadBlockLinkSequenceSeconds': {k:v/1e9 for k,v in links.most_common()},
        'withinEightInstructionsAfterCall': [
            {'distance': k[0], 'mnemonic': k[1], 'seconds': v/1e9}
            for k,v in helper_context.most_common()],
        'limitations': ['Sampling skid prevents exact instruction-latency claims.',
                       'Instruction families and post-call proximity do not prove causality.',
                       'Only direct JIT samples are classified; helpers remain separate.',
                       'Adjacent store/load candidates still need control-flow and alias-safety checks.',
                       'Diagnostic logging and build options can change performance.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', required=True, type=Path)
    parser.add_argument('--perf', required=True, type=Path)
    parser.add_argument('--simpleperf-dir', required=True)
    parser.add_argument('--tid', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--targets', type=Path, help='Local JSON mapping of runtime function addresses to names')
    args = parser.parse_args()
    sys.path.insert(0, args.simpleperf_dir)
    from simpleperf_report_lib import ReportLib
    lib = ReportLib()
    samples = []
    try:
        lib.SetRecordFile(str(args.perf))
        while (s := lib.GetNextSample()):
            if s.tid == args.tid:
                samples.append(dict(time=s.time, ip=s.ip, period=s.period))
    finally:
        lib.Close()
    if not samples:
        raise ValueError('No samples for requested thread')
    with args.metadata.open(encoding='utf-8') as stream:
        targets = {int(k,0):v for k,v in json.loads(args.targets.read_text()).items()} if args.targets else None
        result = summarize((json.loads(line) for line in stream), samples, targets)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in
                     ('withinEightInstructionsAfterCall', 'fixedCallSequenceSeconds')}, indent=2))


if __name__ == '__main__':
    main()
