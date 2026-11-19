# Spec 004 add-on: the app icon

**For**: `specs/004-android-app/` · **Written**: 2026-11-19 · **Status**: decided by the owner (options A and C, 2026-11-19), to be merged into spec.md, plan.md and tasks.md
**Why an add-on**: spec 004 is being built while this was decided. This file states the decision; the agent working on 004 places it in the spec, plan and tasks without disturbing work in progress.

## 1. Decision

Spec 004 defined no launcher icon, and the Android app has none (the system default shows). The owner chose two variants of one design: the web app's two-triangle rewind mark (001 `contracts/design.md`, header) over the letters **ER** for Epic Replay.

| Variant | Use | Background | Rewind mark | ER |
|---|---|---|---|---|
| **C, yellow tile** | The launcher icon (home screen, app drawer, Play Store) | `#FFD23F` | `#0D1012` | `#0D1012` |
| **A, dark** | Inside the app: the splash screen and the top bar, matching the dark broadcast look | `#0D1012` (`--ground`) | `#FFD23F` | `#F4F5F6` |

The yellow is the football narrator's accent (`#FFD23F`). In the web app the accent follows the chosen narrator; the icon has to be fixed, so it uses this one colour on every screen.

## 2. Geometry (108 by 108 adaptive-icon canvas, in dp)

- **Safe zone**: everything sits inside the circle of radius 33 centred at (54, 54), so no launcher mask (circle, squircle, rounded square) cuts it.
- **Rewind mark**: two triangles pointing left, each 15.6 wide and 20 high (width 0.78 of the height), side by side with no gap, together spanning x 38.4 to 69.6, y 26 to 46.
- **ER**: Sofia Sans Extra Condensed, weight 900 (`shared/assets/fonts/sofia-sans-extra-condensed-latin-900-normal.woff2`), 40 units, tracking 1 unit, centred on x = 54, baseline y = 82. The letters are **outlined to paths**: the icon must not depend on a font at run time.

## 3. Source files (attached; to be committed under `shared/assets/brand/`)

| File | What |
|---|---|
| `icon-c-yellow.svg` | C, complete: yellow background, black mark and ER. Source of the Play Store image. |
| `icon-c-foreground.svg` | C's foreground layer only (transparent background). |
| `icon-a-dark.svg` | A, complete: dark background, yellow mark, white ER. |
| `icon-monochrome.svg` | The mark and ER as one black shape, for themed icons (Android 13+). |

All four are 108 by 108, path-only (no text, no fonts, no external references), generated from the font file above. They are the single source of the icon (working rule 11): shared data lives once, in `shared/`. `shared/assets/LICENCES.md` gets a line: own artwork; the letter shapes are outlines of Sofia Sans Extra Condensed 900 by Lettersoup (SIL Open Font License 1.1, as the font's existing entry in that file).

## 4. Requirements

- **FR-ICON-1** The launcher icon is variant C as an adaptive icon: background layer `#FFD23F`, foreground layer from `icon-c-foreground.svg`, monochrome layer from `icon-monochrome.svg` (themed icons on Android 13+). The app's `minSdk` is 28, so every supported phone takes the adaptive icon; no legacy bitmap launcher icons are needed.
- **FR-ICON-2** The splash screen (the AndroidX SplashScreen API) uses variant A: `#0D1012` background, A's mark and ER as the icon. The app's top bar shows A's mark at the size of the web header's mark (30 by 20) beside the wordmark.
- **FR-ICON-3** A 512 by 512 PNG of variant C (full square, no mask, no shadow; the Play Store applies its own mask) is produced for the store listing.
- **FR-ICON-4** The Android resources are derived from the SVGs in `shared/assets/brand/`, not drawn again. Either a Gradle step converts them at build time, or the vector drawables are committed together with a unit test that checks their path data against the SVGs. A changed SVG must fail the build or the test until the drawables follow. The derived files keep different names from the sources, so `test_shared.py` does not count them as copies.
- **FR-ICON-5** Nothing in the icon depends on the chosen narrator, model or language.

## 5. Acceptance

1. On a phone (Android 13 or later), the home screen shows C; with themed icons switched on, the monochrome layer shows in the theme's colours; no mask crops the mark or the letters.
2. Cold start shows A's splash screen, then the first screen.
3. The 512 px store image matches `icon-c-yellow.svg` scaled up.
4. Editing a triangle in `icon-monochrome.svg` (by hand, not committed) makes the build or the check of FR-ICON-4 fail.
5. The PC app is unchanged (its tests and goldens pass).

## 6. Suggested task (about 2.5 h)

**T4xx The app icon** (2.5 h). Commit the four SVGs to `shared/assets/brand/` with the licence line. Produce the adaptive launcher icon (C, with the monochrome layer), the splash screen (A), A's mark in the top bar, and the 512 px store PNG, all derived from the SVGs as FR-ICON-4 says.
*Done when*: acceptance 1 to 5 hold; screenshots of the home screen (normal and themed) and the splash screen are in the task's notes.

Placement: after the Android project exists (T405, done) and before or within the screens task (T425), whichever the plan finds less disruptive; the release task (T429) checks the store image.
