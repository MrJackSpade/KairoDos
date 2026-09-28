"""Check that every cataloged classic Doom release gets its controller profile."""

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "kairodos/src/main/assets/catalog/dos"
DOOM_ENGINE_TITLES = {
    "DOOM", "DOOM II: Hell on Earth", "The Ultimate DOOM", "Final DOOM",
    "Master Levels for DOOM II", "DOOM: eXoWAD", "DOOM: SLIGE",
    "The Lost Episodes of Doom", "Demon Gate: 666 New Levels for Doom & Doom II",
    "DOOM 4",
}


class DosControllerProfilesTest(unittest.TestCase):
    def test_doom_profile_covers_every_catalog_variant(self):
        asset = json.loads((CATALOG / "controller-profiles-v1.json").read_text("utf-8"))
        self.assertEqual(asset["schemaVersion"], 1)
        ids = asset["profiles"]["doom-v1"]
        self.assertEqual(len(ids), len(set(ids)))
        games = {
            content_id: record
            for path in CATALOG.glob("[0-9a-f][0-9a-f].json")
            for content_id, record in json.loads(path.read_text("utf-8"))["games"].items()
        }
        expected = {content_id for content_id, record in games.items()
                    if record.get("title") in DOOM_ENGINE_TITLES}
        self.assertEqual(set(ids), expected)


if __name__ == "__main__":
    unittest.main()
