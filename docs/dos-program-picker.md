# Run a setup program or another executable

Open a game's detail page and press the command-prompt icon to the right of
**Play**, then select a mounted drive,
folder, and executable. The picker accepts DOS EXE, COM, BAT, and DOS-compatible
CMD scripts. D-pad/confirm works throughout; Back/Up navigates to the parent.
**Prompt** is the first root-level option and resumes the mounted DOS session
without executing a program. Cancel at the root also leaves the files mounted
at the prompt. These launch options no longer appear in Game settings.

This is a one-session launch choice. It uses the saved startup variant's hardware
and drive mounts (or the default variant), but skips the catalog's game startup
and player setup scripts. The selected program runs from its own directory.
Regular Play reconstructs its original startup commands; no program selection is
saved to the catalog or user control/startup overrides.

The browser enumerates Staging's mounted guest filesystems, including FAT and ISO
images. It uses DOS short names instead of guessing host-to-DOS filename mappings.
Standalone ISO/CUE media is mounted as a CD drive; IMG/IMA media is mounted for
file access instead of booting its operating system. Non-filesystem/raw boot media
still needs its normal boot path; a file picker cannot enumerate a filesystem
that the DOS core cannot mount.

CMD files are interpreted as DOS batch text, not Windows commands. The selected
script is copied through DOS to an app-owned temporary BAT on a free drive chosen
from the actual mounted-drive list. Its working directory stays with the source
program; the script's `%0` names the temporary BAT. Original game files are not
renamed or overwritten by launch preparation. Changes made by the selected setup
program itself persist in the game's writable files, like ordinary DOS programs.

## Ownership

- Shared frontend: asynchronous `DirectoryPicker`, history, controller navigation,
  Android system Back, loading/error/empty states, retry, and cancellation.
- DOS host: game preparation, launch targets, mounts and program commands.
- DOS backend: filesystem enumeration on the emulation thread, including while
  paused, with DOS search state preserved.
- Session replacement, return to library, and activity destruction close the
  picker and discard pending work.

## RGDS validation, 2026-10-02

- Shared picker and existing exit-dialog tests passed in both apps, including
  companion-screen keys/hats/Back and returning from touch to controller selection.
  Both applications were updated on the RGDS with the same shared source.
- `dosProgramUri=<installed Duke URI>` exercised the actual game settings row and
  controller-driven picker with generated BAT/CMD/COM/MZ-EXE programs. Each wrote
  a verified marker in its own working directory. Cancellation resumed the prompt;
  normal Play restored the exact previous launch configuration; preferences were
  unchanged. Temporary fixture files were removed.
- `dosMedia=true` generated FAT12 and ISO9660 images with nested setup scripts.
  The real core listed both filesystems while paused and executed selected
  programs from both images. Catalog-mounted and standalone-image paths passed.
  Image contents remained byte-for-byte identical.
- `stagingStorage=true` checks normal/prompt/browse/selected launch generation,
  CPU/video configuration, invalid program paths, writable migration, preserved
  saves, and cancellation.
- `dosPromptUri=<installed Duke URI>` verifies the existing prompt action,
  writable shell, directory enumeration, and normal relaunch.

Test component:
`com.loxifi.kairodos.test/com.mrjackspade.kairodos.CatalogUpdateInstrumentation`.
The generated executables and disk images contain no proprietary game assets.

## Combined command launch verification (2026-10-03)

Both apps built and passed detail-page key/hat navigation on RGDS. DOS passed the command-button picker flow, root-only Prompt choice without restarting, cancellation, all four executable formats, working-directory checks, writable shell commands, and restoration of normal Play. Existing user preferences remained unchanged. Retroid was unreachable and not advertised by mDNS.
