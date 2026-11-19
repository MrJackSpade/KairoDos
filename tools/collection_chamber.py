"""The Collection Chamber's games for KairoDos's catalog, from their installers.

    python tools/collection_chamber.py scan <folder> <cache.json> [--count N] [--workers W]
    python tools/collection_chamber.py match <cache.json> <out.json>
    python tools/collection_chamber.py launchbox <folder> <cache.json> <references.json>
    python tools/collection_chamber.py pages <references.json> <listing.tsv>...

scan reads each installer (one per folder of <folder>, the downloads as the blog's MEGA folders
name them) that <cache.json> does not have yet, at most N per run, and keeps what the catalog needs:
its title, its content ID (the app's DosClickteam.PREFIX) and the content ID of the archive it
installs (the app's sha256-dos-manifest-v1, every file unpacked and its CRC-32 taken), with the
launch its shortcuts declare (clickteam_launch.py). Run it again until it reports nothing left.

match pairs each installer with its eXoDOS game by title (catalog/complete-input-v1.zip), which
catalog generation alone may do, and writes the catalog input: each installer's IDs and its game
key, or its own title when eXoDOS has no such game. Read the report before committing it.

launchbox gives each installer eXoDOS has no game for its LaunchBox record (launchbox.py's snapshots
of MS-DOS, Windows 3.X and Windows), for its tags, box art and screenshot
(catalog/research/collection-chamber-launchbox-v1.json), and writes reference text for writing its
description (descriptions.py --references): LaunchBox's overview and the installer's ChamberNotes INFO.

pages finds the same installers' Collection Chamber posts (<listing.tsv>: the blog's game lists, title,
mega and page per row) for the cover and screenshot LaunchBox has none of, kept in catalog/artwork
(catalog/research/collection-chamber-pages-v1.json), and adds each post's text to the reference text.
Run it after launchbox.
"""
import argparse
import csv
import difflib
import glob
import hashlib
import html
import io
import json
import os
import pathlib
import re
import struct
import sys
import unicodedata
import urllib.request
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
    programs, current = [], set()
    for folder in sorted(glob.glob(os.path.join(args.folder, "*"))):
        found = sorted(glob.glob(os.path.join(folder, "Install-*.exe")))
        for program in found:
            key = key_of(program, len(found) == 1)
            current.add(key)
            if key not in cache:
                programs.append((program, key))
    def save():
        temporary = args.cache + ".part"
        with open(temporary, "w", encoding="utf-8") as handle:
            json.dump(cache, handle, indent=1, sort_keys=True)
        os.replace(temporary, args.cache)

    # An installer that is gone, or whose key changed when its folder gained or lost one, is read again.
    gone = sorted(set(cache) - current)
    for key in gone:
        print(f"{key}: no longer in {args.folder}", flush=True)
        del cache[key]
    if gone:
        save()
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
            save()
    print(f"left: {len(programs) - len(todo)}")


ROMAN = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6", "vii": "7", "viii": "8", "ix": "9", "x": "10"}


def normalize(text):
    """A title as words to compare: ASCII, lower case, no punctuation or articles, numerals as digits."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    text = text.replace("&", " and ").replace("+", " plus ")
    text = re.sub(r"\b(\d)\s*-\s*d\b", r"\1d", text)  # 3-D, 3D
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


class TitleIndex:
    """Games by title, to find an installer's: its whole title ("exact"), then either one's main title
    ("main"), then a close spelling ("close"). Several games in a tier are told apart by the years
    the installer names; a tier that still names several, or none, leaves the installer to the next.
    A game of another number (Dark Seed for Dark Seed II), or released more than two years from what
    the installer names (Ghost of 1990 for Ghosts of 1994), is never its game."""

    def __init__(self, entries):
        """[entries]: (game ID, title, year or None), a game once per title it is known by."""
        self.full, self.main, self.titles = {}, {}, {}
        for game, text, year in entries:
            self.full.setdefault(normalize(text), set()).add((game, year))
            self.main.setdefault(normalize(main_title(text)), set()).add((game, year))
            self.titles.setdefault(game, []).append(text)

    @staticmethod
    def numerals(text):
        return {w for w in text.split() if w.isdigit()}

    def decide(self, candidates, years, name):
        def numbered(game):
            # Any of the game's titles whose numbers the installer's title has.
            return any(self.numerals(normalize(text)) <= self.numerals(name) | self.numerals(normalize(main_title(text)))
                       for text in self.titles[game])
        candidates = {(c, y) for c, y in candidates
                      if numbered(c) and (y is None or not years or min(abs(y - i) for i in years) <= 2)}
        ids = {c for c, _ in candidates}
        if len(ids) == 1:
            return ids.pop()
        dated = {c for c, y in candidates if y is not None and y in years}
        return dated.pop() if len(dated) == 1 else None

    def find(self, title, years):
        """(game ID, tier) for [title] and the [years] its installer names; (None, "none") without one."""
        name = normalize(title)
        name_main = normalize(main_title(title))
        tiers = [("exact", self.full.get(name, set())),
                 ("main", self.main.get(name, set()) | self.full.get(name_main, set()) |
                  (self.main.get(name_main, set()) if len(name_main.split()) > 1 else set()))]
        for label, candidates in tiers:
            chosen = self.decide(candidates, years, name) if candidates else None
            if chosen:
                return chosen, label
        close = difflib.get_close_matches(name, list(self.full), n=3, cutoff=0.9)
        candidates = set().union(*(self.full[c] for c in close)) if close else set()
        chosen = self.decide(candidates, years, name) if candidates else None
        return (chosen, "close") if chosen else (None, "none")


def exodos_records():
    with zipfile.ZipFile(os.path.join(ROOT, "catalog/complete-input-v1.zip")) as archive:
        records = {}
        for name in archive.namelist():
            if len(name) == 7:
                records.update(json.loads(archive.read(name))["games"])
    return records


def match(args):
    """Each installer's eXoDOS game (TitleIndex), or a reviewed decision
    (catalog/collection-chamber-review.json) over it."""
    cache = json.load(open(args.cache, encoding="utf-8"))
    records = exodos_records()
    index = TitleIndex((content_id, variant["title"], exodos_year(variant) or exodos_year(record))
                       for content_id, record in records.items()
                       for variant in [record] + list(record.get("variants", {}).values()) if variant.get("title"))
    review_file = os.path.join(ROOT, "catalog/collection-chamber-review.json")
    review = json.load(open(review_file, encoding="utf-8"))["installers"] if os.path.exists(review_file) else {}
    for code, decision in review.items():
        assert decision.get("reason", "").strip(), f"collection-chamber-review.json: {code} gives no reason"
        assert decision.get("exodos") is None or decision["exodos"] in records, f"collection-chamber-review.json: {code} names no eXoDOS game"
    out, report = {}, {}
    for code, record in sorted(cache.items()):
        if "installer" not in record:
            report["skipped"] = report.get("skipped", 0) + 1
            print(f"SKIP {code}: {record.get('error')}")
            continue
        installer_years = set(record.get("years", []))
        chosen, tier = index.find(record["title"], installer_years)
        if "exodos" in review.get(code, {}):
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


LAUNCHBOX_PLATFORMS = ("dos", "win3x", "windows")  # searched in this order: DOS releases first


def own_games(installers):
    """The installers (catalog/collection-chamber-v1.json) eXoDOS has no game for, as the games they
    are: {game: [installer, ...]}, newest first (Install-X_v2.exe before Install-X.exe). A folder's
    installers of one title are versions of one game (the blog keeps a game's older releases beside
    its new one); a folder can also hold other games. A game is named by its folder, or by its
    newest installer when the folder holds other games too; its title, years and notes come from
    its newest installer."""
    groups = {}
    for code, entry in sorted(installers.items(), reverse=True):
        if not entry.get("game"):
            groups.setdefault((code.split("/")[0], normalize(entry["title"])), []).append(code)
    folders = [folder for folder, _ in groups]
    return {(folder if folders.count(folder) == 1 else codes[0]): codes for (folder, _), codes in groups.items()}


def own_game_key(installers, codes):
    """A game's catalog key: its newest installer's, the release the blog offers, as the app keys a
    game by its content ID; adding the older releases it keeps leaves the key as it was."""
    return "dos:" + installers[codes[0]]["installer"].split(":", 1)[1][:16]


def chamber_notes(installer):
    """The INFO section of the installer's ChamberNotes.txt: the game's back-of-box text, for reference."""
    notes = next((f for f in installer.files if f["name"].lower().endswith("chambernotes.txt")), None)
    if notes is None:
        return ""
    text = installer.read(notes).decode("cp1252", "replace").replace("\r\n", "\n")
    found = re.search(r"\n\s*INFO\s*\n=+\n(.*?)\n=+\n", text, re.S)
    return found.group(1).strip() if found else ""


def launchbox(args):
    """The LaunchBox record of each installer the catalog has no eXoDOS game for (TitleIndex over each
    platform's names and aliases, MS-DOS first), and what the catalog takes from it: tags, box art and
    screenshot. Writes catalog/research/collection-chamber-launchbox-v1.json, and the description
    reference text (LaunchBox's overview and the installer's ChamberNotes INFO, reference only) to <references>."""
    sys.path.insert(0, os.path.join(ROOT, "shared/tools"))
    import launchbox as launchbox_tool
    from research import game_tags, year_of
    downloads = os.path.join(ROOT, ".downloads/launchbox")
    snapshots = {slug: json.load(open(os.path.join(downloads, f"{slug}-snapshot.json"), encoding="utf-8"))
                 for slug in LAUNCHBOX_PLATFORMS}
    tags = {slug: json.load(open(os.path.join(downloads, f"{slug}-tags.json"), encoding="utf-8"))
            for slug in LAUNCHBOX_PLATFORMS}
    indexes = {slug: TitleIndex((game_id, text, year_of(game)) for game_id, game in games.items()
                                for text in [game["Name"]] + list(game.get("aliases", [])) if text)
               for slug, games in snapshots.items()}
    cache = json.load(open(args.cache, encoding="utf-8"))
    installers = json.load(open(os.path.join(ROOT, "catalog/collection-chamber-v1.json"), encoding="utf-8"))["installers"]
    review_file = os.path.join(ROOT, "catalog/collection-chamber-review.json")
    review = json.load(open(review_file, encoding="utf-8"))["installers"] if os.path.exists(review_file) else {}
    for code, decision in review.items():
        decided = decision.get("launchbox")
        assert decided is None or decided["id"] in snapshots[decided["platform"]], f"collection-chamber-review.json: {code} names no LaunchBox game"
    out, references, report = {}, {}, {}
    for code, codes in sorted(own_games(installers).items()):
        entry = installers[codes[0]]
        years = set().union(*(cache[c].get("years", []) for c in codes))
        found = next(((slug, *indexes[slug].find(entry["title"], years)) for slug in LAUNCHBOX_PLATFORMS
                      if indexes[slug].find(entry["title"], years)[0]), None)
        if "launchbox" in review.get(code, {}):
            # A reviewed decision (catalog/collection-chamber-review.json) over the title rules.
            decided = review[code]["launchbox"]
            found = None if decided is None else (decided["platform"], decided["id"], "reviewed")
        key = own_game_key(installers, codes)
        installer = clickteam.Installer(os.path.join(args.folder, cache[codes[0]]["code"], cache[codes[0]]["program"]))
        try:
            notes = chamber_notes(installer)
        finally:
            installer.close()
        if not found:
            report["none"] = report.get("none", 0) + 1
            print(f"NONE {code}: {entry['title']!r} {sorted(years)}")
            if notes:
                references[key] = notes
            continue
        slug, game_id, tier = found
        game = snapshots[slug][game_id]
        record = {"platform": slug, "launchbox": game_id, "title": game["Name"], "match": tier,
                  "tags": game_tags(year_of(game), tags[slug].get(game_id), None)}
        for field, source in (("developer", "Developer"), ("publisher", "Publisher")):
            if game.get(source):
                record[field] = game[source]
        box = launchbox_tool.first_image(game, launchbox_tool.BOX_TYPES)
        shot = launchbox_tool.first_image(game, launchbox_tool.SCREENSHOT_TYPES)
        if box:
            record["boxArt"] = box
        if shot:
            record["preview"] = shot
        out[code] = record
        references[key] = "\n\n".join(part for part in (game.get("Overview", "").strip(), notes) if part)
        report[f"{slug} {tier}"] = report.get(f"{slug} {tier}", 0) + 1
        print(f"{tier.upper()} {code}: {entry['title']!r} {sorted(years)} -> {slug} {game['Name']!r} {year_of(game)}"
              f"{'' if box else ' (no box)'}{'' if shot else ' (no screenshot)'}")
    with open(os.path.join(ROOT, "catalog/research/collection-chamber-launchbox-v1.json"), "w", encoding="utf-8") as handle:
        json.dump({"schemaVersion": 1, "games": out}, handle, ensure_ascii=False, indent=1, sort_keys=True)
        handle.write("\n")
    with open(args.references, "w", encoding="utf-8") as handle:
        json.dump(references, handle, ensure_ascii=False, indent=1, sort_keys=True)
    print(report)


DOWNLOADS = os.path.join(ROOT, ".downloads/collection-chamber")
BLOG_IMAGE = re.compile(r'<img[^>]*?\ssrc="(https://blogger\.googleusercontent\.com/img/[^"]+)"')
# The size of a Blogger image URL (/s320/, /w200-h129/ before the file name, or =s320 at the end of a
# nameless one); s0 asks for the uploaded image.
BLOG_IMAGE_SIZE = re.compile(r"/(?:s\d+(?:-c)?|w\d+-h\d+(?:-[a-z0-9-]+)?)/(?=[^/]+$)|=(?:s\d+|w\d+-h\d+)(?:-[a-z0-9-]+)?$")
# Images a post shows that are not the game: its review banner, and the games it suggests after it ("Try - ...").
BLOG_NOT_GAME = re.compile(r"/Review[+%20 ]+by[^/]*$", re.I)
BLOG_SUGGESTION = re.compile(r"/Try(?:[+ -]|%20|---)", re.I)
# Screenshots of a game's DOS release, in posts that show its releases on several machines.
BLOG_DOS = re.compile(r"[^a-z]DOS[^a-z][^/]*$", re.I)
DOS_CAPTION = re.compile(r"\b(?:MS-DOS|DOS|IBM PC|PC)\b")
# The text that opens a post's download section.
BLOG_DOWNLOAD = re.compile(r"\bTo download the game\b", re.I)
# A screenshot's width to height, as screens are (320x200 to 640x480, or a 4:3 picture in a 16:9 one).
SCREEN_ASPECT = (1.2, 1.8)


def read_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, value):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=1, sort_keys=True)
        handle.write("\n")


def page_index(listings):
    """TitleIndex of the blog's pages ([listings]: TSV files of title, mega, page rows, as the blog's
    game lists name them): each by its title, the alternate title it gives in parentheses, and each
    game of a page that offers several ("A & B")."""
    entries = []
    for path in listings:
        with open(path, encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        for row in rows:
            names = [row["title"]] + re.findall(r"\(([^)]*)\)", row["title"])
            parts = re.split(r"\s+&\s+", row["title"])
            names += parts if len(parts) > 1 else []
            entries += [(row["page"], name, None) for name in names]
    return TitleIndex(entries)


def post_body(post):
    start = post.index("post-body entry-content")
    return post[start:post.index("post-footer", start)]


def full_size(url):
    return BLOG_IMAGE_SIZE.sub(lambda size: "/s0/" if size.group(0).startswith("/") else "=s0", url)


def captioned_images(post):
    """[(image URL at its uploaded size, caption, shown after the download section)] of a post: an
    image's caption is the first text after it, the cell under it when a table row of images has a
    caption row with a cell for each."""
    images, waiting, downloads = [], [], False
    for piece in re.split(r"(<img[^>]*>)", post_body(post).split(">", 1)[1]):
        if piece.startswith("<img"):
            found = BLOG_IMAGE.search(piece)
            if found:
                waiting.append(full_size(found.group(1)))
            continue
        text = re.sub(r"\s+", " ", html_text(piece)).strip()
        if not text:
            continue
        rows = [row for row in re.findall(r"<tr[^>]*>(.*?)</tr>", piece, re.S) if html_text(row).strip()]
        cells = [cell for cell in (re.sub(r"\s+", " ", html_text(cell)).strip()
                                   for cell in re.split(r"</td>", rows[0] if rows else "")) if cell]
        captions = cells if len(cells) == len(waiting) else [text[:200]] * len(waiting)
        images += [(url, caption, downloads) for url, caption in zip(waiting, captions)]
        waiting = []
        downloads = downloads or bool(BLOG_DOWNLOAD.search(text))
    return images + [(url, "", downloads) for url in waiting]


def post_images(post):
    """(cover, screenshot candidates) of a Collection Chamber post: the post's first image is the
    game's cover, and the images after it, up to the games it suggests, are screenshots. Those after
    its download section show the installer's own release, the others its history on every machine,
    so the release's are the candidates when it shows any; of either, a DOS release's alone, by file
    name or caption, when it labels some so."""
    images = captioned_images(post)
    if not images:
        return None, []
    shots = []
    for url, caption, released in images[1:]:
        if BLOG_SUGGESTION.search(url):
            break
        if not BLOG_NOT_GAME.search(url):
            shots.append((url, caption, released))
    for group in ([shot for shot in shots if shot[2]], [shot for shot in shots if not shot[2]]):
        named = [url for url, _, _ in group if BLOG_DOS.search(url)]
        captioned = [url for url, caption, _ in group if DOS_CAPTION.search(caption)]
        candidates = named or captioned or [url for url, _, _ in group]
        if candidates:
            return images[0][0], candidates
    return images[0][0], []


def middle_out(items):
    """[items] from the middle outwards: a gallery's title screens come first, its play in the middle."""
    middle = len(items) // 2
    return [item for _, item in sorted(enumerate(items), key=lambda pair: abs(pair[0] - middle))]


def screen_shaped(raw):
    from PIL import Image
    with Image.open(io.BytesIO(raw)) as image:
        width, height = image.size
    return SCREEN_ASPECT[0] <= width / height <= SCREEN_ASPECT[1]


def html_text(fragment):
    text = re.sub(r"<br\s*/?>|</(?:div|p|td|tr)>", "\n", fragment)
    text = re.sub(r"<[^>]+>", "", text)
    return html.unescape(text)


def post_text(page):
    """The post's text, up to its download section: reference for writing a description."""
    text = html_text(post_body(page).split(">", 1)[1]).replace("\xa0", " ")
    text = re.split(r"\n\s*(?:DOWNLOAD|Download)\b", text)[0]
    return re.sub(r"\n\s*\n+", "\n\n", text).strip()


def fetched(url, path):
    """The body at [url], kept at [path] once it has been downloaded."""
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Kairo catalog)"})
        with urllib.request.urlopen(request) as response:
            body = response.read()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + ".part", "wb") as handle:
            handle.write(body)
        os.replace(path + ".part", path)
    with open(path, "rb") as handle:
        return handle.read()


def image_file(url):
    return os.path.join(DOWNLOADS, "images", hashlib.sha256(url.encode()).hexdigest()[:16])


def artwork(code, entry, launchbox_record, page_record):
    """The compact artwork (tools/dos_artwork.py) of a game the catalog has no eXoDOS game for
    ([code], own_games), under its newest installer's ([entry]) content ID: each kind from LaunchBox ([launchbox_record], downloaded by its URL),
    or else from the game's Collection Chamber post ([page_record], kept in the repository); None
    without either."""
    from dos_artwork import compact
    digest = entry["installer"].split(":", 1)[1]
    variant = hashlib.sha256(code.encode()).hexdigest()[:12]
    art = {}
    for kind in ("boxArt", "preview"):
        if (launchbox_record or {}).get(kind) or (page_record or {}).get(kind):
            art[kind] = f"art/catalog/dos/{digest[:2]}/{digest}/{variant}/{kind}.webp"
        if (launchbox_record or {}).get(kind):
            art[kind + "Url"] = launchbox_record[kind]
    return compact(art) if art else None


def pages(args):
    """The Collection Chamber post of each installer the catalog has no eXoDOS game for (page_index over
    [listings], or a reviewed "page" in catalog/collection-chamber-review.json): its cover and a
    screenshot for each kind LaunchBox has none of (collection-chamber-launchbox-v1.json), encoded as
    the bundled artwork (shared fetch_artwork.py) into catalog/artwork, since the repository serves
    them, and the post's text added to the description reference text in <references>. Writes
    catalog/research/collection-chamber-pages-v1.json. Pages and images are kept under
    .downloads/collection-chamber, so a run fetches each once."""
    sys.path.insert(0, os.path.join(ROOT, "shared/tools"))
    from fetch_artwork import bundle
    index = page_index(args.listings)
    installers = read_json(os.path.join(ROOT, "catalog/collection-chamber-v1.json"))["installers"]
    review = read_json(os.path.join(ROOT, "catalog/collection-chamber-review.json"))["installers"]
    launchboxes = read_json(os.path.join(ROOT, "catalog/research/collection-chamber-launchbox-v1.json"))["games"]
    references = read_json(args.references)
    out, report = {}, {}
    for code, codes in sorted(own_games(installers).items()):
        entry = installers[codes[0]]
        page = review.get(code, {}).get("page") or index.find(entry["title"], set())[0]
        assert page, f"{code} ({entry['title']}) has no Collection Chamber page: add its \"page\" to collection-chamber-review.json"
        post = fetched(page, os.path.join(DOWNLOADS, "pages", hashlib.sha256(page.encode()).hexdigest()[:16] + ".html")).decode("utf-8")
        cover, shots = post_images(post)
        launchbox_record = launchboxes.get(code, {})
        record = {"page": page}
        if cover and not launchbox_record.get("boxArt"):
            record["boxArt"] = review.get(code, {}).get("boxArt") or cover
        if not launchbox_record.get("preview"):
            # The middlemost screen-shaped one: posts also show their game's box, manual and maps.
            shot = review.get(code, {}).get("preview") or next(
                (url for url in middle_out(shots) if screen_shaped(fetched(url, image_file(url)))), None)
            if shot:
                record["preview"] = shot
        art = artwork(code, entry, launchbox_record, record)
        if art:
            from dos_artwork import expand
            paths = expand(art)
            for kind in ("boxArt", "preview"):
                if kind not in record:
                    continue
                target = os.path.join(ROOT, "catalog/artwork", paths[kind])
                raw = fetched(record[kind], image_file(record[kind]))
                record[kind + "Sha256"] = hashlib.sha256(raw).hexdigest()
                os.makedirs(os.path.dirname(target), exist_ok=True)
                bundle(raw, pathlib.Path(target), record[kind])
        key = own_game_key(installers, codes)
        text = post_text(post)
        references[key] = "\n\n".join(part for part in (references.get(key, ""), text) if part)
        out[code] = record
        kinds = [kind for kind in ("boxArt", "preview") if kind in record]
        report[" ".join(kinds) or "nothing"] = report.get(" ".join(kinds) or "nothing", 0) + 1
        print(f"{code}: {page} {' '.join(kinds)}")
    write_json(os.path.join(ROOT, "catalog/research/collection-chamber-pages-v1.json"), {"schemaVersion": 1, "games": out})
    write_json(args.references, references)
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
    lb = sub.add_parser("launchbox")
    lb.add_argument("folder")
    lb.add_argument("cache")
    lb.add_argument("references")
    p = sub.add_parser("pages")
    p.add_argument("references")
    p.add_argument("listings", nargs="+")
    args = parser.parse_args()
    {"scan": scan, "match": match, "launchbox": launchbox, "pages": pages}[args.command](args)


if __name__ == "__main__":
    main()
