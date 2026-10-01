# SPDX-License-Identifier: GPL-2.0-or-later
"""Verify ThinLTO-only compile/link/APK differences and retain core identities."""
import argparse,pathlib,json,hashlib,zipfile,subprocess,re
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--artifacts',type=pathlib.Path,required=True)
p.add_argument('--ndk',type=pathlib.Path,required=True)
p.add_argument('--source-revision',required=True)
p.add_argument('--shared-revision',required=True)
a=p.parse_args();r=a.artifacts;llvm=a.ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin'
source_revision=a.source_revision;shared_revision=a.shared_revision
def digest(b):return hashlib.sha256(b).hexdigest()
compiles={name:json.loads((r/name/'compile_commands.json').read_text()) for name in ['baseline','thinlto']}
assert len(compiles['baseline'])==len(compiles['thinlto'])
commands=0
for x,y in zip(compiles['baseline'],compiles['thinlto']):
    assert x['file']==y['file'] and x['directory']==y['directory']
    assert '-flto=thin' not in x['command'] and y['command'].count('-flto=thin')==1
    assert ' '.join(x['command'].split())==' '.join(y['command'].replace('-flto=thin','').split()),x['file']
    commands+=1
with zipfile.ZipFile(r/'baseline/app.apk') as a, zipfile.ZipFile(r/'thinlto/app.apk') as b:
    names=set(a.namelist())
    assert names==set(b.namelist())
    all_changed=[n for n in sorted(names) if a.read(n)!=b.read(n)]
    signatures=lambda n: n=='META-INF/MANIFEST.MF' or (n.startswith('META-INF/') and n.rsplit('.',1)[-1] in ('SF','RSA','DSA','EC'))
    changed=[n for n in all_changed if not signatures(n)]
    assert changed==['lib/arm64-v8a/libdosbox_staging.so'],changed
records={}
for name in ['baseline','thinlto']:
    folder=r/name;core=folder/'libdosbox_staging.so'
    with zipfile.ZipFile(folder/'app.apk') as z: packaged=z.read('lib/arm64-v8a/libdosbox_staging.so')
    (folder/'packaged.so').write_bytes(packaged)
    notes=subprocess.check_output([str(llvm/'llvm-readelf.exe'),'-n',str(core)],text=True)
    package_notes=subprocess.check_output([str(llvm/'llvm-readelf.exe'),'-n',str(folder/'packaged.so')],text=True)
    buildid=notes.split('Build ID: ')[1].split()[0];assert buildid in package_notes
    sections=subprocess.check_output([str(llvm/'llvm-size.exe'),'-A',str(core)],text=True)
    (folder/'sections.txt').write_text(sections)
    section_sizes={line.split()[0]:int(line.split()[1]) for line in sections.splitlines() if line.startswith('.') and len(line.split())>=2 and line.split()[1].isdigit()}
    assert '.debug_info' in section_sizes and section_sizes['.debug_info']>0
    records[name]={'apkSha256':digest((folder/'app.apk').read_bytes()),'debugElfSha256':digest(core.read_bytes()),'packagedCoreSha256':digest(packaged),'buildId':buildid,'packagedCoreBytes':len(packaged),'debugElfBytes':core.stat().st_size,'textBytes':section_sizes['.text'],'debugInfoBytes':section_sizes['.debug_info'],'sectionSizes':section_sizes}
record={'sourceRevision':source_revision,'sharedRevision':shared_revision,'changedNonSignatureApkEntries':changed,'compileCommandsChecked':commands,'compileDifference':'Only -flto=thin; same source paths and all other tokens','linkDifference':'-flto=thin added by add_link_options; prebuilt dependency archives unchanged','builds':records,'patchSha256':digest((r/'thinlto.patch').read_bytes())}
def link(name):
    text=(r/name/'build.ninja').read_text()
    block=next(x for x in text.split('\n\n') if 'CXX_SHARED_LIBRARY_LINKER__dosbox_Debug' in x)
    return {k:' '.join(re.search(r'^  '+k+r' = (.*)$',block,re.M)[1].split()) for k in ['LANGUAGE_COMPILE_FLAGS','LINK_FLAGS','LINK_LIBRARIES']}
baseline_link,candidate_link=link('baseline'),link('thinlto')
assert baseline_link['LANGUAGE_COMPILE_FLAGS']==candidate_link['LANGUAGE_COMPILE_FLAGS']
assert baseline_link['LINK_LIBRARIES']==candidate_link['LINK_LIBRARIES']
assert baseline_link['LINK_FLAGS']==' '.join(candidate_link['LINK_FLAGS'].replace('-flto=thin','').split())
assert candidate_link['LINK_FLAGS'].count('-flto=thin')==1
record['linkFlags']={'baseline':baseline_link['LINK_FLAGS'],'thinlto':candidate_link['LINK_FLAGS']}
record['linkInputsMatch']=True
record['rawSha256']={name:{file:digest((r/name/file).read_bytes()) for file in ['compile_commands.json','build.ninja','sections.txt']} for name in ['baseline','thinlto']}
(r/'builds.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({k:{i:v for i,v in val.items() if i!='sectionSizes'} for k,val in records.items()},indent=2));print('Commands checked:',commands)
