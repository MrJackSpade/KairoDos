# SPDX-License-Identifier: GPL-2.0-or-later
import sys,collections,json,re,subprocess
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description="Attribute Staging VRAM access separately from memory helpers and palette conversion.")
parser.add_argument("--simpleperf-dir", required=True, type=Path)
parser.add_argument("--symbolizer", required=True)
parser.add_argument("--elf", required=True, type=Path)
parser.add_argument("--profile", action="append", nargs=2, metavar=("DIRECTORY", "EMULATION_TID"), required=True)
parser.add_argument("--output", required=True, type=Path)
args=parser.parse_args()
sys.path.insert(0,str(args.simpleperf_dir))
from simpleperf_report_lib import ReportLib
out=args.output;out.mkdir(parents=True,exist_ok=True)
exe=args.symbolizer;elf=str(args.elf)
profiles=[(Path(folder),int(tid)) for folder,tid in args.profile]
results={}
for folder,tid in profiles:
    p=folder
    folder=p.name
    lib=ReportLib();lib.SetRecordFile(str(p/'perf.data'));lib.SetSymfs(str(Path(elf).parent));lib.SetTraceOffCpuMode('on-cpu')
    pcs=collections.Counter();functions=collections.Counter();handler_callees=collections.Counter();generic=collections.Counter();total=0;count=0
    while (sample:=lib.GetNextSample()):
        if sample.tid!=tid:continue
        total+=sample.period;count+=1
        sym=lib.GetSymbolOfCurrentSample(); chain=lib.GetCallChainOfCurrentSample()
        names=[sym.symbol_name]+[chain.entries[i].symbol.symbol_name for i in range(chain.nr)]
        functions[sym.symbol_name]+=sample.period
        if any(re.search(r'^VGA_.*Handler::(?:read|write)[bwdq]\(',n) for n in names):handler_callees[sym.symbol_name]+=sample.period
        if re.match(r'^(?:unsigned (?:char|short|int|long) |void |bool )?mem_(?:read|write|unalignedread|unalignedwrite)[bwdq](?:<|\()',sym.symbol_name):generic[sym.symbol_name]+=sample.period
        if sym.dso_name.endswith('libdosbox_staging.so'):pcs[(sym.vaddr_in_file,sym.symbol_name)]+=sample.period
    lib.Close()
    if count == 0 or not pcs:
        raise RuntimeError(f"No emulator samples for thread {tid} in {p}")
    ordered=list(pcs)
    result=subprocess.check_output([exe,'--obj='+elf,'--inlines','--demangle','--output-style=JSON'],input='\n'.join(hex(a) for a,n in ordered)+'\n',text=True)
    decoded=[json.loads(line) for line in result.splitlines()];assert len(decoded)==len(ordered)
    vram=[];conversion=[];generic_inline=[]
    for ((addr,name),entry) in zip(ordered,decoded):
        frames=entry.get('Symbol',[]);seconds=pcs[(addr,name)]/1e9
        row={'address':hex(addr),'symbol':name,'seconds':seconds,'frames':frames}
        if any(f['FileName'].endswith('/vga_memory.cpp') for f in frames):vram.append(row)
        if any(f['FunctionName'].startswith('draw_linear_line_from_dac_palette(') for f in frames):conversion.append(row)
        if any(re.search(r'\bmem_(?:read|write)[bwdq]_inline(?:<|\()', f['FunctionName']) for f in frames):generic_inline.append(row)
    (out/(folder+'-vram-pcs.json')).write_text(json.dumps(vram,indent=2)+'\n')
    results[folder]={'emulationTid':tid,'threadCpuSeconds':total/1e9,'sampleCount':count,
        'vgaMemorySourceSelfSeconds':sum(x['seconds'] for x in vram),
        'vgaMemorySourceFunctions':dict(collections.Counter({n:sum(x['seconds'] for x in vram if x['symbol']==n) for n in set(x['symbol'] for x in vram)})),
        'vramHandlerInclusiveSeconds':sum(handler_callees.values())/1e9,'handlerSelfAndCallees':{k:v/1e9 for k,v in handler_callees.most_common()},
        'genericMemorySelfSeconds':sum(generic.values())/1e9,'genericMemoryFunctions':{k:v/1e9 for k,v in generic.most_common()},
        'genericMemoryInlineSeconds':sum(x['seconds'] for x in generic_inline),
        'genericMemoryInlineOutsideNamedBodiesSeconds':sum(x['seconds'] for x in generic_inline if x['symbol'] not in generic),
        'checkedDynrecMemoryHelpersSelfSeconds':sum(v for k,v in functions.items() if re.match(r'mem_(read|write)[bwd]_checked_drc\(',k))/1e9,
        'paletteConversionSelfSeconds':sum(x['seconds'] for x in conversion)}
(out/'costs.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
