import json
import unittest
from pathlib import Path
from dos_artwork import compact, expand, transform


class ArtworkTests(unittest.TestCase):
    def test_round_trip_and_missing_kinds(self):
        for bits in (1, 2, 3):
            ref = {'id': 'ab' * 32, 'variant': '0123456789ab', 'kinds': bits}
            paths = expand(ref)
            self.assertEqual(compact(paths), ref)
            self.assertEqual(compact(ref), ref)
            self.assertEqual('boxArt' in paths, bool(bits & 1))
            self.assertEqual('preview' in paths, bool(bits & 2))
            for kind, path in paths.items():
                self.assertEqual(path, f'art/catalog/dos/ab/{"ab" * 32}/0123456789ab/{kind}.webp')

    def test_invalid_and_unrepresentable_references_fail(self):
        ref = {'id': 'a' * 64, 'variant': 'b' * 12, 'kinds': 3}
        for bad in ({**ref, 'id': '../escape'}, {**ref, 'kinds': True},
                    {**ref, 'kinds': 4}, {**ref, 'variant': 'z' * 12},
                    {**ref, 'boxArt': 'path'}, {'id': 'a' * 64}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                expand(bad)
        paths = expand(ref)
        paths['boxArt'] = paths['boxArt'].replace('/aa/', '/bb/')
        with self.assertRaises(ValueError):
            compact(paths)

    def test_generated_catalog_is_compact_and_all_assets_resolve(self):
        root = Path(__file__).resolve().parents[1]
        assets = root / 'kairodos/src/main/assets'
        count = 0
        for path in (assets / 'catalog/dos').glob('??.json'):
            source = json.loads(path.read_text(encoding='utf-8'))
            self.assertEqual(transform(source, compact), source)
            def check(value):
                nonlocal count
                if isinstance(value, dict):
                    for key, item in value.items():
                        if key == 'artwork':
                            for asset in expand(item).values():
                                self.assertTrue((assets / asset).is_file(), asset)
                                count += 1
                        else:
                            check(item)
            check(source)
        self.assertGreater(count, 14000)
