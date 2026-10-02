# Shared splash layout

The shared `FirstRunScreen` places the product at top left, page title at top
right, options in the body, and the terminal Continue/Skip action at bottom right.
The old setup eyebrow is not rendered. Controller configuration has no redundant
instruction paragraph. Empty option subtitles do not reserve a second line.

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

During verification, Gradle reported successful Kairo98 packaging while the output
APK retained its previous hash and UI. Preserving and recreating only the generated
APK and `build/intermediates/incremental/packageWithImagesDebug` state produced the
correct APK. Verify installed APK content and the actual page, not just build success.
