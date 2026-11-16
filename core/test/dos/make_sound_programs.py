"""Builds the sound test programs (KairoDos#154): small DOS COM programs that each play one sound
device, so the PC boot test's "sound" check runs every kind of sound device the mixer takes.

    python make_sound_programs.py   (writes the .COM files into sound/, next to this script)

OPLTONE.COM  plays notes on the OPL (AdLib) ports at 388h: the OPL worker's path, and ESFM's with
             oplmode = esfm.
DISNEY.COM   plays a sawtooth on the Disney Sound Source (lpt_dac = disney): a device that queues
             its sound, resampled by the mixer.
GUS.COM      loads a sawtooth into the Gravis UltraSound's memory and loops it on voice 0.
CMS.COM      plays notes on the Creative Music System / Game Blaster (sbtype = gb).
"""

import os


class Program:
    """A COM program (loaded at 100h) of 16-bit real mode code, with labels for short jumps."""

    def __init__(self):
        self.code = bytearray()
        self.labels = {}
        self.fixups = []  # (offset of the rel8 byte, label)
        self.calls = []   # (offset of the rel16, label)

    def emit(self, *data):
        self.code += bytes(data)

    def label(self, name):
        self.labels[name] = len(self.code)

    def mov_dx(self, v): self.emit(0xBA, v & 0xFF, v >> 8)
    def mov_ax(self, v): self.emit(0xB8, v & 0xFF, v >> 8)
    def mov_bx(self, v): self.emit(0xBB, v & 0xFF, v >> 8)
    def mov_cx(self, v): self.emit(0xB9, v & 0xFF, v >> 8)
    def mov_al(self, v): self.emit(0xB0, v)
    def out_al(self): self.emit(0xEE)        # out dx, al
    def out_ax(self): self.emit(0xEF)        # out dx, ax
    def mov_al_ah(self): self.emit(0x88, 0xE0)
    def mov_al_bl(self): self.emit(0x88, 0xD8)
    def mov_ax_bx(self): self.emit(0x89, 0xD8)
    def xor_bx_bx(self): self.emit(0x31, 0xDB)
    def inc_dx(self): self.emit(0x42)
    def inc_bx(self): self.emit(0x43)
    def dec_bx(self): self.emit(0x4B)
    def cmp_bx(self, v): self.emit(0x81, 0xFB, v & 0xFF, v >> 8)
    def ret(self): self.emit(0xC3)

    def _short(self, opcode, name):
        self.emit(opcode, 0)
        self.fixups.append((len(self.code) - 1, name))

    def jmp(self, name): self._short(0xEB, name)
    def jb(self, name): self._short(0x72, name)
    def jnz(self, name): self._short(0x75, name)
    def loop(self, name): self._short(0xE2, name)

    def call(self, name):
        self.emit(0xE8, 0, 0)
        self.calls.append((len(self.code) - 2, name))

    def out(self, port, value):
        """out port, value (byte)."""
        self.mov_dx(port)
        self.mov_al(value)
        self.out_al()

    def delay(self, outer):
        """A busy wait of [outer] times 65535 loop turns (uses bx and cx)."""
        name = 'delay%d' % len(self.code)
        self.mov_bx(outer)
        self.label(name + 'o')
        self.mov_cx(0xFFFF)
        self.label(name + 'i')
        self.loop(name + 'i')
        self.dec_bx()
        self.jnz(name + 'o')

    def build(self):
        for at, name in self.fixups:
            rel = self.labels[name] - (at + 1)
            assert -128 <= rel <= 127, (name, rel)
            self.code[at] = rel & 0xFF
        for at, name in self.calls:
            rel = self.labels[name] - (at + 2)
            self.code[at] = rel & 0xFF
            self.code[at + 1] = (rel >> 8) & 0xFF
        return bytes(self.code)


def opl_tone():
    p = Program()
    # Channel 0: a plain two-operator voice, both outputs on.
    for reg, val in [(0x20, 0x01), (0x40, 0x10), (0x60, 0xF0), (0x80, 0x77), (0x23, 0x01),
                     (0x43, 0x00), (0x63, 0xF0), (0x83, 0x77), (0xC0, 0x30)]:
        p.mov_ax(val << 8 | reg)
        p.call('write')
    p.label('again')
    for freq_low, key_on in [(0x98, 0x31), (0x6B, 0x31), (0x81, 0x32)]:
        p.mov_ax(freq_low << 8 | 0xA0)
        p.call('write')
        p.mov_ax(key_on << 8 | 0xB0)
        p.call('write')
        p.delay(6)
        p.mov_ax((key_on & 0x1F) << 8 | 0xB0)
        p.call('write')
        p.delay(2)
    p.jmp('again')
    # write: register al, value ah.
    p.label('write')
    p.mov_dx(0x388)
    p.out_al()
    p.inc_dx()
    p.mov_al_ah()
    p.out_al()
    p.ret()
    return p.build()


def disney():
    p = Program()
    p.xor_bx_bx()
    p.label('again')
    p.mov_dx(0x378)
    p.mov_al_bl()
    p.out_al()
    # The rising edge of SELECT (control bit 3) clocks the data into the FIFO.
    p.out(0x37A, 0x00)
    p.out(0x37A, 0x08)
    p.emit(0x80, 0xC3, 0x08)  # add bl, 8: the next step of the sawtooth
    p.mov_cx(40)
    p.label('wait')
    p.loop('wait')
    p.jmp('again')
    return p.build()


def gus():
    p = Program()
    base = 0x240

    def reg8(r, v):
        p.out(base + 0x103, r)
        p.out(base + 0x105, v)

    def reg16(r, v):
        p.out(base + 0x103, r)
        p.mov_dx(base + 0x104)
        p.mov_ax(v)
        p.out_ax()

    reg8(0x4C, 0x00)       # reset
    reg8(0x4C, 0x03)       # run, DAC on
    reg8(0x0E, 13)         # 14 active voices
    # A 256-byte sawtooth at DRAM address 0.
    p.xor_bx_bx()
    p.label('load')
    p.out(base + 0x103, 0x43)
    p.mov_dx(base + 0x104)
    p.mov_ax_bx()
    p.out_ax()
    p.out(base + 0x103, 0x44)
    p.out(base + 0x105, 0x00)
    p.mov_dx(base + 0x107)
    p.mov_al_bl()
    p.out_al()
    p.inc_bx()
    p.cmp_bx(256)
    p.jb('load')
    # Voice 0 loops it: addresses are the sample address shifted left by 9.
    p.out(base + 0x102, 0)
    reg16(0x01, 0x0400)    # one sample a step
    reg16(0x02, 0x0000)    # start 0
    reg16(0x03, 0x0000)
    reg16(0x04, 0x0001)    # end 255
    reg16(0x05, 0xFE00)
    reg16(0x0A, 0x0000)    # from 0
    reg16(0x0B, 0x0000)
    reg16(0x09, 0xF000)    # loud
    reg8(0x0C, 0x07)       # centre
    reg8(0x0D, 0x03)       # volume ramp stopped: the volume stays where it is set
    reg8(0x00, 0x08)       # loop, play
    p.label('again')
    reg16(0x01, 0x0400)
    p.delay(6)
    reg16(0x01, 0x0600)
    p.delay(6)
    p.jmp('again')
    return p.build()


def cms():
    p = Program()

    def reg(r, v):
        p.out(0x221, r)    # chip 1: address at 221h, data at 220h
        p.out(0x220, v)

    reg(0x1C, 0x02)        # reset
    reg(0x1C, 0x01)        # sound on
    reg(0x00, 0xFF)        # channel 0 amplitude, both sides
    reg(0x10, 0x04)        # channels 0 and 1 octave
    reg(0x14, 0x01)        # channel 0 frequency on
    reg(0x15, 0x00)        # noise off
    p.label('again')
    reg(0x08, 0x40)
    p.delay(6)
    reg(0x08, 0xA0)
    p.delay(6)
    p.jmp('again')
    return p.build()


here = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sound')
for name, make in [('OPLTONE.COM', opl_tone), ('DISNEY.COM', disney), ('GUS.COM', gus),
                   ('CMS.COM', cms)]:
    data = make()
    with open(os.path.join(here, name), 'wb') as out:
        out.write(data)
    print(name, len(data), 'bytes')
