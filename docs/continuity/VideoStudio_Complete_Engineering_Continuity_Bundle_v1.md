# VideoStudio Complete Engineering Blueprint & Continuity Bundle

**Target baseline:** VideoStudio Android Native Agent 3.5.0  
**Document role:** single source of truth for product, architecture, engineering, MCP autonomy, storage, generation, animation, editing, testing, and continuity.  
**Primary product principle:** VideoStudio must be a professional editing and generation studio that is equally usable by the human owner and ChatGPT. ChatGPT is an autonomous co-editor, not the only usable editor.

---

## 0. Executive mandate

VideoStudio must evolve from an agent-first proof-of-concept into a full professional non-linear editor, animation studio, VFX compositor, media generator, audio studio, and autonomous production system.

The finished app must satisfy all of the following simultaneously:

1. **Human-first professional editor:** the owner can import, inspect, preview, trim, split, move, layer, animate, grade, mix, generate, render, export, recover, and manage projects without ChatGPT.
2. **Equal ChatGPT capability:** ChatGPT can use the same editing/generation engine through a stable MCP control plane with command-level auditability, idempotency, recovery, and user revocation.
3. **No Gallery enumeration by MCP:** ChatGPT never lists or browses the phone Gallery or arbitrary Files. The owner may pick files through Android system pickers; once explicitly imported, they become VideoStudio-owned assets and can be used by ChatGPT.
4. **Real editing, not metadata theatre:** every reported edit must correspond to actual timeline/project state and every completed render must be verified as a playable media file.
5. **Real preview:** the preview canvas must display selected media and timeline output immediately. A black canvas with hidden work is unacceptable.
6. **Manual actions are immediate:** ordinary owner actions such as selecting, trimming, splitting, previewing, importing, saving, and starting an export must not be unnecessarily routed through the autonomous background queue.
7. **Heavy work is resumable:** generation, VFX, proxy building, 3D, optical flow, model inference, and long exports may use background jobs, but every job must checkpoint and recover.
8. **10–20 GB class projects/files must be supported:** the architecture must avoid loading entire media files into RAM and must use chunked I/O, proxies, indexes, resumable transfer, and spill-to-disk.
9. **Industry-level direction:** target professional animation, CGI, VFX, cinematic editing, manhwa recap production, human/character animation, 2D/3D scenes, image-to-video, prompt-to-video, and multi-image animation.
10. **Honest capability semantics:** if a function is procedural, label it procedural. If a model pack is required, expose that requirement. Never pretend abstract geometry is photorealistic text-to-video.
11. **Security and owner supremacy:** the owner can pause, revoke, restrict, or terminate ChatGPT control at any time, while retaining complete manual access.
12. **Persistent autonomy:** ChatGPT work survives temporary Android sleep/offline states through durable queues and, when the project is cloud-mirrored, through a cloud executor that can work on already-synced VideoStudio-owned assets.

---

# 1. Current baseline and known gaps

## 1.1 Current strengths to preserve

VideoStudio 3.5.0 already demonstrates several important foundations:

- stable device-owned MCP v3 endpoint across compatible APK upgrades;
- Android Native Agent with app-local project ownership;
- durable command queue and reconnect behavior;
- explicit no-Gallery privacy boundary for MCP;
- owner permission mode and ChatGPT activity logging;
- native Media3/MediaCodec based MP4 rendering;
- prompt-video procedural scene generation;
- CreativeIR / job graph concepts;
- motion/effect preset catalog;
- native media analysis/contact-sheet sampling;
- internal storage project persistence;
- thermal, RAM, storage, and heavy-lane guards;
- command idempotency and recovery plans;
- explicit final-render assets and playable-output verification;
- background Native Agent control and autonomous job execution.

These are not to be thrown away. The next architecture must harden and generalize them.

## 1.2 Current problems that must be treated as release blockers

- The human owner cannot reliably perform the same work as ChatGPT.
- Preview often appears black or does not bind to the selected clip/timeline.
- Manual export can remain queued because user commands are conflated with autonomous heavy jobs.
- The timeline is too card-like and not a real multi-track editor.
- Media Bin lacks mature professional interactions.
- Generated-video results may be procedural but are presented too similarly to model-based video generation.
- Image-to-video is not yet true frame synthesis with temporal consistency.
- Large file behavior is not yet engineered for 10–20 GB workloads.
- Codec failures on specific devices can terminate complex renders.
- Cloud storage is not yet a first-class project filesystem.
- Offline autonomy is control-plane durable but not full compute parity when Android is unavailable.
- Project/context identity issues can arise after service restarts or project creation/import races.
- Some activity/status semantics remain too closely tied to queue state rather than user-visible editor state.

---

# 2. Product architecture: seven cooperating engines

VideoStudio should be divided into seven major subsystems with strict boundaries.

## 2.1 Studio UI Shell

Owns:

- Home / project browser
- Editor
- Media Bin
- Timeline
- Preview monitor
- Inspector
- Effects browser
- Animation/keyframe editor
- Audio mixer
- Colour page
- Fusion/VFX-style node compositor page
- AI Tools page
- Export page
- Storage Hub
- Activity / audit page
- Control / permissions page

The UI must never be the source of truth. It renders the same persistent project model used by ChatGPT and background workers.

## 2.2 Project Graph Core

A persistent, transactional project model containing:

- assets;
- media references;
- proxy references;
- tracks;
- clips;
- layers;
- transitions;
- effects;
- keyframes;
- masks;
- transforms;
- audio automation;
- colour nodes;
- 2D rigs;
- 3D scene references;
- generated assets;
- prompts;
- model provenance;
- render settings;
- undo/redo transactions;
- collaboration/autonomous command history.

All mutations are transactions. UI, ChatGPT, background jobs, and recovery code must mutate through the same transaction API.

## 2.3 Real-Time Playback & Preview Engine

Responsibilities:

- decode timeline around the playhead;
- adaptive proxy selection;
- GPU compositing;
- audio sync;
- frame dropping without timeline drift;
- reduced-quality previews while scrubbing;
- full-quality paused-frame previews;
- prefetching around current playhead;
- thumbnail/waveform cache;
- safe fallback to software decode for problematic codecs;
- accurate orientation/rotation handling;
- selection overlay, bounding boxes, masks, guides and safe areas.

Preview must be independent from final render jobs.

## 2.4 Offline/Final Render Engine

Responsibilities:

- deterministic timeline evaluation;
- frame-accurate effects;
- transitions;
- colour pipeline;
- audio resample/mix;
- GPU/Vulkan/OpenGL rendering;
- MediaCodec/Media3 encoding;
- software fallback encoding when a hardware codec crashes;
- segmented render/checkpoints;
- resumable export;
- verified playable output;
- publish to internal/external/cloud destination;
- post-render analysis/critique.

## 2.5 Generation & Animation Engine

A pluggable runtime for:

- prompt-to-video;
- image-to-video;
- multiple-images-to-video;
- procedural motion graphics;
- 2D animation;
- 3D animation;
- human/character animation;
- talking-head/lip sync;
- frame synthesis/interpolation;
- transformation/morph sequences;
- AI B-roll generation;
- background generation/replacement;
- voice narration and dubbing;
- sound effects/music stems;
- storyboard-to-video.

## 2.6 Storage Fabric

A unified virtual filesystem across:

- app internal storage;
- user-selected external storage / SD card / USB OTG through SAF;
- up to five cloud storage connections;
- optional VideoStudio cloud workspace mirror;
- cache/proxy store;
- chunked archive store for very large assets.

## 2.7 MCP / Autonomous Control Plane

A secure, durable command system that gives ChatGPT the same project operations as the owner, while preserving owner supremacy and privacy boundaries.

---

# 3. Real professional NLE editor

## 3.1 Preview monitor

Required capabilities:

- actual video/image frame visible at all times when a valid clip is selected;
- timeline-program output view and source-media view;
- play/pause, frame step, 5s/10s jump, J-K-L shuttle, loop;
- timecode;
- resolution/proxy indicator;
- full-screen preview;
- pinch-to-zoom/pan;
- transform handles;
- crop handles;
- mask/path overlays;
- safe areas, grid, thirds, horizon;
- before/after effect compare;
- colour scopes overlay;
- subtitle safe region;
- keyframe path visualization;
- audio meters.

A project with media but a black monitor is a release-blocking bug.

## 3.2 Multi-track timeline

Minimum professional track types:

- Video
- Image/Graphics
- Adjustment layer
- Text/Subtitle
- 2D animation
- 3D scene
- VFX/node-composite output
- Audio music
- Audio dialogue
- Audio SFX
- Voice-over
- Automation/control track

Timeline interactions:

- drag/drop clips;
- trim heads/tails;
- ripple trim;
- roll trim;
- slip;
- slide;
- razor/split;
- ripple delete;
- magnetic snapping;
- linked audio/video;
- unlink;
- compound clips;
- nested sequences;
- groups;
- markers;
- range selection;
- track lock/mute/solo/visibility;
- waveform display;
- video thumbnails;
- adjustable track height;
- pinch timeline zoom;
- move/copy/paste;
- duplicate;
- speed ramps;
- retime curves;
- freeze frame;
- reverse;
- optical-flow slow motion.

## 3.3 Inspector

Per-clip inspector:

- position X/Y/Z;
- scale X/Y;
- rotation;
- anchor point;
- opacity;
- crop;
- blend mode;
- speed;
- audio volume/pan;
- keyframes;
- stabilization;
- reframe;
- mask;
- chroma key;
- tracking;
- effects stack;
- colour override;
- transform motion blur;
- proxy/full-res toggle.

Every numeric property that can animate must expose a keyframe diamond and curve/easing editor.

## 3.4 Undo/redo and autosave

- command-pattern transactions;
- unlimited practical undo within configured history budget;
- crash-safe journal;
- autosave every significant transaction;
- named snapshots;
- project restore after process kill;
- project schema migrations with backups.

---

# 4. Separate manual and autonomous execution lanes

This is critical to making the app feel owned by the user.

## 4.1 Manual lane

Manual operations should call the editor engine directly whenever safe:

- select media;
- preview;
- split;
- trim;
- move clips;
- change keyframes;
- edit effect parameters;
- import via user picker;
- save;
- start export;
- cancel export;
- render preview;
- play generated result.

Manual export gets foreground priority. It may still become a job internally, but the UI must immediately transition to an explicit export session rather than showing an unexplained “queued” state.

## 4.2 Autonomous lane

ChatGPT commands are durable, idempotent, auditable and may be backgrounded. They use the same project transaction API but include:

- command ID;
- project ID;
- expected project revision;
- user permission scope;
- job class;
- recovery checkpoint;
- resulting revision;
- produced assets;
- proof/verification state.

## 4.3 Arbitration

When owner and ChatGPT edit simultaneously:

- project revisions prevent silent overwrite;
- UI changes take immediate local priority;
- ChatGPT command detects revision conflict and rebases if safe;
- destructive conflicts require a branch/snapshot, not data loss;
- owner can pause ChatGPT without pausing manual work.

---

# 5. Preview/proxy architecture for 10–20 GB media

Never decode or read a huge asset in one operation.

## 5.1 Import pipeline

1. Resolve URI or VideoStudio cloud reference.
2. Read metadata only.
3. Create project asset row immediately.
4. Build seek index in chunks.
5. Generate low-resolution thumbnails lazily.
6. Generate waveform lazily.
7. Optionally generate edit proxy.
8. Keep original untouched.

## 5.2 Proxy tiers

- **Proxy L0:** 240p/360p lightweight scrub proxy.
- **Proxy L1:** 540p/720p editing proxy.
- **Proxy L2:** full-resolution original.

The engine selects tier based on playback speed, device thermals, effect complexity and zoom level.

## 5.3 Large file I/O

- ranged reads;
- memory-mapped indexes, not whole media;
- 8–64 MB buffered reads depending device;
- resumable cloud range fetch;
- LRU cache;
- content hash dedupe;
- background prefetch;
- chunk checksums;
- spill intermediate frames to disk;
- automatic cache purge with project pinning.

## 5.4 Segmented render

Long timelines render in segments, e.g. 2–10 seconds per segment depending complexity. Each segment records:

- input project revision;
- start/end time;
- decoder state;
- effect graph hash;
- codec settings;
- output checksum.

If a codec fails at 62%, the engine resumes from the most recent segment rather than re-rendering from zero.

---

# 6. Codec resilience

VideoStudio must not depend on one hardware codec path.

Priority ladder:

1. hardware decoder/encoder preferred;
2. retry with conservative MediaCodec configuration;
3. use software decoder for problematic source codec;
4. pre-transcode only the problematic source into an edit mezzanine;
5. use segmented render to reduce codec lifetime pressure;
6. degrade preview quality before degrading final quality;
7. never silently mark failed output successful.

Maintain a per-device codec reliability table keyed by codec implementation name, resolution, profile, frame rate and failure signature.

---

# 7. Image-to-video: real animation, not moving stills

The target pipeline for cartoon/anime/illustration and photographic images is:

## 7.1 Analysis

- subject segmentation;
- multi-object masks;
- face landmarks;
- body pose;
- hand pose where visible;
- depth estimation;
- occlusion map;
- hair/clothing soft masks;
- background layers;
- semantic regions;
- motion affordance map.

## 7.2 Layer decomposition

Separate controllable layers such as:

- head;
- eyes;
- pupils;
- mouth/jaw;
- hair front/back;
- torso;
- left/right arms;
- left/right hands;
- legs;
- clothing panels;
- held objects;
- foreground particles;
- background;
- lighting overlays.

For cartoon frames, use edge-aware matte cleanup to preserve line art.

## 7.3 Motion planning

A motion planner converts a natural-language prompt into:

- camera path;
- body motion;
- facial motion;
- object motion;
- secondary motion;
- timing beats;
- easing curves;
- occlusion events;
- motion blur policy;
- frame synthesis strength.

Example: “The cartoon character notices the laptop fire, eyes widen, head recoils, mouth opens, body leans backward, smoke rises, flames flicker, camera subtly pushes in.”

## 7.4 Frame synthesis

Three quality levels:

### Level A: deterministic articulated animation

- mesh warp;
- skeletal/bone rig;
- depth parallax;
- face deformation;
- particle/fire/smoke simulation;
- deterministic and fast.

### Level B: learned motion refinement

- optical flow;
- occlusion-aware warping;
- inpainting for newly exposed regions;
- temporal denoise;
- RIFE-like frame interpolation;
- line-art preservation for cartoons.

### Level C: generative temporal synthesis

- video diffusion/DiT model pack;
- reference-image conditioning;
- pose/depth/edge control;
- identity/style lock;
- temporal attention/consistency;
- latent continuation in chunks;
- overlap-frame stitching.

This is the level required for genuinely new limb poses, turning, complex transformations and cinematic human motion.

## 7.5 Consistency locks

- face/character identity embedding;
- palette lock;
- line-art lock;
- costume lock;
- object identity lock;
- reference frame bank;
- hand correction pass;
- temporal flicker score;
- automatic regeneration of unstable spans only.

---

# 8. Multi-image to real animation

A sequence of images should become a coherent animation, not a slideshow.

Pipeline:

1. detect subjects shared between images;
2. identify scene order;
3. estimate camera and pose change;
4. generate motion paths between key images;
5. synthesize in-between poses/frames;
6. interpolate 24/30/60 fps;
7. maintain identity/style;
8. add camera movement and parallax;
9. generate background transitions when required;
10. synchronize narration/music/effects.

Support “storyboard mode”: each image is a keyframe/shot target, not just a 2-second still.

---

# 9. Prompt-to-video architecture

VideoStudio should expose multiple generator classes rather than one misleading “AI Video” button.

## 9.1 Procedural generator

Strengths: fast, deterministic, offline, motion graphics, abstract scenes, titles, HUD, particles, geometry.

Label clearly: **Procedural Video**.

## 9.2 2D generative animation

- prompt + character references;
- pose control;
- line-art/style consistency;
- background generation;
- camera movement;
- lip sync;
- frame continuation.

## 9.3 Photorealistic/human video generator

Requires model packs or a capable remote GPU executor. Features:

- reference identity conditioning;
- body motion / pose sequence;
- hand-aware refinement;
- facial consistency;
- wardrobe/object persistence;
- camera/lens controls;
- temporal coherence;
- shot continuation;
- upscaling and frame interpolation.

## 9.4 3D scene generator

Text generates:

- primitive/blockout scene;
- camera path;
- lighting;
- materials;
- particles;
- physics;
- animation curves;
- optional model retrieval/import from user-owned libraries.

Then render with a real 3D renderer, not a 2D slideshow.

## 9.5 Story generator

Prompt can expand into:

- concept;
- screenplay;
- shot list;
- storyboard;
- asset list;
- generation prompts;
- voice script;
- sound design plan;
- edit graph;
- final render.

---

# 10. 2D animation studio

Required professional capabilities:

- raster and vector layers;
- onion skin;
- frame-by-frame drawing;
- exposure sheets;
- keyframes;
- tweening;
- motion paths;
- bone rigs;
- mesh deformation;
- IK chains;
- mouth phoneme library;
- blink/eye controls;
- squash/stretch;
- secondary motion;
- paint bucket with gap tolerance;
- vector stroke editing;
- camera multiplane;
- cut-out animation;
- particle layers;
- reusable character rigs;
- pose library;
- lip sync from voice track;
- audio waveform frame alignment.

Character assets from licensed/user-provided references can be rigged while preserving design consistency.

---

# 11. 3D animation / CGI engine

## 11.1 Scene formats

Prioritize:

- glTF/GLB;
- USD/USDZ where feasible;
- OBJ;
- FBX through supported importer/library;
- Alembic/cache support for advanced workflows;
- image-based HDRI environments.

## 11.2 Core 3D features

- PBR materials;
- skeletal animation;
- IK/FK;
- blend shapes;
- morph targets;
- camera rigs;
- lights;
- depth of field;
- motion blur;
- shadows;
- reflections;
- particles;
- rigid bodies;
- cloth approximation;
- hair cards/particles;
- volumetric fog;
- environment maps;
- render passes.

## 11.3 Transformation sequences

For vehicle/robot/creature transformations:

- hierarchical rigs;
- constrained bone chains;
- morph targets;
- object-parent switching;
- visibility animation;
- procedural debris/sparks;
- camera shake;
- speed ramps;
- contact shadows;
- audio cue synchronization.

The transformation is authored as a deterministic 3D timeline where possible, with generative refinement used as an optional polish layer.

---

# 12. VFX / compositing system

A professional node graph page should support:

- media input;
- transform;
- merge/composite;
- masks;
- rotoscoping;
- chroma key;
- difference key;
- luma key;
- garbage matte;
- planar tracking;
- point tracking;
- camera tracking;
- stabilization;
- corner pin;
- lens distortion;
- glow/bloom;
- blur/sharpen;
- displacement;
- turbulence/noise;
- particles;
- depth fog;
- motion blur;
- optical flow;
- object removal;
- clean plate generation;
- relighting;
- depth-based compositing;
- 3D scene node;
- text node;
- colour node;
- render pass merge;
- AI inpaint/outpaint node;
- generative fill node;
- temporal consistency node.

Node graphs are first-class project assets and can be edited by owner or ChatGPT.

---

# 13. Movie-grade colour system

- lift/gamma/gain;
- exposure/contrast/pivot;
- temperature/tint;
- HSL;
- hue curves;
- luma curves;
- RGB curves;
- wheels;
- qualifiers;
- power windows/masks;
- tracking;
- LUT import/export;
- ACES-like color management option;
- HDR metadata path where hardware supports;
- scopes: waveform, RGB parade, vectorscope, histogram;
- shot-match;
- skin-tone protection;
- automatic colour consistency across generated shots.

---

# 14. Professional audio and voice system

## 14.1 Editing/mixing

- multitrack audio;
- sample-accurate cuts;
- fades/crossfades;
- EQ;
- compressor;
- limiter;
- gate;
- de-esser;
- reverb;
- delay;
- noise reduction;
- loudness normalization;
- ducking;
- side-chain;
- stereo pan;
- voice isolation;
- music/SFX stems;
- room tone;
- sync by waveform;
- time stretch/pitch preserve.

## 14.2 Voice narration

Support a Voice Model Registry:

- local lightweight TTS voices;
- expressive neural TTS packs;
- multilingual voices;
- emotional/style controls;
- pace, pitch, breath, pause controls;
- SSML-like pronunciation controls;
- project voice presets;
- consent-gated custom voice cloning;
- narration regeneration by sentence;
- automatic caption generation from narration.

Never bundle unauthorized replicas of real people’s voices.

## 14.3 Speech recognition

- local ASR model;
- speaker diarization optional;
- transcript editing;
- word-level timecodes;
- auto subtitles;
- translation/dubbing pipeline.

---

# 15. Manhwa / manga recap production engine

A dedicated workflow should automate long-form and short-form recap production.

Pipeline:

1. import user-owned/authorized chapter pages;
2. panel detection;
3. OCR;
4. reading order;
5. character/entity tracking;
6. scene grouping;
7. story summarization;
8. recap script;
9. narration generation;
10. music/SFX plan;
11. panel animation using parallax, crops, synthesized in-betweens and effects;
12. subtitles;
13. chapter cards;
14. pacing optimization;
15. render long-form and Shorts/Reels variants.

Advanced mode can synthesize limited motion between panels while preserving artwork style and character identity.

---

# 16. Model Engine Registry

The app must not hard-code one AI model. Define a capability registry with downloadable/replacable model packs.

## 16.1 Model classes

### Vision analysis

- subject segmentation;
- instance segmentation;
- face mesh/landmarks;
- human pose;
- hand pose;
- depth;
- optical flow;
- object detection;
- object tracking;
- OCR;
- scene classification.

### Image/video generation

- text-to-image;
- inpaint/outpaint;
- text-to-video;
- image-to-video;
- video-to-video;
- pose/depth/edge controlled generation;
- style-preserving cartoon/anime generation;
- human identity reference conditioning.

### Temporal processing

- interpolation;
- deblur;
- denoise;
- super-resolution;
- frame restoration;
- flicker correction;
- temporal consistency scoring.

### Audio

- ASR;
- TTS;
- source separation;
- denoise;
- music generation adapter;
- SFX generation adapter;
- lip sync.

## 16.2 Pack metadata

Every model pack declares:

- model ID/version;
- license;
- task capabilities;
- expected RAM/VRAM;
- device backend;
- quantization;
- download size;
- hash/signature;
- supported resolutions;
- expected speed;
- safety constraints;
- offline/online requirement.

## 16.3 Execution backends

- CPU;
- GPU/Vulkan compute;
- Android NNAPI/NPU where available;
- local desktop/cloud companion;
- Work-mode/cloud GPU executor when explicitly connected.

VideoStudio chooses the best backend through a Compute Planner.

---

# 17. Multi-cloud storage fabric: five connections + local storage

## 17.1 Storage slots

Support at least five simultaneously connected cloud storage profiles. Providers may include:

- Google Drive;
- Microsoft OneDrive;
- Dropbox;
- Box;
- S3-compatible/WebDAV/pCloud-style provider through adapters.

A user may connect multiple accounts of a provider if the provider permits it.

**Important engineering rule:** never hard-code “15 GB”. Show the actual provider-reported quota. A target of five approximately 15 GB accounts may provide roughly 75 GB logical capacity when those accounts actually have that quota, but VideoStudio must not fake or bypass provider limits/terms.

## 17.2 Storage Hub UI

For each slot display:

- provider;
- account label;
- used/free/total;
- connection health;
- upload/download speed;
- sync state;
- project pinning;
- default roles: source / proxy / cache / export / archive.

## 17.3 VideoStudio Vault

For very large assets, optionally use an app-managed chunk format:

- 256 MB–1 GB chunks;
- SHA-256 per chunk;
- manifest with order/size/hash/provider location;
- optional encryption;
- chunks may live across different authorized cloud slots;
- resumable upload/download;
- local reassembly or streaming range reads;
- replication of critical chunks/manifest.

This allows a project larger than one account’s remaining quota to be spread across authorized storage locations without pretending it is one provider-native file.

## 17.4 Local/external storage

Support:

- internal app storage;
- user-selected external directory via Android SAF;
- SD card;
- USB OTG storage;
- project relocation;
- portable project package;
- cache on internal, originals on external/cloud.

## 17.5 Storage policy

- originals are never deleted automatically;
- proxies can be regenerated;
- cache can be purged;
- cloud operations are checksummed;
- every file move is transactional;
- project never points to a silently missing file;
- offline placeholders show sync state clearly.

---

# 18. Cloud project mirror for offline ChatGPT autonomy

A durable MCP queue alone cannot execute native functions when Android is actually offline. To allow real work while the phone is offline, add a **Cloud Project Mirror + Cloud Executor**.

## 18.1 Sync model

User explicitly enables “Offline ChatGPT Work” per project.

The app syncs:

- project graph;
- proxies;
- selected originals or required chunks;
- generated assets;
- model-independent metadata;
- render intermediates if desired.

It never uploads Gallery data that the user did not import.

## 18.2 Cloud executor

Can perform:

- timeline edits;
- metadata/index work;
- proxy generation;
- CPU/GPU effects where available;
- model generation;
- cloud render;
- audio processing;
- project analysis;
- storage movement;
- export to cloud storage.

Native-only operations remain queued for device reconnect.

## 18.3 Reconciliation

When Android reconnects:

- compare project revision;
- merge non-conflicting edits;
- create branch/snapshot on conflict;
- download changed assets lazily;
- preserve local manual edits;
- show a reconciliation summary.

---

# 19. MCP v4 target: permanent direct control plane

Keep the stable endpoint concept but evolve the protocol.

## 19.1 Endpoint identity

Pattern:

`https://<worker-host>/app-mcp-v4/<DEVICE_OWNER_KEY>`

Treat the owner key as a credential. Never place a real key in source control or documentation bundles.

## 19.2 Connection guarantees

- stable device-owner identity across compatible APK upgrades;
- protocol min/max negotiation;
- additive schema evolution;
- app-generation fencing;
- device rebinding with owner authorization;
- durable queue;
- leased commands;
- exactly-once/idempotent command journal;
- command result verification;
- reconnect backoff;
- worker/Durable Object compatibility during rolling deploys;
- command schema introspection;
- capability negotiation.

## 19.3 Command families

### Project

- create/open/rename/archive/duplicate/delete;
- snapshot/restore;
- list project-owned assets;
- select sequence;
- project status.

### Import

- explicit ChatGPT attachment;
- owner-picked Android file;
- HTTPS import;
- connected cloud storage import;
- generated media import;
- no Gallery enumeration.

### Timeline

- add/remove/move/split/trim clip;
- create track;
- set transition;
- set speed/retime curve;
- keyframes;
- compound/nested sequence;
- markers.

### Effects/VFX

- apply/remove effect;
- masks;
- trackers;
- keying;
- node graph mutations;
- 3D scene operations;
- particles;
- adjustment layers.

### Generation

- prompt-to-video;
- image-to-video;
- multi-image animation;
- storyboard-to-video;
- text-to-image;
- inpaint/outpaint;
- human/character animation;
- voice generation;
- lip sync;
- music/SFX.

### Audio

- transcription;
- narration;
- denoise;
- stem separation;
- mix automation;
- captions.

### Render

- preview render;
- final render;
- render segment;
- resume render;
- inspect/critique render;
- export destination;
- verify playable result.

### Storage

- connect storage profile only through owner-approved OAuth/UI;
- sync project;
- move/copy VideoStudio-owned media;
- archive/restore;
- quota/health.

## 19.4 Owner control

The app exposes:

- **Disconnect ChatGPT**
- **Pause ChatGPT**
- **Allow this project only**
- **Allow selected assets only**
- **Full Autonomous**
- **Revoke endpoint key / rotate owner key**
- **Cancel all ChatGPT jobs**
- **View audit log**

Manual editing always remains available.

---

# 20. Security model

## 20.1 Data boundaries

MCP can access only:

- VideoStudio project database;
- VideoStudio-owned media;
- user-explicitly imported attachments;
- user-connected cloud storage through VideoStudio’s storage abstraction;
- generated assets.

MCP cannot enumerate:

- Android Gallery;
- arbitrary Documents;
- unrelated app storage;
- contacts/messages/accounts.

## 20.2 Cryptography

- Android Keystore device owner secret;
- signed device registration;
- TLS;
- short-lived OAuth tokens;
- encrypted refresh token storage;
- optional per-project encryption keys;
- encrypted Vault chunks;
- key rotation and revocation.

## 20.3 Audit

Every autonomous action records:

- actor;
- command ID;
- project;
- input assets;
- resulting revision;
- job IDs;
- output assets;
- model/provider used;
- start/end;
- status;
- failure reason.

---

# 21. Stability architecture

## 21.1 Job classes

- UI-immediate
- light background
- heavy local
- cloud eligible
- device-only

## 21.2 Checkpoints

Heavy jobs must checkpoint at meaningful boundaries, not only percentages.

Examples:

- model loaded;
- analysis complete;
- shot N generated;
- render segment N complete;
- audio mix complete;
- upload chunk N complete.

## 21.3 Watchdogs

- Native Agent watchdog;
- render watchdog;
- stalled progress detector;
- codec failure classifier;
- storage-space guard;
- thermal guard;
- memory guard;
- stuck cloud transfer detector;
- stale command lease recovery.

## 21.4 Failure semantics

Never report “completed” because a job was queued.

Statuses:

- queued;
- waiting_native;
- waiting_thermal;
- waiting_memory;
- waiting_storage;
- waiting_network;
- preparing;
- running;
- checkpointed;
- verifying;
- completed;
- failed;
- cancelled.

Completed means the requested state or output has been verified.

---

# 22. Performance targets

These are engineering targets, not guarantees on every device.

- editor launch < 2 s on modern mid/high devices after warm cache;
- project open < 3 s for indexed project;
- timeline scrub response < 100 ms using proxies;
- manual clip trim UI < 16–32 ms response;
- preview start < 500 ms from cached proxy;
- background import does not block UI;
- 10–20 GB media metadata import completes without copying entire file;
- memory usage bounded by cache policy;
- long renders resume from segment checkpoints;
- command round-trip visible in Activity within ~1 s while connected;
- manual export never waits behind unrelated autonomous generation if a render lane is available;
- decoder fallback after repeated device-specific codec failure.

---

# 23. UI redesign: make it a real studio

## 23.1 Main Editor layout

Top:

- project name;
- save/sync state;
- undo/redo;
- preview/export.

Center:

- large preview monitor.

Bottom:

- professional multitrack timeline.

Side/slide panels:

- Media;
- Effects;
- Audio;
- Text;
- Transitions;
- Animation;
- 3D;
- VFX;
- AI;
- Inspector.

## 23.2 Workspace presets

- Edit
- Cut
- Animate 2D
- Animate 3D
- VFX
- Colour
- Audio
- AI Generate
- Manhwa Recap
- Export

## 23.3 Media Bin

Every tile shows:

- thumbnail;
- name;
- duration/dimensions;
- type;
- source location;
- proxy state;
- sync state;
- generated/original badge.

Actions:

- preview;
- rename;
- delete from project;
- reveal storage location where permitted;
- add to timeline;
- generate proxy;
- transcode;
- replace;
- duplicate;
- analyse;
- animate/generate variant.

## 23.4 Export page

User controls:

- filename;
- destination;
- resolution;
- aspect;
- FPS;
- bitrate/quality;
- codec;
- audio codec/bitrate;
- HDR/SDR;
- hardware/software preference;
- range/full timeline;
- subtitles burn-in/sidecar;
- render cache reuse;
- upload after export.

The user presses **Start Export** and immediately sees a real export session with progress/stage/cancel/retry. No mysterious autonomous queue card.

---

# 24. Advanced AI-assisted editing tools

Add tools that can produce editable results, not flattened mystery output:

- semantic scene detection;
- best-take selection;
- silence removal;
- beat sync;
- auto multicam;
- speaker-focused reframing;
- smart crop;
- shot stabilization;
- background removal;
- object removal;
- motion tracking;
- face tracking;
- auto captions;
- auto B-roll plan;
- highlight extraction;
- hook builder;
- pacing rewrite;
- trailer cut;
- montage cut;
- dialogue cleanup;
- colour match;
- relight;
- sky/background replacement;
- cinematic sound design;
- auto music ducking;
- platform adaptation;
- Shorts/Reels/TikTok variants;
- thumbnail generation;
- continuity check;
- render critique;
- targeted regeneration of only failed shots.

---

# 25. Human video generation and avatar tools

Provide clearly separated modes:

- synthetic actor from generated identity;
- user-provided character/actor reference;
- talking head;
- full-body motion;
- dance/action motion;
- lip sync;
- emotion controls;
- camera/lens controls;
- wardrobe/background consistency;
- multi-shot identity lock.

Use consent and permissions for real-person likeness/voice workflows.

---

# 26. Advanced animation features

- motion capture import;
- pose library;
- video-to-pose extraction;
- retarget pose to 2D/3D character;
- facial capture from user-provided recording;
- physics-based secondary motion;
- spline paths;
- procedural walk cycles;
- crowd/duplicate system;
- camera shake presets;
- cinematic camera rigs;
- dolly/crane/orbit/handheld simulation;
- motion blur;
- shutter angle;
- depth of field;
- speed ramps;
- time remapping;
- morphing;
- transformation graph;
- object assembly/disassembly;
- destruction/debris systems;
- smoke/fire/energy particles;
- rain/snow/fog;
- stylized cartoon FX lines/impacts.

---

# 27. Repository/module structure target

```text
VideoStudio/
  android-app/
    ui/
    editor/
    preview/
    timeline/
    project/
    render/
    media/
    audio/
    colour/
    vfx/
    animation2d/
    animation3d/
    ai/
    storage/
    mcp/
    security/
    jobs/
    diagnostics/
  native-core/
    timeline-evaluator/
    gpu-compositor/
    codecs/
    optical-flow/
    image-processing/
    audio-dsp/
    3d-runtime/
  model-runtime/
    registry/
    inference/
    packs/
    schedulers/
  worker/
    mcp-control-plane/
    durable-queue/
    cloud-project-mirror/
    storage-connectors/
  cloud-executor/
    project-engine/
    render-worker/
    generation-worker/
  tests/
    unit/
    integration/
    golden-render/
    instrumentation/
    emulator/
    device/
  docs/
    architecture/
    protocol/
    continuity/
```

Avoid one giant `ControlService` owning every function. Break it into services with interfaces and unit-testable boundaries.

---

# 28. Database/project schema principles

Use a transactional SQLite/Room-like store with migration tests.

Core tables/entities:

- Project
- Sequence
- Track
- Clip
- Asset
- Proxy
- EffectInstance
- EffectParameter
- Keyframe
- Mask
- Tracker
- NodeGraph
- Node
- AnimationRig
- ThreeDScene
- AudioBus
- CaptionTrack
- GeneratedAsset
- ModelInvocation
- StorageLocation
- RenderJob
- JobCheckpoint
- CommandJournal
- ActivityLog
- Snapshot

Every asset has a stable ID independent of local file path.

---

# 29. Testing strategy

## 29.1 Unit tests

- timeline maths;
- trim/split/ripple;
- keyframe interpolation;
- storage manifests;
- chunk checksums;
- command idempotency;
- project migrations;
- permission rules;
- model registry selection;
- recovery state machines.

## 29.2 Integration tests

- import → timeline → preview → export;
- prompt generation → project → render → verify;
- image animation → render;
- cloud sync → offline edit → reconcile;
- MCP command → project mutation → visible UI update;
- owner mutation → ChatGPT observes new revision;
- codec crash → fallback/resume;
- storage disconnect/reconnect;
- 20 GB synthetic sparse-file workflow.

## 29.3 Emulator tests

Use cloud emulator for:

- UI navigation;
- editor interactions;
- project persistence;
- permission flows;
- storage adapter mocks;
- MCP command/state;
- timeline logic;
- background service lifecycle;
- screenshots and screen recordings.

## 29.4 Real-device tests

Required for:

- hardware codecs;
- thermal behavior;
- GPU effects;
- large file I/O;
- background process survival;
- external storage;
- real model inference;
- audio latency.

An emulator passing is not sufficient proof of hardware-codec stability.

## 29.5 Golden render tests

Maintain small reference timelines and compare output frame hashes/perceptual metrics with tolerances.

## 29.6 Evidence artifacts

Every substantial release should produce:

- APK;
- CI run link/ID;
- commit SHA;
- APK SHA-256;
- emulator screenshots;
- emulator screen recording;
- render samples;
- test matrix;
- known limitations;
- continuity report.

---

# 30. Strict autonomous Work-mode engineering contract

This section is intended to be copied directly into a future Work session.

## 30.1 Mission

Continue engineering VideoStudio autonomously until the requested milestone is implemented, integrated, tested and evidenced. Do not stop after planning if implementation tools are available.

## 30.2 Decision authority

The engineering agent is explicitly authorized to make ordinary technical decisions without asking the owner, including:

- architecture;
- module boundaries;
- refactors;
- naming;
- test design;
- UI layout improvements;
- component libraries;
- database schema additions;
- caching/proxy strategy;
- codec fallback;
- performance tuning;
- animation/effect implementation choices;
- storage connector abstraction;
- model registry structure;
- CI fixes;
- non-destructive migrations;
- bug fixes discovered during work.

Choose the strongest maintainable option consistent with this blueprint.

## 30.3 Ask the owner only at an absolute ceiling

Ask only when progress genuinely requires an external decision/credential/authorization that cannot be substituted, for example:

- OAuth account login that only the owner can perform;
- paid service purchase;
- irreversible destructive action affecting owner data;
- legal/license acceptance requiring the owner;
- missing secret or signing credential not present in the workspace;
- ambiguous product choice where both options are mutually exclusive and materially change the owner’s intent.

Do not ask about small design choices, implementation details, library selection, test strategy, naming, layout spacing, or routine refactors.

## 30.4 Continuous execution behavior

- Work continuously within the active Work/session/tool limits.
- Do not pause merely to report that a sub-step is complete.
- After each completed sub-step, continue to the next highest-priority blocker automatically.
- Run tests after meaningful changes.
- Fix discovered failures before moving on.
- Commit/checkpoint frequently.
- If a session ceiling is reached, leave the repository in a clean recoverable state with exact continuation instructions.
- Prefer completing one vertical feature end-to-end over scattering unfinished scaffolding.
- Do not claim completion without verification evidence.

## 30.5 Priority order

1. correctness and data safety;
2. human editor usability;
3. preview/import/export reliability;
4. project consistency;
5. MCP durability/security;
6. large-file stability;
7. generation/animation correctness;
8. performance;
9. visual polish;
10. additional advanced features.

## 30.6 UI authority

If the existing UI is ugly, confusing, cramped, black/empty, card-heavy, or prevents professional editing, redesign it proactively while preserving working functionality.

The target is a real editing studio, not a dashboard of autonomous-job cards.

## 30.7 Testing requirement

For every heavy feature:

- write/extend tests;
- run unit/integration tests;
- run emulator workflow;
- capture screenshots;
- capture screen recording when useful;
- verify produced media plays;
- inspect generated/rendered output;
- test failure/recovery path;
- never count “queued” or “connected” as proof of work.

---

# 31. Implementation order / master roadmap

The agent should not stop after each phase; these are ordering constraints.

## Phase A — editor ownership and reliability

- rebuild preview monitor;
- real multitrack timeline;
- inspector/keyframes;
- Media Bin;
- direct manual import/preview/export;
- manual lane vs autonomous lane;
- undo/redo/autosave;
- export page;
- codec fallback/resume.

## Phase B — large media and storage

- proxy system;
- chunked I/O;
- external storage;
- five cloud connectors;
- Vault chunk format;
- sync manager;
- 10–20 GB synthetic test project.

## Phase C — MCP v4 and offline mirror

- versioned protocol;
- command schemas;
- owner scopes;
- project revision conflict handling;
- cloud project mirror;
- cloud executor;
- offline ChatGPT queue/execution;
- reconciliation.

## Phase D — 2D animation + image-to-video

- segmentation/pose/depth;
- layer decomposition;
- mesh/bone animation;
- optical-flow refinement;
- frame interpolation;
- temporal consistency;
- cartoon line-art protection;
- motion prompt workspace.

## Phase E — generative video models

- model pack registry;
- local/remote compute planner;
- image-to-video generative backend;
- text-to-video backend;
- controlled human/character generation;
- shot continuation;
- targeted regeneration;
- upscaling/interpolation.

## Phase F — 3D/CGI/VFX

- 3D scene runtime;
- import formats;
- rigging/animation;
- particles/physics;
- node compositor;
- tracking/roto/keying;
- transformation workflows;
- render passes.

## Phase G — audio/narration/recap automation

- ASR;
- TTS voice registry;
- dialogue cleanup;
- captions;
- lip sync;
- Manhwa recap pipeline;
- long-form project automation.

## Phase H — polish/performance/release

- profiling;
- memory/thermal tuning;
- accessibility;
- tablet layouts;
- crash analytics;
- migration tests;
- full regression suite;
- release artifacts/evidence.

---

# 32. Definition of done

VideoStudio is not “complete” merely because features exist in menus.

A major feature is done only when:

1. the owner can use it directly;
2. ChatGPT can use it through MCP where appropriate;
3. both mutate the same project model;
4. the result is visible in preview/timeline;
5. it persists across restart;
6. undo/redo behavior is defined;
7. failure states are clear;
8. recovery is tested;
9. output is verified;
10. tests pass;
11. emulator evidence exists;
12. hardware-sensitive features have device verification or an explicit pending-device note;
13. documentation/continuity is updated.

For generated video specifically, completion requires a playable media file and an honest label describing whether the result came from procedural generation, 2D synthesis, 3D rendering, or generative video inference.

---

# 33. Next-session startup checklist

When a new Work session begins:

1. Read this entire bundle.
2. Inspect repository `main` and current open PRs.
3. Read current APK/app version and MCP protocol version.
4. Run baseline CI.
5. Inspect current editor screenshots/video if available.
6. Do not regress working stable MCP identity behavior.
7. Start from the highest-priority unfinished roadmap item.
8. Use tests first for bugs and risky behavior changes.
9. Continue without asking the owner for routine decisions.
10. End only at completion, an unavoidable external ceiling, or a system/session boundary, leaving precise continuity state.

---

# 34. Product north-star statement

**VideoStudio should feel like a real professional studio in the owner’s hands and like a full autonomous production environment in ChatGPT’s hands. Neither side should be a second-class controller.**

The owner sees and controls every clip, image, audio file, generated asset, keyframe, track, model, effect, render, and storage destination. ChatGPT works on the exact same project graph with auditable commands, safe privacy boundaries, durable offline-capable execution, and immediate revocation.

The long-term differentiator is not “another AI video button.” It is one integrated system where deterministic editing, 2D/3D animation, generative models, VFX, audio, large-media engineering, multi-cloud storage, and autonomous agents operate on one editable project.

That is the architecture that can support everything from quick social edits to Manhwa recaps, stylized cartoon animation, vehicle transformations, character animation, human-video generation, cinematic VFX, CGI sequences, and feature-length project assembly without turning the app into a collection of fragile demos.

---

# 35. Engineering continuation — 2026-10-09 UTC

## 35.1 Actual repository, version and delivery state

This section continues the original requirements; it does not declare the complete blueprint finished. Sections 0–34 above remain the product authority. The version stated at the top is the target, not the version of the delivered earlier APK.

- Repository: `RezoxNemesis/VideoStudio-MCP`.
- Local workspace: `/workspace/VideoStudio-MCP`; branch: `codex/studio-owner-editor`.
- Latest implementation commit: `2c9e684ca6e8bd48f6bfbc1954e9efd0a3f36fcf`. The delivery manifest records the final source commit including this continuation and its build instructions.
- Local baseline `f8a94cb` reconstructs upstream `d652066ce428e4f128514da4d1caee919974554e`. All 94 recovered file blobs matched; both baseline trees are `4e879210a997fdcde092a1978aee801d73e9f424`. Commit histories differ; do not force-push the reconstructed local history over upstream.
- Last confirmed remote review head: `4e30ae1ee0fb33609674f7a5a540b6677ddc9d72`, tree `ba58ffd08f41897d70955f1b73df8078e3a77aac`.
- Existing draft PR: [#35](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/35). It contains the earlier verified source; the later local commits have not been published.
- Actual Android version remains **3.4.7 / versionCode 347**; **3.5.0 is still the target**. Package, owner identity, database, v3 control identity and development signing workflow are preserved. Protocol v4 alias/schema 2 are additive and gate newer operations against older APKs.
- Last verified debug APK: `artifacts/verified-4e30ae1/apk/app-debug.apk`, **98,805,842 bytes**, SHA-256 **`c3c6772d14a71154157006b0305fa50fc331f6b4c519886e047dc03b3a942122`**.
- This APK predates the local Vault, DSP/narration, four-tier proxy, compositing and review fixes described below. It also predates fixes to inherited programme source-duration/audio-clock defects. It is an earlier installable build, not a build of the latest source or the completed professional app.

## 35.2 Implemented work and remaining roadmap

“Local source” means integrated implementation awaiting Android compilation/device execution. A passing pure Java, relay or software shader check does not substitute for that evidence.

| Phase | Implemented foundation and evidence | Still required for the complete phase |
| --- | --- | --- |
| A — owner editor | Revisioned SQLite/WAL graph, shared owner/MCP editing engine, multitrack ruler/playhead/drag/trim/snap/zoom, Media Bin, source image/video/audio monitor, shared programme/render composition, undo/redo/history, inspector/keyframes, foreground owner export lane and verified publication. Earlier build has unit/emulator proof; latest timing, cancellation, fallback, alpha and recovery corrections are local source. | Compile/test the latest source; run all six authored device cases; prove programme effects/audio/seek/restart parity and codec/cancellation/recovery on devices. Complete remaining owner tools that still have placeholder tiles. |
| B — large media/storage | Five authorized SAF profile slots, owner-selected external/USB/provider folders, role defaults, grant health and honest unknown quotas. Local source has four proxy tiers (240/360/540/720), manual small-video requests, original fallback, verified cache reuse and original-source export. Vault has checksummed 256 MB logical objects, bounded 1 MB AES-GCM records, manifests, random ranges, encrypted deduplication, durable chunk resume and full-source identity binding. | Direct five-provider OAuth adapters and credentials, cloud chunk upload/download integration, full sync reconciliation and segmented-render reuse; real 10–20 GB import/proxy/transfer/performance workloads. A sparse 20 GB seek test is not that benchmark. |
| C — MCP/offline mirror | Stable v3 transport/durable queue, command IDs/receipts/leases/recovery, additive v4 alias and shared editor schema, revisions, owner project/selected-assets/one-file scopes, filtered queries and separate manual/autonomous cancellation. Existing folder-scoped archive/restore foundations remain available. | A cloud project mirror plus actual cloud executor, compute-backed offline editing/rendering, placeholders/download and conflict reconciliation. Queued native work while Android sleeps is not cloud execution. |
| D — image/2D animation | Existing procedural/geometry/perspective and segmented portrait/face-aware 2.5D paths remain; motion effects share programme/export timing. | Complete owner-authored layers/meshes/bones/poses/depth, optical flow, frame interpolation, consistency locks and line-art protection, with actual rendered animation evidence. |
| E — generative models | Existing registry/planner/install/provider capability structures and explicit procedural output labels remain. | Actual licensed/verified model weights and inference adapters for image/video/human generation, shot continuation, regeneration, interpolation/upscaling; local and remote runtime validation. Menus and registered capabilities alone do not satisfy this phase. |
| F — 3D/CGI/VFX | Existing bounded procedural geometry plus local frame-evaluated alpha, RGB-distance chroma key, spill suppression, feathered rectangle/rounded/ellipse masks and an opaque compositor base. Owner and MCP settings persist/undo on the shared graph. Real shader source passes software pixel tests. | Android compositor proof, full glTF/PBR scene runtime/import, rigging/animation, particles/physics, node compositor, tracking/roto, transformation workflows and render passes. |
| G — audio/narration/recap | Local shared PCM DSP applies EQ, high/low-pass filters, compressor, gate, delay, stereo width, limiter, gain/pan keyframes and seek-aware retimed audio. Long narration chunks Android TTS work, checkpoints SHA-256, assembles compatible PCM WAVs with bounded buffers and publishes unique outputs without replacing originals; optional append is explicit. | Android DSP and installed-voice execution/restart proof, ASR, voice/model registry completion, captions, lip sync, cleanup/recap workflows and long-form automation. The WAV/chunk tests do not prove TTS voice quality or runtime execution. |
| H — polish/release | Earlier CI APK and emulator media/UI artifacts retained; local low-memory cores and software GLSL tests pass; isolated draft PR, source review and precise continuity exist. | Full latest-source build/regression, accessibility/tablet work, profiling/memory/thermal testing, migrations, real-device checks, crash analytics and release artifacts matching the final implementation. |

## 35.3 Correctness fixes from independent review

The latest source fixes concrete defects rather than only adding feature declarations:

1. A real 256 MB encrypted Vault object caused a one-byte read to exhaust a 64 MB heap. Vault format 2 authenticates separate bounded records, with position/length AAD and provider-generated IVs. Range reads authenticate selected records without loading the logical object. Large legacy single-record encrypted objects require a new copy from retained originals.
2. Vault chunk checkpoints persist before progress callbacks and reuse verified saved objects after restart. Resume identity now includes the live full-source SHA-256; reconstructed manifests and publication reject changed-document hybrids. Verification intentionally adds full source I/O; 20 GB throughput is unmeasured.
3. Programme Media3 items use the original source duration before trim/speed. Video effects subtract the precise sequence offset once; audio processors use stream seek metadata without reapplying speed. Silent gaps match retimed video at microsecond precision.
4. Proxy reuse checks current bytes/decoded frames against the stored proof. Missing/failed proxies fall back to originals, preserving programme seek/play state and avoiding an infinite retry loop.
5. Jobs persist their input asset IDs, filter results against current owner scopes and revalidate recovery. Scope changes cancel autonomous work; narration publication/registration observes cancellation and owner authorization.
6. Recovery preserves validated generation/MCP command identifiers. Terminal plans reject late callbacks and reattachment, so cancellation cannot resurrect work.
7. Job completion is synchronously journaled before retiring its recovery plan. A 100% progress checkpoint alone remains running. Startup reconciles the commit/retire crash window off the service main thread.
8. Recovered media must match every saved root or legacy nested checksum. Fresh proofs replace both result locations consistently. Invalid output bindings are cleared durably without deleting external files, guarded against clearing a newer URI.
9. Export retries reuse only a decoded prior output matching the new encoded file's checksum. Graph recovery verifies its bound URI and invalidates final-render/downstream caches when output is deleted or invalid; cache persistence must succeed before forgetting the binding.
10. Deferred MCP video completion verifies frames/container/checksum before reporting verified playable output. The focused reviewer confirmed the checksum, invalid-output reuse and graph invalidation findings closed by inspection. Android regressions for the latest changes remain authored/unrun.

## 35.4 Evidence matrix

| Evidence | Exact result | Scope/limit |
| --- | --- | --- |
| Latest local Node suites | 282 source smoke assertions, 20 executable connection cases, 12 executable editor-relay cases pass | Source smoke assertions are static checks; the connection/relay suites execute behavior. |
| Pure Java core suite | 40 timeline, 52 Vault/range, 14 audio DSP, 510 narration assertions pass | Bounded core tests run under a 64 MB heap where applicable; include actual 256 MB encryption/resume and a sparse 20 GB 64-bit seek. They do not run Android UI/Keystore/TTS/codecs. |
| Actual GLSL source | 15 EGL/llvmpipe ES pixel checks pass | Software shader execution; Android Media3 compositor/device proof pending. |
| Java syntax | 99 Android source files parse | No Android symbol resolution, compilation, unit run or APK produced by this check. |
| Earlier Android CI | Run `37835673775`, job `113511943425`, source `4e30ae1`; downloaded XML: 117 tests, zero failures/errors/skips | Earlier source only; artifact `11575522024` contains the verified APK, `11576095028` its unit results. |
| Earlier Worker CI | Run `37835673723` passed at `4e30ae1` | Latest local Worker suites pass; latest source is unpublished. |
| Earlier emulator CI | Run `37835673770`, job `113511946051` passed at `4e30ae1` | Earlier two device cases; latest six-case suite is not executed. |
| Retained media/screenshots | Source `c28592a`, run `37833966966`, job `113506171407`, artifact `11574488092`; two instrumentation cases passed | Workflow evidence check then failed because `rg` was absent; repaired at `4e30ae1`. Retained exports/screenshots prove that earlier source, not the latest local work. |
| Mixed timeline media inspection | H.264 1280×720, 30 fps, AAC mono 44.1 kHz, 4.000 s; audible signal mean −19.7 dB, peak −16.6 dB | Retained `c28592a` video/image/gap/audio render. |

The delivery packet separates the current source, earlier APK, earlier unit/device evidence and local test logs. SHA-256 sums and a manifest identify every artifact. Do not replace these distinctions with a single “all tests passed” claim.

## 35.5 Unavoidable build/publish ceiling

The local executor has Node, Python, FFmpeg, EGL and a Java runtime/compiler module, but **no Gradle, Android SDK, adb or emulator**. It cannot build or device-test the latest Android source.

The GitHub connector can read repository/CI data, but full tree writes stalled and were interrupted. Bounded attempts at a 26 KB tree, a 30-byte blob and a different Contents API update all timed out; a subsequent branch read confirmed the remote head stayed `4e30ae1`. The Contents API attempt was bounded to 20 seconds. The managed network proxy also refuses connections. No explicit authentication failure or automatic approval rejection was returned. Do not describe this as missing owner permission, wait indefinitely, bypass the managed proxy, or count an interrupted write as a published change.

The repository is checkpointed locally and packaged for recovery at this external ceiling. The complete app remains unfinished. No main merge, release promotion or production deployment was performed.

## 35.6 Exact next actions

1. Restore the final source from the delivery ZIP or Git bundle. Read this section, `IMPLEMENTATION_LEDGER.md` and `BUILD_RESUME.md`; inspect the manifest's source commit and verify `SHA256SUMS`.
2. First restore an Android build path: JDK 17, Gradle 8.9, Android SDK platform 36/build tools 35.0.0 and an API 33 emulator. Do not add more Android functionality before resolving compilation/test failures in this source.
3. From the repository root, execute:

   ```bash
   npm ci
   npm test
   bash scripts/core-test.sh
   python3 scripts/shader-test.py
   gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
   bash scripts/run-device-evidence.sh
   ```

   `npm ci`/Gradle require dependencies and a working network/cache. The shader check needs EGL/GLES with a software or hardware renderer. This repository has no `gradlew`; the commands use installed Gradle.
4. Run the six authored `StudioDeviceTest` cases: owner preview/edit/export; mixed video/image/gap/audio; Vault Android Keystore/original retention; alpha on a second timeline clip; chroma key/mask actual pixels; manual small-video proxy/audio/original export. Add/execute device proof for corrected audio seek/speed, narration cancellation/restart and durable completion/output replacement paths.
5. Fix failures before continuing. Save XML, screenshots, actual MP4/WAV output, media inspection, APK SHA-256 and source/CI IDs. Verify a restart/upgrade using retained owner data and check stable development signing.
6. When writes work, publish onto an isolated branch using upstream ancestry. The supplied patch is against the exact `d652066` baseline; it is not an incremental patch against the existing PR's `4e30ae1` head. Follow `BUILD_RESUME.md`, inspect current upstream and avoid overwriting others' changes.
7. Continue Phases A–H in the original priority order, with real model/runtime/storage credentials only where actually required. Keep owner and MCP operations on the same persistent graph; every new heavy feature needs end-to-end output/recovery evidence.
8. Promote the version/release only after the defined milestone is implemented and verified. Do not advertise the baseline APK or this uncompiled source as the complete 3.5.0 app.


# 36. Continued engineering and verified build recovery — 2026-10-09 UTC

This section supersedes the build/publish ceiling in section 35 while preserving the original instructions and historical evidence. The whole product remains unfinished; phase ordering and the definition of done still apply.

## 36.1 Published matching-source evidence

The review branch is codex/studio-owner-editor and draft PR #35. Published b3fb1ba3560175d6d02b6c7cd8f0d6c7bcd9b434 and local 6d844343dcb11edcd4fbb139a727b2a70f012b13 have the same tree eab22ffae481651104efc7e7e5cd468442d774be. Android run 37882409101 passed 145 unit tests; device run 37882409116 passed all six API 33 cases; Worker run 37882409117 succeeded. Downloaded unit XML has zero failures/errors/skips. Four retained MP4 exports matched their device proof hashes and fully decoded with FFmpeg. Actual Keystore/proxy/audio/alpha/chroma/mask/owner export evidence is retained in artifacts/verified-b3fb1ba.

APK SHA-256: 865fa790151d0a87261bcc19ec29f8f981d4b3af9e60eae7af17d0242b056947. Certificate SHA-256: 51a213102b466f2a974d84b5274d8a899228d5a25be1b66f9bb693f13cb2e253. The archived 4e30 certificate differs. Never claim these two APKs can upgrade each other or wipe owner data to install a test build. Signing keys remain private.

## 36.2 Current legacy editor correctness work

Supported legacy apply_tool commands now use one shared editor transaction in service and foreground paths. They enforce track locks, source ranges, strict numeric/settings validation, speed ripple, actual clip keyframes, undo and request-bound durable receipts. Optional legacy revisions remain compatible; shared editor operations still require explicit revisions and command IDs. Invalid tools/settings fail before edits. A replay returns its original receipt without overwriting newer owner edits.

Public command dispatch also validates action/argument binding before terminal replay; a changed payload cannot receive another command's success. Interrupted synchronous editor actions recover through SQLite receipts instead of waiting for a nonexistent job. Journal writes are synchronously committed before dispatch.

Schema 4 adds set_effect_preset while retaining set_creator_style at minimum native schema 3. Only executing colour/Gaussian providers are selectable; soft_glow/dream currently mean mild Gaussian blur. Clear removes the migrated preset; undo restores it. Shared blur construction now reaches generated animation layers. Owner Effects, legacy tool, shared operation and relay gates agree, including selected-clip scope.

STOP is rechecked when executing posted protocol callbacks, before public service replay/recovery and in foreground dispatch. Stopped remote control cannot return old success or mutate projects; independent owner editing remains available. Legacy plans/creator presets/autonomous_edit still require further atomic parity work.

## 36.3 Fresh local verification and execution limits

All 73 main Java and 31 test sources compiled fresh; 172 actual JUnit/Robolectric tests passed. Node: 282 static smoke assertions, 20 connection cases, 15 executable relay cases. Real Wrangler deploy --dry-run compiled the Worker; no production deploy occurred. Pure core: timeline 40, Vault/range 52 with a 64 MB heap, audio DSP 14, narration 510; actual software GLES pixel cases 15. Saved logs include intended RED regressions, final GREEN and environment failures. Latest source still needs matching Gradle APK/device CI; the preceding six device cases verify b3fb, not later source.

Free JDK 17, Gradle 8.9, SDK 36/build tools, emulator and API 33 images are installed in /workspace/toolchains. npm dependencies and package-lock.json are installed. Default managed Terminal execution still rejects Gradle daemon socket creation and network grants can prompt. Use the authorized GitHub runner for full builds/devices; scripts/android-offline-tests.py provides isolated fresh Java tests using exact cached dependencies and a copied test resource archive with current schema. It does not rebuild Android resources/manifests or assemble an APK. Do not label it a device test.

Orez acknowledged issue #36 and is monitoring without overlapping implementation: https://github.com/RezoxNemesis/VideoStudio-MCP/issues/36. This is the verified coordination route; no direct assistant messaging or unattended Terminal control was established. Routine repository work, installations, commits, PR updates and fixes remain authorized. Platform approval policy cannot be self-approved from source changes.

## 36.4 Continue the full blueprint

Next priority: shared atomic legacy plans/presets/autonomous edits, then remaining editor depth. Existing working cores include SQLite history, owner timeline/bin/preview/export, durable scoped MCP, four proxy tiers, bounded encrypted Vault records, five SAF profiles, PCM DSP, chunked Android TTS, actual title animation and procedural/layered image animation.

Still incomplete: direct five-provider OAuth adapters; cloud mirror/executor and offline reconciliation; complete 2D mesh/bone/IK/frame/flow authoring; model-backed image/text video and identity/temporal control; full 3D import/PBR/rig/physics/VFX/compositor/tracking/render passes; ASR/subtitles/lip-sync/recap; full accessibility/tablet/performance/crash tooling and 10–20 GB cloud benchmark. Treat missing weights/provider configuration as concrete dependencies, never simulated success. Preserve originals and owner scope; keep progressing through independent work.


# 37. Atomic bulk edits and export handoff — 2026-10-09 UTC

## 37.1 Verified preceding publication

Published 82c600faf7dc18e5f4a772f14d9a42ecdc7a0dfe matches local d8ee058d9412f7e89cbd37cb6540c133cf3d9b2c at tree 6b415c64a2e6fe5656906ee5f8fb188ba6406f26. Android run 37890111134, device run 37890111246 and Worker run 37890111099 all succeeded. The device job explicitly reports OK (6 tests) and retained 28 evidence files. The previous 172-case unit source checkpoint is now built by matching-source CI. Metadata and job logs were inspected through GitHub; new artifact byte, MP4 and APK certificate inspection is not claimed. Exact limits are saved in artifacts/verified-82c600f/verification-manifest.json. Earlier b3fb bytes remain independently verified.

## 37.2 Current schema 5 editing and recovery

Legacy apply_edit_plan, creator_preset and autonomous_edit now use the same atomic editor engine from service and foreground owner paths. Timeline replacement preserves project assets and enforces all existing track locks, source bounds, speed, overlaps, supported effects, titles and keyframes. Plan plus style changes form one transaction and undo entry. Request-bound SQL receipts preserve newer owner edits on retry. Invalid styles, controls and ambiguous source-time units fail atomically. Schema 5 exposes replace_timeline and apply_creator_preset; older operation minimum versions remain compatible. Selected-assets scope checks every targeted clip. Relay gates optional explicit project/revision guarantees against native schema 5.

Generated-layer creator motion and transition overrides now change the executing motion matrix without mutating the original animation specification. Supported editor preset choices are reported separately from the broader creator roadmap. Unimplemented transitions/providers are rejected rather than recorded as successful effects.

Remote foreground exports are handed to the foreground service with the original command and separate project binding. The activity does not acknowledge completion or submit owner-origin jobs for remote requests. Service dispatch strips caller-provided internal recovery handles, enforces autonomous origin and checks STOP. Each command/action/project has one durable plan; its job binding is persisted before the worker starts and a retry reuses a live job. Exports persist a deep project snapshot and deterministic command-derived default filename. Render-only requests enforce expectedRevision; a valid retry uses the original snapshot even after later owner edits. Changed render settings conflict. A full recovery queue rejects new work while preserving unfinished command bindings.

## 37.3 Actual current-source verification

Fresh compilation of 73 main Java and 33 test Java sources passed all 200 JUnit/Robolectric cases. Node passed 282 static smoke assertions, 20 executable connection cases and 19 executable editor-relay cases. Actual Wrangler deploy --dry-run compiled the Worker without production deployment. Logs are under artifacts/current-2026-10-09/bulk-recovery-full-release-check.log and bulk-recovery-{node,worker}-final.log. Regression RED logs reproduce duplicate export settings, lost unfinished plans, stale render revisions, inert generated motion and premature foreground completion before their fixes. Independent review and matching-source Gradle/APK/device CI remain required for this new checkpoint; the prior six device cases do not cover all newly added controls.

Android CI now explicitly uses the restored development keystore and prints parsed unit counts, APK SHA-256/size and apksigner certificate verification. This resolves the previous discrepancy between the restored keystore and Gradle's default signing location. Until an actual new signed APK is built and its certificate compared, do not claim upgrade compatibility or uninstall owner data. No private key material is committed.

## 37.4 Continue continuously

Atomic legacy editing closes this parity gap. Continue editor depth and phases B–H from the original bundle. Full five-provider OAuth, cloud mirror/executor/reconciliation, model-backed generation, complete 2D/3D/VFX and speech/recap work remain unfinished. Free dependencies and ordinary engineering actions stay authorized; missing credentials/model weights and platform-enforced restrictions must be reported precisely while independent work continues.

Follow-up review reproduced implicit-project retry drift, an oversized Binder handoff, an eight-plan recovery ceiling, and cross-instance journal data loss. Project bindings now persist before dispatch; ID-only intents refer to validated immutable journal payloads. Journal operations share a process lock, terminal completion preserves captured requests, and all 24 eligible plans resume through bounded lanes. Concurrent sixteen-instance and terminal ordering regressions verify the journal fixes. One full test run hung because a test fixture reset Android queued work before cancelled workers finished their checkpoint writes; its thread dump is retained. The fixture now waits for worker termination before reset; this was not device evidence or a passing run.


## 38. Verified bulk checkpoint and resumable storage fabric (2026-10-09)

Published bulk/editor checkpoint 4129378b4fbfaba80b0e59a078c03abc00081e31 exactly matches local source tree 475e13e8a3671488ff804e1aa12777de3c478cea. Android CI 37895158494 passes 200 tests (zero failures/errors/skips), APK 98,958,847 bytes SHA-256 a3d73795ad4bea97447ada593bb0ad958a2af663cfc100505ac0afe30dc4b676; signing certificate 7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Device CI 37895158443 passes six cases and retains 28 files; Worker 37895158717 passes. Evidence is inspected CI logs/metadata, not locally fetched artifact bytes. Signing continuity with the owner's installed app remains unverified.

New schema 6/app 3.4.8 implements actual streaming document-folder replication of complete local Vault chunks and binary manifests, including encrypted stored objects without exporting their keys. Owner and ChatGPT share the durable service job; replication requires existing explicitly selected profiles and project-owned complete Vault source, obeys selected-media scopes and STOP, retains originals/local chunks, and commits current-project metadata narrowly after checksums. Storage discovery accepts a bound project and withholds folder URIs/private project pins. Historical replica records are explicitly distinguished from current live verification.

Upload identity is persisted in SQLite before remote document creation. Pending and renamed token-bound objects can be recovered after death. Confirmed corruption is repaired in the existing managed document; transient read errors preserve the old location and fail recoverably. The additive v2 replica-index migration retains v1 verified locations. Tests reproduce death after bytes are written, changing document IDs on rename, zero-free-quota repair, temporary provider outages, checksum rejection, distinct replicas, cancellation/restart, deduplication, encrypted-byte copying and scope redaction. Full fresh native suite passes 222 cases; Node 282/20/21 and actual Worker bundle pass. New full Gradle/APK and seven-case device verification remain pending matching publication; the new device test uses Android's actual folder grant and encrypted replication/restart.

The full local 20 GiB benchmark under -Xmx64m hashes every source byte, packs 80 × 256 MiB chunks, resumes at 2 GiB and reads every restored byte. Original/restored SHA-256 match: 7be2439cc956e17374a1f6f38844f56873c504c6b457b401f88ee5f56bc9236e. Dedup stores five objects totaling 1,342,177,280 bytes. Source hash 57,210 ms, pack with restart 154,168 ms, full read 44,377 ms. Fixture is sparse synthetic plaintext local media, not playable video, encrypted cloud transfer or Android-device performance proof.

Continue next with verified download/hydration and cloud range fallback, direct provider OAuth/quota/sync adapters and full editor/cloud/generation/2D/3D/audio/release requirements. Connected third-party accounts/model weights/cloud executor remain unavailable; preserve those explicit limitations. The complete app is still unfinished. Do not stop at this checkpoint or claim completion.


# 39. Verified folder replication and recoverable original media — 2026-10-09 UTC

Storage publication `806620a9f138ae2928dcafd2962570a7867734d0` (local `ba07402`, tree `22ece642b2fbed421687f271e399943b32731830`) passes matching Android run 37901073791 (222 cases, zero failures/errors/skips), Worker 37901073863 and device 37901073760 (7 cases, 31 retained files). APK: 98,991,611 bytes, SHA-256 `1969af653b21a380ddac60fcd8d70cc49bd1352828991285611b99898b165c30`. Certificate: `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`, matching 4129378 development signing. Owner installation compatibility remains unproven. Evidence is inspected CI logs/metadata; binaries were not independently fetched. The seventh device case covers actual owner/system folder grant, encrypted SAF replication and verified restart reuse. Initial naming-dialog and button-case fixture failures were corrected.

New schema 7 / app 3.4.9 recovery restores missing or corrupt local binary manifests and chunks from previously verified replicas. Local binary manifests are checked against indexed binary hashes; parseable plaintext identity alone is insufficient. Downloads stream into deterministic stages, verify size/SHA, fsync and atomically replace only validated cache entries. Alternate connected replicas are tried after corruption or provider grant failures; owner cancellation propagates immediately. Range recovery downloads whole intersecting chunks before authenticated local range reads. This SAF path does not implement direct HTTP byte ranges.

Owner “Restore media from Vault” and MCP vault_restore use one durable heavy job. Plans bind the original source URI and manifest before queuing. Complete media restoration uses the original device-held key, full plaintext checksum, deterministic stages and SHA-verified whole-chunk prefix reuse after interruption. The original file/URI is retained; atomic current-project publication relinks only the bound asset and preserves newer owner source/project edits. Committed retry requires matching recovery metadata. Caller URIs and Gallery enumeration remain excluded; recovery does not connect folders or delete remote objects.

Fresh final native verification: 76 main and 36 test Java classes, 242 JUnit/Robolectric cases. Node: 282 smoke assertions, 20 connection cases, 22 relay cases; actual Worker bundle passes. Pure core: 40 timeline, 52 Vault, 14 DSP and 510 narration checks. Hydration RED 6 and restoration RED 4 preceded implementation. Seven review regressions fail in the unfixed behaviour variants, then pass in 45 focused cases; independent review closes four Important findings. A further cancellation RED 1 is fixed and included in final GREEN 242. Restart evidence includes durable-state fault injection; a full device force-stop/power-loss benchmark remains pending.

The seventh device case is extended to remove only fixture-owned local Vault cache, recover/decrypt identical PNG bytes, decode them and check the restored editor preview. These new assertions await matching-source Gradle/APK/device CI. Direct provider OAuth/account-network tests, HTTP range adapters, cloud mirror/executor/reconciliation, remaining editor depth, full 2D/models/3D/audio/accessibility/performance/release remain unfinished. Cloud environment reports no runtime secrets or outbound provider identities; Terminal proxy access still fails. Continue independent work without inventing credentials or claiming deployment. Do not stop at this checkpoint.


Matching recovery publication `576098de2245b0c74ae76caa2fbd2264f2d01b08`, local `a1b4e9f`, tree `657a608c3477cdab2a672a349c1b0956d0e50e25`: Android37903124572 passes242 tests, zero failures/errors/skips. APK99,008,011 bytes, SHA256 `ccaba6a8b2719565fcfd88910c1f7ca6c549873bff31369a3ba100238bd3e98e`, certificate `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`. Worker37903124578 succeeds; device37903124755 reports OK(7 tests),32 pulled evidence files. The extended seventh case passes encrypted missing-cache recovery, exact plaintext PNG identity, decoded colour and restored editor preview. Logs/metadata inspected; artifact bytes not independently downloaded. Owner installed-app upgrade compatibility and direct cloud-account transfer remain unverified. Continue codec resilience, then segment checkpoints and the complete roadmap.


# 40. Verified recovery checkpoint and native codec resilience — 2026-10-09 UTC

Recovery576098de is now fully matching-source CI verified: Android37903124572 passes242 unit cases, APK99,008,011 bytes SHA256ccaba6a8b2719565fcfd88910c1f7ca6c549873bff31369a3ba100238bd3e98e; certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37903124578 succeeds; API33 device37903124755 passes7 cases/32 pulled files including encrypted Vault missing-cache hydration, exact plaintext image bytes, decoded PNG and restored editor preview. Logs/metadata inspected; artifact binaries were not independently fetched. Owner installation upgrade compatibility remains unverified.

App3.4.10/schema7 adds default, conservative, software-video-decoder and software-video-decoder/encoder export routes. All attempts use the captured original composition. Only decoder/encoder failures retry, at most four routes; source, permission, shader/DSP, muxer and verification failures are explicit. Retired callbacks and STOP cannot complete a later attempt. Each attempt releases codecs/polling. Final success requires container validation, three sampled decoded frames and a whole-file checksum on a bounded worker. This is sampled decoding, not every-frame verification.

ReliableDecoderFactory binds initialization and runtime failures to actual input Format dimensions, source codec/profile string and executing frame rate, including failures before any initialization succeeds. Encoder policy uses executing dimensions/fps, AVC macroblock/rate/bitrate level requirements, exact target bitrate-capable candidates and resolved Codec format checks. Consistent encoder keys follow its actual configuration, including platform portrait rotation. SQLite retains at most128 per-device codec/configuration/signature rows; named failures affect candidate ordering and checksum-verified successes reduce the penalty. Service publication retains codec route/names/normalized configuration and proof. Canonical workspace checks protect original sources and animation layers from path aliases.

Initial lifecycle/indexRED15 and policyRED3 precede implementation. Source-alias and operating-rateRED1 each, review scope-cancelRED2/level-and-bitrateRED2/input-initRED1/resolved-formatRED1/ordinary-checkpointRED1, and terminal-checkpoint old-variantRED1 are saved. Corrected final80 main/40 test Java classes pass270 JUnit/Robolectric cases; Node282 smoke assertions/20 connection/22 relay cases and actual Worker bundle pass. Bounded independent review closes Important findings. Device source adds an eighth real conservative/software render case asserting named software codecs, unchanged1280x720/target bitrate/audio/red pixels/original bytes and stored proof. Matching new APK/device execution is pending publication; do not claim that new codec path has already run on a device.

The full app remains unfinished. Continue segment checkpoints and verified segment reuse, source mezzanine fallback, deeper NLE, direct OAuth/account-network storage adapters, cloud mirror/executor/reconciliation, complete2D/model/3D/VFX/speech/accessibility/performance/release. Cloudflare's connected automatic preview bot reports failed builds; authenticated account build logs/reason are unavailable here. The successful Worker dry-run is compilation evidence, not deployment. No configured cloud runtime secrets/outbound identities or usable default Terminal network. Continue independent authorized work, without inventing credentials or treating a checkpoint as final delivery.


## 41. Device-discovered encoder format correction — 2026-10-09 UTC

Codec publication bdc5566d7eeb9ac13598f6fd85d40345cb6c0bd4 matches tree136f71eeb0bfe9916c31d700acc3a2d792e24ea5. Android37907677115 passes270 unit tests and builds the APK (99,040,787 bytes; SHA256db4f07866cd1c29fa5882fb4b9252ebcbe9e0b3a322cb9ae183c2fd1ba35231e; certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd). Worker37907677006 succeeds. Device37907676874 compiles instrumentation and runs8 cases, but6 fail at the resolved encoder configuration check. This source is not device-green and must not be delivered as fully working.

DefaultEncoderFactory bytecode confirms Media3 substitutes30 fps when its requested Format has an unspecified frame rate. The wrapper previously compared resolved30 with unspecified-1 and rejected valid encoders. A focused RED1/9 reproduces the rejection; the shared factory boundary now normalizes only unspecified rates to the same30 default before capability checks, creation and validation. Explicit frame rates, exact dimensions and requested bitrate still reject downgrades. Corrected fresh80 main/40 test classes pass271 cases; Node282/20/22 passes. Bounded independent review finds no Important issues. Corrected matching APK/device rerun remains pending; old CI metadata is evidence, artifact bytes were not fetched.

Segment feasibility inspection finds Media3 resume supports only single-sequence compositions and rejects speed-changing effects. The executing VideoStudio compositor uses multiple sequences, so directly calling Transformer.resume would not provide general checkpoint recovery. Continue a verified segmented architecture that preserves global effects/audio clocks and source identity. The complete app and Cloudflare deployment remain unfinished; continue independent work.


Corrected codec publication190ab4e3944aa22824fafeb39b65143fe984e885, local6dba807/treee814d3ac1ffa2f592f7be0ac7d35b4bc30e3046e: matching Android37908874926 passes271 unit cases/APK99,040,787 bytes SHA2564cb729cb7e3cce40d306cd21e819e6edd7c43389a0290cf083ecec7018ccac88, certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37908874965 succeeds; API33device37908874872 OK(8 tests),38files/5,502,279bytes. Actual conservative/software routes preserve1280x720/30fps/2Mbps/audio1second and unchangedoriginal; software c2.android.avc decoder/encoder verified. Both outputSHAbee243a53098dd55cfb6c849bdc8d3a3508880ec0d5b20adf85c8fea2de45ba1. CI logs/metadata inspected; binaries not independently fetched. Owner upgrade compatibility, full app and deployment remain unverified. Continue segmented original-clock compositions/checkpoints.


## 42. Original-clock video window builder — 2026-10-09 UTC

The new factory path builds bounded video-only windows from original-media compositions, retaining selected track order/solo/visibility, source trims/speed, gaps, opaque base, generated layers and original automation/title/motion durations. Signed origins retain elapsed clip time when a clip begins before the window. Source clipping uses microseconds, including tiny remainders; original graphs are serialized without normalizing the owner object. Image/layer/base EditedMediaItem durationUs is exact, verified against actual Media3 ImageAssetLoader bytecode. Shared programDurationUs includes accumulated executing retime fractions while excluding non-solo sources from extra validation/extension.

RED8/8 preceded implementation. Independent review found fractional-tail bounds, rounded image/layer durations and owner normalization; RED4/13 reproduces those. A solo-selection parity follow-up RED2/15 reproduces missing excluded assets and unwanted fractional extension. Corrections pass final81 main/41 test Java classes,286 native cases; Node282/20/22, core40/52/14/510 and actual Worker dry-run. Bounded review closes Important findings. This is the factory path only: it is not yet wired into owner/autonomous exports or a durable checkpoint journal, and no real window-device render is claimed.

Continue immutable source snapshot identity, verified SQLite segment rows, continuous full-program audio, lossless native mux and shared resumable execution. The complete app and release/deployment remain unfinished. Keep working autonomously.


## 43. Immutable render-source identity — 2026-10-09 UTC

RenderSourceIdentity captures the normalized graph only on a deep copy, hashes all selected original and generated source bytes in256KiB buffers, and pins byte-identical sources into private content-addressed snapshots. Canonical graph keys, project revision/settings, final aspect/quality and renderer/source hashes bind a stable session ID. Later owner media edits cannot modify the pinned bytes; original files/URIs remain unchanged. Duplicate bytes reuse one object only after full size/SHA validation; corrupt pins repair through a synced atomic stage. Actual whole/window MediaItems retain original MIME hints despite content-addressed filenames. Insufficient local snapshot space fails explicitly; direct Vault-backed pinning and cache reference/eviction policy remain integration work.

Source RED10/10 preceded implementation. Growing copy bounds, stale stage and MIME RED3/14 preceded corrections. Independent review found old-hash abandoned stages after death/source changes and uncancellable monitor waits; RED2/16 reproduces both. Managed stages reclaim under a shared timed/interruptible writer lock, preserving completed pins and an active writer's stage. Focused37 source/window/composition cases pass; final82 main/42 test Java classes pass302 native cases and Node282/20/22. Independent helper review closes Important findings. Evidence is unit byte-stream/filesystem fault injection, not new source-pin device or whole-process-death evidence.

These are preparation components: durable segment/session journal, continuous audio, encoded mux and shared owner/autonomous segmented execution are not yet implemented. Public exports still use the previously verified codec engine. Continue the journal and complete integration/device verification, then the full roadmap. Full app/release/deployment remain unfinished.

Matching preparation publication f42d4022597c5fccd3a5eceec21579659855322f, local88ecb4dee8cebf7f02fa1f1695ed8f3e21fee750/tree d062652100f3a4a8e6daf5916bd289142deb66d8: Android37913566915 passes302 unit cases, zero failures/errors/skips, APK99,073,555 bytes SHA2567ba1a47dcb877ab2dd98af0eea451ecc41af8d8e573f50a68bb0a3f2c2970a8d; certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37913566897 succeeds; API33device37913566923 OK(8 tests),38files/5,498,714bytes. Both actual conservative/software codec outputs preserve1280x720/30fps/2Mbps/audio/originals, SHA320ef15a4773a9937a0093335280c2827bb905ba4b3726d59706837544f148cd. Existing device assertions establish baseline export/Vault/owner behaviour only, not direct window/source-pin execution. CI logs/metadata inspected; binaries not independently downloaded. Journal/audio/mux/shared segmented engine, whole app and Cloudflare deployment remain unfinished. Continue autonomously.

## 44. Durable render checkpoint journal — 2026-10-09 UTC

Private SQLite now saves the captured graph/manifest and per-video-window or whole-program audio intent, source-seek metadata, actual producer configuration and fresh playable/size/SHA proof. A generation-specific stage is verified and synced, its encoded intent persists, then an atomic rename and verified row commit finalize it. Recovery revalidates encoded stages or finalized files after either crash boundary without rerendering valid bytes. Missing/corrupt checkpoints invalidate only their own row; transient decoder/read failures preserve checksum-valid files. Duplicate completion is idempotent. Process-generation leases reject conflicting writers and stale callbacks/releases; old encoder file descriptors cannot write into a new generation's differently named stage. Retired pending stages reclaim before replacement, preserving other verified windows and all owner originals.

Admission freezes the caller graph/manifest once, restores the original URI mapping to verify canonical graph/project/revision identity, recomputes the source/settings/renderer session hash and validates full pinned bytes under the managed canonical snapshot root before any lease mutation. Caller graph mutations, paired bound/manifest URI redirection and same-path pin corruption cannot claim stale identities. Source validation streams256KiB buffers and checks interruption; repeated bindings deduplicate only validated pinned objects.

Actual RED12/12 precedes journal implementation. Stage leak and mutable/invented identity RED3/18, then paired URI redirect/corrupt pin RED2/20 precede corrections. Focused51 and final83 main/43 test classes pass322 native cases; Node282/20/22 passes. The127 Java/schema compiled source hashes match the committed files. Bounded independent review closes all Important journal/admission findings. These tests inject a byte-stream verifier and filesystem/process identity faults; actual video/audio window encoding, mux, device force-stop/power loss and owner/autonomous checkpoint reuse remain unverified. This checkpoint is not full segmented rendering or full app completion.

Continue continuous full-program audio, bounded native window worker, compatible lossless mux, shared resumable lifecycle/cache references and actual device evidence, then the remaining professional NLE/providers/cloud/models/2D/3D/VFX/speech/accessibility/performance/release blueprint. Cloudflare deployment remains separately blocked; keep working autonomously.

Matching journal publication cc3450f90b6c9d8cc615769214662950d470cf2b/localb2ea807/tree765e32504990b5bc7d97aec2463ac99f8b39d102: Android37916899169 passes322 unit cases/APK99,089,939 bytes SHA2563ce848bfdb9477a76213dd7f919553670fc3a081c7f09a0256ec881ab2ac463b, certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37916899224 succeeds; API33device37916899571 OK(8 tests),38files/5,493,496bytes. Both actual codec paths preserve1280x720/30fps/2Mbps/audio/originals. Existing baseline device assertions only; journal/segmented recovery has not yet been tested with actual encoded windows. CI logs/metadata inspected, binaries not independently downloaded. Continuous audio/mux/shared segmented engine and full app remain unfinished. Continue.

## 45. Continuous audio and verified lossless segment join — 2026-10-09 UTC

The shared composition factory now builds a separate whole-program audio composition from originals, retaining source trim/speed, track mute/solo selection, exact retime gaps and ClipAudioProcessor gain/pan/DSP. Silent programs return explicitly absent audio. A12s clip keeps one DSP instance across video5s boundaries; delay-impulse unit evidence confirms state crosses input buffers. The ordinary whole-program builder shares this audio path. Native exportComposition accepts video or audio-only verification while retaining the codec retry/cancellation controller; audio-only output does not record an empty video-encoder success. Owner graphs are captured without normalization mutation.

SegmentMediaMuxer preflights fresh whole-file/playable proof, selected track duration/start and a contiguous video plan. AVC dimensions, frame rate, profile/level/rotation/colour and initialization bytes must match; each window begins on an unencrypted complete sync sample. Samples retain original global presentation times, and bounded direct buffers are reused rather than allocated per frame. Incompatible codecs fail recoverably with an explicit consistent-route message; no append/reencode or final quality reduction. A single AAC stream supplies continuous audio. Explicit zero-byte EOS sets video and AAC ends to the planned microsecond; extracted final track durations must agree within1ms container rounding. Source interval tolerance is one frame or AAC packet plus1ms, capped100ms; a truncated playable file cannot masquerade as a full window.

Output admission requires the owner original graph, canonical source protection, a fresh path and atomic O_EXCL/O_NOFOLLOW open. MediaMuxer writes through the held FD; fstat/lstat association prevents using or deleting a replaced path. Final duration inspection, three sampled decoded frames and streamed256KiB SHA verification use the same held FD, with pread preserving caller position/ownership. Cancellation predicates reach every checksum chunk and frame sample. Failed output cleanup targets only the created inode; all owner originals remain untouched.

Audio RED6 and mux RED10 precede implementation. Review owner-output/EOS RED4/14, selected duration/chunk cancellation RED11/21 and held-FD proof RED2/6 precede corrections. Focused42/29 and fresh84 main/46 test Java classes pass351 cases; Node282/20/22 passes. Bounded independent review closes four Important mux findings and the subsequent proof inode race. Instrumentation source is compiled separately against current main/dependencies; matching Gradle/APK/device execution remains required.

The ninth device case is added, not yet executed: actual two5s native windows and continuous10s AAC, encoded-intent fault reuse, individual corruption repair, five sampled frame comparisons with whole-program automation/title clocks, AAC packet/time byte identity, refusal to touch originals, output inode replacement protection and held-MP4 verification while its path refers to a PNG. A separate fractional-tail fixture copies one native encoded sync frame into a1001us container and checks exact final join duration; it is not a tiny Media3 Transformer window or whole-device death test. Public owner/MCP exports still use ordinary native dispatch until shared segmented lifecycle/cache integration. Full app/cloud deployment/release remain unfinished. Continue Task4 and the full blueprint autonomously.

## 46. Matching audio/mux CI and unresolved AAC preflight — 2026-10-09 UTC

Publication8eab08408c30c0d67538b25bd52fd599b9eece60/locald471535/tree d80c7ab54570e53a2ab0362498cc6e8d0aad1010 passes matching Android37920647421:351 unit cases, no failures/errors/skips, APK99,106,323 bytes SHA25660e275f053a903e57220cafcc803b33a19e0c70d25d15abca02b3de0a5a0231d, certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37920647372 succeeds. Actual API33device37920647437 runs9 tests with1 failure: continuous AAC first-sample preflight rejects a leading timestamp before final mux in the new segmented case; existing8 pass. This does not establish successful lossless joining, sampled boundary frames, AAC identity or fractional-tail duration. No success claim or removal of timing validation is justified.

Diagnostic follow-up preserves behavior and emits actual track formats/first sample times. Error detail includes MIME, firstPTS and allowed rounding. Evidence is collected as the app UID using tar because exclusive mux files are private0600; owner file permissions are not broadened. Local focused17 mux tests pass and instrumentation compiles with132 current Java/schema hashes. Direct retrieval of the CI artifact into the workspace is blocked by HTTP403 error1010, although CI logs remain accessible. Continue measured root-cause correction, matching device verification, shared segmented lifecycle/cache ownership and the complete roadmap.

## 47. Measured native AAC preroll correction — 2026-10-09 UTC

Matching diagnostic publication b10bdeac13b2b5f2836375b2536110338ab20db9/locald438da2/tree f8f40b526c47c501549f72d37d9c5cd9e212ef4a: Android37922461892 passes351 tests, APK99,106,323 bytes SHA256476709a48f323ef7d670f2b0363ab45103fa6736c15615b1e0654610c878f583 with the same7e018747 development certificate. Worker37922461915 succeeds. API33device37922461742 runs9 with1 failure and reports actual continuous AAC firstPTS=-36281us, not a positive leading gap. Existing8 cases pass. External-storage app-UID evidence tar is truncated; this run does not establish collected timing JSON or successful joining.

Android13 MPEG4Writer source (LineageOS AOSP-derived) explicitly stores negative first-sample offset, shifts internal track timestamps and restores presentation through an edit list. The empty EOS path does not add that offset. Correction retains negative AAC packets/times, uses sample size rather than timestamp sign to detect end of samples, checks monotonic times and bounds preroll using sample rate/encoder delay (two AAC packets when delay is absent). AAC EOS includes the native internal preroll offset so presentation still ends at the planned program time. No packet dropping, audio reencoding, program shift or final-quality reduction. Video zero-origin checks stay strict.

Regression RED3/20 then EOS RED4/21 precede correction; focused mux/audio/checksum33 pass and instrumentation Java source compiles. Its134-file hash map includes two intentionally untracked lifecycle contract files; they are excluded from this correction publication and remain RED10/12, not implemented. Device AAC packet/time assertion now includes preroll packets. Evidence moves to app-private files/evidence, with app-UID tar and logcat timing diagnostics;0600 mux permissions remain intact. Corrected native device execution and review are still required. Continue shared lifecycle/cache ownership and the full blueprint autonomously.

Bounded review identifies raw AAC duration including preroll, all-negative short-program packets and API29 platform rejection. Review RED4/32 precedes presentation-duration/shifted-EOS corrections and38 focused GREEN. Paired preflight RED1/24 and paired/API29 RED2/25 precede final40 focused GREEN. AAC gapless delay/padding sample counts trim raw mdhd duration for verification and preflight/final interval checks; video duration checks stay strict. AAC EOS validates against the internally shifted last packet and permits valid short programs with only negative packet PTS. Android10/API29 rejects negative MediaMuxer timestamps and lacks the native edit-list support; typed preflight failure occurs before output creation and preserves checkpoints. API29 needs an alternative lossless mux route or ordinary whole-program export; compatible segmented support is unfinished. Independent review closes these bounded Important corrections; matching native execution is still pending.


## 48. Measured AAC source extent and lifecycle review — 2026-10-09

Published85e3180 / local6db7b84 / treec7619acf88f3ab6d011eca924e0dfd980e8e7b5f: Android37925284877 passes362 unit cases and builds99,106,323-byte APK, SHA2568d86d9e4bc02dc437b0e213f12d4956e43d39aa5f34c5810eedda0f6153cf779. Worker37925284919 succeeds. Device37925284900 runs9 with1 failure; existing8 pass. Private evidence collection succeeds. Continuous10s AAC raw duration9984580us,44100Hz,delay1600,padding0 presents9948299us. Strict duration preflight correctly refuses joining that short input. Fresh complete-track diagnostics must include negative preroll, packet count and final PTS. API33 extractor raw duration is not pretrimmed. Media3 empty encoder EOS plus software AAC flush handling suggest lost partial PCM/codec tail; confirm before correction. Do not extend the last packet or remove codec-delay trimming to hide missing audio.

Local shared coordinator tests use real source hashes/SQLite and synthetic codec proofs, not actual codec execution. Review caught invalidation losing cleanup references, late progress after terminal delivery, unbounded callback queues and audio encoder metadata copied from video-only windows. Regression work is in progress. Public dispatch, checkpoint cache ownership/eviction and shared native end-to-end export remain unfinished. Continue these and the full blueprint; this is not project completion.


## 49. Shared segmented coordinator source and reviewed recovery — 2026-10-09

Internal SegmentedRenderEngine serializes checksum-bound source capture, five-second original video windows, one continuous audio pass and verified mux. Real SQLite/source-hash tests use explicitly synthetic codecs. Initial10/12RED plus journal2/22RED preceded implementation; review5/39RED, missing-route1/41RED and interrupted-final-route retirement1/43RED preceded fixes. Focused43 and full385 Android unit cases pass with85 main/47 test classes freshly compiled. Current instrumentation source compiles independently; neither check establishes actual shared device execution. Bounded review closes the lifecycle delta.

Progress has one pending callback carrying the newest value; retired encoder stages and STOP cannot deliver late progress/success. A throwing completion-persistence callback explicitly sends error to wake its waiter; successful delivery suppresses later terminal changes. Source graph is frozen before queued work. Result audio encoder comes from the continuous audio track. Chosen codec route and all old VIDEO retirement intents commit in one transaction. Cleanup retains generation references until files are removed; recovery replays retirement while preserving new-route verified prefixes and AUDIO. Journal schema1->2 migration retains captured graphs/generation and defaults conservative. Header repairs are bounded through software routes without lowering requested quality.

Matching678e633 runs Android37927569481/Worker37927569291 successfully,362 unit cases/APK99,106,323 bytes with SHA2568d86d9e4bc02dc437b0e213f12d4956e43d39aa5f34c5810eedda0f6153cf779. Device37927569295 existing8 pass/new segmented1 fails preflight. Fresh track diagnostics show exactly430 AAC packets from both10s whole and continuous audio-only exports, first-36281us,last9925079us,raw9984580us,presentation9948299us. Confirmed missing codec tail blocks native joining. Public dispatch/cache ownership and native shared completion remain unfinished; next correct encoder draining and trim only codec padding, then rerun matching device proof and continue every remaining blueprint phase.


## 50. Owner-requested usable APK checkpoint and AAC drain correction (2026-10-09)

Immediate scope changes to a polished, installable3.4.11/version351 preview, then continuation on the owner’s next resume/continue request. Keep every remaining full-blueprint requirement active. The original document prefix is preserved. Do not call the project complete.

The measured Android13 C2 AAC encoder emits430 packets for10s input and loses the partial final frame/delay on empty EOS. Native source confirms its flush output is not queued. A bounded codec adapter feeds8192 zero PCM frames after the real programme through actual codec input/backpressure, then one real EOS. It wraps only c2.android.aac.encoder. A held-FD MP4 muxer retains every encoded packet and raw mdhd/stts duration; a bounded4096-box metadata parser adjusts existing edit/tkhd/movie durations to the exact programme. No fake packet stretching, dropped tail or duration relaxation. Version0/1 metadata, sparse5GiB mdat skip, malformed/cancelled inputs, codec backpressure/format/release, low-rate padding and source/workspace protection have focused regressions.

Independent review found and closed two Important gaps: exact zero-delay AAC without an edit list now has a validated byte-preserving no-op, and native verification must match writer-held SHA/size before success. RED1/8 and RED2/12 preceded fixes. The service additionally binds the render proof before publication. Current preview fresh Java suite passes402 cases; instrumentation source compiles. These are JVM/source facts, not native AAC evidence. Actual device verification and release publication remain mandatory and pending at this append.

The home screen prioritizes media import and opening the editor and adds a short guide. ChatGPT operations continue through the stable native MCP surface and durable foreground service; a new actual-device fixture edits, replays without duplication and exports while the owner editor is closed. This does not verify a live ChatGPT account/connector session. Private identity and Gallery boundaries remain unchanged.

The owner-preview workflow checks out exact PR-head source, restores the established development key, builds unit/app/test APKs, executes API33 emulator checks on that signed app, and only then creates a separate immutable preview release with APK/SHA/verification manifest. Full cloud/model/3D work, owner Realme tests and public segmented dispatch/cache references remain unfinished. No main history or latest release is overwritten.

Next resume: inspect owner preview findings first, preserve source/identity/signing/project data, reproduce relevant failures, then complete matching native segmented engine integration, reference-aware cache lifecycle and remaining blueprint phases. The released verification manifest is authoritative for source/tree/APK/certificate/run identity.
