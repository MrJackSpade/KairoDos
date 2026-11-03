#!/usr/bin/env python3
"""Writes the shared PC boot test's DOS programs for KairoDos: core/test/dos/timing/TIMING.COM, its clock program
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


# core/test/dos/mouse/MOUSE.COM, the guest input check's mouse program (KairoDos#132): asks the DOS
# mouse driver for the pointer (INT 33h, AX 3) for good, as a mouse program does.
MOUSE = bytes([
    0xB8, 0x03, 0x00,        # 0100  mov ax, 3 (the pointer's position and buttons)
    0xCD, 0x33,              # 0103  int 33h
    0xEB, 0xF9,              # 0105  jmp 0100
])


def main():
    if 0x100 + len(CODE) != 0x117:
        raise SystemExit(f"the message is not at 0117h but {0x100 + len(CODE):04X}h")
    for name, data in (("timing/TIMING.COM", CODE + MESSAGE), ("mouse/MOUSE.COM", MOUSE)):
        target = ROOT / "core/test/dos" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(f"{target}: {len(data)} bytes")


if __name__ == "__main__":
    main()
