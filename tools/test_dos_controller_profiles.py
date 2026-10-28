"""Check that every cataloged classic Doom release gets its controller profile."""

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESEARCH = ROOT / "catalog/research"
DOOM_ENGINE_TITLES = {
    "DOOM", "DOOM II: Hell on Earth", "The Ultimate DOOM", "Final DOOM",
    "Master Levels for DOOM II", "DOOM: eXoWAD", "DOOM: SLIGE",
    "The Lost Episodes of Doom", "Demon Gate: 666 New Levels for Doom & Doom II",
    "DOOM 4",
}


class DosControllerProfilesTest(unittest.TestCase):
    def test_doom_profile_covers_every_catalog_variant(self):
        asset = json.loads((RESEARCH / "controller-profiles-v1.json").read_text("utf-8"))
        self.assertEqual(asset["schemaVersion"], 1)
        ids = asset["profiles"]["doom-v1"]
        self.assertEqual(len(ids), len(set(ids)))
        games = json.loads((RESEARCH / "games-v1.json").read_text("utf-8"))["games"]
        expected = {content_id for record in games.values() if record.get("title") in DOOM_ENGINE_TITLES
                    for content_id in record.get("contentIds", [])}
        self.assertEqual(set(ids), expected)


if __name__ == "__main__":
    unittest.main()
