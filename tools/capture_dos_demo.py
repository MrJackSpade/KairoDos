# SPDX-License-Identifier: GPL-2.0-or-later
import argparse, subprocess, time, json, pathlib
parser=argparse.ArgumentParser(description='Capture a running DOS demo after answering the verified N sound prompt')
parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True);parser.add_argument('--output',required=True,type=pathlib.Path)
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
pid=shell('pidof com.loxifi.kairodos')
if not pid.isdigit(): raise RuntimeError('Expected one product process')
threads=shell(f'for t in /proc/{pid}/task/*; do printf "%s " "${{t##*/}}"; cat "$t/comm"; done')
(OUT/'threads.txt').write_text(threads)
layer=next(x for x in shell('dumpsys SurfaceFlinger --list').splitlines() if x.startswith('SurfaceView[com.loxifi.kairodos/') and '(BLAST)' in x)
record=subprocess.Popen([ADB,'-s',SERIAL,'shell',f'cd /data/local/tmp && ./kairo-simpleperf record --clockid monotonic --trace-offcpu -o kairo-demo.perf.data -e cpu-clock -f 99 --call-graph fp -p {pid} --duration 120'],stdout=(OUT/'record.log').open('w'),stderr=subprocess.STDOUT)
stat=subprocess.Popen([ADB,'-s',SERIAL,'shell',f'cd /data/local/tmp && ./kairo-simpleperf stat -p {pid} -e cpu-cycles,instructions,task-clock,cache-references,cache-misses,stalled-cycles-backend,stalled-cycles-frontend --duration 120'],stdout=(OUT/'counters.txt').open('w'),stderr=subprocess.STDOUT)
start=time.monotonic(); next_shot=30
with (OUT/'samples.jsonl').open('w') as output:
    while time.monotonic()-start<120:
        raw=shell(f'''cat /proc/uptime; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; cat /sys/kernel/debug/clk/armclk/clk_rate /sys/kernel/debug/clk/clk_scmi_ddr/clk_rate /sys/kernel/debug/clk/clk_scmi_gpu/clk_rate; cat /sys/class/devfreq/fde60000.gpu/cur_freq; for z in /sys/class/thermal/thermal_zone*; do cat "$z/type" "$z/temp"; done; for z in /sys/class/thermal/cooling_device*; do cat "$z/type" "$z/cur_state"; done; cat /proc/{pid}/stat; for t in /proc/{pid}/task/*; do cat "$t/stat"; done; echo FRAMES; dumpsys SurfaceFlinger --latency '{layer}' ''')
        power=shell('dumpsys power')
        if 'mWakefulness=Awake' not in power:
            (OUT/'invalid-power.txt').write_text(power)
            raise RuntimeError('Device slept during capture; capture invalid')
        elapsed=time.monotonic()-start
        output.write(json.dumps({'elapsed':elapsed,'hostUtc':time.time(),'raw':raw})+'\n'); output.flush()
        if elapsed>=next_shot and next_shot<120:
            screenshot(f'capture-{next_shot}s'); next_shot+=30
        time.sleep(.5)
screenshot('capture-end')
record.wait(); stat.wait()
(OUT/'capture.json').write_text(json.dumps({'duration':time.monotonic()-start,'layer':layer,'pid':pid,'recordExit':record.returncode,'statExit':stat.returncode}))
print('Completed real application baseline:',OUT)

subprocess.run([ADB,'-s',SERIAL,'pull','/data/local/tmp/kairo-demo.perf.data',str(OUT/'perf.data')],check=True)
