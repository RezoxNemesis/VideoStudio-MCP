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
