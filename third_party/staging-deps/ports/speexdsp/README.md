# SpeexDSP recipe overlay

The recipe, CMake build, manifest and jitter patch are copied from the pinned
vcpkg archive at commit 6283825b81bb60f952af1d0703638df1de611243, under its
included MIT license. The unchanged SpeexDSP 1.2.1 source archive and its BSD
notices remain under `../../sources/`.

`interpolate-neon.patch` is a first-party GPL-2.0-or-later modification. It
vectorizes the four independent floating-point interpolation accumulators on
AArch64, retaining their accumulation order and the existing final reduction.
The original complete function is retained for oversample factors at most one
to preserve the baseline compiler's rounding in that path. Other architectures,
fixed-point builds, filter quality, rates and resampler state handling are unchanged.

This source overlay participates in vcpkg's recipe ABI hash; an existing cached
unpatched archive must not satisfy it. No generated library belongs in Git.
