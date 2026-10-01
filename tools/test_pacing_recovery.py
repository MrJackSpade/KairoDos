# SPDX-License-Identifier: GPL-2.0-or-later
"""Run extracted Staging pacing/guest tick code with controlled and real host clocks."""
import argparse, hashlib, json, pathlib, subprocess
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
base=pathlib.Path('third_party/dosbox-staging/src')
sources={name:(base/name).read_text() for name in ['dosbox.cpp','hardware/pic.cpp','hardware/timer.h']}
def method(text,signature):
    start=text.index(signature);opening=text.index('{',start);end=opening+1;depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
methods={
 'increase_ticks':method(sources['dosbox.cpp'],'static void increase_ticks()\n{'),
 'normal_loop':method(sources['dosbox.cpp'],'static Bitu normal_loop()'),
 'reset':method(sources['dosbox.cpp'],'void KairoResetHostTiming()'),
 'tick':method(sources['hardware/pic.cpp'],'void TIMER_AddTick(void)'),
 'diff':method(sources['hardware/timer.h'],'static inline int64_t GetTicksDiff('),
 'since':method(sources['hardware/timer.h'],'static inline int64_t GetTicksUsSince(')}
start=sources['dosbox.cpp'].index('static struct {');end=sources['dosbox.cpp'].index('} ticks = {};',start)+len('} ticks = {};')
state=sources['dosbox.cpp'][start:end]
# Only the sleep primitive is replaced: advance the deterministic clock or
# really sleep. Scheduler arithmetic/branches and normal_loop remain intact.
assert methods['increase_ticks'].count('std::this_thread::sleep_for(sleep_duration);')==1
body=methods['increase_ticks'].replace('std::this_thread::sleep_for(sleep_duration);','host_sleep(sleep_duration);')
template=pathlib.Path('tools/pacing_recovery_fixture.cpp.in').read_text()
source=template.replace('/* EXTRACTED */','\n'.join([state,methods['diff'],methods['since'],methods['reset'],methods['tick'],body,methods['normal_loop']]))
cpp=a.output/'pacing-recovery.cpp';cpp.write_text(source)
compiler=a.ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
subprocess.run([str(compiler),'--target=aarch64-linux-android26','-std=c++17','-O2','-static-libstdc++',str(cpp),'-o',str(a.output/'pacing-recovery')],check=True)
record={'revision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'methods':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in methods.items()},'sourceFiles':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in sources.items()},'generatedSourceSha256':hashlib.sha256(cpp.read_bytes()).hexdigest(),'binarySha256':hashlib.sha256((a.output/'pacing-recovery').read_bytes()).hexdigest(),'limits':'Actual scheduler, normal loop and TIMER_AddTick bodies; CPU execution, GUI, PIC_RunQueue and peripheral callbacks are test sinks. Controlled-clock cases plus real Android host sleeps. Not a game performance or audio test.'}
(a.output/'provenance.json').write_text(json.dumps(record,indent=2)+'\n')
print('Built',a.output/'pacing-recovery')
