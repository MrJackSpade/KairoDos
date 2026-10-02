import copy
import hashlib
import json
import unittest
import zipfile

from dos_catalog_review import CATALOG, ROOT, apply_review, read_review


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

    def test_bundled_and_public_catalog_retain_reviewed_descriptions(self):
        games = {key: value for path in CATALOG.glob("[0-9a-f][0-9a-f].json")
                 for key, value in json.loads(path.read_text(encoding="utf-8"))["games"].items()}
        self.assertEqual(games, apply_review(games, read_review()))
        archive = ROOT / "catalog/online-v1.zip"
        metadata = json.loads(archive.with_suffix(".json").read_text(encoding="utf-8"))
        self.assertEqual(metadata["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(metadata["size"], archive.stat().st_size)
        descriptions = 0
        with zipfile.ZipFile(archive) as source:
            for path in CATALOG.glob("[0-9a-f][0-9a-f].json"):
                public = json.loads(source.read(path.name))["games"]
                for key, record in json.loads(path.read_text(encoding="utf-8"))["games"].items():
                    self.assertEqual(public[key].get("title"), record.get("title"))
                    for name, variant in record.get("variants", {"": record}).items():
                        exported = public[key]["variants"][name] if name else public[key]
                        if variant.get("description", "").strip():
                            self.assertEqual(exported["description"], variant["description"])
                            descriptions += 1
                        else:
                            self.assertNotIn("description", exported)
                        self.assertEqual(exported.get("tags"), variant.get("tags"))
                        self.assertEqual(exported.get("launch"), variant.get("launch"))
                        self.assertNotIn("heart", exported)
        self.assertGreater(descriptions, 7000)
