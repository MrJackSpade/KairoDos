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

Instrumentation component:
`com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation`.
