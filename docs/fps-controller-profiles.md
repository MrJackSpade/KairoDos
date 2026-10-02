# FPS controller defaults

The **With Sticks** Doom and Duke Nukem 3D defaults use the following layout.
These bindings send the games' existing keyboard/mouse inputs; they do not alter
game files. Saved custom mappings take precedence. **Without Sticks** retains
its existing D-pad movement, shoulder strafing and face-button firing layout.

Profiles are generated from `catalog/controller-defaults.json` by
`tools/GenerateDosControllerProfiles.ps1` and published with
`tools/BuildPublicDosCatalog.ps1`. A downloaded catalog replaces the bundled
controller catalog. Only explicit per-game user mappings take precedence;
there are no executable Doom/Duke default overrides. Catalog refresh also
reloads the active game's mapping, releasing held inputs through the shared mapper.

| Control | Doom | Duke Nukem 3D |
| --- | --- | --- |
| Left stick | Move forward/back, strafe left/right | Same |
| Right stick | Turn (original Doom has no vertical look) | Turn, aim up/down |
| RT / R2 | Fire | R |
| LT / L2 | Hold to run | H |
| LB / L1 | Unassigned | Previous weapon |
| RB / R1 | Fire (duplicate of RT) | Ctrl |
| X | Open / use | Open / use |
| A | Open / use (Space) | Space |
| B | Unassigned | Q |
| Y | Map (duplicate of D-pad Up) | Shift |
| D-pad Up | Map | ; |
| D-pad Left / Right | Previous / next weapon key in 1–7 cycle | Previous / next key in 1234567890 cycle |
| D-pad Down | Map overview | J |
| Left stick click | Unbound | W |
| Right stick click | Unbound | N |
| Start | Game menu (Escape) | Confirm (Enter) |
| Select | Confirm (Enter) | Game menu / cancel (Escape) |

Kairo's menu button is unchanged. Use left-stick forward/back to navigate the
games' own menus; Select confirms in Doom and Start confirms in Duke. Duke retains its existing right-stick
sensitivity; both right-stick axes now send proportional mouse movement.
Enable mouse aiming in Duke for vertical movement to control looking up/down.
The catalog sets Doom turning to 8x and Duke turning to 2x. Doom was doubled
from 4x for Kairo #16. In Doom, only Start and Select send Escape and Enter respectively;
the former A/B menu duplicates are removed. X remains a duplicate Open/use.

Doom has no native previous/next weapon action: D-pad Left/Right share Kairo's key
cycle. This cycles selection keys, including weapons not yet owned, and cannot
track weapon changes made in-game or on the keyboard. Duke also uses the shared key cycle, with keys 1234567890. Doom's map controls follow
[the original game source](https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/am_map.c).
Duke bindings follow the installed game's original `DUKE3D.CFG` key definitions.

`ControllerMouseFixture` exercises these profiles through the shared mapper:
trigger button/axis ownership and release, stick movement and sensitivity,
D-pad cycle direction/wrap, map and inventory inputs, profile serialization,
saved custom mappings and the unchanged Without Sticks controls.
`ControllerCatalogFixture` installs two different snapshots into an isolated
catalog directory and verifies both override bundled Doom/Duke defaults without
changing the APK. It also verifies explicit custom controls win and clearing
them returns to the downloaded mapping.

RGDS catalog-only verification (October 1, 2026): installed KairoDos SHA-256
`fc2d8cdc24bad5a27d5e0bd9b77bac9e0e94e574a5a9baae5ba1e15f3ec51acd`
resolved Doom turning at 8x before publication and 4x after fetching public
catalog revision `a96e570da72d0f4a597a3b47b9badb9271b3423d1b713e8e426d306b47bd1473`.
The application APK was unchanged. Only the instrumentation test package was
updated to inspect the resolved bindings and expose download errors. Run
`CatalogUpdateInstrumentation` with `catalogControllerSpeed=4` to verify the
active profile; add `catalogFetch=true` to exercise the production download path.

## Doom update, Kairo #16 (October 2, 2026)

Both apps passed the shared `KeyCycleFixture` on the RGDS before publication,
including PC98 guest keys, existing shoulders, D-pad hat events, independent
pair/sequence state, wrapping, release and controller-confirmed editor save.

The generated archive audit found only Doom's With Sticks preset changed.
Without Sticks, legacy Doom bindings, Duke, every other profile and every other
archive entry were byte/structurally unchanged as applicable.

Installed KairoDos APK SHA-256
`15f3979d597f237ca2e4279846d026938247435af802724a12e34909046525fa`
retained the previous bundled profile. After publishing catalog revision
`fe25f352dd4ef5d76ea6ba1d51f1556e9dd69ee327a31fe4f5cf25fb790df251`,
the same APK fetched it through `DosGameCatalog.downloadUpdate()` and resolved
8x turning, A=Space, D-pad Left/Right cycles, and R1/R2 fire. No app APK reinstall
occurred after publication; only the test package changed.

Run `CatalogUpdateInstrumentation` with `catalogControllerSpeed=8`,
`catalogFetch=true`, and `controllerMouse=true` for this check. It also exercises
the downloaded defaults through the real mapper: stick movement and measured
mouse distance, D-pad shared wrap, overlapping R1/R2 ownership, unchanged Duke
and Without Sticks inputs, designated-only Escape/Enter, explicit custom
override precedence and return to catalog defaults after clearing an override.
The fixture's old 35% deadzone expectations were updated to the existing 10%
default from Kairo #7; production deadzone behavior was not changed here.

## Duke Start/Select correction, Kairo #21 (October 2, 2026)

The RGDS `retrogame_joypad` is `/dev/input/event9`; its
`Vendor_484b_Product_1101.kl` maps Linux key 315 to BUTTON_START and 314 to
BUTTON_SELECT. No saved physical mapping overrides were present. The active
downloaded Duke With Sticks profile had Start=Escape (27) and Select=Enter (13),
opposite to its existing Without Sticks profile.

Only those two With Sticks assignments changed:

| Physical input | Virtual input | Guest key |
| --- | --- | --- |
| Start (315 / Android BUTTON_START) | start | Enter, 13 |
| Select (314 / Android BUTTON_SELECT) | select | Escape, 27 |

Raw press/release events injected through the discovered gamepad node reproduced
the old behavior: Start dismissed the New Game menu and Select opened episode
selection. After the catalog update, Select opened the menu, Start opened
episode selection, and Select returned to the main menu. One brief 80 ms Select
event did not visibly dismiss the episode menu; a deliberate 350 ms press did.
This test establishes the assignment correction, not an input-latency guarantee.

Public revision `cc5a34e34887b7314693aa2a15721fbf76529e46788019b3b6203b3800a58244`
was fetched by the unchanged installed APK
`468884b91f80508999bb696007c9a907ba08f3c73f2163c53a6cbd364b0d4acd`.
The production resolver and shared-mapper fixture passed both Duke assignments,
Doom/Without Sticks regressions, and explicit custom override precedence.
Only the instrumentation APK changed; no app reinstall or control reset was used.
The archive audit found only the two requested Duke bindings changed.

Duke's own configuration was not edited; its before/after SHA-256 remained
`4da3770012f4860101e78581b6c56f1bac6658326f32d54cb35a834e40f20cdb`.

## Explicit Duke With Sticks correction (October 2, 2026)

The user specified R1=Ctrl, L3=W, R3=N, B=Q, A=Space, Y=Shift,
D-pad Up=semicolon, Down=J, Left/Right=weapon key cycle 1234567890,
L2=H and R2=R. Treat this table as the requested layout; do not replace it
with inferred conventional FPS defaults. Right-stick up/down also send analog mouse movement at 2x, matching horizontal
turning. Other unspecified controls retain their bindings.
