# SPDX-License-Identifier: GPL-2.0-or-later
"""Build actual-source ordinary OPL port/timer differential tests for ARM64."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
path = 'third_party/dosbox-staging/src/hardware/audio/opl.cpp'
revision = '9dcfd7f6'
old = subprocess.check_output(['git', 'show', revision + ':' + path]).decode()
new = Path(path).read_text()
header = Path(path).with_suffix('.h').read_text()
declarations = header[header.index('class Timer {'):header.index('// The cache for two OPL chips')]
old_header = subprocess.check_output(['git', 'show', revision + ':' + str(Path(path).with_suffix('.h')).replace('\\','/')]).decode()
old_declarations = old_header[old_header.index('class Timer {'):old_header.index('// The cache for two OPL chips')]
assert declarations == old_declarations, 'Timer/chip state declarations changed'

def method(source, name):
    match = re.search(r'^[^\n]*\b' + re.escape(name) + r'\([^;]*?\)\s*(?:const\s*)?(?:\:[^{]*)?\{', source, re.M)
    assert match, name
    start = match.start()
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

names = ['Timer::' + n for n in ('Timer','Update','Reset','SetCounter','GetCounter',
    'SetMask','IsMasked','Stop','Start','IsEnabled')]
names += ['OplChip::' + n for n in ('OplChip','Write','Read','EsfmReadbackReg')]
names += ['Opl::' + n for n in ('WriteReg','WriteAddr','CacheWrite','DualWrite','PortWrite','PortRead')]
old_methods = {name:method(old,name) for name in names}
new_methods = {name:method(new,name) for name in names}
def registration(source):
    ctor = method(source, 'Opl::Opl')
    begin = ctor.index('using namespace std::placeholders;')
    return ctor[begin:ctor.index('MAPPER_AddHandler', begin)]
old_registration, new_registration = registration(old), registration(new)
assert old_registration == new_registration, 'Port registration changed; inspect before claiming parity'
template = Path(__file__).with_name('opl_port_fixture.cpp.in').read_text()
fixture = template.split('// @NAMESPACE_FIXTURE\n')[1].split('// @END_NAMESPACE_FIXTURE\n')[0]
source = template.split('// @NAMESPACE_FIXTURE\n')[0]
for namespace, methods in [('legacy',old_methods), ('candidate',new_methods)]:
    source += '\nnamespace '+namespace+' {\n' + declarations + fixture
    source += '\n'.join(methods.values())
    if namespace == 'candidate':
        source += '\n' + method(new, 'Opl::QueueWrite')
    source += '\n}\n'
source += template.split('// @END_NAMESPACE_FIXTURE\n')[1]
cpp = a.output/'opl-ports.cpp'
cpp.write_text(source)
compiler = a.ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
subprocess.run([str(compiler), '--target=aarch64-linux-android26', '-std=c++17', '-O3',
    '-static-libstdc++', str(cpp), '-o', str(a.output/'opl-ports')], check=True)
sha = lambda b: hashlib.sha256(b).hexdigest()
(a.output/'provenance.json').write_text(json.dumps({
    'referenceRevision':revision,
    'timerChipDeclarationsUnchanged':True,
    'timerChipDeclarationsSha256':sha(declarations.encode()),
    'methods':{n:{'unchanged':old_methods[n]==new_methods[n],
        'referenceSha256':sha(old_methods[n].encode()), 'currentSha256':sha(new_methods[n].encode())} for n in names},
    'queueWriteSha256':sha(method(new,'Opl::QueueWrite').encode()),
    'portRegistrationUnchanged':True,
    'portRegistrationSha256':sha(new_registration.encode()),
    'sourceSha256':sha(cpp.read_bytes()), 'binarySha256':sha((a.output/'opl-ports').read_bytes()),
    'limits':'Actual Timer/OplChip/ordinary port methods with deterministic PIC time and recording sinks. DSP synthesis, catch-up rendering, capture file serialization and GUS internals are replaced by sinks; unsupported ESFM/Gold calls fail. Real worker ordering and synthesis are validated separately.'},indent=2)+'\n')
print('Built', a.output/'opl-ports')
