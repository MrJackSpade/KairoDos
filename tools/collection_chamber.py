"""The Collection Chamber's games for KairoDos's catalog, from their installers.

    python tools/collection_chamber.py scan <folder> <cache.json> [--count N] [--workers W]
    python tools/collection_chamber.py match <cache.json> <out.json>

scan reads each installer (one per folder of <folder>, the downloads as the blog's MEGA folders
name them) that <cache.json> does not have yet, at most N per run, and keeps what the catalog needs:
its title, its content ID (the app's DosClickteam.PREFIX) and the content ID of the archive it
installs (the app's sha256-dos-manifest-v1, every file unpacked and its CRC-32 taken), with the
launch its shortcuts declare (clickteam_launch.py). Run it again until it reports nothing left.

match pairs each installer with its eXoDOS game by title (catalog/complete-input-v1.zip), which
catalog generation alone may do, and writes the catalog input: each installer's IDs and its game
key, or its own title when eXoDOS has no such game. Read the report before committing it.
"""
import argparse
import difflib
import glob
import hashlib
import json
import os
import re
import struct
import sys
import unicodedata
import zipfile
import zlib
from concurrent.futures import ProcessPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clickteam  # noqa: E402
import clickteam_launch  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = "sha256-dos-manifest-v1:"
MANIFEST_HEADER = b"kairo-dos-manifest-v1\0"


def ascii_lower(text):
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in text)


def manifest_id(files):
    """DosContentHash's ID of an archive of [files] (path, size, CRC-32): one common folder and .exo markers left out."""
    files = [(path, size, crc) for path, size, crc in files if not path.lower().endswith(".exo")]
    roots = {path.split("/")[0] for path, _, _ in files}
    strip = ""
    if len(roots) == 1:
        root = next(iter(roots))
        if all(path.startswith(root + "/") for path, _, _ in files):
            strip = root + "/"
    framed = sorted((ascii_lower(path[len(strip):]), size, crc) for path, size, crc in files)
    paths = [p for p, _, _ in framed]
    if len(set(paths)) != len(paths):
        raise ValueError("two files of one path")
    digest = hashlib.sha256(MANIFEST_HEADER)
    for path, size, crc in framed:
        digest.update(path.encode("utf-8") + b"\0" + str(size).encode("ascii") + b"\0" + ("%08x" % crc).encode("ascii") + b"\0")
    return MANIFEST + digest.hexdigest()


def title(installer):
    """The installer's product name: in its settings block, the string just before its setup type's
    name ("Default", unless renamed). Current installers' settings (flags 0x1000) give where that
    name is (u32 at 24); earlier ones' always call it "Default"."""
    block = installer.blocks[0x143E]
    if struct.unpack_from("<H", block, 0)[0] & 0x1000:
        at = struct.unpack_from("<I", block, 24)[0] - 1
        if not 0 < at < len(block) or block[at] != 0:
            raise ValueError("the settings block's setup type is not where it says")
    else:
        at = block.index(b"\0Default\0")
    start = block.rindex(b"\0", 0, at) + 1
    return block[start:at].decode("cp1252")


def key_of(program, alone):
    """An installer's key: its folder (the blog's code), with its program's name when the folder holds several."""
    code = os.path.basename(os.path.dirname(program))
    return code if alone else f"{code}/{os.path.basename(program)}"


def scan_one(program, key):
    code = os.path.basename(os.path.dirname(program))
    record = {"code": code, "key": key, "program": os.path.basename(program)}
    if not clickteam.is_installer(program):
        record["error"] = "not a Clickteam installer"
        return record
    installer = clickteam.Installer(program)
    try:
        record["title"] = title(installer)
        record["years"] = years(installer)
        record["installer"] = installer.content_id()
        record["files"] = len(installer.files)
        record["patched"] = sum(1 for f in installer.files if f["kind"] == clickteam.PATCHED)
        files = []
        for f in installer.files:
            data = installer.read(f)
            files.append((f["name"], len(data), zlib.crc32(data)))
        record["installed"] = manifest_id(files)
    finally:
        installer.close()
    launches = clickteam_launch.derive(program)
    record["launch"] = None if not launches else {
        "folder": launches[0]["folder"], "configs": [l["configs"][-1].split("/")[-1] for l in launches]}
    return record


def scan(args):
    cache = json.load(open(args.cache, encoding="utf-8")) if os.path.exists(args.cache) else {}
    programs = []
    for folder in sorted(glob.glob(os.path.join(args.folder, "*"))):
        found = sorted(glob.glob(os.path.join(folder, "Install-*.exe")))
        for program in found:
            key = key_of(program, len(found) == 1)
            if key not in cache:
                programs.append((program, key))
    todo = programs[:args.count]
    print(f"{len(cache)} done, {len(programs)} left, reading {len(todo)}", flush=True)
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(scan_one, program, key): (program, key) for program, key in todo}
        for future in as_completed(futures):
            program, code = futures[future]
            try:
                cache[code] = future.result()
            except clickteam.MissingPart as error:
                print(f"{code}: missing part {error.index}", flush=True)
                continue
            except Exception as error:  # kept in the cache as the installer's result, and reported
                cache[code] = {"code": os.path.basename(os.path.dirname(program)), "key": code,
                               "program": os.path.basename(program), "error": f"{type(error).__name__}: {error}"}
            print(code, json.dumps(cache[code])[:200], flush=True)
            temporary = args.cache + ".part"
            with open(temporary, "w", encoding="utf-8") as handle:
                json.dump(cache, handle, indent=1, sort_keys=True)
            os.replace(temporary, args.cache)
    print(f"left: {len(programs) - len(todo)}")


ROMAN = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6", "vii": "7", "viii": "8", "ix": "9", "x": "10"}


def normalize(text):
    """A title as words to compare: ASCII, lower case, no punctuation or articles, numerals as digits."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    text = text.replace("&", " and ").replace("+", " plus ")
    text = re.sub(r"\(aka [^)]*\)|\([^)]*\)", " ", text)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    words = [ROMAN.get(w, w) for w in text.split() if w not in ("the", "a", "an")]
    return " ".join(words)


def main_title(text):
    """A title without its subtitle: the part before ': ' or ' - '."""
    return re.split(r":\s| - ", text, maxsplit=1)[0]


def years(installer):
    """The years an installer's information text names (its copyright lines), before the installer's own time."""
    text = " ".join(m.group().decode("cp1252") for m in re.finditer(rb"[\x20-\x7e\xa9]{4,}", installer.blocks[0x143E]))
    return sorted({int(y) for y in re.findall(r"\b(19[789]\d|200[0-5])\b", text)})


def exodos_year(record):
    """An eXoDOS record's release year: its "(YYYY)" or its year tag."""
    found = re.search(r"\((\d{4})\)", record["title"]) or next(
        (re.fullmatch(r"(\d{4})", tag) for tag in record.get("tags", []) if re.fullmatch(r"\d{4}", tag)), None)
    return int(found.group(1)) if found else None


def match(args):
    """Each installer's eXoDOS game, by title in tiers: the whole title ("exact"), then either one's main
    title ("main"), then a close spelling ("close"). Several games in a tier are told apart by the
    years the installer names; a tier that still names several, or none, leaves the installer to the next."""
    cache = json.load(open(args.cache, encoding="utf-8"))
    with zipfile.ZipFile(os.path.join(ROOT, "catalog/complete-input-v1.zip")) as archive:
        records = {}
        for name in archive.namelist():
            if len(name) == 7:
                records.update(json.loads(archive.read(name))["games"])
    titles = []  # (content ID, title, year) for every eXoDOS game and collection member
    for content_id, record in records.items():
        for variant in [record] + list(record.get("variants", {}).values()):
            if variant.get("title"):
                titles.append((content_id, variant["title"], exodos_year(variant) or exodos_year(record)))
    full = {}
    main = {}
    for content_id, text, year in titles:
        full.setdefault(normalize(text), set()).add((content_id, year))
        main.setdefault(normalize(main_title(text)), set()).add((content_id, year))

    def numerals(text):
        return {w for w in text.split() if w.isdigit()}

    def decide(candidates, installer_years, name):
        # A game of another number (Dark Seed for Dark Seed II), or released years from what the
        # installer names (Ghost of 1990 for Ghosts of 1994), is another game.
        candidates = {(c, y) for c, y in candidates
                      if numerals(normalize(records[c]["title"])) <= numerals(name) | numerals(normalize(main_title(records[c]["title"])))
                      and (y is None or not installer_years or min(abs(y - i) for i in installer_years) <= 2)}
        ids = {c for c, _ in candidates}
        if len(ids) == 1:
            return ids.pop()
        dated = {c for c, y in candidates if y is not None and y in installer_years}
        return dated.pop() if len(dated) == 1 else None

    review_file = os.path.join(ROOT, "catalog/collection-chamber-review.json")
    review = json.load(open(review_file, encoding="utf-8"))["installers"] if os.path.exists(review_file) else {}
    for code, decision in review.items():
        assert decision.get("reason", "").strip(), f"collection-chamber-review.json: {code} gives no reason"
        assert decision["exodos"] is None or decision["exodos"] in records, f"collection-chamber-review.json: {code} names no eXoDOS game"
    out, report = {}, {}
    for code, record in sorted(cache.items()):
        if "installer" not in record:
            report["skipped"] = report.get("skipped", 0) + 1
            print(f"SKIP {code}: {record.get('error')}")
            continue
        name = normalize(record["title"])
        name_main = normalize(main_title(record["title"]))
        installer_years = set(record.get("years", []))
        chosen, tier = None, "none"
        tiers = [
            ("exact", full.get(name, set())),
            ("main", main.get(name, set()) | full.get(name_main, set()) | (main.get(name_main, set()) if len(name_main.split()) > 1 else set())),
        ]
        for label, candidates in tiers:
            if candidates:
                chosen = decide(candidates, installer_years, name)
                if chosen:
                    tier = label
                    break
        if not chosen:
            close = difflib.get_close_matches(name, list(full), n=3, cutoff=0.9)
            candidates = set().union(*(full[c] for c in close)) if close else set()
            if candidates:
                chosen = decide(candidates, installer_years, name)
                tier = "close" if chosen else "none"
        if code in review:
            # A reviewed decision (catalog/collection-chamber-review.json) over the title rules.
            chosen = review[code]["exodos"]
            tier = "reviewed" if chosen else "none"
        entry = {"title": record["title"], "installer": record["installer"], "installed": record.get("installed")}
        if chosen:
            entry["game"] = "dos:" + chosen.split(":", 1)[1][:16]
            entry["match"] = tier
            print(f"{tier.upper()} {code}: {record['title']!r} {sorted(installer_years)} -> {records[chosen]['title']!r} {exodos_year(records[chosen])}")
        else:
            print(f"NONE {code}: {record['title']!r} {sorted(installer_years)}")
        report[tier] = report.get(tier, 0) + 1
        out[code] = entry
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump({"schemaVersion": 1, "installers": out}, handle, ensure_ascii=False, indent=1, sort_keys=True)
        handle.write("\n")
    print(report)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("scan")
    s.add_argument("folder")
    s.add_argument("cache")
    s.add_argument("--count", type=int, default=20)
    s.add_argument("--workers", type=int, default=6)
    m = sub.add_parser("match")
    m.add_argument("cache")
    m.add_argument("out")
    args = parser.parse_args()
    scan(args) if args.command == "scan" else match(args)


if __name__ == "__main__":
    main()
