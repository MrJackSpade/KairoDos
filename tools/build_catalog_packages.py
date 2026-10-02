#!/usr/bin/env python3
"""Partition preserved full inputs into the clean core and a manually installed catalog."""
import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'shared/tools'))
from catalog_package import build_package, compact, image_paths, filter_artwork
from dos_artwork import expand, compact as compact_art
from dos_catalog_review import apply_review, read_review


def controllers(source, ids):
    result = json.loads(json.dumps(source))
    defaults = json.loads((ROOT / 'catalog/controller-defaults.json').read_text('utf8'))
    result['presets'].update(defaults['presets'])
    result['assignments'].update(defaults['assignments'])
    result['assignments'] = {k:v for k,v in result['assignments'].items() if k in ids}
    result['profiles'] = {k:[v for v in values if v in ids] for k,values in result['profiles'].items()}
    result['profiles'] = {k:v for k,v in result['profiles'].items() if v}
    used = set(result['assignments'].values()) | result['profiles'].keys()
    result['presets'] = {k:v for k,v in result['presets'].items() if k in used}
    return result


def generate(check=False):
    policy = json.loads((ROOT / 'catalog/core-review-v1.json').read_text('utf8'))
    with zipfile.ZipFile(ROOT / 'catalog/complete-input-v1.zip') as archive:
        files = {name:json.loads(archive.read(name)) for name in archive.namelist()}
    games = apply_review({k:v for name,root in files.items() if re.fullmatch('[0-9a-f]{2}.json',name)
                          for k,v in root['games'].items()}, read_review())
    excluded = set(policy['excluded'])
    marked = {k for k,v in games.items() if any('♥' in r.get('tags',[]) for r in [v,*v.get('variants',{}).values()])}
    assert marked <= excluded and excluded <= games.keys(), 'Review every adult-marked record'
    core_ids = games.keys() - excluded
    approved = policy['approvedArtwork']
    art_root = ROOT / 'catalog/artwork'
    for path,sha in approved.items():
        assert hashlib.sha256((art_root/path).read_bytes()).hexdigest() == sha, path
    core = filter_artwork({k:v for k,v in games.items() if k in core_ids}, approved, expand, compact_art)
    optional = {'schemaVersion':1,'games':{k:v for k,v in games.items() if k in excluded},
                'folders':{k:[x for x in v if x in excluded] for k,v in files['folders.json'].items() if any(x in excluded for x in v)},
                'controllers':controllers(files['controller-profiles-v1.json'],excluded)}
    def clean(value):
        if isinstance(value,dict): return {k:clean(v) for k,v in value.items() if not(k=='description' and not v.strip())}
        if isinstance(value,list): return [clean(x) for x in value]
        return value
    optional=clean(optional); core=clean(core)
    output=ROOT/'kairodos/src/main/assets/catalog/dos'
    generated={f'{i:02x}.json':{'schemaVersion':1,'games':{k:v for k,v in core.items() if k.split(':')[1].startswith(f'{i:02x}')}} for i in range(256)}
    generated['folders.json']={k:[x for x in v if x in core_ids] for k,v in files['folders.json'].items() if any(x in core_ids for x in v)}
    generated['hidden-index-v1.json']={'schemaVersion':1,'hidden':{k:v['hidden'] for k,v in core.items() if 'hidden' in v}}
    generated['controller-profiles-v1.json']=controllers(files['controller-profiles-v1.json'],core_ids)
    generated['core-v2.json']={'schemaVersion':2}
    for name,value in generated.items():
        if check:
            assert json.loads((output/name).read_text('utf8'))==value, f'Stale generated core: {name}'
        else: (output/name).write_bytes(compact(value))
    if check:
        with zipfile.ZipFile(ROOT/'catalog/optional/kairodos-adult-v1.zip') as archive:
            assert json.loads(archive.read('data.json')) == optional, 'Stale optional DOS catalog'
    if not check:
        for path in approved:
            destination=ROOT/'kairodos/src/main/assets'/path
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(art_root/path,destination)
        build_package(optional,image_paths(optional,expand),art_root,
            ROOT/'catalog/optional/kairodos-adult-v1.zip',product='dos',identity='kairodos-adult',
            name='KairoDos adult catalog',revision=1,
            source='https://raw.githubusercontent.com/MrJackSpade/KairoDos/main/catalog/optional/kairodos-adult-v1.meta.json',
            archive_url='https://raw.githubusercontent.com/MrJackSpade/KairoDos/main/catalog/optional/kairodos-adult-v1.zip')
        (ROOT/'catalog/excluded-ids-v1.json').write_bytes(compact({'schemaVersion':1,'ids':sorted(excluded),
            'artwork':sorted(image_paths(optional,expand))}))
    print(f'DOS: {len(core)} core records; {len(excluded)} excluded; {len(approved)} reviewed core images')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true')
    generate(parser.parse_args().check)
