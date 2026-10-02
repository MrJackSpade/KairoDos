# Native Debug policy — issue #51 checkpoint

The shared CMake helper owns Debug `-O2` for the DOS host and both PC-98 native
targets. Existing effective flags are preserved: generated compiler commands
before and after consolidation contained the same tokens for all 2 DOS host and
236 PC-98 translation units, including `-g`, `-fno-limit-debug-info`, and no
`NDEBUG`. Kairo98 LTO and core-specific flags are unchanged. The DOS build used
`KAIRO_OPTIMIZED_PROFILE=OFF`; the historical missing optimization had already
been fixed before this consolidation.

Both app builds and instrumentation builds succeeded on October 2, 2026.
The old isolated `VideoPresentationFixture` Duke run was rejected: after its
60-second warmup, the 120-second capture recorded zero frames and remained on
the loading screen. It is not usable performance evidence.

A new `presentationUri` instrumentation argument measures the installed game's
normal ACTION_VIEW activity, audio and surface lifecycle. It uses a 60-second
warmup, configurable capture length, and rejects zero-frame results. This harness
compiled, but its runtime measurement has not yet been validated. It does not
modify game configurations or replace normal application behavior.

Issue #51 remains open. Before closing, finish the valid Doom and Duke conversion
measurements without `presentationProfile`, and record the PC-98 game boot check.
Work was paused at the user's request; this checkpoint does not claim those
remaining checks passed.
