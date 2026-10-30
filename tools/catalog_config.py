"""KairoDos's catalog settings for the shared generator (shared/tools/app_catalog.py): eXoDOS
games named by their files' manifest hash, KairoDos's compact artwork references, the launch a
game's record carries, and the launch folders index."""
import json
import re
import sys
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "shared/tools"))
sys.path.insert(0, str(ROOT / "tools"))
import dos_artwork

APP = "kairodos"
PLATFORMS = ("dos",)
KINDS = {"dos-manifest"}
# KairoDos's own artwork references (dos_artwork.py): images served from this repository or
# downloaded from LaunchBox. The research names each game's reviewed pick, never image URLs.
ARTWORK_CODE = None
ARTWORK = SimpleNamespace(expand=dos_artwork.expand, compact=dos_artwork.compact)
COMPANION = {}

# The folder a game's files are in, the eXoDOS DOSBox configuration of each of its versions
# ({file name: text}), and whether eXoDOS ships an exception.bat for it.
FOLDER = re.compile(r"[^\\/:*?\"<>|\x00-\x1f]{1,64}")


def _configs(value):
    return (isinstance(value, dict) and len(value) <= 8 and
            all(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_. ()-]{1,64}", name) and
                isinstance(text, str) and len(text) <= 256 * 1024 for name, text in value.items()))


# The machine settings a record can give its game (DosCatalog.options), each a per-game setting the
# player can change: whether DOS sees a joystick (catalog/research/machine-v1.json).
MACHINE_OPTIONS = {"joystick": {"on", "off"}}

LAUNCH_FIELDS = {"folder": lambda value: isinstance(value, str) and bool(FOLDER.fullmatch(value)),
                 "configs": _configs,
                 "exception": lambda value: type(value) is bool}


def DOCUMENTS(games):
    """The launch folders index, {"schemaVersion": 1, "folders": {folder: [content IDs]}}: which
    games provide a folder another game's configuration mounts (catalog/research/folders-v1.json)."""
    folders = json.loads((ROOT / "catalog/research/folders-v1.json").read_text(encoding="utf-8"))
    assert folders.get("schemaVersion") == 1 and isinstance(folders.get("folders"), dict), "invalid folders index"
    return {"folders-v1.json": folders}

PROVENANCE = {
    "source": "KairoDos research catalog: the owner's eXoDOS v6 import (tools/generate_dos_catalog.py), metadata review",
    "license": "Original descriptions; titles, tags, launch configurations and image references from eXoDOS and LaunchBox; artwork used under fair use",
    "attribution": "eXoDOS contributors; LaunchBox Games Database contributors; original descriptions"}

# The extensions the app removes from a file's name before looking it up (the catalog schema's
# mediaExtensions); the name index is built the same way (shared app_catalog.lookup_name).
MEDIA_EXTENSIONS = r"\.(?:zip|dosz)$"
