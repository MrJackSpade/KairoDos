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
| RT / R2 | Fire | Fire |
| LT / L2 | Hold to run | Hold to run |
| LB / L1 | Unassigned | Previous weapon |
| RB / R1 | Fire (duplicate of RT) | Next weapon |
| X | Open / use | Open / use |
| A | Open / use (Space) | Jump |
| B | Unassigned | Crouch |
| Y | Map (duplicate of D-pad Up) | Medkit |
| D-pad Up | Map | Map |
| D-pad Left / Right | Previous / next weapon key in 1–7 cycle | Previous / next inventory item |
| D-pad Down | Map overview | Use selected inventory item |
| Left stick click | Unbound | Hold to run |
| Right stick click | Unbound | Quick kick |
| Start | Game menu (Escape) | Game menu (Escape) |
| Select | Confirm (Enter) | Confirm / use selected inventory item (Enter) |

Kairo's menu button is unchanged. Use left-stick forward/back to navigate the
games' own menus and Select to confirm. Duke retains its existing right-stick
sensitivity and native Home/End aiming, avoiding changes to mouse-aiming mode.
The catalog sets Doom turning to 8x and Duke turning to 2x. Doom was doubled
from 4x for Kairo #16. Only Start and Select send Escape and Enter respectively;
the former A/B menu duplicates are removed. X remains a duplicate Open/use.

Doom has no native previous/next weapon action: D-pad Left/Right share Kairo's key
cycle. This cycles selection keys, including weapons not yet owned, and cannot
track weapon changes made in-game or on the keyboard. Duke uses native previous/
next weapon keys instead. Doom's map controls follow
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
