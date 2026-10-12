# VideoStudio current engineering priority

The owner asked to complete the 2026-10-12 Cinematic Animation System Blueprint
and QA first, then resume the earlier complete engineering blueprint. Preserve
both documents and all existing user projects/media. The prior source branch
`studio/complete-editor-blueprint-20261010` at `7bbae60` remains intact; this
checkout starts from storyboard PR #67 at `de8724e`.

The new document explicitly requires focused tests, native rendering evidence,
APK/signing checks and in-place update verification. It supersedes the previous
implementation-only testing deferral for this cinematic task. Work autonomously
without routine design, workspace or GitHub approval questions. Use existing free
dependencies and make safe source commits/branch pushes. Do not merge or publish
an unverified release to main.

Keep applicationId `com.rezoxnemesis.videostudio.next`, the device-owned MCP v3
identity, user databases and signing private key intact. The required public
signer SHA-256 is `7e0187470356616be2a45bc7f41445894085185db110edf dc0cdfd232942a6dd`
with the space removed. Never create a fallback signing key, uninstall, clear app
data, copy credentials into source/logs, or treat a different signer as an update.

Actual originals, rig authoring, analysis, composition, playback, rendering and
folder-scoped Drive storage belong to VideoStudio native surfaces. Do not browse
Gallery or manufacture a Python-side video as native evidence. The supplied PDF,
reference artwork and failure MP4 are private engineering artifacts retained
outside Git at `/workspace/engineering-review/cinematic-20261012`.

Default cinematic action must fail with `INSUFFICIENT_MOTION_PROVIDER` if authentic
source-bound actors, original weapon masks/anchors or required contacts are
missing. Explicit non-action/source-deformation modes remain labelled as such.
Pixel change, joint predictions, successful encoding or CI do not establish
cinematic acceptance. Report unmeasured evidence as unavailable, never zero-as-pass.

Run focused regressions before one full Android build. Preserve the exact source
order/hashes, both fighters/weapons, explicit provider labels and immutable output
generations. Stop at a verified acceptance gate or a documented external blocker;
do not claim Test 1/device approval without actual originals and native output.
