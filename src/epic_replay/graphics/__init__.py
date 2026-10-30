"""S9 graphics (T023, FR-029, FR-030): HTML templates rendered to transparent PNGs.

One template (`overlay.html`) draws the four overlays at the output size: the title card, the
replay bug, the end card and the AI label. The text comes from the script's headline, the
pack's labels and `AI_LABEL`; the pack's `graphics.accent` colours them, and its `graphics.skin`
names a CSS file in `skins/` for the type (face, weight, case). The templates and the positions
are the same in every skin. Sizes follow the shorter side of the frame (`--u`) and text wraps
inside the frame, so a vertical video needs no second layout. The lower third and a drawn
vertical layout are deferred (tasks.md, "Deferred").

`render(...)` starts Chromium once (Playwright, as the page tests do) in a child process limited
to `TIMEOUT_S`, writes one HTML file and one PNG per overlay under `overlays/`, and returns the
PNGs by type, or raises. No network: fonts are the files in `assets/fonts/`.
"""
