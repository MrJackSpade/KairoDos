# Boot to DOS prompt

On a playable game's detail page, press the command-prompt icon beside **Play**,
then choose **Prompt** at the top of the browser. This resumes the mounted session
without executing an EXE, COM, BAT, or CMD. The same browser can navigate drives
and folders to launch a chosen program instead.

The browser retains the selected startup variant's hardware and drive mounts,
including mounted images, but skips game startup commands and player setup scripts.
This is a one-session choice; ordinary Play restores its usual startup commands.
User settings and writable game files are preserved. See [program picker](dos-program-picker.md).

The shared detail page supplies an optional command button and controller navigation;
the DOS host supplies the launch action. Kairo98 retains its full-width Play button.

The historical checks below describe the former standalone prompt settings action.
Current UI regression fixtures exercise the combined detail-page browser.

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

## Mounted file enumeration (issue #25)

The native `nativeListDirectory` worker API queues directory reads onto the
emulation thread, including while paused. Empty input lists mounted user drives;
an absolute DOS directory lists its children using the core's filesystem and DOS
short names. It preserves the DOS DTA pointer, temporary search record, and error
state. Requests time out or cancel on session shutdown; host filesystem paths,
wildcards, parent traversal, and the emulator's Z: drive are rejected.

The RGDS prompt fixture additionally passed mounted-drive discovery, recursive
navigation to a Duke executable while paused, invalid-path rejection, followed
by the writable shell and normal-launch checks above.

The shared asynchronous directory picker passes UI/controller tests in both apps
on RGDS (see `shared/docs/directory-picker.md`). The DOS Run program action uses it
for one-session executable selection. See [dos-program-picker.md](dos-program-picker.md)
for launch behavior, CMD handling, and FAT/ISO validation.

Instrumentation component:
`com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation`.
