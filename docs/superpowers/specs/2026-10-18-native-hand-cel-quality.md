# Native cel-hand drawing target

The owner's standard-2D review rejects the existing isolated hand's triangle
grid, closed palm-pad seams, angular tips and blocky/protruding nail plates.
Technical checks and fixed bones do not establish finished artwork. Continue
this focused repair without routine permission requests.

The bounded addition is `settings.renderStyle`, optional `smooth` (the legacy
default) or `cel`. Keep the existing geometry, joint controls, camera transforms
and retained/editable format. The cel branch may round terminal skin caps and
nail plates while preserving every bone length and joint centre. Group visible
skin lighting into three broad tones by clipping at interpolated thresholds and
native Path unions, with one coherent palm/pad lighting field. Keep nail/crease
occlusion separate; the skin mask alone defines the external contour. Never
smooth the complete silhouette in a way that closes finger gaps or moves grips.

Acceptance: one readable palm mass; intentional broad shadows without a mesh
grid or closed pad seams; rounded inset nails and soft fingertips; fine readable
creases; no detached marks or hidden-surface leakage. Inspect actual native PNGs
at normal size and detail scale across front/left/right/rear/high/low, open/grasp,
both hands, extremes and transitions. A tone count or path union is not an art
pass. Preserve every generation and any NEEDS_REWORK verdict.

The new style needs manual Hand animation controls, native settings validation,
strict MCP parameters/capability guidance and a 4.8.1-specific field gate. Missing
renderStyle must preserve old fingerprints and pixels; the existing hand API
keeps its 4.8.0 floor. Do not add a new command alias or change the closed set.

Verification: meaningful native regressions for unchanged joints, visible skin
coverage, detail containment/occlusion and rounded tips/nails; UI/schema parity;
48 different style scenarios (4 patterns × 2 hands × 6 views), 96 frames at
24 FPS and 1280×720; actual archive-restored comparisons; old 4.8.0 pixel probes
from matrix-v3 decoded and compared to the updated legacy renderer. The 48 new
inputs augment the earlier 240 distinct scenarios; repeated frames/checks and
retries do not increase the scenario count. Fully render selected motions and
package unchanged native PNGs only for review. Preserve source commit/diff,
native requests, version, execution location, hashes and independent technical,
visual and live-parity statuses. Signed candidates retain the original identity.

Reproduction is part of acceptance. Keep the four study recipes (open/close,
grasp, finger cascade, thumb opposition) inside a shared native product helper.
Expose fresh complete 1280×720/96-frame/24-FPS operation examples through native
hand capabilities, and an explicit manual Load study action that fills the
existing custom pose editor. Loading adapts keys to the current valid clock
without changing canvas, FPS, render style or saved artwork. At the study clock,
the requests must match the original study inputs exactly, apart from the
explicit cel style. The matrix must author these actual app-owned examples.
Reuse existing poses/settings fields and actions; no additional command or
constructor enum is needed. A private test helper is insufficient for ordinary
ChatGPT reproduction.
