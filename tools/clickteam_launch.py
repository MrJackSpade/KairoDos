"""The DOS launch a Collection Chamber installer declares, derived from its own files.

The installer's shortcuts name what the player starts: a batch file, a DOSBox program renamed
Run.exe, or an AutoPlay Menu Designer menu whose buttons run those. Following them to DOSBox gives
each way the game starts: the folder DOSBox runs in and the configuration it reads (-conf, else its
default). The same derivation as KairoDos's DosClickteamLaunch, for checking installers on the PC.

    python tools/clickteam_launch.py <Install-XXX.exe> ...
"""
import json
import re
import shlex
import struct
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
import clickteam  # noqa: E402

AUTOPLAY = b"AMD50_RESOURCES_HEADER"
PROGRAMS = (".exe", ".bat", ".cmd", ".com")


def is_dosbox(program):
    """A DOSBox build (0.74, SVN-Daum, ECE, DOSBox-X): its own name and its IMGMOUNT command."""
    return b"DOSBox" in program and b"IMGMOUNT" in program


def is_dosbox_x(program):
    return b"DOSBox-X" in program


MENU_KEY = re.compile(rb"P\d+\.O\d+\.S\d+\.RO\d+\.([12])")


def menu_actions(program):
    """A menu program's run actions (files relative to its folder, and arguments): AutoPlay's, or a keyed menu's."""
    return autoplay_actions(program) or keyed_actions(program)


def keyed_actions(program):
    """A menu whose properties are keyed records (kind 14, total, u16 key length, u32 value length,
    key, value): each button's run action is `P<page>.O<object>.S<n>.RO<n>.1` (its file) and `.2` (its arguments)."""
    values = {}
    for match in re.finditer(rb"\x0e\x00\x00\x00", program):
        at = match.start()
        if at + 14 > len(program):
            continue
        total, key_length, value_length = struct.unpack_from("<IHI", program, at + 4)
        if key_length < 2 or total != 14 + key_length + value_length or at + total > len(program):
            continue
        key = program[at + 14:at + 14 + key_length]
        if not key.endswith(b"\0") or not MENU_KEY.fullmatch(key[:-1]):
            continue
        values[key[:-1].decode("ascii")] = program[at + 14 + key_length:at + total].rstrip(b"\0").decode("cp1252")
    return [(value, values.get(key[:-1] + "2", "")) for key, value in values.items() if key.endswith(".1") and value]


def autoplay_actions(program):
    """An AutoPlay menu's run actions: each file it runs (property 1, under %ROOT%) and its arguments (property 2)."""
    at = program.find(AUTOPLAY)
    if at < 0:
        return []
    actions = []
    pattern = re.compile(rb"\x0e\x00\x00\x00(....)\x00\x00(....)\x01\x00\x00\x00", re.S)
    for match in pattern.finditer(program, at):
        total, length = struct.unpack("<II", match.group(1) + match.group(2))
        if total != 18 + length or length % 2:
            continue
        start = match.end()
        value = program[start:start + length]
        if not value.endswith(b"\0\0"):
            continue
        target = value[:-2].decode("utf-16le", "replace")
        if not target.startswith("%ROOT%"):
            continue
        target = target[len("%ROOT%"):].lstrip("\\/")
        arguments = ""
        nxt = start + length
        if program[nxt:nxt + 4] == b"\x0e\x00\x00\x00":
            total2, _, length2, pid2 = struct.unpack_from("<IHII", program, nxt + 4)
            if pid2 == 2 and total2 == 18 + length2:
                arguments = program[nxt + 18:nxt + 18 + length2].decode("utf-16le", "replace").rstrip("\0")
        actions.append((target, arguments))
    return actions


class Files:
    """An installer's files by lower-case path."""

    def __init__(self, installer):
        self.installer = installer
        self.by_path = {f["name"].lower(): f for f in installer.files}

    def get(self, path):
        f = self.by_path.get(path.lower())
        return None if f is None else self.installer.read(f)

    def exists(self, path):
        return path.lower() in self.by_path


def join(folder, path):
    path = path.replace("\\", "/").strip()
    if path.startswith("./"):
        path = path[2:]
    parts = [p for p in (folder.split("/") if folder else [])]
    for p in path.split("/"):
        if p in ("", "."):
            continue
        if p == "..":
            if not parts:
                raise ValueError(f"{path} leaves the install folder")
            parts.pop()
        else:
            parts.append(p)
    return "/".join(parts)


def program_path(files, folder, name):
    """The installed file a command [name] runs from [folder]: as written, else with a program extension."""
    candidate = join(folder, name)
    if files.exists(candidate):
        return candidate
    if not os.path.splitext(name)[1]:
        for ext in PROGRAMS:
            if files.exists(candidate + ext):
                return candidate + ext
    return None


def batch_commands(text):
    """A batch file's commands that run programs: (folder change or None, program, arguments)."""
    out = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        line = raw.strip().lstrip("@").strip()
        if not line or line.lower().startswith(("rem ", "rem\t", "::", "echo", "set ", "cls", "pause", "exit", "goto", ":")) or line.lower() == "rem":
            continue
        try:
            tokens = shlex.split(line.replace("\\", "/"), posix=False)
        except ValueError:
            tokens = line.replace("\\", "/").split()
        tokens = [t.strip('"') if t.startswith('"') else t for t in tokens]
        verb = tokens[0].lower()
        if verb in ("cd", "chdir", "pushd"):
            rest = [t for t in tokens[1:] if t.lower() != "/d"]
            out.append(("cd", rest[0] if rest else "", []))
            continue
        if verb == "start":
            rest = tokens[1:]
            # START "title" [/options] program arguments
            if rest and (rest[0] == "" or line.split(None, 1)[1].lstrip().startswith('"')):
                rest = rest[1:]
            while rest and rest[0].startswith("/") and len(rest[0]) <= 12 and not rest[0][1:2] == "/":
                rest = rest[1:]
            if rest:
                out.append(("run", rest[0], rest[1:]))
            continue
        if verb == "call":
            if len(tokens) > 1:
                out.append(("run", tokens[1], tokens[2:]))
            continue
        out.append(("run", tokens[0], tokens[1:]))
    return out


def dosbox_launch(files, program, folder, arguments):
    """DOSBox [program] run in [folder] with [arguments]: its configurations, in order, and -c commands."""
    args = arguments if isinstance(arguments, list) else shlex.split(arguments.replace("\\", "/"), posix=False)
    args = [a.strip('"') for a in args]
    confs, commands = [], []
    i = 0
    while i < len(args):
        a = args[i].lower()
        if a == "-conf" and i + 1 < len(args):
            confs.append(join(folder, args[i + 1])); i += 2; continue
        if a == "-c" and i + 1 < len(args):
            commands.append(args[i + 1]); i += 2; continue
        i += 1
    # As DOSBox loads them: every -conf file there is, in order; when none is, its default in the
    # folder it runs in (DOSBox-X's own name first).
    confs = [c for c in confs if files.exists(c)]
    if not confs:
        names = (["dosbox-x.conf"] if is_dosbox_x(program) else []) + ["dosbox.conf"]
        found = next((join(folder, n) for n in names if files.exists(join(folder, n))), None)
        if found is None:
            raise ValueError(f"DOSBox in '{folder}' finds no configuration")
        confs.append(found)
    return dict(folder=folder, configs=confs, commands=commands)


def follow(files, target, arguments, folder, seen, depth=0):
    """The DOSBox launches running [target] with [arguments] from [folder] leads to."""
    key = (target, " ".join(arguments) if isinstance(arguments, list) else arguments, folder)
    if depth > 8 or key in seen:
        return []
    seen.add(key)
    ext = os.path.splitext(target)[1].lower()
    if ext in (".bat", ".cmd"):
        text = files.get(target).decode("cp1252", "replace")
        launches, cwd = [], folder
        for kind, name, args in batch_commands(text):
            if kind == "cd":
                cwd = join(cwd, name) if name and not re.match(r"^[a-zA-Z]:", name) else cwd
                continue
            path = program_path(files, cwd, name)
            if path:
                launches += follow(files, path, args, cwd, seen, depth + 1)
        return launches
    if ext == ".exe":
        program = files.get(target)
        if is_dosbox(program):
            return [dosbox_launch(files, program, folder, arguments)]
        launches = []
        menu_folder = os.path.dirname(target)
        for action, args in menu_actions(program):
            if os.path.splitext(action)[1].lower() not in PROGRAMS:
                continue
            path = join(menu_folder, action)
            if files.exists(path):
                launches += follow(files, path, args, os.path.dirname(path), seen, depth + 1)
        return launches
    return []


def derive(program):
    installer = clickteam.Installer(program)
    try:
        files = Files(installer)
        launches, seen = [], set()
        for f in installer.files:
            for shortcut in f["shortcuts"]:
                launches += follow(files, f["name"], shortcut["arguments"], os.path.dirname(f["name"]), seen)
        unique = []
        for launch in launches:
            if launch not in unique:
                unique.append(launch)
        return unique
    finally:
        installer.close()


if __name__ == "__main__":
    for program in sys.argv[1:]:
        try:
            print(os.path.basename(program), json.dumps(derive(program)))
        except Exception as error:
            print(os.path.basename(program), "FAIL", f"{type(error).__name__}: {error}")
