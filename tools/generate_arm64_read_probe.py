# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import argparse
parser = argparse.ArgumentParser(description="Generate a standalone ARM64 guard/load probe from the patched #71 sources.")
parser.add_argument("source_root", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = args.source_root
arm = (root/'third_party/dosbox-staging/src/cpu/core_dynrec/risc_armv8le.h').read_text()
decoder = (root/'third_party/dosbox-staging/src/cpu/core_dynrec/decoder_basic.h').read_text()
macros = arm[arm.index('// register mapping'):arm.index('// move a full register')]
imm = arm[arm.index('static void gen_mov_qword_to_reg_imm'):arm.index('// helper function for gen_mov_word_to_reg')]
branches = arm[arm.index('static const uint8_t* gen_create_branch_on_nonzero'):arm.index('// conditional jump if register is nonzero')]
prototype = decoder[decoder.index('static const uint8_t* dyn_arm64_direct_read'):decoder.index('#endif', decoder.index('static const uint8_t* dyn_arm64_direct_read'))]
source = r'''
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <sys/mman.h>
using Bitu = uintptr_t;
using Bits = intptr_t;
struct { uint8_t* pos; } cache;
void cache_addd(uint32_t v, const uint8_t* p) { memcpy(const_cast<uint8_t*>(p), &v, 4); }
void cache_addd(uint32_t v) { cache_addd(v, cache.pos); cache.pos += 4; }
void cache_addw(uint16_t v, const uint8_t* p) { memcpy(const_cast<uint8_t*>(p), &v, 2); }
void cache_addb(uint8_t v, const uint8_t* p) { *const_cast<uint8_t*>(p) = v; }
static uintptr_t tlb[1 << 20];
uintptr_t* PAGING_GetReadBaseAddress() { return tlb; }
'''+macros+imm+branches+prototype+r'''
int main() {
    auto code = static_cast<uint8_t*>(mmap(nullptr, 4096, PROT_READ|PROT_WRITE,
                                          MAP_PRIVATE|MAP_ANONYMOUS, -1, 0));
    if (code == MAP_FAILED) return 2;
    alignas(4096) uint8_t ram[4096];
    for (unsigned n=0; n<4096; ++n) ram[n] = (n*37+13) & 255;
    unsigned checks=0;
    for (unsigned width : {1u,2u,4u}) {
        if (mprotect(code,4096,PROT_READ|PROT_WRITE)) return 3;
        cache.pos=code;
        const auto done=dyn_arm64_direct_read(0,0,width);
        gen_mov_qword_to_reg_imm(0,0xdeadbeef);
        dyn_arm64_finish_direct_read(done);
        cache_addd(RET);
        __builtin___clear_cache(reinterpret_cast<char*>(code),reinterpret_cast<char*>(cache.pos));
        if (mprotect(code,4096,PROT_READ|PROT_EXEC)) return 4;
        auto read = reinterpret_cast<uint32_t(*)(uint32_t)>(code);
        for (uint32_t page : {0u,1u,0xfffffu}) {
            const uint32_t base=page<<12;
            for (unsigned pass=0;pass<3;++pass) {
                // The same generated code must observe each live mapping change.
                tlb[page]=pass==1 ? 0 : reinterpret_cast<uintptr_t>(ram)-base;
                if (pass==2) for (auto& value:ram) value^=0x5a;
                for (unsigned offset=0;offset<4096;++offset) {
                    uint32_t expected=0xdeadbeef;
                    if (pass!=1 && offset+width<=4096) {
                        expected=0;
                        memcpy(&expected,ram+offset,width);
                    }
                    const auto actual=read(base+offset);
                    if (actual!=expected) {
                        printf("FAIL width=%u page=%x offset=%u pass=%u actual=%x expected=%x\n",
                               width,page,offset,pass,actual,expected);
                        return 1;
                    }
                    ++checks;
                }
            }
        }
    }
    printf("PASS %u generated-code guard/load checks; full core fault/ABI integration still required\n", checks);
    return 0;
}
'''
source = '#include <initializer_list>\n' + source
args.output.write_text(source)
print('Extracted actual prototype emitter into device harness')
