# VideoStudio Creative Engine Roadmap

## Goal

Turn VideoStudio from a conventional editor with local AI helpers into a programmable, local-first creative engine that ChatGPT can drive end-to-end from natural-language prompts.

The design must stay usable without paid cloud services. Heavy assets and models live in app-managed device storage as optional model/content packs so the APK can remain reasonably small.

## Foundation status

The first executable foundation slice is now present in the v3.3 code line:

- [x] safe MotionScript 0.1 parser/compiler
- [x] versioned CreativeIR JSON output
- [x] app-private creative workspace and atomic scene persistence
- [x] generated-output registration into project media
- [x] editor Media Bin and timeline insertion path
- [x] stage-level heavy-job checkpoints
- [x] thermal/memory wait states
- [x] durable action/parameter recovery plans for animation, prompt-video and export
- [x] published-output recovery to avoid intentional duplicate rerenders after restart
- [x] capability-first provider registry
- [x] optional installed model-manifest discovery
- [x] typed MCP tools for MotionScript/workspace/provider inspection
- [ ] transactional model-pack installer
- [ ] native Android Google Drive storage provider
- [ ] Rust/native MotionScript compiler runtime
- [ ] body/hand pose provider
- [ ] monocular depth provider beyond current layered analysis
- [ ] optical-flow deformation provider
- [ ] frame interpolation provider
- [ ] local image-generation provider
- [ ] local image-to-video provider
- [ ] 3D mesh runtime
- [ ] local multi-voice narration stack
- [ ] full automated visual critique/targeted repair loop

Unchecked items are explicitly future work and must not be represented as already implemented.

## Core architecture

### 1. MotionScript scene compiler

A constrained VideoStudio-specific scene language, not arbitrary shell/code execution.

Input:
- natural-language prompt
- imported stills/videos
- optional style/motion/voice directives

Compiler output:
- scene graph
- timeline
- camera tracks
- subject rigs
- depth layers
- effect graph
- particle systems
- lighting cues
- audio/narration plan
- render graph

The compiler is deterministic, inspectable, resumable and safe to execute locally.

### 2. Multi-domain animation engine

The engine should support the same scene graph across several visual modes:

- photorealistic 2.5D portrait animation
- 2D cutout/puppet animation
- cel/cartoon rendering
- motion graphics
- depth-to-mesh pseudo-3D scenes
- GLTF/mesh-based 3D scenes
- mixed 2D/3D compositing

For still-image animation, build:
- subject/background segmentation
- face landmarks
- optional body/pose landmarks
- depth estimation
- optical-flow-assisted warp fields
- head/hair, torso, arms/hands and lower-body region rigs
- cloth/hair spring simulation
- eye/blink and subtle expression channels where supported
- parallax camera
- environment motion
- particle/atmosphere layers
- frame interpolation

### 3. Local generative model runtime

A model registry should allow optional downloadable modules without forcing them into the base APK.

Candidate roles:
- image generation
- image-to-image refinement
- depth estimation
- pose estimation
- segmentation
- optical flow
- frame interpolation
- super-resolution
- inpainting/background reconstruction
- lightweight image-to-video models where hardware allows
- speech synthesis
- speech recognition
- audio cleanup

Each module exposes capability, RAM/VRAM estimate, storage size, quantization and compatible device backends.

### 4. Prompt-to-video director

A prompt becomes a structured screenplay and shot plan before rendering.

Pipeline:
1. understand prompt
2. choose visual mode
3. break into shots
4. create or reuse assets
5. generate MotionScript
6. compile to native scene graph
7. render preview
8. inspect continuity
9. revise weak shots
10. final render/export

ChatGPT should be able to perform this loop without repeatedly asking the user to drive the editor.

### 5. Native temporary workspace

Use app-private device storage for:
- model packs
- generated images
- masks
- depth maps
- pose data
- meshes
- motion fields
- intermediate frames
- audio stems
- compiled scene data
- checkpoints
- render caches

Expose:
- automatic cleanup after successful export
- retention options
- per-project workspace size
- manual Clear Workspace
- crash-safe checkpoint/resume

### 6. Voice, narration and action

Audio pipeline:
- script generation
- local TTS narration
- per-character voices
- timing/alignment
- ducking
- music/SFX layers
- lip-sync cue generation where a face rig supports it

Action pipeline:
- pose keyframes
- camera choreography
- rig constraints
- IK-style limb targets where available
- procedural impacts, shakes, wind, rain, water and debris
- motion interpolation and continuity checks

### 7. Stability requirements

Heavy generation must never behave like the current all-or-nothing animation job.

Every heavy job should:
- checkpoint per asset/shot
- yield when thermal state becomes unsafe
- resume automatically
- keep intermediate outputs
- report exact stage and percentage to Activity
- never disappear from the editor after completion
- automatically attach successful output to the project media bin
- optionally insert rendered output into the active timeline

### 8. MCP surface

Keep the stable private v3 endpoint and generic app_execute bridge.

Add native actions behind it over time:
- compile_scene
- run_motion_script
- generate_asset
- generate_voice
- build_character_rig
- estimate_depth
- build_depth_mesh
- interpolate_frames
- render_scene
- render_sequence
- cleanup_workspace
- workspace_status
- install_model_pack
- model_pack_status

Gallery enumeration remains permanently blocked.

## Product principle

VideoStudio should not pretend procedural motion is the same thing as a frontier video-diffusion model. Instead, it should combine local AI, procedural animation, 2D/3D rendering, compositing, audio and optional downloadable generative modules into one controllable system. The result is broader and more editable than a single opaque prompt-to-video call, while remaining local-first and zero-paid-service by default.
