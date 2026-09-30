# Local file selection audit

Issue [47](https://github.com/MrJackSpade/KairoDos/issues/47) covers both KairoDos
and Kairo98. The shared frontend supplies the same native artwork picker in both
apps; both products pin the same frontend revision.

| Flow | Selection and access |
| --- | --- |
| DOS game folder / PC-98 ROM folder, including first run | Android `ACTION_OPEN_DOCUMENT_TREE`; `LibraryFlow` retains the granted read/write permissions and checks them when reopening the saved folder. Expired access asks the user to select the folder again. |
| Box art / screenshot in both game settings sheets | “Choose image” opens `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, `image/*` through shared `DocumentPicker`. The selected provider URI goes to the existing bounded decoder and atomic import in `CatalogArtworkStore`. The editor no longer accepts a typed local or packaged path. |
| Kairo98 hard disk / floppy A / floppy B | Shared `DocumentPicker` opens `ACTION_OPEN_DOCUMENT`, `*/*`. Existing format, size, copy, and mount checks consume the provider URI. Provider metadata failures are reported rather than crashing. |
| Kairo98 BIOS / font BMP / rhythm ROM, including first run | Shared `DocumentPicker` opens `ACTION_OPEN_DOCUMENT`, `*/*`. Existing firmware validation and atomic imports consume the provider URI. No firmware is added to either app. |
| Opening a game from another app | Existing `ACTION_VIEW` handling consumes the externally granted URI; this is an incoming selection, not a local text editor. |

Individual selected files are copied into app-owned storage. Their temporary read
grants are sufficient; neither app stores these provider URIs as the saved copy or
requires a permanent grant for them. Artwork selection saves the pending content
ID and artwork kind in Activity instance state, so a provider result still saves
to the correct game after Activity recreation. Cancellation leaves existing
artwork intact. Decode/import failure leaves its override intact and reports the
error. Reset removes only the override and restores the catalog choice.

The remaining text fields are game titles, library search, DOS player names,
PC-98 guest commands, controller input codes, and numeric graphics settings.
None chooses a host file. The audit found no editable remote URL field in the
current apps; catalog download URLs and larger artwork URLs remain remote data.

The picker and grant policy follows the Android
[Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).

## Retroid acceptance, September 30, 2026

Built `:kairodos:assembleDebug` in an isolated checkout without the existing
controller/catalog/presentation edits, and `:kairo98:assembleWithImagesDebug`
with the existing Kairo98 update key. The tested frontend code was committed as
`b18929b`, then rebased to `c9f2b07` over a concurrent DOS catalog refresh;
`git diff b18929b c9f2b07 -- frontend` is empty. Both apps pin `c9f2b07`.
Updated the existing `com.loxifi.kairodos` and `com.loxifi.kairo98` packages using
`adb install -r` after verifying model `Retroid Pocket Classic` at
`192.168.1.247:5555`. All device tests used this Retroid only.

- In both apps, box art and screenshot settings expose “Choose image” without
  a path text field. DocumentsUI selected images from its Downloads provider.
  Valid imports saved app-owned WebP paths; images reopened after force-stop
  and relaunch. Reset removed only the chosen artwork field.
- In both apps, cancelling the screenshot picker left the saved override
  unchanged. Selecting a 42-byte corrupt PNG left the existing box art intact.
- In both apps, `am kill` stopped the background app while DocumentsUI remained
  open. Selecting the valid 32-by-32 PNG recreated the app and saved box art to
  the original content ID, verifying the saved pending selection.
- Folder pickers opened in both apps. Back cancelled without replacing the
  existing ROM/DOS folder; the saved libraries reopened on relaunch.
- Kairo98 BIOS, Font BMP, and rhythm-ROM imports opened DocumentsUI. Invalid
  files were rejected by the existing validators. BIOS and font hashes stayed
  unchanged; no rhythm ROM was created. Rhythm-picker cancellation was also
  checked.
- From an existing Kairo98 game session, hard disk and floppy A pickers opened
  DocumentsUI and rejected a PNG as unsupported media. Floppy B opened the
  same picker and cancelled without mounting anything.

Restored the original artwork override files after testing. Test inputs were
small images; no game, OS, or firmware files were added to the repositories.
This verifies picker behavior, not a new release-readiness or license-audit claim.
