"""Generate tiny original mode-13 DOS fixtures; no game files are used."""
from pathlib import Path
import argparse

p = argparse.ArgumentParser()
p.add_argument("output", type=Path)
p.add_argument("case", choices=("static", "pixel", "unused_palette"))
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
body = {
    "static": "nop",
    "pixel": "mov al, bl\n and al, 1\n mov byte ptr es:[0], al",
    "unused_palette": "mov dx, 0x3c8\n mov al, 255\n out dx, al\n inc dx\n mov al, bl\n and al, 63\n out dx, al\n xor al, al\n out dx, al\n out dx, al",
}[a.case]
(a.output / "probe.s").write_text(""".intel_syntax noprefix
.code16
.global _start
_start:
 mov ax, 0x13
 int 0x10
 mov ax, 0xa000
 mov es, ax
 xor di, di
 xor ax, ax
 mov cx, 32000
 cld
 rep stosw
 mov ax, 0x40
 mov ds, ax
again:
 mov bx, word ptr [0x6c]
""" + body + "\n jmp again\n", encoding="ascii")
(a.output / "probe.conf").write_text("""[sdl]
output=texturenb
[dosbox]
machine=svga_s3
[cpu]
core=dynamic
cycles=10000
[midi]
mididevice=none
[sblaster]
sbtype=none
[autoexec]
mount c /data/local/tmp/kairo63
c:
PROBE.COM
exit
""", encoding="ascii")
