"""KairoDos's local changes to the DOSBox Staging snapshot in third_party/dosbox-staging, which shared/tools/local_patches.py applies and checks. Recorded from the snapshot as committed; docs/source-import.md describes each change."""

# One group per changed file: (path under third_party/, old text, new text) in order.
PATCHES = {
    'dosbox-staging/src/cpu/core_dynrec.cpp': [
        ('dosbox-staging/src/cpu/core_dynrec.cpp',
         '''
#if (C_DYNREC)

#include <cassert>
// simde needs std::isnan
#include <cmath>
''',
         '''
#if (C_DYNREC)

#ifdef KAIRO_STAGING
#include "staging_bridge.h"
#include "kairo_guest_profile.h"
#endif

#include <cassert>
// simde needs std::isnan
#include <cmath>
'''),
        ('dosbox-staging/src/cpu/core_dynrec.cpp',
         '''			if (!chandler->invalidation_map || (chandler->invalidation_map[ip_point&4095]<4)) {
				// translate up to 32 instructions
				block=CreateCacheBlock(chandler,ip_point,32);
			} else {
				// let the normal core handle this instruction to avoid zero-sized blocks
				Bitu old_cycles=CPU_Cycles;
''',
         '''			if (!chandler->invalidation_map || (chandler->invalidation_map[ip_point&4095]<4)) {
				// translate up to 32 instructions
				block=CreateCacheBlock(chandler,ip_point,32);
#ifdef KAIRO_STAGING
				++kairo_staging_cpu_counters.translated_blocks;
#endif
			} else {
				// let the normal core handle this instruction to avoid zero-sized blocks
				Bitu old_cycles=CPU_Cycles;
'''),
        ('dosbox-staging/src/cpu/core_dynrec.cpp',
         '''		// now we're ready to run the dynamic code block
//		BlockReturn ret=((BlockReturn (*)(void))(block->cache.start))();
		BlockReturn ret=core_dynrec.runcode(block->cache.start);

		switch (ret) {
		case BR_Iret:
''',
         '''		// now we're ready to run the dynamic code block
//		BlockReturn ret=((BlockReturn (*)(void))(block->cache.start))();
		BlockReturn ret=core_dynrec.runcode(block->cache.start);
#ifdef KAIRO_STAGING
		// Count only after generated host code has returned successfully.
		// Plain counters stay on this thread; polling publishes snapshots.
		++kairo_staging_cpu_counters.executed_blocks;
#endif

		switch (ret) {
		case BR_Iret:
'''),
    ],
    'dosbox-staging/src/cpu/core_dynrec/decoder.h': [
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '''
static CacheBlock *CreateCacheBlock(CodePageHandler *codepage, PhysPt start, Bitu max_opcodes)
{
	// initialize a load of variables
	decode.code_start=start;
	decode.code=start;
''',
         '''
static CacheBlock *CreateCacheBlock(CodePageHandler *codepage, PhysPt start, Bitu max_opcodes)
{
#ifdef KAIRO_GUEST_PROFILE
    kairo_guest_profile::Translation profile;
    const auto profile_cs_base = SegPhys(cs);
    const bool profile_code32 = cpu.code.big;
#endif
	// initialize a load of variables
	decode.code_start=start;
	decode.code=start;
'''),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '''				}
			}
		}
		switch (opcode) {
		// instructions 'op reg8,reg8' and 'op [],reg8'
		case 0x00:dyn_dop_ebgb(DOP_ADD);break;
''',
         '''				}
			}
		}
#ifdef KAIRO_GUEST_PROFILE
        profile.instruction(cache.pos, decode.op_start, opcode);
#endif
		switch (opcode) {
		// instructions 'op reg8,reg8' and 'op [],reg8'
		case 0x00:dyn_dop_ebgb(DOP_ADD);break;
'''),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '''					goto finish_block;

				// conditional byte set instructions
/*				case 0x90:case 0x91:case 0x92:case 0x93:case 0x94:case 0x95:case 0x96:case 0x97:	
				case 0x98:case 0x99:case 0x9a:case 0x9b:case 0x9c:case 0x9d:case 0x9e:case 0x9f:	
					dyn_set_byte_on_condition((BranchTypes)(dual_code&0xf));
					AcquireFlags(FMASK_TEST);
					break; */

				// push/pop segment registers
				case 0xa0:dyn_push_seg(DRC_SEG_FS);break;
''',
         '''					goto finish_block;

				// conditional byte set instructions
				case 0x90:case 0x91:case 0x92:case 0x93:case 0x94:case 0x95:case 0x96:case 0x97:	
				case 0x98:case 0x99:case 0x9a:case 0x9b:case 0x9c:case 0x9d:case 0x9e:case 0x9f:	
					dyn_set_byte_on_condition((BranchTypes)(dual_code&0xf));
					break;

				// push/pop segment registers
				case 0xa0:dyn_push_seg(DRC_SEG_FS);break;
'''),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '''	assert(decode.block->cache.size <= cache_bytes);
	//	LOG_MSG("Created block size %d start %d end
	//%d",decode.block->cache.size,decode.block->page.start,decode.block->page.end);
	return decode.block;
}
''',
         '''	assert(decode.block->cache.size <= cache_bytes);
	//	LOG_MSG("Created block size %d start %d end
	//%d",decode.block->cache.size,decode.block->page.start,decode.block->page.end);
#ifdef KAIRO_GUEST_PROFILE
    profile.publish(decode.block, decode.block->cache.start, decode.block->cache.size,
                    start, decode.code, profile_cs_base, profile_code32);
#endif
	return decode.block;
}
'''),
    ],
    'dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h': [
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '''	Bitu eip_base=decode.code-decode.code_start;
	dyn_reduce_cycles();

	dyn_branchflag_to_reg(btype);
	const uint8_t* data=gen_create_branch_on_nonzero(FC_RETOP,true);

 	// Branch not taken
''',
         '''	Bitu eip_base=decode.code-decode.code_start;
	dyn_reduce_cycles();

	dyn_branchflag_from_producer(btype);
	const uint8_t* data=gen_create_branch_on_nonzero(FC_RETOP,true);

 	// Branch not taken
'''),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '''	dyn_closeblock();
}

/*
static void dyn_set_byte_on_condition(BranchTypes btype) {
	dyn_get_modrm();
	dyn_branchflag_to_reg(btype);
	gen_and_imm(FC_RETOP,1);
	if (decode.modrm.mod<3) {
		dyn_fill_ea(FC_ADDR);
		dyn_write_byte(FC_ADDR,FC_RETOP);
	} else {
		MOV_REG_BYTE_FROM_HOST_REG_LOW(FC_RETOP,decode.modrm.rm&3,(decode.modrm.rm>>2)&1);
	}
}
*/

static void dyn_loop(LoopTypes type) {
	dyn_reduce_cycles();
''',
         '''	dyn_closeblock();
}

// SETcc reads condition flags without modifying them. Keep their producer
// alive even when a later instruction in this block overwrites the flags.
static void dyn_set_byte_on_condition(BranchTypes btype) {
	dyn_get_modrm();
	AcquireFlags(FMASK_TEST);
	if (decode.modrm.mod<3) {
		dyn_fill_ea(FC_ADDR);
		gen_protect_addr_reg();
	}
	dyn_branchflag_to_reg(btype);
	// Some lazy flag helpers return a bit mask rather than the integer 1.
	// Zero is already normalized; replace every nonzero result with 1.
	const auto zero = gen_create_branch_on_zero(FC_RETOP, true);
	gen_mov_dword_to_reg_imm(FC_RETOP, 1);
	gen_fill_branch(zero);
	if (decode.modrm.mod<3) {
		gen_restore_addr_reg();
		dyn_write_byte(FC_ADDR,FC_RETOP);
	} else {
		MOV_REG_BYTE_FROM_HOST_REG_LOW(FC_RETOP,decode.modrm.rm&3,(decode.modrm.rm>>2)&1);
	}
}

static void dyn_loop(LoopTypes type) {
	dyn_reduce_cycles();
'''),
    ],
    'dosbox-staging/src/cpu/core_dynrec/operators.h': [
        ('dosbox-staging/src/cpu/core_dynrec/operators.h',
         '''}


static void DRC_CALL_CONV dynrec_mul_byte(uint8_t op) DRC_FC;
static void DRC_CALL_CONV dynrec_mul_byte(uint8_t op) {
	FillFlagsNoCFOF();
''',
         '''}


// The pending flag-optimization queue identifies a producer whose flags
// are still live at this point in the same translated block. Only unconditional
// CMP/TEST producers qualify; all other cases retain runtime flag dispatch.
#ifdef KAIRO_KNOWN_FLAGS_VERIFY
static uint64_t kairo_known_flags_checks = 0;
#endif
template <Bitu Type, BranchTypes Condition>
static uint32_t DRC_CALL_CONV dynrec_known_condition() {
#ifdef KAIRO_KNOWN_FLAGS_VERIFY
    if (lflags.type != Type)
        E_Exit("Known flag producer mismatch: expected %u, actual %u",
               unsigned(Type), unsigned(lflags.type));
    ++kairo_known_flags_checks;
    if (kairo_known_flags_checks == 1 || (kairo_known_flags_checks & 0xfffff) == 0)
        LOG_MSG("Kairo known flags verified %llu", (unsigned long long)kairo_known_flags_checks);
#endif
    constexpr bool byte = Type == t_CMPb || Type == t_TESTb;
    constexpr bool word = Type == t_CMPw || Type == t_TESTw;
    constexpr bool compare = Type == t_CMPb || Type == t_CMPw || Type == t_CMPd;
    constexpr uint32_t sign = byte ? 0x80u : word ? 0x8000u : 0x80000000u;
    const uint32_t result = byte ? lf_resb : word ? lf_resw : lf_resd;
    const uint32_t left = byte ? lf_var1b : word ? lf_var1w : lf_var1d;
    const uint32_t right = byte ? lf_var2b : word ? lf_var2w : lf_var2d;
    const uint32_t cf = compare ? left < right : 0;
    const uint32_t of = compare ? ((left ^ right) & (left ^ result) & sign) : 0;
    const uint32_t zf = result == 0;
    const uint32_t sf = result & sign;
    if constexpr (Condition == BR_O) return of;
    if constexpr (Condition == BR_NO) return !of;
    if constexpr (Condition == BR_B) return cf;
    if constexpr (Condition == BR_NB) return !cf;
    if constexpr (Condition == BR_Z) return zf;
    if constexpr (Condition == BR_NZ) return !zf;
    if constexpr (Condition == BR_BE) return cf || zf;
    if constexpr (Condition == BR_NBE) return !cf && !zf;
    if constexpr (Condition == BR_S) return sf;
    if constexpr (Condition == BR_NS) return !sf;
    if constexpr (Condition == BR_L) return (sf != 0) != (of != 0);
    if constexpr (Condition == BR_NL) return (sf != 0) == (of != 0);
    if constexpr (Condition == BR_LE) return zf || ((sf != 0) != (of != 0));
    if constexpr (Condition == BR_NLE) return !zf && ((sf != 0) == (of != 0));
    return 0;
}

template <Bitu Type>
static bool dyn_emit_known_condition(BranchTypes condition) {
    switch (condition) {
    case BR_O: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_O>); return true;
    case BR_NO: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NO>); return true;
    case BR_B: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_B>); return true;
    case BR_NB: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NB>); return true;
    case BR_Z: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_Z>); return true;
    case BR_NZ: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NZ>); return true;
    case BR_BE: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_BE>); return true;
    case BR_NBE: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NBE>); return true;
    case BR_S: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_S>); return true;
    case BR_NS: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NS>); return true;
    case BR_L: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_L>); return true;
    case BR_NL: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NL>); return true;
    case BR_LE: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_LE>); return true;
    case BR_NLE: gen_call_function_raw((void*)&dynrec_known_condition<Type, BR_NLE>); return true;
    default: return false;
    }
}

static void dyn_branchflag_from_producer(BranchTypes condition) {
#ifdef DRC_FLAGS_INVALIDATION
    if (mf_functions_num == 1) {
        bool emitted = false;
        switch (mf_functions[0].ftype) {
        case t_CMPb: emitted = dyn_emit_known_condition<t_CMPb>(condition); break;
        case t_CMPw: emitted = dyn_emit_known_condition<t_CMPw>(condition); break;
        case t_CMPd: emitted = dyn_emit_known_condition<t_CMPd>(condition); break;
        case t_TESTb: emitted = dyn_emit_known_condition<t_TESTb>(condition); break;
        case t_TESTw: emitted = dyn_emit_known_condition<t_TESTw>(condition); break;
        case t_TESTd: emitted = dyn_emit_known_condition<t_TESTd>(condition); break;
        default: break;
        }
        if (emitted) return;
    }
#endif
    dyn_branchflag_to_reg(condition);
}

static void DRC_CALL_CONV dynrec_mul_byte(uint8_t op) DRC_FC;
static void DRC_CALL_CONV dynrec_mul_byte(uint8_t op) {
	FillFlagsNoCFOF();
'''),
    ],
    'dosbox-staging/src/cpu/cpu.cpp': [
        ('dosbox-staging/src/cpu/cpu.cpp',
         '''	return 0;
}

void CPU_HLT(Bitu oldeip)
{
	reg_eip = oldeip;
''',
         '''	return 0;
}

// Kairo: the core the CPU runs, or for a halted CPU the core it resumes with
// (staging_bridge's CPU telemetry, which a halt must not read as another core).
CPU_Decoder* KairoRunningDecoder()
{
	return cpudecoder == &hlt_decode ? cpu.hlt.old_decoder : cpudecoder;
}

void CPU_HLT(Bitu oldeip)
{
	reg_eip = oldeip;
'''),
    ],
    'dosbox-staging/src/cpu/dyn_cache.h': [
        ('dosbox-staging/src/cpu/dyn_cache.h',
         '''
void CacheBlock::Clear()
{
	Bitu ind;
	// check if this is not a cross page block
	if (hash.index) for (ind=0;ind<2;ind++) {
''',
         '''
void CacheBlock::Clear()
{
#ifdef KAIRO_GUEST_PROFILE
    kairo_guest_profile::clear(this);
#endif
	Bitu ind;
	// check if this is not a cross page block
	if (hash.index) for (ind=0;ind<2;ind++) {
'''),
        ('dosbox-staging/src/cpu/dyn_cache.h',
         '''}

static void cache_close(void) {
/*	for (;;) {
		if (cache.used_pages) {
			CodePageHandler * cpage=cache.used_pages;
''',
         '''}

static void cache_close(void) {
#ifdef KAIRO_STAGING
	// Desktop Staging keeps this cache for its process lifetime. Kairo unloads
	// a session's DSO, so anonymous mappings and heap page handlers must be
	// released while guest memory and paging still exist (CPU_Destroy order).
	while (cache.used_pages) {
		cache.used_pages->ClearRelease();
	}
	while (cache.free_pages) {
		auto* page = cache.free_pages;
		cache.free_pages = page->next;
		delete[] page->invalidation_map;
		delete page;
	}
	if (cache_code_start_ptr) {
		// Released as cache_init allocated it: VirtualAlloc on Windows (the shared PC boot
		// test's build, KairoDos#140), mmap, or malloc.
#if defined(WIN32)
		if (!VirtualFree(cache_code_start_ptr, 0, MEM_RELEASE)) {
			LOG_WARNING("DYNCACHE: Could not release session cache: Windows error %lu", GetLastError());
		}
#elif defined(HAVE_MMAP)
		if (munmap(cache_code_start_ptr, cache_code_size) != 0) {
			LOG_WARNING("DYNCACHE: Could not release session cache: %s", strerror(errno));
		}
#else
		free(cache_code_start_ptr);
#endif
	}
	cache_code_start_ptr = nullptr;
	cache_code = nullptr;
	cache_code_link_blocks = nullptr;
	cache = {};
	cache_initialized = false;
#endif
/*	for (;;) {
		if (cache.used_pages) {
			CodePageHandler * cpage=cache.used_pages;
'''),
    ],
    'dosbox-staging/src/cpu/lazyflags.h': [
        ('dosbox-staging/src/cpu/lazyflags.h',
         '''	uint_fast8_t oldcf = 0;
};

extern LazyFlags lflags;

inline constexpr uint8_t &lf_var1b = lflags.var1.byte[BL_INDEX];
inline constexpr uint8_t &lf_var2b = lflags.var2.byte[BL_INDEX];
''',
         '''	uint_fast8_t oldcf = 0;
};

// Kairo's C callback bridge does not export the core's lazy-flag state.
#if defined(KAIRO_STAGING)
extern LazyFlags lflags __attribute__((visibility("hidden")));
#else
extern LazyFlags lflags;
#endif

inline constexpr uint8_t &lf_var1b = lflags.var1.byte[BL_INDEX];
inline constexpr uint8_t &lf_var2b = lflags.var2.byte[BL_INDEX];
'''),
    ],
    'dosbox-staging/src/cpu/paging.h': [
        ('dosbox-staging/src/cpu/paging.h',
         '''	bool enabled = false;
};

extern PagingBlock paging; 

/* Some support functions */

''',
         '''	bool enabled = false;
};

// Kairo's core owns this object. Its C callback ABI never exports paging
// state to the Android host; preserve ordinary upstream visibility elsewhere.
#if defined(KAIRO_STAGING)
extern PagingBlock paging __attribute__((visibility("hidden")));
#else
extern PagingBlock paging;
#endif

/* Some support functions */

'''),
    ],
    'dosbox-staging/src/dosbox.cpp': [
        ('dosbox-staging/src/dosbox.cpp',
         '''	}
}

void DOSBOX_SetMachineTypeFromConfig(SectionProp& section)
{
	const auto arguments = &control->arguments;
''',
         '''	}
}

#ifdef KAIRO_STAGING
// The fast-forward hotkey's own switch, for the host's fast-forward button (emulation thread).
void KairoSetFastForward(bool enabled)
{
	if (enabled != ticks.locked) DOSBOX_UnlockSpeed(enabled);
}
#endif

void DOSBOX_SetMachineTypeFromConfig(SectionProp& section)
{
	const auto arguments = &control->arguments;
'''),
        ('dosbox-staging/src/dosbox.cpp',
         '''	int64_t scheduled = {};
	bool locked       = {};
} ticks = {};
''',
         '''	int64_t scheduled = {};
	bool locked       = {};
#ifdef KAIRO_STAGING
	// The host's turns since increase_ticks last counted (KairoHostHeld), in microseconds.
	int64_t held_us = {};
#endif
} ticks = {};
'''),
        ('dosbox-staging/src/dosbox.cpp',
         '''	// ticks_new > ticks.last
	ticks.remain = GetTicksDiff(ticks_new, ticks.last);
	ticks.last   = ticks_new;
	ticks.done += ticks.remain;
''',
         '''	// ticks_new > ticks.last
	ticks.remain = GetTicksDiff(ticks_new, ticks.last);
	ticks.last   = ticks_new;
	ticks.done += ticks.remain;
#ifdef KAIRO_STAGING
	// The host's turns passed as sleep does: not work done (KairoHostHeld).
	ticks.done = std::max<int64_t>(ticks.done - ticks.held_us / MicrosInMillisecond, 0);
	ticks.held_us %= MicrosInMillisecond;
#endif
'''),
        ('dosbox-staging/src/dosbox.cpp',
         '''// The fast-forward hotkey's own switch, for the host's fast-forward button (emulation thread).
void KairoSetFastForward(bool enabled)
{
	if (enabled != ticks.locked) DOSBOX_UnlockSpeed(enabled);
}
#endif
''',
         '''// The fast-forward hotkey's own switch, for the host's fast-forward button (emulation thread).
void KairoSetFastForward(bool enabled)
{
	if (enabled != ticks.locked) DOSBOX_UnlockSpeed(enabled);
}

// The host held the emulation thread for its turn between frames (KairoDos#124): time the guest
// did not run, which auto cycles counts as slept rather than as work.
void KairoHostHeld(const int64_t micros)
{
	if (!ticks.locked) ticks.held_us += micros;
}
#endif
'''),
    ],
    'dosbox-staging/src/gui/render/render.cpp': [
        ('dosbox-staging/src/gui/render/render.cpp',
         '''#include "utils/fraction.h"
#include "utils/math_utils.h"
#include "utils/string_utils.h"

CHECK_NARROWING();

Render render;
ScalerLineHandler RENDER_DrawLine;

static void render_callback(GFX_CallbackFunctions_t function);

''',
         '''#include "utils/fraction.h"
#include "utils/math_utils.h"
#include "utils/string_utils.h"
#if defined(KAIRO_STAGING)
#include "kairo_palette_cache.h"
#endif

CHECK_NARROWING();

Render render;
ScalerLineHandler RENDER_DrawLine;
#if defined(KAIRO_STAGING)
static KairoPaletteCache kairo_palette_cache;
static uint64_t kairo_palette_frame = 0;
void KairoInvalidatePaletteLines() { kairo_palette_cache.Invalidate(); }
#endif

static void render_callback(GFX_CallbackFunctions_t function);

'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''	int pitch            = 0;

	if (!GFX_StartUpdate(pixel_data, pitch)) {
		return false;
	}

''',
         '''	int pitch            = 0;

	if (!GFX_StartUpdate(pixel_data, pitch)) {
#if defined(KAIRO_STAGING)
		KairoInvalidatePaletteLines();
#endif
		return false;
	}

'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''static void start_line_handler(const void* src_line_data)
{
	if (src_line_data) {
		if (std::memcmp(src_line_data,
		                render.scale.cache_read,
		                render.scale.cache_pitch) != 0) {

			// This triggers transferring the pixel data to the
			// render backend if the contents of the current frame
''',
         '''static void start_line_handler(const void* src_line_data)
{
	if (src_line_data) {
		const bool unchanged = std::memcmp(src_line_data,
		                                   render.scale.cache_read,
		                                   render.scale.cache_pitch) == 0;
		if (!unchanged) {

			// This triggers transferring the pixel data to the
			// render backend if the contents of the current frame
'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''	scaler_changed_lines[0] += render.scale.y_scale;
}

static void finish_line_handler(const void* src_line_data)
{
	if (src_line_data) {
''',
         '''	scaler_changed_lines[0] += render.scale.y_scale;
}

#if defined(KAIRO_STAGING)
bool KairoTryReusePaletteLine(const uint8_t* indices, const void* palette,
                             size_t width, size_t rows, size_t row)
{
	// Only paths that maintain the RGB scaler cache can establish history.
	// Unhandled paths fall back to upstream conversion.
	if (!render.render_in_progress || is_deinterlacing() ||
	    render.src.pixel_format != PixelFormat::BGRX32_ByteArray ||
	    width != static_cast<size_t>(render.src.width) ||
	    rows != static_cast<size_t>(render.src.height) || row >= rows ||
	    render.scale.cache_read != reinterpret_cast<uint8_t*>(render.scale.cache.data()) +
	                                       row * render.scale.cache_pitch ||
	    (RENDER_DrawLine != start_line_handler &&
	     RENDER_DrawLine != render.scale.line_handler)) {
		KairoInvalidatePaletteLines();
		return false;
	}
	const bool same = kairo_palette_cache.Remember(indices, palette, width,
	                                              rows, row, kairo_palette_frame);
	if (same && RENDER_DrawLine == start_line_handler) {
		// Exact same input as this row's previous RGB cache: advance the
		// unchanged-line bookkeeping without expanding or re-comparing RGB.
		start_line_handler(nullptr);
		return true;
	}
	return false;
}
#endif

static void finish_line_handler(const void* src_line_data)
{
	if (src_line_data) {
'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''	if (!render.active) {
		return false;
	}

	if (render.scale.line_palette_handler) {
		check_palette();
''',
         '''	if (!render.active) {
		return false;
	}
#if defined(KAIRO_STAGING)
	++kairo_palette_frame;
#endif

	if (render.scale.line_palette_handler) {
		check_palette();
'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''
void RENDER_EndUpdate([[maybe_unused]] bool abort)
{
	if (!render.render_in_progress) {
		return;
	}
''',
         '''
void RENDER_EndUpdate([[maybe_unused]] bool abort)
{
#if defined(KAIRO_STAGING)
	if (abort) KairoInvalidatePaletteLines();
#endif
	if (!render.render_in_progress) {
		return;
	}
'''),
        ('dosbox-staging/src/gui/render/render.cpp',
         '''	// be called from the rendering callback, which might come from a video
	// driver operating in a different thread or process.
	std::lock_guard<std::mutex> guard(render_reset_mutex);

	int render_width_px = render.src.width;
	bool double_width   = render.src.double_width;
''',
         '''	// be called from the rendering callback, which might come from a video
	// driver operating in a different thread or process.
	std::lock_guard<std::mutex> guard(render_reset_mutex);
#if defined(KAIRO_STAGING)
	KairoInvalidatePaletteLines();
#endif

	int render_width_px = render.src.width;
	bool double_width   = render.src.double_width;
'''),
    ],
    'dosbox-staging/src/hardware/audio/opl.cpp': [
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''#include "utils/checks.h"
#include "utils/math_utils.h"
#include "utils/string_utils.h"

CHECK_NARROWING();

''',
         '''#include "utils/checks.h"
#include "utils/math_utils.h"
#include "utils/string_utils.h"
#if defined(KAIRO_OPL_WORKER)
#include "kairo/ordered_worker.h"
#include <array>
#if defined(KAIRO_OPL_STRESS)
#include <chrono>
#endif
#endif

CHECK_NARROWING();

'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''

	} else { // OPL
		OPL3_WriteRegBuffered(&opl.chip, selected_reg, val);
		if (selected_reg == 0x105) {
			opl.newm = selected_reg & 0x01;
''',
         '''

	} else { // OPL
#if defined(KAIRO_OPL_WORKER)
		if (worker) QueueWrite(selected_reg, val);
		else
#endif
		OPL3_WriteRegBuffered(&opl.chip, selected_reg, val);
		if (selected_reg == 0x105) {
			opl.newm = selected_reg & 0x01;
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''	ESFM_write_port(&esfm.chip, 0, 0);
}

template <LineIndex line_index>
int16_t remove_dc_bias(const int16_t back_sample)
{
	// Calculate the number of samples we need average across to maintain
	// the lowest frequency given an assumed playback rate.
	constexpr auto PcmPlaybackRateHz      = 16000;
''',
         '''	ESFM_write_port(&esfm.chip, 0, 0);
}

#if defined(KAIRO_OPL_VERIFY)
// Verification builds expose the existing persistent DC state so the shadow
// engine starts from the same state even after a previous game/session.
struct KairoBiasState {
	int sum = 0;
	std::queue<int16_t> samples = {};
	int16_t Process(const int16_t sample) {
		if (sample < 5) { sum = 0; samples = {}; return sample; }
		sum += sample;
		samples.push(sample);
		int16_t average = 0, front = 0;
		if (samples.size() == 16000 / 200) {
			average = static_cast<int16_t>(sum / (16000 / 200));
			front = samples.front();
			sum -= front;
			samples.pop();
		}
		return static_cast<int16_t>(front - average);
	}
};
template <LineIndex line> KairoBiasState& KairoActualBias() {
	static KairoBiasState state;
	return state;
}
#endif

template <LineIndex line_index>
int16_t remove_dc_bias(const int16_t back_sample)
{
#if defined(KAIRO_OPL_VERIFY)
	return KairoActualBias<line_index>().Process(back_sample);
#else
	// Calculate the number of samples we need average across to maintain
	// the lowest frequency given an assumed playback rate.
	constexpr auto PcmPlaybackRateHz      = 16000;
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''		samples.pop();
	}
	return static_cast<int16_t>(front_sample - average);
}

AudioFrame Opl::RenderFrame()
{
''',
         '''		samples.pop();
	}
	return static_cast<int16_t>(front_sample - average);
#endif
}

#if defined(KAIRO_OPL_WORKER)
struct Opl::WorkerState {
	static constexpr uint32_t BlockFrames = 64;
#if defined(KAIRO_OPL_STRESS)
	static constexpr size_t QueueCapacity = 1;
#else
	static constexpr size_t QueueCapacity = 64;
#endif
#if defined(KAIRO_OPL_VERIFY)
	static constexpr bool TrackQueue = true;
	size_t maxFifoFrames = 0;
#else
	static constexpr bool TrackQueue = false;
#endif
	struct Command {
		uint64_t sequence = 0;
		uint32_t frames = 0;
		io_port_t address = 0;
		uint8_t value = 0;
#if defined(KAIRO_OPL_VERIFY)
		std::array<AudioFrame, BlockFrames> expected = {};
#endif
	};
	struct Result {
		uint64_t sequence = 0;
		uint32_t frames = 0;
		std::array<AudioFrame, BlockFrames> pcm = {};
#if defined(KAIRO_OPL_VERIFY)
		std::array<AudioFrame, BlockFrames> expected = {};
#endif
	};
	Opl& owner;
	uint64_t issued = 0, consumed = 0, generated = 0;
#if defined(KAIRO_OPL_VERIFY)
	opl3_chip reference = {};
	KairoBiasState left_bias = KairoActualBias<Left>();
	KairoBiasState right_bias = KairoActualBias<Right>();
	uint64_t compared = 0, mismatches = 0;
	double reference_rendered_ms = 0;
	std::queue<AudioFrame> expected_catchup = {}, reference_fifo = {};
	uint64_t catchups = 0, wakeups = 0, callback_frames = 0, callbacks = 0;
	uint64_t current_callback_frames = 0;
#endif
	// Declared last: join before destroying processor state.
	kairo::OrderedWorker<Command, Result, QueueCapacity, TrackQueue> queue;
	explicit WorkerState(Opl& opl_owner) : owner(opl_owner),
	        queue([this](const Command& command) { return Process(command); }) {
#if defined(KAIRO_OPL_VERIFY)
		reference_rendered_ms = owner.last_rendered_ms;
		OPL3_Reset(&reference, OplSampleRateHz);
		initialize_opl_tone_generators(reference);
		if (owner.opl.mode == OplMode::DualOpl2)
			OPL3_WriteRegBuffered(&reference, 0x105, 1);
#endif
	}
	Result Process(const Command& command) {
#if defined(KAIRO_OPL_STRESS)
		// Verification only: pressure the actual caller/mixer ownership path.
		std::this_thread::sleep_for(std::chrono::microseconds(200));
#endif
		Result result;
		result.sequence = command.sequence;
		result.frames = command.frames;
		if (!command.frames)
			OPL3_WriteRegBuffered(&owner.opl.chip, command.address, command.value);
		for (uint32_t i = 0; i < command.frames; ++i)
			result.pcm[i] = owner.RenderFrame();
#if defined(KAIRO_OPL_VERIFY)
		result.expected = command.expected;
#endif
		return result;
	}
#if defined(KAIRO_OPL_VERIFY)
	void PrepareCatchup(const double now, const bool woke) {
		if (!expected_catchup.empty() || reference_rendered_ms != owner.last_rendered_ms)
			throw std::runtime_error("OPL reference timeline diverged");
		++catchups;
		if (woke) {
			++wakeups;
			reference_rendered_ms = now;
			return;
		}
		// Original synchronous loop, independent of batched command lengths.
		while (reference_rendered_ms < now) {
			reference_rendered_ms += owner.ms_per_frame;
			const auto frame = ReferenceFrame();
			expected_catchup.push(frame);
			reference_fifo.push(frame);
		}
	}
	void FinishCatchup() {
		if (!expected_catchup.empty() || reference_rendered_ms != owner.last_rendered_ms)
			throw std::runtime_error("OPL catch-up sample count diverged");
	}
	void CompareQueuedMixerFrame(const AudioFrame& frame) {
		if (reference_fifo.empty())
			throw std::runtime_error("OPL mixer emitted extra queued frame");
		Compare(frame, reference_fifo.front());
		reference_fifo.pop();
		++current_callback_frames;
	}
	void CompareDirectMixerFrame(const AudioFrame& frame) {
		if (!reference_fifo.empty())
			throw std::runtime_error("OPL mixer skipped reference FIFO frames");
		Compare(frame, ReferenceFrame());
		++current_callback_frames;
	}
	void FinishCallback(const int requested, const double now) {
		if (requested < 0 || current_callback_frames != static_cast<uint64_t>(requested))
			throw std::runtime_error("OPL mixer callback length diverged");
		++callbacks;
		callback_frames += current_callback_frames;
		current_callback_frames = 0;
		reference_rendered_ms = now;
	}
	AudioFrame ReferenceFrame() {
		int16_t pcm[2] = {};
		OPL3_GenerateStream(&reference, pcm, 1);
		if (owner.ctrl.wants_dc_bias_removed) {
			pcm[0] = left_bias.Process(pcm[0]);
			pcm[1] = right_bias.Process(pcm[1]);
		}
		return {pcm[0], pcm[1]};
	}
	void Compare(const AudioFrame& actual, const AudioFrame& expected) {
		compared += 2;
		mismatches += actual.left != expected.left;
		mismatches += actual.right != expected.right;
	}
#endif
	void Consume(Result result) {
		if (result.sequence != consumed++)
			throw std::runtime_error("OPL worker result sequence mismatch");
		for (uint32_t i = 0; i < result.frames; ++i) {
#if defined(KAIRO_OPL_VERIFY)
			Compare(result.pcm[i], result.expected[i]);
#endif
			owner.fifo.emplace(result.pcm[i]);
		}
#if defined(KAIRO_OPL_VERIFY)
		maxFifoFrames = std::max(maxFifoFrames, owner.fifo.size());
#endif
		generated += result.frames;
	}
	void Submit(Command command) {
		command.sequence = issued++;
#if defined(KAIRO_OPL_VERIFY)
		if (!command.frames)
			OPL3_WriteRegBuffered(&reference, command.address, command.value);
		for (uint32_t i = 0; i < command.frames; ++i) {
			if (expected_catchup.empty())
				throw std::runtime_error("OPL worker requested extra catch-up frame");
			command.expected[i] = expected_catchup.front();
			expected_catchup.pop();
		}
#endif
		queue.submit(std::move(command), [this](Result r) { Consume(std::move(r)); });
	}
	void Drain() {
		queue.drain([this](Result r) { Consume(std::move(r)); });
	}
};

void Opl::StartWorker() {
	if (opl.mode == OplMode::Opl2 || opl.mode == OplMode::DualOpl2 ||
	    opl.mode == OplMode::Opl3) {
		worker = std::make_unique<WorkerState>(*this);
		LOG_MSG("KAIRO OPL worker enabled: %s", to_string(opl.mode));
	} else {
		LOG_MSG("KAIRO OPL worker synchronous fallback: %s", to_string(opl.mode));
	}
}
void Opl::DrainWorker() { if (worker) worker->Drain(); }
void Opl::QueueWrite(const io_port_t address, const uint8_t value) {
	WorkerState::Command command;
	command.address = address;
	command.value = value;
	worker->Submit(std::move(command));
}
void Opl::QueueFrames(uint32_t frames) {
	while (frames) {
		WorkerState::Command command;
		command.frames = std::min(frames, WorkerState::BlockFrames);
		frames -= command.frames;
		worker->Submit(std::move(command));
	}
}
void Opl::VerifyMixerFrame(const AudioFrame& frame) {
#if defined(KAIRO_OPL_VERIFY)
	if (worker) worker->CompareDirectMixerFrame(frame);
#else
	(void)frame;
#endif
}
#endif

AudioFrame Opl::RenderFrame()
{
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''
	// Wake up the channel and update the last rendered time datum.
	assert(channel);
	if (channel->WakeUp()) {
		last_rendered_ms = now;
		return;
	}
	// Keep rendering until we're current
	while (last_rendered_ms < now) {
		last_rendered_ms += ms_per_frame;
		fifo.emplace(RenderFrame());
''',
         '''
	// Wake up the channel and update the last rendered time datum.
	assert(channel);
	const auto woke = channel->WakeUp();
#if defined(KAIRO_OPL_VERIFY)
	if (worker) worker->PrepareCatchup(now, woke);
#endif
	if (woke) {
		last_rendered_ms = now;
		return;
	}
	// Keep rendering until we're current
#if defined(KAIRO_OPL_WORKER)
	if (worker) {
		uint32_t frames = 0;
		while (last_rendered_ms < now) {
			last_rendered_ms += ms_per_frame;
			if (++frames == WorkerState::BlockFrames) {
				QueueFrames(frames);
				frames = 0;
			}
		}
		QueueFrames(frames);
#if defined(KAIRO_OPL_VERIFY)
		worker->FinishCatchup();
#endif
		return;
	}
#endif
	while (last_rendered_ms < now) {
		last_rendered_ms += ms_per_frame;
		fifo.emplace(RenderFrame());
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''{
	std::lock_guard lock(mutex);
	assert(channel);
#if 0
	if (fifo.size()) {
		LOG_MSG("%s: Queued %2lu cycle-accurate frames",
''',
         '''{
	std::lock_guard lock(mutex);
	assert(channel);
#if defined(KAIRO_OPL_WORKER)
	// Complete queued writes and catch-up audio before touching the chip here.
	// Holding the existing OPL mutex prevents another producer from submitting
	// work until this callback finishes. Mixer synthesis already runs off the
	// emulation thread, so retain it here after this explicit ownership handoff.
	DrainWorker();
#endif
#if 0
	if (fifo.size()) {
		LOG_MSG("%s: Queued %2lu cycle-accurate frames",
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''
	// First, send any frames we've queued since the last callback
	while (frames_remaining && fifo.size()) {
		channel->AddSamples_sfloat(1, &fifo.front()[0]);
		fifo.pop();
		--frames_remaining;
''',
         '''
	// First, send any frames we've queued since the last callback
	while (frames_remaining && fifo.size()) {
#if defined(KAIRO_OPL_VERIFY)
		if (worker) worker->CompareQueuedMixerFrame(fifo.front());
#endif
		channel->AddSamples_sfloat(1, &fifo.front()[0]);
		fifo.pop();
		--frames_remaining;
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''	// If the queue's run dry, render the remainder and sync-up our time datum
	while (frames_remaining) {
		const auto frame = RenderFrame();
		channel->AddSamples_sfloat(1, &frame[0]);
		--frames_remaining;
	}
	last_rendered_ms = PIC_AtomicIndex();
}

void Opl::CacheWrite(const io_port_t port, const uint8_t val)
''',
         '''	// If the queue's run dry, render the remainder and sync-up our time datum
	while (frames_remaining) {
		const auto frame = RenderFrame();
#if defined(KAIRO_OPL_WORKER)
		VerifyMixerFrame(frame);
#endif
		channel->AddSamples_sfloat(1, &frame[0]);
		--frames_remaining;
	}
	const auto now = PIC_AtomicIndex();
	last_rendered_ms = now;
#if defined(KAIRO_OPL_VERIFY)
	if (worker) worker->FinishCallback(requested_frames, now);
#endif
}

void Opl::CacheWrite(const io_port_t port, const uint8_t val)
'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''	}

	Init();

	using namespace std::placeholders;

''',
         '''	}

	Init();
#if defined(KAIRO_OPL_WORKER)
	StartWorker();
#endif

	using namespace std::placeholders;

'''),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '''	LOG_MSG("%s: Shutting down %s", channel->GetName().c_str(), to_string(opl.mode));

	MIXER_LockMixerThread();

	// Stop playback
	if (channel) {
''',
         '''	LOG_MSG("%s: Shutting down %s", channel->GetName().c_str(), to_string(opl.mode));

	MIXER_LockMixerThread();
#if defined(KAIRO_OPL_WORKER)
	DrainWorker();
	if (worker) {
		LOG_MSG("KAIRO OPL worker completed: commands=%llu frames=%llu",
		        static_cast<unsigned long long>(worker->consumed),
		        static_cast<unsigned long long>(worker->generated));
#if defined(KAIRO_OPL_VERIFY)
		if (!worker->expected_catchup.empty() || worker->reference_fifo.size() != fifo.size())
			throw std::runtime_error("OPL shutdown FIFO count diverged");
		LOG_MSG("KAIRO OPL verify: samples=%llu mismatches=%llu",
		        static_cast<unsigned long long>(worker->compared),
		        static_cast<unsigned long long>(worker->mismatches));
		LOG_MSG("KAIRO OPL timeline: catchups=%llu wakeups=%llu callbacks=%llu callback_frames=%llu pending_catchup=%zu reference_fifo=%zu actual_fifo=%zu",
		        static_cast<unsigned long long>(worker->catchups),
		        static_cast<unsigned long long>(worker->wakeups),
		        static_cast<unsigned long long>(worker->callbacks),
		        static_cast<unsigned long long>(worker->callback_frames),
		        worker->expected_catchup.size(), worker->reference_fifo.size(), fifo.size());
		const auto queue_stats = worker->queue.statistics();
		LOG_MSG("KAIRO OPL queue: capacity=%zu commands=%zu results=%zu current_commands=%zu current_results=%zu submit_drains=%llu submit_waits=%llu fifo_frames=%zu issued=%llu consumed=%llu",
		        WorkerState::QueueCapacity, queue_stats.maxCommands,
		        queue_stats.maxResults, queue_stats.currentCommands,
		        queue_stats.currentResults,
		        static_cast<unsigned long long>(queue_stats.submitDrains),
		        static_cast<unsigned long long>(queue_stats.submitWaits),
		        worker->maxFifoFrames,
		        static_cast<unsigned long long>(worker->issued),
		        static_cast<unsigned long long>(worker->consumed));
#endif
		worker.reset();
	}
#endif

	// Stop playback
	if (channel) {
'''),
    ],
    'dosbox-staging/src/hardware/audio/opl.h': [
        ('dosbox-staging/src/hardware/audio/opl.h',
         '''	Opl& operator=(Opl&) = delete;

private:
	IO_ReadHandleObject ReadHandler[3];
	IO_WriteHandleObject WriteHandler[3];

''',
         '''	Opl& operator=(Opl&) = delete;

private:
#if defined(KAIRO_OPL_WORKER)
	struct WorkerState;
	std::unique_ptr<WorkerState> worker = {};
	void StartWorker();
	void DrainWorker();
	void QueueFrames(uint32_t frames);
	void QueueWrite(io_port_t address, uint8_t value);
	void VerifyMixerFrame(const AudioFrame& frame);
#endif
	IO_ReadHandleObject ReadHandler[3];
	IO_WriteHandleObject WriteHandler[3];

'''),
    ],
    'dosbox-staging/src/hardware/video/vga_draw.cpp': [
        ('dosbox-staging/src/hardware/video/vga_draw.cpp',
         '''#include "utils/math_utils.h"
#include "utils/mem_unaligned.h"
#include "utils/rgb565.h"

// This is a high-level overview of the VGA drawing emulation:
//
''',
         '''#include "utils/math_utils.h"
#include "utils/mem_unaligned.h"
#include "utils/rgb565.h"
#if defined(KAIRO_STAGING)
#include "kairo_palette_cache.h"
#endif

// This is a high-level overview of the VGA drawing emulation:
//
'''),
        ('dosbox-staging/src/hardware/video/vga_draw.cpp',
         '''	return TempLine;
}

static uint8_t* draw_linear_line_from_dac_palette(Bitu vidstart, Bitu /*line*/)
{
	const auto offset = vidstart & vga.draw.linear_mask;
''',
         '''	return TempLine;
}

#if defined(KAIRO_STAGING)
static uint8_t* draw_cached_vesa_palette_line(Bitu vidstart, Bitu line)
{
	const auto width = vga.draw.line_length / sizeof(vga.dac.palette_map[0]);
	const auto offset = vidstart & vga.draw.linear_mask;
	// Wrapped VRAM and hardware cursors retain upstream handling. The cache
	// only replaces a pure palette conversion, never scanout scheduling.
	if (width <= vga.draw.linear_mask + 1 - offset && !ReelMagic_IsVideoMixerEnabled()) {
		if (KairoTryReusePaletteLine(vga.draw.linear_base + offset,
		                            vga.dac.palette_map, width,
		                            vga.draw.lines_total, vga.draw.lines_done))
			return nullptr; // Renderer already consumed this unchanged line.
	} else KairoInvalidatePaletteLines();
	return draw_unwrapped_line_from_dac_palette(vidstart, line);
}
#endif

static uint8_t* draw_linear_line_from_dac_palette(Bitu vidstart, Bitu /*line*/)
{
	const auto offset = vidstart & vga.draw.linear_mask;
'''),
        ('dosbox-staging/src/hardware/video/vga_draw.cpp',
         '''{
	while (lines--) {
		uint8_t* data = VGA_DrawLine(vga.draw.address, vga.draw.address_line);
		ReelMagic_RENDER_DrawLine(data);

		++vga.draw.address_line;
		if (vga.draw.address_line >= vga.draw.address_line_total) {
''',
         '''{
	while (lines--) {
		uint8_t* data = VGA_DrawLine(vga.draw.address, vga.draw.address_line);
#if defined(KAIRO_STAGING)
		if (data) // Indexed cache may have already consumed the unchanged line.
#endif
			ReelMagic_RENDER_DrawLine(data);

		++vga.draw.address_line;
		if (vga.draw.address_line >= vga.draw.address_line_total) {
'''),
        ('dosbox-staging/src/hardware/video/vga_draw.cpp',
         '''	vga.draw.panning = vga.config.pel_panning;
}

static void VGA_VerticalTimer(uint32_t /*val*/)
{
	vga.draw.delay.framestart = PIC_FullIndex();
	PIC_AddEvent(VGA_VerticalTimer, vga.draw.delay.vtotal);

''',
         '''	vga.draw.panning = vga.config.pel_panning;
}

// Kairo: counts every emulated frame, changed or not (staging_bridge.h); the
// renderer skips GFX_EndUpdate for a frame that did not change.
void KairoStagingFrameEnd();

static void VGA_VerticalTimer(uint32_t /*val*/)
{
	KairoStagingFrameEnd();
	vga.draw.delay.framestart = PIC_FullIndex();
	PIC_AddEvent(VGA_VerticalTimer, vga.draw.delay.vtotal);

'''),
        ('dosbox-staging/src/hardware/video/vga_draw.cpp',
         '''		VGA_DrawLine = use_hw_cursor
		                     ? draw_unwrapped_line_from_dac_palette_with_hwcursor
		                     : draw_unwrapped_line_from_dac_palette;
		break;
	}
	return pixel_format;
''',
         '''		VGA_DrawLine = use_hw_cursor
		                     ? draw_unwrapped_line_from_dac_palette_with_hwcursor
		                     : draw_unwrapped_line_from_dac_palette;
#if defined(KAIRO_STAGING)
		if (vga.mode == M_LIN8 && !use_hw_cursor && vga.draw.mode == DrawMode::Part)
			VGA_DrawLine = draw_cached_vesa_palette_line;
#endif
		break;
	}
	return pixel_format;
'''),
    ],
    'dosbox-staging/src/misc/cross.cpp': [
        ('dosbox-staging/src/misc/cross.cpp',
         '''
std_fs::path resolve_home(const std::string &str) noexcept
{
	if (!str.size() || str[0] != '~') // No ~
		return str;

''',
         '''
std_fs::path resolve_home(const std::string &str) noexcept
{
#ifdef KAIRO_STAGING
	// A host path the launch names by reference, since AUTOEXEC.BAT holds only DOS code page text.
	extern const char* KairoStagingHostPath(const char* reference);
	if (const auto host = KairoStagingHostPath(str.c_str())) return std_fs::path(host);
#endif
	if (!str.size() || str[0] != '~') // No ~
		return str;

'''),
    ],
    # The guest's MIDI out to the MIDI port the player picked (KairoDos#150): mididevice=kairo, a device of
    # KairoDos's own (backend-dos kairo_midi_port.h), which gets every byte the guest writes first, as
    # written (Staging's parser rewrites running status); what Staging sends of its own goes to it whole.
    # Staging's default, port, still opens no device on Android, as before.
    'staging-midi-port': [
        ('dosbox-staging/src/midi/midi.cpp',
         '#include "private/soundcanvas.h"\n',
         '#include "private/soundcanvas.h"\n\n#if defined(KAIRO_STAGING)\n#include "kairo_midi_port.h" // KairoDos\n#endif\n'),
        ('dosbox-staging/src/midi/midi.cpp',
         '\t// Internal MIDI synths\n\tif (name == MidiDeviceName::SoundCanvas) {\n',
         '#if defined(KAIRO_STAGING)\n\t// KairoDos: the MIDI port the player picked (kairo_midi_port.h)\n'
         '\tif (name == "kairo") {\n\t\treturn KAIRO_CreateMidiPort();\n\t}\n#endif\n\n'
         '\t// Internal MIDI synths\n\tif (name == MidiDeviceName::SoundCanvas) {\n'),
        ('dosbox-staging/src/midi/midi.cpp',
         'void MIDI_RawOutByte(const uint8_t data)\n{\n\tif (!MIDI_IsAvailable()) {\n\t\treturn;\n\t}\n',
         'void MIDI_RawOutByte(const uint8_t data)\n{\n\tif (!MIDI_IsAvailable()) {\n\t\treturn;\n\t}\n'
         '#if defined(KAIRO_STAGING)\n\t// KairoDos: the byte as the guest wrote it, to the MIDI port when it is the device\n'
         '\tconst KairoMidiRawByte kairo_raw(data);\n#endif\n'),
        ('dosbox-staging/src/midi/midi.cpp',
         '\t                     MidiDeviceName::FluidSynth,\n\t                     "none"});\n',
         '\t                     MidiDeviceName::FluidSynth,\n#if defined(KAIRO_STAGING)\n\t                     "kairo",\n#endif\n'
         '\t                     "none"});\n'),
    ],
    # The shared PC boot test's Windows build (KairoDos#140): mingw's own clock_gettime, and the
    # standard headers Android's libc++ reached through others.
    'staging-windows-host': [
        ('dosbox-staging/src/libs/enet/include/enet.h',
         '    #ifdef _WIN32\n        static LARGE_INTEGER getFILETIMEoffset() {\n',
         "    // KairoDos: mingw's time.h has clock_gettime (the shared PC boot test's Windows build).\n    #if defined(_WIN32) && !defined(__MINGW32__)\n        static LARGE_INTEGER getFILETIMEoffset() {\n"),
        ('dosbox-staging/src/dosbox.h',
         '#include <functional>\n#include <memory>\n',
         '#include <functional>\n#include <memory>\n#include <string>\n#include <vector>\n'),
        ('dosbox-staging/src/dos/dos_code_page.cpp',
         '#include <fstream>\n#include <optional>\n',
         '#include <fstream>\n#include <iterator>\n#include <optional>\n'),
        ('dosbox-staging/src/gui/render/shader_manager.cpp',
         '#include <regex>\n#include <unordered_map>\n',
         '#include <regex>\n#include <sstream>\n#include <unordered_map>\n'),
    ],
    # A repeatable run's guest clock (KairoDos#156): the BIOS's time at boot and the CMOS clock read
    # KairoStagingClock (backend-dos staging_bridge.h), the host's local time, or the fixed start the
    # shared tests ask for (kairo_staging_fixed_clock) plus the emulated time since the run began.
    'staging-fixed-clock': [
        ('dosbox-staging/src/ints/bios.cpp',
         '#include "utils/math_utils.h"\n\n',
         '#include "utils/math_utils.h"\n#ifdef KAIRO_STAGING\n#include "staging_bridge.h"\n#endif\n\n'),
        ('dosbox-staging/src/ints/bios.cpp',
         '\tuint32_t milli = 0;\n\t// TODO investigate if clock_gettime and ftime can be replaced\n\t// by using C++11 chrono\n#if defined(HAVE_CLOCK_GETTIME) && !defined(WIN32)\n\tstruct timespec tp;\n',
         "\tuint32_t milli = 0;\n#ifdef KAIRO_STAGING\n\t// KairoDos#156: the host's time, or a repeatable run's fixed clock.\n\tstruct tm kairo_time = KairoStagingClock(&milli);\n\tstruct tm *loctime = &kairo_time;\n#elif defined(HAVE_CLOCK_GETTIME) && !defined(WIN32)\n\tstruct timespec tp;\n"),
        ('dosbox-staging/src/hardware/cmos.cpp',
         '#include "misc/cross.h"\n\n',
         '#include "misc/cross.h"\n#ifdef KAIRO_STAGING\n#include "staging_bridge.h"\n#endif\n\n'),
        ('dosbox-staging/src/hardware/cmos.cpp',
         '\n\tconst time_t curtime = time(nullptr);\n',
         "\n#ifdef KAIRO_STAGING\n\t// KairoDos#156: the host's time, or a repeatable run's fixed clock.\n\tconst struct tm datetime = KairoStagingClock(nullptr);\n#else\n\tconst time_t curtime = time(nullptr);\n"),
        ('dosbox-staging/src/hardware/cmos.cpp',
         '\tcross::localtime_r(&curtime, &datetime);\n\n',
         '\tcross::localtime_r(&curtime, &datetime);\n#endif\n\n'),
    ],
    # KairoDos#154: the mixer on the emulated tick, OPL rendered each tick on its worker.
    'staging-mixer-on-tick': [
        ('dosbox-staging/src/audio/mixer.cpp',
         '#include "hardware/audio/lpt_dac.h"\n#include "hardware/audio/pcspeaker.h"\n',
         '#include "hardware/audio/lpt_dac.h"\n#include "hardware/audio/opl.h"\n#include "hardware/audio/pcspeaker.h"\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         'CHECK_NARROWING();\n\n',
         'CHECK_NARROWING();\n\n#ifdef KAIRO_STAGING\n// KairoDos#154: the mixer runs on the emulation thread, on the emulated tick (KairoMixerFrameEnd).\nint KairoMixerFramesBehind();\nint64_t KairoMixerTicks();\n#endif\n\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tSBLASTER_NotifyLockMixer();\n\n',
         '\tSBLASTER_NotifyLockMixer();\n#ifdef KAIRO_STAGING\n\tOPL_WaitForWorker();\n#endif\n\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tis_enabled = should_enable;\n}\n',
         "\tis_enabled = should_enable;\n#ifdef KAIRO_STAGING\n\t// Nothing came from this channel since the mixer's position, so it leads with silence up to\n\t// the last tick: its first frames are the next tick's, as every other channel's are.\n\tif (should_enable) {\n\t\taudio_frames.resize(audio_frames.size() + check_cast<size_t>(KairoMixerFramesBehind()));\n\t}\n#endif\n}\n"),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t\tspeex_resampler_reset_mem(speex_resampler.state);\n\t\tspeex_resampler_skip_zeros(speex_resampler.state);\n',
         "\t\tspeex_resampler_reset_mem(speex_resampler.state);\n\t\t// Kairo's mixer mixes only what its channels made by the last tick (KairoMixerFrameEnd), so\n\t\t// the resampler starts from zeros instead of reading its latency ahead.\n#ifndef KAIRO_STAGING\n\t\tspeex_resampler_skip_zeros(speex_resampler.state);\n"),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t\tspeex_resampler_skip_zeros(speex_resampler.state);\n\n',
         '\t\tspeex_resampler_skip_zeros(speex_resampler.state);\n#endif\n\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t// is surely more than enough.\n\tconst auto awake_for_ms = check_cast<int>(GetTicksSince(woken_at_ms));\n',
         '\t// is surely more than enough.\n#ifdef KAIRO_STAGING\n\t// Emulated time, so a channel falls asleep at the same point in every run.\n\tconst auto awake_for_ms = check_cast<int>(KairoMixerTicks() - woken_at_ms);\n#else\n\tconst auto awake_for_ms = check_cast<int>(GetTicksSince(woken_at_ms));\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tconst auto awake_for_ms = check_cast<int>(GetTicksSince(woken_at_ms));\n\n',
         '\tconst auto awake_for_ms = check_cast<int>(GetTicksSince(woken_at_ms));\n#endif\n\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t// Always reset for another round of awakeness\n\twoken_at_ms   = GetTicks();\n',
         '\t// Always reset for another round of awakeness\n#ifdef KAIRO_STAGING\n\twoken_at_ms = KairoMixerTicks();\n#else\n\twoken_at_ms   = GetTicks();\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\twoken_at_ms   = GetTicks();\n\tfadeout_level = 1.0f;\n',
         '\twoken_at_ms   = GetTicks();\n#endif\n\tfadeout_level = 1.0f;\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\treturn static_cast<int>(received);\n}\n#endif\nvoid MIXER_CloseAudioDevice()\n',
         '\treturn static_cast<int>(received);\n}\n\n// KairoDos#154: the mixer runs on the emulation thread, on the emulated 1 ms tick. Mixed on its\n// own thread, as much sound reached the host each frame as that thread had finished by then, and\n// channels slept by the host\'s clock, so identical runs played the same sound cut differently.\n// Now each frame ends with the sound of the emulated time up to KairoMixLagTicks before it (mixed\n// in blocks between frame ends, as the thread mixed), and the host plays it, dropping what its\n// queue cannot hold while frames run back to back, as in fast-forward.\n//\n// A device that queues its sound (MIXER_PullFromQueueCallback) adds each tick\'s frames on that\n// tick, and OPL renders each tick\'s on its worker (Opl::RenderTick): mixing KairoMixLagTicks\n// behind, every channel already holds what a mix takes.\nconstexpr int64_t KairoMixLagTicks = 4;\nstatic int64_t kairo_ticks        = 0;\nstatic int64_t kairo_mixed_frames = 0;\n\n// The output frames of the first [ticks] ticks.\nstatic int64_t kairo_frames_of_ticks(const int64_t ticks)\n{\n\treturn ticks * mixer.sample_rate_hz.load() / 1000;\n}\n\nstatic void kairo_mix_up_to(const int64_t frames_total)\n{\n\tconst auto frames = check_cast<int>(frames_total - kairo_mixed_frames);\n\tif (frames <= 0) {\n\t\treturn;\n\t}\n\tkairo_mixed_frames = frames_total;\n\t// The OPL worker adds its frames to its channel itself: idle, it leaves the channel to the\n\t// mix alone.\n\tOPL_WaitForWorker();\n\tstd::unique_lock lock(mixer.mutex);\n\tmix_samples(frames);\n\tlock.unlock();\n\tif (mixer.state == MixerState::NoSound) {\n\t\treturn;\n\t}\n\tif (mixer.state == MixerState::Muted) {\n\t\tmixer.output_buffer.clear();\n\t\tmixer.output_buffer.resize(frames);\n\t}\n\t// The host takes every frame\'s sound at its end, so a second of output never fills.\n\tif (mixer.final_output.Size() + mixer.output_buffer.size() > mixer.final_output.MaxCapacity()) {\n\t\tthrow std::runtime_error("MIXER: the host has not taken the output of " +\n\t\t                         std::to_string(mixer.final_output.Size()) + " frames");\n\t}\n\tmixer.final_output.BulkEnqueue(mixer.output_buffer);\n}\n\nstatic void kairo_mixer_tick()\n{\n\t++kairo_ticks;\n\t// In blocks between frame ends, as the mixer\'s thread mixed, so a queue holds at most a block\n\t// beyond the lag.\n\tconst auto ready = kairo_frames_of_ticks(std::max<int64_t>(kairo_ticks - KairoMixLagTicks, 0));\n\tif (ready - kairo_mixed_frames >= mixer.blocksize) {\n\t\tkairo_mix_up_to(ready);\n\t}\n}\n\nvoid KairoMixerFrameEnd()\n{\n\tkairo_mix_up_to(kairo_frames_of_ticks(std::max<int64_t>(kairo_ticks - KairoMixLagTicks, 0)));\n}\n\n// The output frames from the mixer\'s position up to the last tick: the silence a channel that starts\n// now leads with, so its sound lines up with the tick it was made on.\nint KairoMixerFramesBehind()\n{\n\treturn check_cast<int>(kairo_frames_of_ticks(kairo_ticks) - kairo_mixed_frames);\n}\n\n// Emulated milliseconds, for the channel sleeper.\nint64_t KairoMixerTicks()\n{\n\treturn kairo_ticks;\n}\n#endif\nvoid MIXER_CloseAudioDevice()\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = false; }\n#endif\n',
         '\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = false; }\n\tTIMER_DelTickHandler(kairo_mixer_tick);\n#endif\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\n\tmixer.final_output.Resize(mixer.blocksize + prebuffer_frames);\n',
         "\n#ifdef KAIRO_STAGING\n\t// KairoDos#154: the host takes each emulated frame's sound at its end (KairoMixerFrameEnd), so\n\t// a second of output always holds it.\n\tmixer.final_output.Resize(mixer.sample_rate_hz);\n#else\n\tmixer.final_output.Resize(mixer.blocksize + prebuffer_frames);\n"),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tmixer.final_output.Resize(mixer.blocksize + prebuffer_frames);\n\n',
         '\tmixer.final_output.Resize(mixer.blocksize + prebuffer_frames);\n#endif\n\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tmixer.capture_queue.Resize(mixer.sample_rate_hz * 2);\n\n\tmixer.thread = std::thread(mixer_thread_loop);\n#ifdef KAIRO_STAGING\n\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = mixer.state != MixerState::NoSound; }\n',
         '\tmixer.capture_queue.Resize(mixer.sample_rate_hz * 2);\n\n#ifdef KAIRO_STAGING\n\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = mixer.state != MixerState::NoSound; }\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = mixer.state != MixerState::NoSound; }\n#endif\n',
         '\t{ std::lock_guard lock(kairo_audio_mutex); kairo_audio_ready = mixer.state != MixerState::NoSound; }\n\tkairo_ticks        = 0;\n\tkairo_mixed_frames = 0;\n\tTIMER_AddTickHandler(kairo_mixer_tick);\n#else\n\tmixer.thread = std::thread(mixer_thread_loop);\n\tset_thread_name(mixer.thread, "dosbox:mixer");\n#endif\n'),
        ('dosbox-staging/src/audio/mixer.cpp',
         '\tset_thread_name(mixer.thread, "dosbox:mixer");\n#endif\n\tset_thread_name(mixer.thread, "dosbox:mixer");\n\n\tTIMER_AddTickHandler(capture_callback);\n',
         '\tset_thread_name(mixer.thread, "dosbox:mixer");\n#endif\n\n\tTIMER_AddTickHandler(capture_callback);\n'),
        ('dosbox-staging/src/audio/mixer.h',
         '\n\tconst auto frames_received = check_cast<int>(\n',
         '\n#ifdef KAIRO_STAGING\n\t// KairoDos#154: the mixer runs on the emulation thread, which is the one that fills this queue,\n\t// and mixes behind it (KairoMixLagTicks): a running queue always holds what it asks for.\n\tif (device->output_queue.IsRunning() &&\n\t    device->output_queue.Size() < check_cast<size_t>(frames_requested)) {\n\t\tthrow std::runtime_error(device->channel->GetName() + ": the mixer needs " +\n\t\t                         std::to_string(frames_requested) + " frames, the queue holds " +\n\t\t                         std::to_string(device->output_queue.Size()));\n\t}\n#endif\n\tconst auto frames_received = check_cast<int>(\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '#include "hardware/memory.h"\n#include "misc/support.h"\n',
         '#include "hardware/memory.h"\n#include "hardware/timer.h"\n#include "misc/support.h"\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '#endif\n\t// Declared last: join before destroying processor state.\n',
         "#endif\n#ifdef KAIRO_STAGING\n\t// The channel's resampling and filters, a second stage after synthesis, so the two run at\n\t// once: each block goes to the channel in the order it was made (KairoDos#154). Declared\n\t// before the synthesis stage, which hands it blocks: it is joined after it.\n\tstruct ChannelWork {\n\t\tuint32_t frames = 0;\n\t\tstd::array<AudioFrame, BlockFrames> pcm = {};\n\t};\n\tstruct ChannelDone {};\n\tkairo::OrderedWorker<ChannelWork, ChannelDone, QueueCapacity, false> channel_stage;\n#endif\n\t// Declared last: join before destroying processor state.\n"),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\texplicit WorkerState(Opl& opl_owner) : owner(opl_owner),\n\t        queue([this](const Command& command) { return Process(command); }) {\n',
         '\texplicit WorkerState(Opl& opl_owner) : owner(opl_owner),\n#ifdef KAIRO_STAGING\n\t        channel_stage([this](const ChannelWork& work) {\n\t\t        owner.channel->AddSamples_sfloat(static_cast<int>(work.frames), &work.pcm[0][0]);\n\t\t        return ChannelDone{};\n\t        }),\n#endif\n\t        queue([this](const Command& command) { return Process(command); }) {\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\t\tresult.pcm[i] = owner.RenderFrame();\n#if defined(KAIRO_OPL_VERIFY)\n',
         '\t\t\tresult.pcm[i] = owner.RenderFrame();\n#ifdef KAIRO_STAGING\n\t\t// The emulation thread touches the channel only while both stages are idle: the mixer\n\t\t// waits for them before each mix (OPL_WaitForWorker), as locking the mixer does.\n\t\tif (command.frames) {\n\t\t\tChannelWork work;\n\t\t\twork.frames = command.frames;\n\t\t\twork.pcm    = result.pcm;\n\t\t\tchannel_stage.submit(std::move(work), [](ChannelDone) {});\n\t\t}\n#endif\n#if defined(KAIRO_OPL_VERIFY)\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\t\texpected_catchup.push(frame);\n\t\t\treference_fifo.push(frame);\n',
         '\t\t\texpected_catchup.push(frame);\n#ifndef KAIRO_STAGING\n\t\t\treference_fifo.push(frame);\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\t\treference_fifo.push(frame);\n\t\t}\n',
         '\t\t\treference_fifo.push(frame);\n#endif\n\t\t}\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t}\n\tvoid FinishCallback(const int requested, const double now) {\n',
         '\t}\n#ifdef KAIRO_STAGING\n\t// The mixer takes made frames only; the render datum stays on the emulated timeline.\n\tvoid FinishCallback(const int requested) {\n\t\tif (requested < 0 || current_callback_frames != static_cast<uint64_t>(requested))\n\t\t\tthrow std::runtime_error("OPL mixer callback length diverged");\n\t\t++callbacks;\n\t\tcallback_frames += current_callback_frames;\n\t\tcurrent_callback_frames = 0;\n\t}\n#else\n\tvoid FinishCallback(const int requested, const double now) {\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t}\n\tAudioFrame ReferenceFrame() {\n',
         '\t}\n#endif\n\tAudioFrame ReferenceFrame() {\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '#endif\n\t\t\towner.fifo.emplace(result.pcm[i]);\n',
         '#endif\n#ifndef KAIRO_STAGING\n\t\t\towner.fifo.emplace(result.pcm[i]);\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\t\towner.fifo.emplace(result.pcm[i]);\n\t\t}\n',
         '\t\t\towner.fifo.emplace(result.pcm[i]);\n#endif\n\t\t}\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\tqueue.drain([this](Result r) { Consume(std::move(r)); });\n\t}\n',
         "\t\tqueue.drain([this](Result r) { Consume(std::move(r)); });\n#ifdef KAIRO_STAGING\n\t\t// The synthesis stage is idle now, so this thread is the channel stage's only consumer.\n\t\tchannel_stage.drain([](ChannelDone) {});\n#endif\n\t}\n"),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t\tlast_rendered_ms = now;\n\t\treturn;\n',
         '\t\tlast_rendered_ms = now;\n#ifdef KAIRO_STAGING\n\t\t// What was made before the channel fell asleep is not played: the mixer leads the channel\n\t\t// with silence up to now (MixerChannel::Enable).\n\t\tDiscardRendered();\n#endif\n\t\treturn;\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         "\t}\n\t// Keep rendering until we're current\n",
         "\t}\n\tRenderUpTo(now);\n}\n\nvoid Opl::RenderUpTo(const double now)\n{\n\t// Keep rendering until we're current\n"),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\t// work until this callback finishes. Mixer synthesis already runs off the\n\t// emulation thread, so retain it here after this explicit ownership handoff.\n',
         '\t// work until this callback finishes.\n#ifdef KAIRO_STAGING\n\tconst auto frames_on_channel_before_drain = channel->audio_frames.size();\n#endif\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '#endif\n\tauto frames_remaining = requested_frames;\n',
         '#endif\n#ifdef KAIRO_STAGING\n#if defined(KAIRO_OPL_WORKER)\n\tif (worker) {\n\t\t// The worker added every frame up to the last tick to the channel (RenderTick), and the\n\t\t// mixer mixes behind it: a drain that adds nothing leaves the mixer short.\n\t\tif (frames_on_channel_before_drain == channel->audio_frames.size()) {\n\t\t\tthrow std::runtime_error(channel->GetName() + ": the mixer needs " +\n\t\t\t                         std::to_string(requested_frames) +\n\t\t\t                         " more frames, none are made");\n\t\t}\n\t\treturn;\n\t}\n#endif\n\t// Every frame up to the last tick is made (RenderTick), and the mixer mixes behind it.\n\tif (fifo.size() < static_cast<size_t>(requested_frames)) {\n\t\tthrow std::runtime_error(channel->GetName() + ": the mixer needs " +\n\t\t                         std::to_string(requested_frames) + " frames, " +\n\t\t                         std::to_string(fifo.size()) + " are made");\n\t}\n\tstatic std::vector<AudioFrame> frames = {};\n\tframes.resize(check_cast<size_t>(requested_frames));\n\tfor (auto& frame : frames) {\n#if defined(KAIRO_OPL_VERIFY)\n\t\tif (worker) worker->CompareQueuedMixerFrame(fifo.front());\n#endif\n\t\tframe = fifo.front();\n\t\tfifo.pop();\n\t}\n\tchannel->AddSamples_sfloat(requested_frames, &frames[0][0]);\n#if defined(KAIRO_OPL_VERIFY)\n\tif (worker) worker->FinishCallback(requested_frames);\n#endif\n#else\n\tauto frames_remaining = requested_frames;\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\tif (worker) worker->FinishCallback(requested_frames, now);\n#endif\n}\n\n',
         '\tif (worker) worker->FinishCallback(requested_frames, now);\n#endif\n#endif\n}\n\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\nvoid Opl::CacheWrite(const io_port_t port, const uint8_t val)\n',
         '\n#ifdef KAIRO_STAGING\nvoid Opl::RenderTick()\n{\n\tstd::lock_guard lock(mutex);\n\tif (!channel->is_enabled) {\n\t\treturn;\n\t}\n\tconst auto now = PIC_FullIndex();\n#if defined(KAIRO_OPL_VERIFY)\n\tif (worker) worker->PrepareCatchup(now, false);\n#endif\n\tRenderUpTo(now);\n}\n\nvoid Opl::DiscardRendered()\n{\n#if defined(KAIRO_OPL_WORKER)\n\tDrainWorker();\n#if defined(KAIRO_OPL_VERIFY)\n\tif (worker) worker->reference_fifo = {};\n#endif\n#endif\n\tfifo = {};\n}\n\nvoid OPL_WaitForWorker()\n{\n#if defined(KAIRO_OPL_WORKER)\n\tif (opl) {\n\t\topl->DrainWorker();\n\t}\n#endif\n}\n\nstatic void opl_render_tick()\n{\n\tif (opl) {\n\t\topl->RenderTick();\n\t}\n}\n#endif\n\nvoid Opl::CacheWrite(const io_port_t port, const uint8_t val)\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\tMAPPER_AddHandler(OPL_SaveRawEvent, SDL_SCANCODE_UNKNOWN, 0, "caprawopl", "Rec. OPL");\n\n',
         '\tMAPPER_AddHandler(OPL_SaveRawEvent, SDL_SCANCODE_UNKNOWN, 0, "caprawopl", "Rec. OPL");\n#ifdef KAIRO_STAGING\n\tTIMER_AddTickHandler(opl_render_tick);\n#endif\n\n'),
        ('dosbox-staging/src/hardware/audio/opl.cpp',
         '\tMIXER_LockMixerThread();\n#if defined(KAIRO_OPL_WORKER)\n',
         '\tMIXER_LockMixerThread();\n#ifdef KAIRO_STAGING\n\tTIMER_DelTickHandler(opl_render_tick);\n#endif\n#if defined(KAIRO_OPL_WORKER)\n'),
        ('dosbox-staging/src/hardware/audio/opl.h',
         '\tvoid RenderUpToNow();\n\n',
         "\tvoid RenderUpToNow();\n\t// The frames from the last rendered one up to [now].\n\tvoid RenderUpTo(double now);\n#ifdef KAIRO_STAGING\npublic:\n\t// Renders up to the emulated now while the channel is awake: every tick, so each frame is made\n\t// at its own time, in order with the writes, as the mixer's queued devices make theirs\n\t// (KairoDos#154).\n\tvoid RenderTick();\n\nprivate:\n\tfriend void OPL_WaitForWorker();\n\tvoid DiscardRendered();\n#endif\n\n"),
        ('dosbox-staging/src/hardware/audio/opl.h',
         'void OPL_Destroy();\n\n',
         'void OPL_Destroy();\n#ifdef KAIRO_STAGING\n// Waits until the OPL worker has done everything given to it, so it stays idle until the\n// emulation thread gives it more: before each mix and while the mixer is locked (KairoDos#154).\nvoid OPL_WaitForWorker();\n#endif\n\n'),
    ],
    'dynrec-flag-consumers': [
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '\n\t\tcase 0xf5:\t\t//CMC\n',
         '\n\t\t// CMC, CLC and STC fill the flags from the lazy state before\n\t\t// changing CF, so they consume the pending flag producers.\n\t\tcase 0xf5:\t\t//CMC\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '\t\tcase 0xf5:\t\t//CMC\n\t\t\tgen_call_function_raw((void*)dynrec_cmc);\n',
         '\t\tcase 0xf5:\t\t//CMC\n\t\t\tAcquireFlags(FMASK_TEST);\n\t\t\tgen_call_function_raw((void*)dynrec_cmc);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '\t\tcase 0xf8:\t\t//CLC\n\t\t\tgen_call_function_raw((void*)dynrec_clc);\n',
         '\t\tcase 0xf8:\t\t//CLC\n\t\t\tAcquireFlags(FMASK_TEST);\n\t\t\tgen_call_function_raw((void*)dynrec_clc);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder.h',
         '\t\tcase 0xf9:\t\t//STC\n\t\t\tgen_call_function_raw((void*)dynrec_stc);\n',
         '\t\tcase 0xf9:\t\t//STC\n\t\t\tAcquireFlags(FMASK_TEST);\n\t\t\tgen_call_function_raw((void*)dynrec_stc);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\n\tif (decode.big_op) gen_call_function_raw((void*)dynrec_dimul_dword);\n',
         '\n\t// The multiply helpers fill the flags from the lazy state.\n\tAcquireFlags(FMASK_TEST);\n\tif (decode.big_op) gen_call_function_raw((void*)dynrec_dimul_dword);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\tcase 0x4:\t// mul Eb\n\t\tgen_call_function_raw((void*)&dynrec_mul_byte);\n',
         '\tcase 0x4:\t// mul Eb\n\t\tAcquireFlags(FMASK_TEST);\n\t\tgen_call_function_raw((void*)&dynrec_mul_byte);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\tcase 0x5:\t// imul Eb\n\t\tgen_call_function_raw((void*)&dynrec_imul_byte);\n',
         '\tcase 0x5:\t// imul Eb\n\t\tAcquireFlags(FMASK_TEST);\n\t\tgen_call_function_raw((void*)&dynrec_imul_byte);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\tcase 0x4:\t// mul Eb\n\t\tif (decode.big_op) gen_call_function_raw((void*)&dynrec_mul_dword);\n',
         '\tcase 0x4:\t// mul Eb\n\t\tAcquireFlags(FMASK_TEST);\n\t\tif (decode.big_op) gen_call_function_raw((void*)&dynrec_mul_dword);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\tcase 0x5:\t// imul Eb\n\t\tif (decode.big_op) gen_call_function_raw((void*)&dynrec_imul_dword);\n',
         '\tcase 0x5:\t// imul Eb\n\t\tAcquireFlags(FMASK_TEST);\n\t\tif (decode.big_op) gen_call_function_raw((void*)&dynrec_imul_dword);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\t\t\t\t\tbreak;\n\t\t\t\tcase 0x04:\t// VERR\n',
         '\t\t\t\t\tbreak;\n\t\t\t\t// VERR and VERW fill the flags from the lazy state to set ZF.\n\t\t\t\tcase 0x04:\t// VERR\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\t\t\t\tcase 0x04:\t// VERR\n\t\t\t\t\tgen_call_function_R((void*)CPU_VERR,FC_RETOP);\n',
         '\t\t\t\tcase 0x04:\t// VERR\n\t\t\t\t\tAcquireFlags(FMASK_TEST);\n\t\t\t\t\tgen_call_function_R((void*)CPU_VERR,FC_RETOP);\n'),
        ('dosbox-staging/src/cpu/core_dynrec/decoder_opcodes.h',
         '\t\t\t\tcase 0x05:\t// VERW\n\t\t\t\t\tgen_call_function_R((void*)CPU_VERW,FC_RETOP);\n',
         '\t\t\t\tcase 0x05:\t// VERW\n\t\t\t\t\tAcquireFlags(FMASK_TEST);\n\t\t\t\t\tgen_call_function_R((void*)CPU_VERW,FC_RETOP);\n'),
    ],
    'staging-queued-rate': [
        ('dosbox-staging/src/audio/mixer.h',
         '\n// Generic callback used for audio devices which generate audio on the main\n',
         "\n#ifdef KAIRO_STAGING\n// Sizes a device's queue for its channel's rate: two of the mixer's blocks, or a hundred in\n// fast-forward, where the mixer asks for far more at once.\ntemplate <class DeviceType>\ninline void KAIRO_SizeQueue(DeviceType* device)\n{\n\tconst float blocks = MIXER_FastForwardModeEnabled() ? 100.0f : 2.0f;\n\tdevice->output_queue.Resize(iceil(device->channel->GetFramesPerBlock() * blocks));\n}\n\n// Hands frames dequeued from a device's queue to its channel, which converts them at its rate.\ntemplate <class DeviceType, class AudioType, bool stereo, bool signeddata, bool nativeorder>\ninline void KAIRO_AddQueuedFrames(DeviceType* device, const std::vector<AudioType>& frames)\n{\n\tif (frames.empty()) {\n\t\treturn;\n\t}\n\tif constexpr (std::is_same_v<AudioType, AudioFrame>) {\n\t\tdevice->channel->AddAudioFrames(frames);\n\t} else {\n\t\tdevice->channel->template AddSamples<AudioType, stereo, signeddata, nativeorder>(\n\t\t        check_cast<int>(frames.size()), frames.data());\n\t}\n}\n\n// KairoDos#154: a device that queues its sound is mixed KairoMixLagTicks behind the tick that made\n// it, in its channel's rate. When the rate changes, the frames still queued were made at the old\n// one; mixed at the new one, the mix would ask the queue for a different count than it holds. So\n// they go to the channel first, converted at the rate they were made at, and only then does the\n// channel take the new rate. The queue is then sized for the new rate at once: sized for a lower\n// one until the next pull, it would drop what the device makes at the higher one. Every queued\n// device changes its rate through here.\ntemplate <class DeviceType, class AudioType, bool stereo, bool signeddata, bool nativeorder>\ninline void KAIRO_SetQueuedSampleRate(DeviceType* device, const int rate_hz)\n{\n\tassert(device && device->channel);\n\tif (device->channel->GetSampleRate() == rate_hz) {\n\t\treturn;\n\t}\n\tstatic std::vector<AudioType> queued = {};\n\tdevice->output_queue.BulkDequeue(queued, device->output_queue.Size());\n\tKAIRO_AddQueuedFrames<DeviceType, AudioType, stereo, signeddata, nativeorder>(device, queued);\n\tdevice->channel->SetSampleRate(rate_hz);\n\tKAIRO_SizeQueue(device);\n}\n#endif\n\n// Generic callback used for audio devices which generate audio on the main\n"),
        ('dosbox-staging/src/audio/mixer.h',
         '\n\tif (MIXER_FastForwardModeEnabled()) {\n',
         '\n#ifdef KAIRO_STAGING\n\tKAIRO_SizeQueue(device);\n#else\n\tif (MIXER_FastForwardModeEnabled()) {\n'),
        ('dosbox-staging/src/audio/mixer.h',
         '\t}\n\tstatic std::vector<AudioType> to_mix = {};\n',
         '\t}\n#endif\n\tstatic std::vector<AudioType> to_mix = {};\n'),
        ('dosbox-staging/src/audio/mixer.h',
         '\n\tif (frames_received > 0) {\n',
         '\n#ifdef KAIRO_STAGING\n\tKAIRO_AddQueuedFrames<DeviceType, AudioType, stereo, signeddata, nativeorder>(device, to_mix);\n#else\n\tif (frames_received > 0) {\n'),
        ('dosbox-staging/src/audio/mixer.h',
         '\t}\n\t// Fill any shortfall with silence\n',
         '\t}\n#endif\n\t// Fill any shortfall with silence\n'),
        ('dosbox-staging/src/hardware/audio/soundblaster.cpp',
         '\tassert(channel);\n\tif (channel->GetSampleRate() != rate_hz) {\n',
         '\tassert(channel);\n#ifdef KAIRO_STAGING\n\tKAIRO_SetQueuedSampleRate<SoundBlaster, AudioFrame, true, true, true>(this, rate_hz);\n#else\n\tif (channel->GetSampleRate() != rate_hz) {\n'),
        ('dosbox-staging/src/hardware/audio/soundblaster.cpp',
         '\t\tchannel->SetSampleRate(rate_hz);\n\t}\n}\n\n',
         '\t\tchannel->SetSampleRate(rate_hz);\n\t}\n#endif\n}\n\n'),
        ('dosbox-staging/src/hardware/audio/gus.cpp',
         '\n\t\tchannel->SetSampleRate(sample_rate_hz);\n',
         '\n#ifdef KAIRO_STAGING\n\t\tKAIRO_SetQueuedSampleRate<Gus, AudioFrame, true, true, true>(this, sample_rate_hz);\n#else\n\t\tchannel->SetSampleRate(sample_rate_hz);\n'),
        ('dosbox-staging/src/hardware/audio/gus.cpp',
         '\t\tchannel->SetSampleRate(sample_rate_hz);\n\t}\n',
         '\t\tchannel->SetSampleRate(sample_rate_hz);\n#endif\n\t}\n'),
        ('dosbox-staging/src/hardware/audio/tandy_sound.cpp',
         '\n\t\t\tchannel->SetSampleRate(new_sample_rate_hz);\n',
         '\n#ifdef KAIRO_STAGING\n\t\t\tKAIRO_SetQueuedSampleRate<TandyDAC, uint8_t, false, false, true>(this, new_sample_rate_hz);\n#else\n\t\t\tchannel->SetSampleRate(new_sample_rate_hz);\n'),
        ('dosbox-staging/src/hardware/audio/tandy_sound.cpp',
         '\t\t\tchannel->SetSampleRate(new_sample_rate_hz);\n\n',
         '\t\t\tchannel->SetSampleRate(new_sample_rate_hz);\n#endif\n\n'),
        ('dosbox-staging/src/hardware/video/reelmagic/player.cpp',
         '\tassert(reel_magic_audio.channel);\n\treel_magic_audio.channel->SetSampleRate(active_fifo->GetSampleRate());\n',
         '\tassert(reel_magic_audio.channel);\n#ifdef KAIRO_STAGING\n\tKAIRO_SetQueuedSampleRate<ReelMagicAudio, AudioFrame, true, true, true>(&reel_magic_audio,\n\t                                                                      active_fifo->GetSampleRate());\n#else\n\treel_magic_audio.channel->SetSampleRate(active_fifo->GetSampleRate());\n'),
        ('dosbox-staging/src/hardware/video/reelmagic/player.cpp',
         '\treel_magic_audio.channel->SetSampleRate(active_fifo->GetSampleRate());\n\treel_magic_audio.output_queue.Start();\n',
         '\treel_magic_audio.channel->SetSampleRate(active_fifo->GetSampleRate());\n#endif\n\treel_magic_audio.output_queue.Start();\n'),
    ],
}
