# VideoStudio 3.4.1 independent creative runtime

The generation path stays within VideoStudio. There is no Runway, Veo, Kling or paid inference API dependency. This release adds original procedural scene generation, not a neural video model. Phone storage and Drive hold assets/checkpoints; they do not replace inference compute.

## Connection Core 3

Keep the existing private connector. Its stable URL and device identity survive this upgrade. The app has a **Repair Connection** button, network-availability reconnection, and a SQLite result outbox. Completed command results remain on disk until a matching server receipt arrives. Reconciliation polls unresolved leases regardless of the highest previously acknowledged sequence. The relay also rejects queue saturation instead of dropping pending work. HTTP redirects cannot carry owner credentials to another origin.

The native reconciliation change works with the existing deployed relay. The relay queue-preservation improvement and dedicated image tool require a Worker deployment; existing `app_execute` can dispatch the new native actions without that deployment. This repository does not contain deployment credentials.

State reads are deliberately excluded from the mutation journal. Legacy stored get_state results are purged during journal initialisation: storing a snapshot of a journal inside the same journal recursively inflated diagnostics on the live phone. Mutation results keep their replay protection.

## Executable generation

`generate_image` renders an original local PNG, persists generation provenance and registers the image in the Media Bin. It is a recoverable job; `job_status` returns the generated asset id and URI. `prompt_video` now builds procedural scene graphs and evaluates them at every Media3 video timestamp. It no longer uses title cards as its default visuals.

The built-in renderer supports circles, rectangles, ellipses, lines, polygons, cubes, pyramids and custom triangle meshes. Objects expose start/end position, rotation, spin, secondary oscillation and wrapping. Meshes use actual XYZ vertices, a perspective camera, camera orbit, sorted triangles and directional diffuse shading. This is bounded software 3D rendering, not a PBR/glTF engine or photorealistic human synthesis. The same geometry renders the initial still and every export frame.

The offline prompt director supports procedural space/geometry, forests/rain and abstract graphics. More precise or novel compositions should be supplied as a sceneGraph by ChatGPT. It does not understand arbitrary natural-language scenes like a trained video model. Photorealistic human requests return a missing-provider error instead of silently generating graphic cards. Imported portraits still use the existing segmentation, face-aware layers and articulated 2.5D motion.

## Autonomous bridge examples

Use `app_status` then `app_state` and wait for command completion before editing. All examples below are action/parameters payloads for the installed generic `app_execute` bridge.

```json
{"action":"creative_system_status","parameters":{}}
```

```json
{"action":"generate_image","parameters":{"prompt":"3D orbiting geometry","width":720,"height":1280,"appendToTimeline":false}}
```

For a video, ChatGPT can author any supported geometry rather than relying on the small offline prompt director:

```json
{"action":"prompt_video","parameters":{"prompt":"An original orbiting sculpture","durationSeconds":8,"aspect":"9:16","quality":"720p","scenes":[{"durationMs":8000,"transition":"none","sceneGraph":{"version":1,"background":"#080d21","cameraOrbit":8,"fov":48,"objects":[{"type":"cube","x":0,"y":0,"z":4.8,"size":0.8,"spin":25,"color":"#59d9e8"},{"type":"pyramid","x":1.1,"y":-0.4,"z":5.2,"size":0.35,"spin":-18,"color":"#d892ef"}]}}]}}
```

Custom `mesh` objects carry `vertices` as XYZ arrays and `faces` as integer index triples. Limits are 128 objects, 512 vertices per custom mesh, 1024 triangles per mesh and 4096 triangles per scene. Split heavy compositions into shots. The quality/resource governor remains active. Budget failures are explicit rather than crashing on uncontrolled mesh input.

Inspect `job_status`, the exported Media Bin asset and a contact sheet before reporting video success. Encoding completion alone does not prove visual quality.

## Drive

ChatGPT's Drive connector and Android's Drive storage access are distinct. The existing Android integration archives and restores through one selected document-tree folder. The app needs a persisted folder grant from its Control screen. Google Drive can be used only if its installed document provider supports this folder operation; otherwise this path is a local/document-provider archive, not a direct Google Drive API integration. No OAuth client or Drive token is fabricated. The ChatGPT Drive connector remains available for authorised separate transfers.

## Remaining work and verification

Neural text/image-to-image and image-to-video adapters, compatible licensed local model packs, full facial/body synthesis, optical flow, learned interpolation, glTF/PBR and advanced cloth/hair simulation remain unimplemented. Registry installation alone is insufficient. Each model must have a real loader, input/output conversion, scheduler integration, checksum validation and a device benchmark before it can be advertised as executable.

Connection tests exercise actual relay methods for out-of-order acknowledgements, expired/active leases, replayed completion, queue preservation/saturation and wrong credentials. Android unit tests exercise geometry distance preservation, perspective scale, near-plane clipping, easing and finite lighting. CI builds an APK and runs these unit tests. Real phone rendering, process-death outbox recovery, screen-off reconnection and Drive restore still need on-device tests; an offline phone cannot be controlled or remotely reopened by this connector.
