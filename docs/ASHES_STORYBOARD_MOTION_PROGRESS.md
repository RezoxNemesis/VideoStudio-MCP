# Ashes storyboard native animation — 3.5.x engineering progress

## Verified source and baseline
Three ordered images represent opening, sword clash, and aftermath; they are storyboard states, not adjacent frames. A 33-frame optical-flow test showed a long hold and a hard visual jump. A 150-frame native combat-rig test showed genuine articulated motion but generic thin stick figures and ghosts in the estimated fixed city plate.

## Implemented on this feature branch
- Thicker shaded IK-connected limbs, radial 3D head materials and continuous tapered metallic sword blades.
- Optional storyboardMotion:true on the existing animate_combat_rig native action. It preserves the default benchmark but retimes the three-beat attack and sends the white fighter backward into a low stagger.
- A restricted temporal-median foreground outlier pass which reduces repeated black/white fighter silhouettes wherever real alternative source pixels exist. This is not neural inpainting. Three images are now sufficient for a bounded background ESTIMATE.
- Pose PNG and combat-rig JPEG files saved into creative_workspace/projects/<projectId>/generated/ so the selected folder-scoped Drive backup sees them.
- The exact VideoStudio-generated final MP4 is archived through a previously published MediaStore URI, with explicit ownership filtering and no Gallery enumeration.

## Native MCP after the upgraded APK is installed
Action: animate_combat_rig
nativeParameters: sourceProjectId=<project containing all three uploaded storyboard images>, backgroundMode=source_median, storyboardMotion=true, durationSeconds=5, fps=30, width=540, height=960, quality=720p, render=true.

## Required acceptance
- Android unit tests and real signed APK build must pass before installing.
- Render a fresh project on 3.5.x+ and visually analyze at least 12 to 16 source-contact-sheet frames plus the MP4.
- Check wrist/leg joints, planted feet, blade contacts, rebound and knockback, source city stability, missing duplicates, output orientation and linked-Drive frame/MP4 storage.
- Never claim image-faithful full cinematic animation until visually tested: exact character appearance registration, hidden-body reconstruction and consistent camera geometry across independent drawings are still unsolved. Follow-up: GitHub issue #51.

Everything executes through VideoStudio; Gallery browsing is permanently disabled.