# Open Animation Workbench

The owner wants a stable native VideoStudio where a person and ChatGPT can perform the same precise editing, sketching, animation, import/export and generation work. Commercial Veo/Kling/Runway APIs and subscriptions are explicitly excluded. Use open-source model inference and controllable production techniques; do not claim proprietary architecture parity or exact inverse reconstruction from drawings.

Baseline is recovery PR69 commit `68e3b50`, retaining its fair durable ACK outbox and independent 3D executor. Preserve every existing project, earlier blueprint, applicationId `com.rezoxnemesis.videostudio.next`, MCP application v3 identity and pinned signer. No database resets, fallback keys or unverified main release.

## Native authoring

Add a native Sketch Studio with a bounded app-private document, layered vector strokes/shapes/reference images, exposure/cel animation, onion skins, explicit transforms and transform keys. Pressure and normalized source coordinates are retained. Touch tools and `sketch_*` commands share the document operation engine, exact revision/CAS and bounded undo/redo. Actual Canvas frame rasterization feeds native Media3 export into a separate output project. Export always carries frame count/FPS and encoding evidence, not invented decoded proof.

Repair the main editor's preview/export gap. A shared clip-keyframe contract and renderer effect builder are consumed by real Media3 editing preview and export. Add numeric keyframe controls and batched clip edits with source revision checks. Unsupported tools must say so; UI labels cannot stand in for an implemented effect.

## Expanded 3D production

Keep the existing eleven sword actions and their defaults. Add IDLE/WALK/RUN/JUMP/DODGE/DUCK/PUNCH/KICK/DANCE/WAVE and explicit grounded/anime/martial/cartoony motion styles. Add bounded rest-skeleton-aware actor morphology/material presets. Imported action packs are first-class; procedural effects stay tied to actual body/weapon/contact clocks. Native and Worker contracts validate the same optional actors/motionStyle data; changing baked poses, morphology or styles requires a matching rebuilt bundle.

Provide genuine cloud conditioning passes from the evaluated scene: projections/pose, cameras, depth, normals, actor/prop masks and beauty. Sidecars retain exact frame order/hashes without inflating phone archives. Explicit 2D-to-3D mapping retains source/profile hashes, coordinate/depth assumptions and uncertainty. Estimated guides must be labelled.

## Open-source generation and storage

Use a self-hosted Python job runner around actual Blender, CogVideoX/Diffusers, configured ComfyUI and interpolation providers. Requests are inert, validated and idempotent; executables/endpoints/model paths come only from trusted runner configuration. Jobs have durable status/checkpoints, resource admission, timeouts, heartbeat and cooperative cancellation with process cleanup. The runner requires authenticated HTTP, bounds request/artifact sizes and owns every accepted path. No hosted commercial generation service.

The common schema is `videostudio.openpipeline.request.v1`: stable `requestId`, `projectId`, `sourceProjectUpdatedAt`, backend (`blender`, `cogvideox`, `frame_sequence`, `interpolation`), explicit generation controls and an ordered hash-bound source list. An immutable `.vsjob` contains the request and owned input files; `.vsresult` contains a manifest and hash-bound usable outputs. Native capabilities report actual endpoint/model/compute readiness. Human UI and `open_pipeline_*` commands use the same operation facade.

Drive is the durable artifact exchange: project-scoped Requests/Results/Models folders and immutable job generations. Verify copies/readback before reporting archived or importing results. Preserve local originals and final exports. A configured free runner can watch a local/rclone-mounted Drive folder; Drive storage does not itself execute inference. Existing native single-folder SAF capability is reused, without inferring access from a separate ChatGPT Drive connection.

## Completion evidence

Run focused meaningful regressions per subsystem, then one full native and Worker verification and one original-signer CI APK. Verify real Blender conditioning and actual CPU model inference if supported; mark unavailable prerequisites precisely. Never promote surrogate ACKs, cloud output or JVM checks into phone GPU/ChatGPT completion. Publish source and a concrete verified build with usage documentation and runtime limitations.
