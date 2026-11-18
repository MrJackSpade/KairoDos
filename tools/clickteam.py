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
# Record layouts: header size, then a file record's field offsets from the record's start.
LAYOUTS = {
    "current": dict(header=4, offset=6, packed=10, size=18, checksum=22, name=68),
    "earlier": dict(header=8, offset=28, packed=32, size=24, checksum=20, name=64),
}


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
        if kind == 0:
            start = pos + layout["name"]
            end = data.index(b"\0", start, pos + size)
            name = data[start:end].decode("cp1252").replace("\\", "/")
            parts = name.split("/")
            if not name or name.startswith("/") or ":" in name or any(p in ("", ".", "..") for p in parts):
                raise ValueError(f"unsafe path {name!r}")
            if name.lower() in names:
                raise ValueError(f"duplicate path {name!r}")
            names.add(name.lower())
            field = lambda key: struct.unpack_from("<I", data, pos + layout[key])[0]
            files.append(dict(name=name, offset=field("offset"), packed=field("packed"),
                              size=field("size"), checksum=field("checksum")))
        pos += size
        records += 1
    if pos != len(data) or records != count:
        raise ValueError("records do not tile the list")
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
        while True:
            block, _, size = struct.unpack("<HHI", self.source.read(pos, 8))
            pos += 8
            if block == DATA:
                break
            if size > MAX_BLOCK:
                raise ValueError(f"block {block:04x} is {size} bytes")
            if block == FILE_LIST:
                raw = self.source.read(pos, size)
                file_list = unpack(raw[4], raw[5:], struct.unpack_from("<I", raw, 0)[0])
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
