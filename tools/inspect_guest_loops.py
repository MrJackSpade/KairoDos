# SPDX-License-Identifier: GPL-2.0-or-later
"""Screen sampled guest blocks for short backward branches using a local RAM snapshot.

Requires Capstone (analysis used 5.0.7). Output contains guest disassembly: keep it
local. Candidates are NOT classified as polling or safe to skip by this tool.
"""
import argparse
import json
from pathlib import Path

from capstone import Cs, CS_ARCH_X86, CS_MODE_16, CS_MODE_32, CS_GRP_JUMP, CS_OP_IMM


def decode_blocks(blocks, ram):
    valid, rejected = [], []
    decoders = {False: Cs(CS_ARCH_X86, CS_MODE_16), True: Cs(CS_ARCH_X86, CS_MODE_32)}
    for decoder in decoders.values():
        decoder.detail = True
    for block in blocks:
        start = int(block['guestLinear'], 16)
        first_bytes = {}
        for instruction in block['instructions']:
            # Prefix records share the instruction start; compare its first byte.
            first_bytes.setdefault(int(instruction['guestLinear'], 16),
                                   int(instruction['opcodeByte'], 16))
        addresses = sorted(first_bytes)
        reason = None
        if not addresses or start < 0 or addresses[-1] >= len(ram):
            reason = 'outside snapshot or empty block'
        elif any(ram[address] != opcode for address, opcode in first_bytes.items()):
            reason = 'opcode differs from recorded translation'
        else:
            instructions = [i for i in decoders[block['code32']].disasm(
                ram[start:addresses[-1] + 16], start) if i.address <= addresses[-1]]
            if [i.address for i in instructions] != addresses:
                reason = 'instruction boundaries differ from recorded translation'
        if reason:
            rejected.append({'guestLinear': block['guestLinear'], 'reason': reason})
        else:
            valid.append((block, instructions))
    return valid, rejected


def inspect(blocks, ram, max_span=512):
    valid, rejected = decode_blocks(blocks, ram)
    edges = {}
    for block, instructions in valid:
        # 16-bit relative targets require segment-aware wrapping; do not guess.
        if not block['code32']:
            continue
        for instruction in instructions:
            if instruction.group(CS_GRP_JUMP) and instruction.operands[0].type == CS_OP_IMM:
                target = instruction.operands[0].imm
                if 0 <= target <= instruction.address and instruction.address - target < max_span:
                    edges[target, instruction.address] = instruction.size
    decoder = Cs(CS_ARCH_X86, CS_MODE_32)
    candidates = []
    for (start, end), size in edges.items():
        members = [b for b, _ in valid if b['code32'] and start <= int(b['guestLinear'], 16) <= end]
        candidates.append({
            'start': hex(start), 'end': hex(end),
            'overlappingBlockCpuSeconds': sum(b['directSeconds'] + b['callerSeconds'] for b in members),
            'instructions': [{'address': hex(i.address), 'mnemonic': i.mnemonic, 'operands': i.op_str}
                             for i in decoder.disasm(ram[start:end + size], start)],
        })
    return {
        'validatedBlockForms': len(valid), 'rejected': rejected,
        'validatedCpuSeconds': sum(b['directSeconds'] + b['callerSeconds'] for b, _ in valid),
        'maxSpanBytes': max_span,
        'candidates': sorted(candidates, key=lambda x: -x['overlappingBlockCpuSeconds']),
        'limitations': [
            'Candidates overlap and their CPU times must not be added.',
            'Block cost can include instructions outside the loop; this is a screening estimate.',
            'Snapshot operands are not contemporaneous translation operands; only opcode starts and boundaries are validated.',
            'Only sampled 32-bit short backward branches are screened, not every possible wait path.',
            'A backward branch, no memory writes, or repeated registers alone does not prove idleness.'
        ]
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True, type=Path)
    parser.add_argument('--ram', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--max-span', type=int, default=512)
    args = parser.parse_args()
    if args.max_span <= 0:
        raise ValueError('Maximum span must be positive')
    result = inspect(json.loads(args.profile.read_text())['hotBlocks'], args.ram.read_bytes(), args.max_span)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"Validated {result['validatedBlockForms']} forms; rejected {len(result['rejected'])}; "
          f"found {len(result['candidates'])} overlapping branch candidates")


if __name__ == '__main__':
    main()
