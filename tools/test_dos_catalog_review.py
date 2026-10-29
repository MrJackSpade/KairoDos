import copy
import gzip
import hashlib
import json
import unittest
import zipfile

from dos_catalog_review import ROOT, apply_review, read_review


class CatalogReviewTests(unittest.TestCase):
    def test_display_edits_preserve_launch_and_identity(self):
        original = {"id": {"title": "Fuck Quest", "description": "Old summary",
            "tags": ["1998", "Adventure", "♥"], "launch": {"folder": "original"},
            "artwork": {"boxArt": "original.webp"}}}
        saved = copy.deepcopy(original)
        review = {"schemaVersion": 1, "records": [{"contentId": "id",
            "sourceTitle": "Fuck Quest", "fields": {"title": "F♥ck Quest",
                "description": "A short comedy adventure.", "heart": True}}]}
        result = apply_review(original, review)
        self.assertEqual(original, saved)
        self.assertEqual(result.keys(), original.keys())
        self.assertEqual(result["id"]["launch"], original["id"]["launch"])
        self.assertEqual(result["id"]["artwork"], original["id"]["artwork"])
        self.assertEqual(result["id"]["tags"], ["1998", "Adventure", "♥"])
        self.assertNotIn("heart", result["id"])  # Compatible with released validators.
        self.assertEqual(apply_review(result, review), result)
        review["records"][0]["fields"]["heart"] = False
        self.assertEqual(apply_review(result, review)["id"]["tags"], ["1998", "Adventure"])

    def test_variants_keep_filename_keys_and_clean_fallback_title(self):
        games = {"id": {"title": "Old", "variants": {
            "original.zip": {"title": "Sex"}, "other.zip": {"title": "Other"}}}}
        review = {"schemaVersion": 1, "records": [{"contentId": "id",
            "variant": "original.zip", "sourceTitle": "Sex", "fields": {"title": "S♥x"}}]}
        result = apply_review(games, review)
        self.assertEqual(result["id"]["title"], "S♥x / Other")
        self.assertEqual(result["id"]["variants"].keys(), games["id"]["variants"].keys())

    def test_stale_ambiguous_and_non_display_reviews_fail(self):
        games = {"id": {"title": "Title"}}
        item = {"contentId": "id", "sourceTitle": "Title", "fields": {"heart": True}}
        def apply(items):
            return apply_review(games, {"schemaVersion": 1, "records": items})
        for bad in ({**item, "sourceTitle": "Stale"}, {**item, "contentId": "missing"},
                    {**item, "variant": "missing"}, {**item, "fields": {"heart": 1}},
                    {**item, "fields": {"launch": {}}}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                apply([bad])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            apply([item, item])

    def test_research_catalog_carries_reviewed_titles(self):
        # The import (tools/import_catalog.py) names each game by its content ID's first 16 digits;
        # a reviewed variant is that game's "<key>.<n>". Descriptions are original, never the review's.
        games = json.loads(gzip.decompress((ROOT / "catalog/research/games-v1.json.gz").read_bytes()))["games"]
        titles = {title for game in games.values() for title in [game["title"]]}
        reviewed = [item["fields"]["title"] for item in read_review()["records"] if "title" in item["fields"]]
        self.assertTrue(reviewed)
        self.assertEqual([title for title in reviewed if title not in titles], [])


if __name__ == "__main__":
    unittest.main()
