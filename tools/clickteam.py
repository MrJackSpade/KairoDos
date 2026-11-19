"""Clickteam Install Creator installers (The Collection Chamber's downloads), read in place.

The same reader as shared ClickteamInstaller.kt, for catalog generation on the PC; its docstring
there describes the layout. Nothing of an installer runs.

    python tools/clickteam.py list <Install-XXX.exe>
    python tools/clickteam.py extract <Install-XXX.exe> <out_dir> [path-substring ...]
"""
import bz2
import hashlib
import os
import struct
import sys
import zlib

SIGNATURE = bytes([0x77, 0x77, 0x67, 0x54, 0x29, 0x48])
FILE_LIST = 0x143A
DATA = 0x7F7F
PART_HEADER = 4
MAX_BLOCK = 64 * 1024 * 1024
# Record layouts: header size, then a file record's field offsets from the record's start. A record
# whose flags have TIMES holds three FILETIMEs before its path; one without starts its path that much sooner.
LAYOUTS = {
    "current": dict(header=4, offset=6, packed=10, size=18, checksum=22, flags=26, reference=40, name=68),
    "earlier": dict(header=8, offset=28, packed=32, size=24, checksum=20, flags=8, reference=None, name=64),
}
TIMES = 0x08
# Record types that are files; type 2 is the uninstaller's shortcut.
FILE_TYPES = (0, 1)
# A type 1 file is a patch against another file of the list (its reference record's index, counting
# every record, is the u32 at offset 40): Clickteam's patch format, as ScummVM's
# common/compression/clickteam.cpp applies it. See patch().
PATCHED = 1
TIMES_SIZE = 24


def checksum(data):
    """A record's checksum of its file: per byte, rotate left by 1 then add the byte (32 bits)."""
    value = 0
    for b in data:
        value = (((value << 1) | (value >> 31)) + b) & 0xFFFFFFFF
    return value


def patch_block(raw, pos, name):
    """A patch block at [pos]: u8 codec (bit 0 packed, bit 1 u32 unpacked size, bit 2 u32 packed size),
    unpacked size, packed size when packed, then a method byte and stream (packed) or the bytes."""
    codec = raw[pos]
    pos += 1
    if codec > 7 or codec & 5 == 4:
        raise ValueError(f"{name}: unknown patch block codec {codec}")
    unpacked, = struct.unpack_from("<I" if codec & 2 else "<H", raw, pos)
    pos += 4 if codec & 2 else 2
    packed = unpacked
    if codec & 1:
        packed, = struct.unpack_from("<I" if codec & 4 else "<H", raw, pos)
        pos += 4 if codec & 4 else 2
    body = raw[pos:pos + packed]
    if len(body) != packed:
        raise ValueError(f"{name}: a patch block runs past its data")
    data = unpack(body[0], body[1:], unpacked) if codec & 1 else body
    return data, pos + packed


def patch(raw, size, reference, name):
    """A patched file from its record's data (4 bytes, the operations block, the literals block) and its reference."""
    ops, pos = patch_block(raw, 4, name)
    literals, pos = patch_block(raw, pos, name)
    if pos != len(raw):
        raise ValueError(f"{name}: {len(raw) - pos} bytes after the patch")
    out = bytearray()
    p = lit = base = 0

    def signed(value, bits):
        return value - (1 << bits) if value & (1 << (bits - 1)) else value

    def away(value, amount):
        return value + amount if value >= 0 else value - amount

    while p < len(ops):
        b = ops[p]
        p += 1
        length = 0
        if b & 0x80:
            k = (b >> 5) & 3
            if k == 0:
                k = (b & 0x1F) + 1
            else:
                length = (b & 0x1F) + 2
            if lit + k > len(literals):
                raise ValueError(f"{name}: the patch uses more literals than it holds")
            out += literals[lit:lit + k]
            lit += k
        elif b == 0:
            length = struct.unpack_from("<H", ops, p)[0] + 0x81
            p += 2
        else:
            length = b + 1
        if length:
            c = ops[p]
            p += 1
            if c & 0x80:
                if not c & 0x40:
                    delta = signed(c & 0x3F, 6)
                else:
                    v = (c & 0x3F) << 16 | struct.unpack_from(">H", ops, p)[0]
                    p += 2
                    if v == 0x100000:
                        delta = struct.unpack_from(">i", ops, p)[0]
                        p += 4
                    else:
                        delta = away(signed(v, 22), 0x4020)
            else:
                delta = away(signed((c & 0x7F) << 8 | ops[p], 15), 0x20)
                p += 1
            at = base + delta
            if at < 0 or at + length > len(reference):
                raise ValueError(f"{name}: a patch copy of {length} at {at} leaves its {len(reference)}-byte reference")
            out += reference[at:at + length]
            base = at + length
    if lit != len(literals):
        raise ValueError(f"{name}: {len(literals) - lit} patch literals unused")
    if len(out) != size:
        raise ValueError(f"{name}: the patch made {len(out)} bytes, not {size}")
    return bytes(out)


def part_name(program, index):
    return os.path.splitext(program)[0] + ".D%02d" % index


class MissingPart(Exception):
    def __init__(self, index):
        super().__init__(f"the installer's part {index} is missing")
        self.index = index


class Source:
    """The program and its parts (.D01 on, each after a 4-byte header) as one stream."""

    def __init__(self, paths):
        self.pieces = []
        at = 0
        for i, path in enumerate(paths):
            skip = 0 if i == 0 else PART_HEADER
            length = os.path.getsize(path) - skip
            self.pieces.append((open(path, "rb"), at, length, skip))
            at += length
        self.size = at

    def read(self, pos, n):
        out = bytearray()
        while len(out) < n:
            at = pos + len(out)
            if at >= self.size:
                raise MissingPart(len(self.pieces))
            handle, start, length, skip = next(p for p in reversed(self.pieces) if p[1] <= at)
            handle.seek(at - start + skip)
            out += handle.read(min(n - len(out), start + length - at))
        return bytes(out)

    def close(self):
        for handle, *_ in self.pieces:
            handle.close()


def overlay_offset(program):
    with open(program, "rb") as f:
        head = f.read(4096)
    if head[:2] != b"MZ":
        return None
    pe = struct.unpack_from("<I", head, 0x3C)[0]
    with open(program, "rb") as f:
        f.seek(pe)
        header = f.read(24)
        if header[:4] != b"PE\0\0":
            return None
        sections, = struct.unpack_from("<H", header, 6)
        optional, = struct.unpack_from("<H", header, 20)
        f.seek(pe + 24 + optional)
        table = f.read(40 * sections)
    end = 0
    for i in range(sections):
        raw_size, raw_pointer = struct.unpack_from("<II", table, 40 * i + 16)
        end = max(end, raw_pointer + raw_size)
    return end if sections else None


def is_installer(program):
    overlay = overlay_offset(program)
    if overlay is None:
        return False
    with open(program, "rb") as f:
        f.seek(overlay)
        return f.read(6) == SIGNATURE


def unpack(method, data, size):
    if method == 1:
        out = zlib.decompress(data)
    elif method == 2:
        out = bz2.decompress(data)
    else:
        raise ValueError(f"unknown compression method {method}")
    if len(out) != size:
        raise ValueError(f"unpacked to {len(out)} bytes, not {size}")
    return out


def parse(data, layout):
    """The files of the file list in [layout]; fails unless its records tile it and number its count."""
    count, = struct.unpack_from("<I", data, 0)
    pos, records, files, names = 4, 0, [], set()
    while pos < len(data):
        if layout["header"] == 4:
            size, kind = struct.unpack_from("<HH", data, pos)
        else:
            size, kind = struct.unpack_from("<II", data, pos)
        if size < layout["header"] or size > len(data) - pos:
            raise ValueError("record outside the list")
        if kind in FILE_TYPES:
            timed = data[pos + layout["flags"]] & TIMES
            start = pos + layout["name"] - (0 if timed else TIMES_SIZE)
            end = data.index(b"\0", start, pos + size)
            name = data[start:end].decode("cp1252").replace("\\", "/")
            # After the path: the shortcuts made to it (a Start menu group, then a Start menu and a
            # desktop shortcut, each its name, arguments and icon; an empty name is none).
            tail = data[end + 1:pos + size].split(b"\0")
            shortcuts = [dict(name=tail[i].decode("cp1252"), arguments=tail[i + 1].decode("cp1252"))
                         for i in (1, 4) if len(tail) > i + 1 and tail[i]]
            parts = name.split("/")
            if not name or name.startswith("/") or ":" in name or any(p in ("", ".", "..") for p in parts):
                raise ValueError(f"unsafe path {name!r}")
            if name.lower() in names:
                raise ValueError(f"duplicate path {name!r}")
            names.add(name.lower())
            field = lambda key: struct.unpack_from("<I", data, pos + layout[key])[0]
            if kind == PATCHED and layout["reference"] is None:
                raise ValueError(f"{name}: a patched file in the earlier layout, whose reference is not known")
            files.append(dict(name=name, kind=kind, index=records,
                              reference=field("reference") if kind == PATCHED else None,
                              offset=field("offset"), packed=field("packed"),
                              size=field("size"), checksum=field("checksum"), shortcuts=shortcuts))
        pos += size
        records += 1
    if pos != len(data) or records != count:
        raise ValueError("records do not tile the list")
    # The files' data tiles the data block; identical files share one copy. A misread record breaks this.
    ordered = sorted(files, key=lambda f: f["offset"])
    for before, f in zip(ordered, ordered[1:]):
        if f["offset"] not in (before["offset"], before["offset"] + before["packed"]):
            raise ValueError(f"{f['name']}: its data does not follow {before['name']}'s")
    return files


class Installer:
    def __init__(self, program):
        directory, name = os.path.split(program)
        siblings = {n.lower(): n for n in os.listdir(directory or ".")}
        paths = [program]
        while part_name(name, len(paths)).lower() in siblings:
            paths.append(os.path.join(directory, siblings[part_name(name, len(paths)).lower()]))
        self.source = Source(paths)
        overlay = overlay_offset(program)
        if overlay is None or self.source.read(overlay, 6) != SIGNATURE:
            raise ValueError("not a Clickteam installer")
        pos = overlay + 6
        file_list = None
        self.blocks = {}  # every block before the data, unpacked, by ID
        while True:
            block, _, size = struct.unpack("<HHI", self.source.read(pos, 8))
            pos += 8
            if block == DATA:
                break
            if size > MAX_BLOCK:
                raise ValueError(f"block {block:04x} is {size} bytes")
            raw = self.source.read(pos, size)
            self.blocks[block] = unpack(raw[4], raw[5:], struct.unpack_from("<I", raw, 0)[0])
            if block == FILE_LIST:
                file_list = self.blocks[block]
            pos += size
        if file_list is None:
            raise ValueError("the installer has no file list")
        fits, failures = [], []
        for label, layout in LAYOUTS.items():
            try:
                fits.append(parse(file_list, layout))
            except (ValueError, struct.error) as error:
                failures.append(f"{label}: {error}")
        if len(fits) != 1:
            raise ValueError(("ambiguous" if fits else "unrecognised") + " file list: " + "; ".join(failures))
        self.files = fits[0]
        self.file_list = file_list
        self.data = pos
        needed = max((pos + f["offset"] + f["packed"] + 4 for f in self.files), default=pos)
        if needed > self.source.size:
            raise MissingPart(len(paths))

    def content_id(self):
        """KairoDos's content ID of the installer (DosClickteam.PREFIX)."""
        return "sha256-dos-clickteam-v1:" + hashlib.sha256(self.file_list).hexdigest()

    def read(self, f):
        if f["kind"] == PATCHED:
            by_index = {x["index"]: x for x in self.files}
            reference = by_index.get(f["reference"])
            if reference is None:
                raise ValueError(f"{f['name']}: its reference record {f['reference']} is not a file")
            raw = self.source.read(self.data + f["offset"], f["packed"] + 4)
            data = patch(raw, f["size"], self.read(reference), f["name"])
            if checksum(data) != f["checksum"]:
                raise ValueError(f"{f['name']}: the patched file's checksum is not its record's")
            return data
        if f["packed"] <= 1:  # an empty file is its method byte alone
            if f["size"]:
                raise ValueError(f"{f['name']}: {f['size']} bytes with no stream")
            return b""
        raw = self.source.read(self.data + f["offset"], f["packed"] + 4)
        try:
            return unpack(raw[4], raw[5:], f["size"])
        except Exception as error:
            raise ValueError(f"{f['name']}: {error}") from error

    def close(self):
        self.source.close()


def main():
    command, program = sys.argv[1], sys.argv[2]
    installer = Installer(program)
    try:
        if command == "list":
            for f in installer.files:
                print(f"{f['size']:>12} {f['name']}")
            print(len(installer.files), "files", installer.content_id())
        elif command == "extract":
            out, wanted = sys.argv[3], [s.lower() for s in sys.argv[4:]]
            count = 0
            for f in installer.files:
                if wanted and not any(s in f["name"].lower() for s in wanted):
                    continue
                dest = os.path.join(out, *f["name"].split("/"))
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as handle:
                    handle.write(installer.read(f))
                count += 1
            print("extracted", count)
        else:
            raise SystemExit(f"unknown command {command}")
    finally:
        installer.close()


if __name__ == "__main__":
    main()
