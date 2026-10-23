"""Lossless compact artwork references for distributed DOS catalogs.

{"id": <64 hex>, "variant": <12 hex>, "kinds": 1 box | 2 screenshot | 3 both} names each image's
path, art/catalog/dos/<id[:2]>/<id>/<variant>/<boxArt|preview>.webp, which the repository serves.
An optional "launchbox": {"boxArt"|"preview": {"id": uuid, "format": 0 jpg | 1 png,
"revision": 0 | 2}} names LaunchBox's image for a kind, which is downloaded in place of the
repository's copy: expanded, that kind also has its "<kind>Url".
"""
import copy
import re

PATH = re.compile(r"art/catalog/dos/([0-9a-f]{2})/([0-9a-f]{64})/([0-9a-f]{12})/(boxArt|preview)\.webp")
KINDS = {"boxArt": 1, "preview": 2}
LAUNCHBOX = "https://images.launchbox-app.com/"
LAUNCHBOX_URL = re.compile(re.escape(LAUNCHBOX) +
                           r"(r2_)?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(jpg|png)")


def launchbox_url(ref):
    if (not isinstance(ref, dict) or set(ref) != {"id", "format", "revision"}
            or not isinstance(ref["id"], str) or not LAUNCHBOX_URL.fullmatch(LAUNCHBOX + ref["id"] + ".jpg")
            or type(ref["format"]) is not int or ref["format"] not in (0, 1)
            or type(ref["revision"]) is not int or ref["revision"] not in (0, 2)):
        raise ValueError("Invalid LaunchBox image reference")
    return LAUNCHBOX + ("r2_" if ref["revision"] == 2 else "") + ref["id"] + (".png" if ref["format"] else ".jpg")


def launchbox_ref(url):
    match = LAUNCHBOX_URL.fullmatch(url)
    if not match:
        raise ValueError(f"Not a LaunchBox image URL: {url}")
    return {"id": match[2], "format": int(match[3] == "png"), "revision": 2 if match[1] else 0}


def expand(art):
    if not isinstance(art, dict):
        raise ValueError("Invalid artwork object")
    if not (art.keys() & {"id", "variant", "kinds"}):
        return copy.deepcopy(art)
    if (set(art) - {"launchbox"} != {"id", "variant", "kinds"}
            or not isinstance(art["id"], str) or not re.fullmatch(r"[0-9a-f]{64}", art["id"])
            or not isinstance(art["variant"], str) or not re.fullmatch(r"[0-9a-f]{12}", art["variant"])
            or type(art["kinds"]) is not int or art["kinds"] not in (1, 2, 3)):
        raise ValueError("Invalid compact DOS artwork")
    result = {kind: f'art/catalog/dos/{art["id"][:2]}/{art["id"]}/{art["variant"]}/{kind}.webp'
              for kind, bit in KINDS.items() if art["kinds"] & bit}
    launchbox = art.get("launchbox", {})
    if not isinstance(launchbox, dict) or not launchbox or launchbox.keys() - result.keys():
        if "launchbox" in art:
            raise ValueError("Invalid LaunchBox images for DOS artwork")
    for kind, ref in launchbox.items():
        result[kind + "Url"] = launchbox_url(ref)
    return result


def compact(art):
    original = expand(art)
    if not original:
        return {}
    refs = set()
    kinds = 0
    launchbox = {}
    for kind, path in original.items():
        if kind.endswith("Url"):
            launchbox[kind[:-3]] = launchbox_ref(path)
            continue
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
    if launchbox:
        result["launchbox"] = dict(sorted(launchbox.items()))
    if expand(result) != original:
        raise ValueError("DOS artwork did not round-trip")
    return result


def with_launchbox(art, images):
    """Compact [art] with LaunchBox's image for each kind it has a path for and [images] names,
    {"boxArt"|"preview": LaunchBox URL}."""
    expanded = expand(art)
    for kind in KINDS:
        if kind in expanded and images.get(kind):
            expanded[kind + "Url"] = images[kind]
    return compact(expanded)


def transform(value, operation):
    if isinstance(value, dict):
        return {key: operation(item) if key == "artwork" else transform(item, operation)
                for key, item in value.items()}
    if isinstance(value, list):
        return [transform(item, operation) for item in value]
    return value
