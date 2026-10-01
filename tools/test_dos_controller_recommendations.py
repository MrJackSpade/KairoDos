"""Verify ledger recommendations flow through the generated DOS profile catalog."""
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from generate_dos_catalog import apply_controller_recommendations  # noqa: E402


class DosControllerRecommendationsTest(unittest.TestCase):
    def test_all_current_ledger_assignments_generate_without_replacing_doom(self):
        catalog = ROOT / "kairodos/src/main/assets/catalog/dos"
        recommendations = json.loads(
            (ROOT / "catalog/controller-research/recommendations.json").read_text("utf-8"))
        base = json.loads((catalog / "controller-profiles-v1.json").read_text("utf-8"))
        current_ids = {
            content_id
            for path in catalog.glob("[0-9a-f][0-9a-f].json")
            for content_id in json.loads(path.read_text("utf-8"))["games"]
        }

        generated = apply_controller_recommendations(
            base, recommendations, current_ids, {})

        self.assertEqual(generated["profiles"], base["profiles"])
        self.assertEqual(generated["assignments"], recommendations["assignments"])
        self.assertEqual(set(generated["presets"]),
                         set(recommendations["assignments"].values()))
        self.assertTrue(set(generated["assignments"]).issubset(current_ids))
        self.assertFalse(set(generated["assignments"]) &
                         set(generated["profiles"].get("doom-v1", [])))
        for profile in generated["presets"].values():
            inputs = [binding["input"] for binding in profile["bindings"]]
            self.assertEqual(len(inputs), len(set(inputs)))
            self.assertFalse(any(value.startswith(("virtual:ls", "virtual:rs"))
                                 for value in inputs))
            self.assertFalse(any(binding.get("joystick") in ("l3", "r3")
                                 for binding in profile["bindings"]))

    def test_identity_migration_carries_assignment(self):
        source = "sha256-dos-manifest-v1:old"
        target = "sha256-dos-manifest-v1:new"
        profile_id = "test-profile"
        base = {"schemaVersion": 1, "profiles": {"doom-v1": []}}
        recommendations = {
            "schemaVersion": 1,
            "profiles": {profile_id: {"bindings": []}},
            "assignments": {source: profile_id},
        }

        generated = apply_controller_recommendations(
            base, recommendations, {target}, {source: {target}})

        self.assertEqual(generated["assignments"], {target: profile_id})

    def test_defaults_migrate_legacy_and_preserve_explicit_empty_with_sticks(self):
        identity = "sha256-dos-manifest-v1:test"
        legacy = [{"input": "virtual:a", "keys": [13]}]
        for preset, expected in (
            ({"bindings": legacy}, {"withoutSticks": legacy}),
            ({"bindings": legacy, "defaults": {"withoutSticks": legacy, "withSticks": []}},
             {"withoutSticks": legacy, "withSticks": []}),
            ({"bindings": legacy, "defaults": {"withoutSticks": legacy,
                "withSticks": [{"input": "virtual:rsright", "mouse": "moveRight"}]}},
             {"withoutSticks": legacy, "withSticks": [{"input": "virtual:rsright", "mouse": "moveRight"}]}),
        ):
            with self.subTest(preset=preset):
                generated = apply_controller_recommendations(
                    {"profiles": {"doom-v1": []}}, {"schemaVersion": 1,
                    "profiles": {"test": preset}, "assignments": {identity: "test"}}, {identity}, {})
                self.assertEqual(generated["presets"]["test"]["defaults"], expected)


if __name__ == "__main__":
    unittest.main()
