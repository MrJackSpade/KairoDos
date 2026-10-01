# SPDX-License-Identifier: GPL-2.0-or-later
"""Compare ARM64 ELF binding policies without executing either library."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct


def read_elf(path):
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
    assert struct.unpack_from("<H", data, 18)[0] == 183, "Expected ARM64"
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58)
    headers = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * size)
               for i in range(count)]

    def content(section):
        return data[section[4]:section[4] + section[5]]

    def string(table, index):
        return table[index:table.index(b"\0", index)].decode()

    names = content(headers[names_index])
    sections = {string(names, s[0]): s for s in headers}
    dynsym = sections[".dynsym"]
    strings = content(headers[dynsym[6]])
    symbols = []
    for pos in range(dynsym[4], dynsym[4] + dynsym[5], 24):
        name, info, other, index, value, length = struct.unpack_from("<IBBHQQ", data, pos)
        symbols.append(dict(name=string(strings, name), type=info & 15,
                            binding=info >> 4, visibility=other & 3,
                            defined=index != 0))
    relocations = []
    for name in (".rela.plt", ".rela.dyn"):
        section = sections[name]
        for pos in range(section[4], section[4] + section[5], 24):
            address, info, addend = struct.unpack_from("<QQq", data, pos)
            relocations.append(dict(section=name, relocation=info & 0xffffffff,
                                    symbolIndex=info >> 32, **symbols[info >> 32]))
    dynamic = sections[".dynamic"]
    needed = []
    for pos in range(dynamic[4], dynamic[4] + dynamic[5], 16):
        tag, value = struct.unpack_from("<qQ", data, pos)
        if tag == 1:
            needed.append(string(strings, value))
    summary = dict(sha256=hashlib.sha256(data).hexdigest(), needed=needed,
                   pltBytes=sections[".plt"][5], gotBytes=sections[".got"][5],
                   pltCounts=dict(Counter(
                       ("internal-strong" if r["binding"] == 1 else "internal-weak")
                       if r["defined"] else "external"
                       for r in relocations if r["section"] == ".rela.plt")))
    return symbols, relocations, summary


def compare(baseline, candidate):
    old_symbols, old_relocs, old_summary = read_elf(baseline)
    new_symbols, new_relocs, new_summary = read_elf(candidate)

    def contract(symbols):
        return Counter(tuple(sorted(s.items())) for s in symbols)

    def relocation_contract(rows, predicate):
        return Counter((r["name"], r["type"], r["binding"], r["relocation"])
                       for r in rows if predicate(r))

    checks = dict(dynamicSymbolContractIdentical=contract(old_symbols) == contract(new_symbols),
                  neededLibrariesIdentical=old_summary["needed"] == new_summary["needed"])
    for name, predicate in (
            ("objectRelocationsIdentical", lambda r: r["type"] == 1),
            ("weakRelocationsIdentical", lambda r: r["binding"] == 2),
            ("externalImportsIdentical", lambda r: bool(r["name"]) and not r["defined"])):
        checks[name] = relocation_contract(old_relocs, predicate) == relocation_contract(new_relocs, predicate)
    return dict(baseline=old_summary, candidate=new_summary, checks=checks,
                limits="Static ABI/binding checks; not runtime identity, lifecycle or game compatibility proof.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = compare(args.baseline, args.candidate)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    if not all(result["checks"].values()):
        raise SystemExit("Binding compatibility checks failed")
