"""Apply reviewed display metadata without changing game identities or launch data."""
import argparse
import copy
import json
from pathlib import Path
from dos_artwork import compact, transform

ROOT = Path(__file__).resolve().parent.parent
REVIEW = ROOT / "catalog/metadata-review-v1.json"
CATALOG = ROOT / "kairodos/src/main/assets/catalog/dos"


def apply_review(games, review, *, require_all=True):
    if review.get("schemaVersion") != 1:
        raise ValueError("Unsupported metadata review schema")
    result = copy.deepcopy(games)
    seen = set()
    for item in review["records"]:
        key = (item["contentId"], item.get("variant", ""))
        if key in seen:
            raise ValueError(f"Duplicate metadata review: {key}")
        seen.add(key)
        if key[0] not in result:
            if require_all:
                raise ValueError(f"Review identity missing from catalog: {key}")
            continue
        record = result[key[0]]
        if key[1]:
            if key[1] not in record.get("variants", {}):
                raise ValueError(f"Review variant missing from catalog: {key}")
            record = record["variants"][key[1]]
        fields = item["fields"]
        if not fields or fields.keys() - {"title", "description", "heart"}:
            raise ValueError(f"Invalid review fields: {key}")
        for field, value in fields.items():
            if field == "heart":
                valid = type(value) is bool
            else:
                valid = isinstance(value, str) and bool(value.strip()) and len(value) <= (
                    160 if field == "title" else 8000)
            if not valid:
                raise ValueError(f"Invalid reviewed {field}: {key}")
        if record.get("title") not in (item["sourceTitle"], fields.get("title")):
            raise ValueError(f"Review title no longer matches: {key}")
        record.update({field: value for field, value in fields.items() if field != "heart"})
        if "heart" in fields:
            # DOS already displays tags. Keep the public schema compatible with
            # installed apps, whose validators reject unknown boolean fields.
            tags = [tag for tag in record.get("tags", []) if tag != "♥"]
            record.pop("heart", None)
            if fields["heart"]:
                tags.append("♥")
            record["tags"] = tags
    # Combined fallback titles must use the same reviewed spelling as variants.
    for record in result.values():
        if "variants" in record:
            record["title"] = " / ".join(v["title"] for v in record["variants"].values())
    return result


def read_review():
    return json.loads(REVIEW.read_text(encoding="utf-8"))


def regenerate(catalog=CATALOG, *, check=False):
    paths = sorted(catalog.glob("[0-9a-f][0-9a-f].json"))
    shards = {p: json.loads(p.read_text(encoding="utf-8")) for p in paths}
    original = {key: record for shard in shards.values() for key, record in shard["games"].items()}
    reviewed = transform(apply_review(original, read_review()), compact)
    changed = 0
    for path, shard in shards.items():
        games = {key: reviewed[key] for key in shard["games"]}
        if games != shard["games"]:
            if check:
                raise ValueError(f"Regenerate reviewed catalog metadata: {path.name}")
            shard["games"] = games
            path.write_text(json.dumps(shard, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            changed += 1
    print(f"Reviewed DOS metadata: {len(read_review()['records'])} records, {changed} changed shards")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    regenerate(args.catalog, check=args.check)
