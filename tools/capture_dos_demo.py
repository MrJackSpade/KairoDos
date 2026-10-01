# SPDX-License-Identifier: GPL-2.0-or-later
import argparse, subprocess, time, json, pathlib, hashlib, shlex
parser=argparse.ArgumentParser(description='Capture a running DOS demo after answering the verified N sound prompt')
parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True);parser.add_argument('--output',required=True,type=pathlib.Path)
parser.add_argument('--observation', choices=('full', 'light'), default='full',
                    help='Light omits simpleperf and intermediate screenshots and samples CPU only at sparse checkpoints')
parser.add_argument('--frame-period', choices=('0.5', '1.0'), default='0.5',
                    help='Local frame-history polling interval; gaps still invalidate exact rates')
parser.add_argument('--prompt-response', choices=('n', 'none'), default='n',
                    help='Use none only when the caller verified that no launch prompt needs answering')
args=parser.parse_args()
ADB=args.adb
SERIAL=args.serial
OUT=args.output
OUT.mkdir(parents=True,exist_ok=True)
assert not (OUT/'start.json').exists(), 'Use a new directory; do not overwrite a capture'
def shell(command):
    return subprocess.check_output([ADB,'-s',SERIAL,'shell',command],text=True,encoding='utf-8').strip()
def screenshot(name):
    remote='/data/local/tmp/kairo-baseline-'+name+'.png'
    shell('screencap -d 1 -p '+remote)
    subprocess.run([ADB,'-s',SERIAL,'pull',remote,str(OUT/(name+'.png'))],check=True,stdout=subprocess.DEVNULL)
# Caller must verify the launch sound prompt before invoking this script.
assert 'mWakefulness=Awake' in shell('dumpsys power'), 'Device is not awake'
assert 'SurfaceView[com.loxifi.kairodos/' in shell('dumpsys SurfaceFlinger --list'), 'Game surface absent'
if args.prompt_response == 'n':
    shell('input -d 2 keyevent 42')
print('Warm-up started: 60 seconds', flush=True)
launch=time.monotonic()
(OUT/'start.json').write_text(json.dumps({'launchHostMonotonic':launch,'launchUtc':time.time(),'warmupSeconds':60,'captureSeconds':120}))
deadline=launch+60
while time.monotonic()<deadline:
    assert 'mWakefulness=Awake' in shell('dumpsys power'), 'Sleep during warmup'
    time.sleep(min(1,max(0,deadline-time.monotonic())))
(OUT/'warmup.json').write_text(json.dumps({'actualWarmupSeconds':time.monotonic()-launch}))
print('Capture started: 120 seconds',flush=True)
screenshot('capture-start')
(OUT/'audio-start.txt').write_text(shell('dumpsys media.audio_flinger'))
(OUT/'audio-start-time.json').write_text(json.dumps({'hostMonotonic':time.monotonic(),'hostUtc':time.time()}))
pid=shell('pidof com.loxifi.kairodos')
if not pid.isdigit(): raise RuntimeError('Expected one product process')
threads=shell(f'for t in /proc/{pid}/task/*; do printf "%s " "${{t##*/}}"; cat "$t/comm"; done')
(OUT/'threads.txt').write_text(threads)
layer=next(x for x in shell('dumpsys SurfaceFlinger --list').splitlines() if x.startswith('SurfaceView[com.loxifi.kairodos/') and '(BLAST)' in x)
sampler_source=pathlib.Path(__file__).with_name('collect_surface_history.sh').read_text()
(OUT/'surface-sampler.sh').write_bytes(sampler_source.replace('\r\n','\n').encode())
remote_sampler='/data/local/tmp/kairo-frames-'+hashlib.sha256(str(OUT.resolve()).encode()).hexdigest()[:12]
subprocess.run([ADB,'-s',SERIAL,'push',str(OUT/'surface-sampler.sh'),remote_sampler+'.sh'],check=True,stdout=subprocess.DEVNULL)
frame_sampler=subprocess.Popen([ADB,'-s',SERIAL,'shell',
    'sh '+shlex.quote(remote_sampler+'.sh')+' '+shlex.quote(remote_sampler+'.txt')+' '+shlex.quote(layer)+' 125 '+args.frame_period],
    stdout=(OUT/'surface-sampler.log').open('w'),stderr=subprocess.STDOUT)
record=stat=None
if args.observation == 'full':
    record=subprocess.Popen([ADB,'-s',SERIAL,'shell',f'cd /data/local/tmp && ./kairo-simpleperf record --clockid monotonic --trace-offcpu -o kairo-demo.perf.data -e cpu-clock -f 99 --call-graph fp -p {pid} --duration 120'],stdout=(OUT/'record.log').open('w'),stderr=subprocess.STDOUT)
    stat=subprocess.Popen([ADB,'-s',SERIAL,'shell',f'cd /data/local/tmp && ./kairo-simpleperf stat -p {pid} -e cpu-cycles,instructions,task-clock,cache-references,cache-misses,stalled-cycles-backend,stalled-cycles-frontend --duration 120'],stdout=(OUT/'counters.txt').open('w'),stderr=subprocess.STDOUT)
start=time.monotonic(); next_shot=30
with (OUT/'samples.jsonl').open('w') as output:
    def sample():
        raw=shell(f'''cat /proc/uptime; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; cat /sys/kernel/debug/clk/armclk/clk_rate /sys/kernel/debug/clk/clk_scmi_ddr/clk_rate /sys/kernel/debug/clk/clk_scmi_gpu/clk_rate; cat /sys/class/devfreq/fde60000.gpu/cur_freq; for z in /sys/class/thermal/thermal_zone*; do cat "$z/type" "$z/temp"; done; for z in /sys/class/thermal/cooling_device*; do cat "$z/type" "$z/cur_state"; done; cat /proc/{pid}/stat; for t in /proc/{pid}/task/*; do cat "$t/stat"; done; echo FRAMES''')
        power=shell('dumpsys power')
        if 'mWakefulness=Awake' not in power:
            (OUT/'invalid-power.txt').write_text(power)
            raise RuntimeError('Device slept during capture; capture invalid')
        elapsed=time.monotonic()-start
        output.write(json.dumps({'elapsed':elapsed,'hostUtc':time.time(),'raw':raw})+'\n'); output.flush()
        return elapsed
    while time.monotonic()-start<120:
        elapsed=sample()
        if args.observation == 'full' and elapsed>=next_shot and next_shot<120:
            screenshot(f'capture-{next_shot}s'); next_shot+=30
        time.sleep(min(60 if args.observation == 'light' else .5, max(0,120-(time.monotonic()-start))))
    if args.observation == 'light':
        sample()
screenshot('capture-end')
if record is not None:
    record.wait(); stat.wait()
(OUT/'audio-end.txt').write_text(shell('dumpsys media.audio_flinger'))
(OUT/'audio-end-time.json').write_text(json.dumps({'hostMonotonic':time.monotonic(),'hostUtc':time.time()}))
capture_duration=time.monotonic()-start
frame_sampler.wait()
subprocess.run([ADB,'-s',SERIAL,'pull',remote_sampler+'.txt',str(OUT/'surface-history.txt')],check=True)
(OUT/'capture.json').write_text(json.dumps({'duration':capture_duration,'layer':layer,'pid':pid,'observation':args.observation,'framePeriod':args.frame_period,'promptResponse':args.prompt_response,'recordExit':record.returncode if record is not None else None,'statExit':stat.returncode if stat is not None else None,'frameSamplerExit':frame_sampler.returncode,'frameSampler':'device-local-v1'}))
print('Completed real application capture:',OUT)

if record is not None:
    subprocess.run([ADB,'-s',SERIAL,'pull','/data/local/tmp/kairo-demo.perf.data',str(OUT/'perf.data')],check=True)
