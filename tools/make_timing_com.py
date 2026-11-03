#!/usr/bin/env python3
"""Writes core/test/dos/timing/TIMING.COM, the shared PC boot test's clock program for KairoDos
(KairoDos#138): 8086 code that runs LOOP 200 times 65535 times, then prints KAIRO TIMING DONE and
ends. DOSBox's shell runs batch commands as host code, so only guest code times the emulated CPU.

    make_timing_com.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MESSAGE = b"KAIRO TIMING DONE$"
CODE = bytes([
    0xBB, 0xC8, 0x00,        # 0100  mov bx, 200
    0xB9, 0xFF, 0xFF,        # 0103  mov cx, 0FFFFh
    0xE2, 0xFE,              # 0106  loop 0106
    0x4B,                    # 0108  dec bx
    0x75, 0xF8,              # 0109  jnz 0103
    0xBA, 0x17, 0x01,        # 010B  mov dx, 0117h (the message)
    0xB4, 0x09,              # 010E  mov ah, 9 (print a $-ended string)
    0xCD, 0x21,              # 0110  int 21h
    0xB8, 0x00, 0x4C,        # 0112  mov ax, 4C00h (end)
    0xCD, 0x21,              # 0115  int 21h
])


def main():
    if 0x100 + len(CODE) != 0x117:
        raise SystemExit(f"the message is not at 0117h but {0x100 + len(CODE):04X}h")
    target = ROOT / "core/test/dos/timing/TIMING.COM"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(CODE + MESSAGE)
    print(f"{target}: {len(CODE) + len(MESSAGE)} bytes")


if __name__ == "__main__":
    main()
