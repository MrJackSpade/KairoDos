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
| LB / L1 | Previous weapon key in 1–7 cycle | Previous weapon |
| RB / R1 | Next weapon key in 1–7 cycle | Next weapon |
| X | Open / use | Open / use |
| A | Confirm (Enter) | Jump |
| B | Back / game menu (Escape) | Crouch |
| Y | Map (duplicate of D-pad Up) | Medkit |
| D-pad Up | Map | Map |
| D-pad Left / Right | Map zoom out / in | Previous / next inventory item |
| D-pad Down | Map overview | Use selected inventory item |
| Left stick click | Unbound | Hold to run |
| Right stick click | Unbound | Quick kick |
| Start | Game menu (Escape) | Game menu (Escape) |
| Select | Confirm (Enter) | Confirm / use selected inventory item (Enter) |

Kairo's menu button is unchanged. Use left-stick forward/back to navigate the
games' own menus and Select to confirm. Duke retains its existing right-stick
sensitivity and native Home/End aiming, avoiding changes to mouse-aiming mode.
The catalog now sets Doom turning to 4x and Duke turning to 2x. Doom's 8x-to-4x
change was published after deployment of the APK containing the catalog fix.

Doom has no native previous/next weapon action: its bumpers share Kairo's key
cycle. This cycles selection keys, including weapons not yet owned, and cannot
track weapon changes made in-game or on the keyboard. Duke uses native previous/
next weapon keys instead. Doom's map controls follow
[the original game source](https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/am_map.c).
Duke bindings follow the installed game's original `DUKE3D.CFG` key definitions.

`ControllerMouseFixture` exercises these profiles through the shared mapper:
trigger button/axis ownership and release, stick movement and sensitivity,
bumper cycle direction/wrap, map and inventory inputs, profile serialization,
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
