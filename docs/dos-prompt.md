# Boot to DOS prompt

Game settings contains **Boot to DOS prompt** for playable DOS entries. It opens
the game's existing writable directory as `C:` and lists its files. Commands and
file changes operate on the same persistent drive used by normal Play.

This is a one-session choice. It retains the selected startup variant's hardware
settings, or the default variant when none is saved, without changing that saved
selection. It skips catalog autoexec commands, player-name setup scripts,
dependency archives, automatic executable selection, and image boot commands.
Image files remain accessible from the mounted directory for manual mounting.
Normal Play regenerates its usual startup commands.

The DOS host supplies this emulator-specific action to the existing shared
`GameSettingsSheet`; the shared dialog supplies layout and controller navigation.
No Kairo98 or shared implementation change is required.

## RGDS validation (2026-10-02)

- Signed debug app built and installed over `com.loxifi.kairodos`.
- `stagingStorage=true` passed: catalog startup scripts, missing dependency
  mounts, image boot, player setup, and unknown single-executable archives do not
  run in prompt mode. Hardware settings remain and normal launch generation is
  unchanged; existing writable storage regression checks pass.
- `dosPromptUri=<Duke installed archive URI>` passed through the real settings
  dialog with controller confirmation. A guest `echo` command created a temporary
  file in the existing writable game directory, its contents were verified, and
  the file was removed. Ordinary Play then regenerated the exact prior launch
  configuration and started the core. App preferences remained unchanged.
- Duke's `DUKE3D.CFG` remained
  `4da3770012f4860101e78581b6c56f1bac6658326f32d54cb35a834e40f20cdb`.

## Mounted file enumeration (issue #25, in progress)

The native `nativeListDirectory` worker API queues directory reads onto the
emulation thread, including while paused. Empty input lists mounted user drives;
an absolute DOS directory lists its children using the core's filesystem and DOS
short names. It preserves the DOS DTA pointer, temporary search record, and error
state. Requests time out or cancel on session shutdown; host filesystem paths,
wildcards, parent traversal, and the emulator's Z: drive are rejected.

The RGDS prompt fixture additionally passed mounted-drive discovery, recursive
navigation to a Duke executable while paused, invalid-path rejection, followed
by the writable shell and normal-launch checks above.

This is infrastructure for the executable picker, not the completed feature.
The shared asynchronous directory picker now passes UI/controller tests in both
apps on RGDS (see `shared/docs/directory-picker.md`). Remaining work includes DOS
mounting/selection/launch integration and FAT/ISO fixture coverage. The DOS host
must use mounted guest paths, preserve normal startup
defaults, set the selected program's working directory, and handle cancellation
and session teardown. Staging natively executes EXE/COM/BAT; DOS-compatible CMD
scripts need explicit handling without claiming Windows command support.

Instrumentation component:
`com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation`.
