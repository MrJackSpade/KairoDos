"""Lossless compact artwork references for distributed DOS catalogs."""
import copy
import re

PATH = re.compile(r"art/catalog/dos/([0-9a-f]{2})/([0-9a-f]{64})/([0-9a-f]{12})/(boxArt|preview)\.webp")
KINDS = {"boxArt": 1, "preview": 2}


def expand(art):
    if not isinstance(art, dict):
        raise ValueError("Invalid artwork object")
    if not (art.keys() & {"id", "variant", "kinds"}):
        return copy.deepcopy(art)
    if (set(art) != {"id", "variant", "kinds"}
            or not isinstance(art["id"], str) or not re.fullmatch(r"[0-9a-f]{64}", art["id"])
            or not isinstance(art["variant"], str) or not re.fullmatch(r"[0-9a-f]{12}", art["variant"])
            or type(art["kinds"]) is not int or art["kinds"] not in (1, 2, 3)):
        raise ValueError("Invalid compact DOS artwork")
    return {kind: f'art/catalog/dos/{art["id"][:2]}/{art["id"]}/{art["variant"]}/{kind}.webp'
            for kind, bit in KINDS.items() if art["kinds"] & bit}


def compact(art):
    original = expand(art)
    if not original:
        return {}
    refs = set()
    kinds = 0
    for kind, path in original.items():
        match = PATH.fullmatch(path) if isinstance(path, str) else None
        if kind not in KINDS or not match:
            raise ValueError("Unrepresentable DOS artwork")
        prefix, digest, variant, filename = match.groups()
        if prefix != digest[:2] or filename != kind:
            raise ValueError("DOS artwork path does not match template")
        refs.add((digest, variant))
        kinds |= KINDS[kind]
    if len(refs) != 1:
        raise ValueError("DOS artwork pair has different asset identities")
    digest, variant = refs.pop()
    result = {"id": digest, "variant": variant, "kinds": kinds}
    if expand(result) != original:
        raise ValueError("DOS artwork did not round-trip")
    return result


def transform(value, operation):
    if isinstance(value, dict):
        return {key: operation(item) if key == "artwork" else transform(item, operation)
                for key, item in value.items()}
    if isinstance(value, list):
        return [transform(item, operation) for item in value]
    return value
