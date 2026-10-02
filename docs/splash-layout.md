# Shared splash layout

The shared `FirstRunScreen` places the product at top left, page title at top
right, options in the body, and the terminal Continue/Skip action at bottom right.
The old setup eyebrow is not rendered. Controller configuration has no redundant
instruction paragraph. Empty option subtitles do not reserve a second line.
Pixel-font product branding, cyan accents, shaded option cards, and an accented
primary footer preserve the shared visual style without restoring redundant text.

Verified on RGDS at 640x480, density 160, with both signed normal apps:

- Controller choices and Continue are fully visible in Kairo98 and KairoDos.
- ROM-folder, DOS-folder and PC-98 firmware pages use the same layout and fit.
- D-pad Down reaches the footer; Enter advances controller and folder pages.
- Controller rows retain the same bounds when focus reaches Continue, and all
  three firmware actions remain visible when the footer is focused.
- The footer occupies y=412..460 with a 20-pixel bottom margin. Body scrolling is
  unnecessary at this resolution; the body retains an overflow fallback for
  smaller windows or enlarged accessibility fonts, with the footer fixed.

Device screenshots and hierarchy dumps are under ignored `.tmp/splash-check/`.
Temporary onboarding preferences were backed up and restored after verification.
No game library, firmware, or controller choice was replaced for the test.

## Secondary display during setup

Library restoration can establish a highlighted row before the first-run overlay
is shown. Previously, that row's selection/artwork callbacks published its preview
on the second display, including while controller setup used a separate dialog.

`FirstRunVisibility` now tracks all setup views within the owning activity, including
dialog attachment/detachment. The shared secondary-display coordinator suppresses
library previews, keyboard and swapped guest output while any setup view is active.
It retains the latest library selection so finishing setup restores the correct
preview. Asynchronous artwork updates use the same visibility gate. Setup state
also routes secondary-display key/hat input to the active page rather than the
underlying library, and its observer is removed when the coordinator stops.

RGDS checks cover both physical displays through setup and after completion,
including D-pad/Enter sent to the lower display. Kairo98's controller, folder and
firmware pages leave the bottom display clear; the selected-game preview returns
after Continue to library. Screenshots are under `.tmp/setup-display-check/`.
KairoDos's controller and folder stages likewise leave the lower display clear,
and finishing setup restores its selected-game preview. Original preferences for
both apps were restored after these checks.

During verification, Gradle reported successful Kairo98 packaging while the output
APK retained its previous hash and UI. Preserving and recreating only the generated
APK and `build/intermediates/incremental/packageWithImagesDebug` state produced the
correct APK. Verify installed APK content and the actual page, not just build success.
