# Doom analog turning investigation — RGDS, October 2, 2026

User report: right-stick turning feels digital, with little perceived speed
control. No response-curve or sensitivity change has been made on this evidence.
The active downloaded Doom With Sticks profile uses mouse movement at 8x,
with 10% deadzone. Doom DEFAULT.CFG has mouse_sensitivity=5 and use_mouse=1.
There were no saved controller binding overrides in the app preferences.

## End-to-end observations

`MouseAnalogFixture` launches the installed Doom entry through ACTION_VIEW, then
uses the existing one-session program launch to run a generated DOS COM probe in
an owned KAIROANA directory. It reads INT 33h function 0Bh CX/DX motion counters.
It tests the actual activity, shared mapper, native input bridge, and Staging DOS
mouse driver. The generated program and result are removed after the core stops;
no game binary, archive, or configuration is edited. This measures mouse counts,
not Doom's player angle or an actual gameplay turn rate.

Each interval requests approximately two seconds of steady motion. The first
pass dispatches Android joystick events through the real activity. The second
uses controlled ABS_RX values on RGDS `/dev/input/event9`, followed by SYN_REPORT,
so Android InputReader normalization and actual display routing are exercised.
The kernel pass is guarded to RGDS/retrogame_joypad and returns the axis to zero.
Raw getevent independently confirmed intermediate positions during that pass.

| Raw normalized stick | Strength beyond 10% deadzone | Activity injection X counts | RGDS kernel injection X counts |
| --- | --- | --- | --- |
| 0 | 0% | 0 | 0 |
| 0.145 | 5% | 383 | 409 |
| 0.19 | 10% | 766 | 825 |
| 0.325 | 25% | 1917 | 2048 |
| 0.55 | 50% | 3831 | 4050 |
| 1.0 | 100% | 7658 | 8227 |

Y counts were zero throughout. Activity intervals were 2025–2031 ms. Kernel
intervals were 2299–2360 ms including command dispatch/wait overhead; do not
compare their absolute counts directly with the shorter activity pass. Within
each pass, output follows input proportionally. The first kernel attempt used
compound syntax with executeShellCommand, which does not execute shell compound
commands; its zero-count result was rejected and command execution corrected.

The formula after deadzone is `(deflection - 0.1) / 0.9`. The shared mouse router
multiplies that strength by sensitivity and elapsed time, retaining fractional
counts. The native bridge forwards relative amounts to Staging without reducing
them to directional booleans.

## Scope and remaining uncertainty

These results rule out an on/off conversion in the exercised software path at
these values. They do not measure the physical stick's sensor output while a
person moves it, or camera angle/frame pacing in the running Doom game. The user
was occupied; no physical-motion capture was available. The perceived symptom
has not been reproduced or declared fixed. High sensitivity/response feel and
physical stick response remain possibilities, not established diagnoses.

Run the DOS instrumentation with `mouseAnalogUri=<installed Doom URI>`; add
`mouseAnalogHardware=true` only on the inspected RGDS with the stated axis range
(-16384..16384). The fixture rejects a zero-motion full-deflection result.

## Duke catalog work completed alongside this investigation

The exact requested button bindings and 1234567890 D-pad weapon cycle are published
in catalog revision `cdcd9a7fa1d877146098d53a59558f85425b1d060792152d88ab3c03502a3e7c`.
Both right-stick axes send proportional mouse motion at 2x. RGDS downloaded this
revision using the unchanged application APK; mapper checks passed, including
half/full vertical mouse magnitude, release, trigger ownership, cycle wrap,
custom override precedence and unchanged Without Sticks bindings.
Duke's own mouse-aiming configuration was left unchanged as requested.
