# VideoStudio 2.3 — Code Studio, the libraries and the Blender forge

## Code Studio (the Code page)
A film written as code: a folder with `film.js`, `seq/*.js`, `script.json`, `cues.json` (kept in `~/Videos/VideoStudio/Code/<name>`).
- **Engine**: `desktop/film/` — a plain Node (ESM) package: `engine/` (the 2D character renderer), `kit/` (sequences, acting,
  places, props, people, sound, fingerprints), `runner/worker.mjs`. The app runs it as worker processes
  (`desktop/electron/film-pool.cjs`): one "desk" worker answers quick questions (a frame, what changed), the others draw shots
  side by side. `desktop/electron/film.cjs` is the IPC surface (`vsd.film`).
- **Only what changed is drawn**: every shot has a fingerprint made from what it will show (poses, camera, focus, faces,
  designs, set options, the source of its drawing hooks; shots with drawing hooks also count a few small drawn frames). Each
  shot is its own video file with identical encoder settings; the film is joined with the concat demuxer and `-c copy`, the
  sound is mixed apart and laid under, subtitles are a track. Shots can be switched off without deleting anything.
- **Made in the window, kept with the film** (`desktop/src/film/prepare.ts`): voices (the open voice engine), body motion
  (the 3D Stage's motion capture + IK on a scratch studio), music and beds (the composer). All keyed, so only new lines /
  changed performances / new cues are made again.
- **Commands** (`desktop/src/film/session.ts`): `code.help` (the full reference, `desktop/film/API.md`), `code.list/new/open/
  status/files/read/write/patch/remove/frame/sheet/build/stop/shots/check/export/toTimeline/config/people/person/sets`.
- **Page** (`desktop/src/ui/codepage.ts`): files, a CodeMirror editor, the frame under the scrubber, the shot strip
  (green = drawn, amber = changed, grey = new, striped = off), People and Places tabs.
- **Libraries**: 380 people (28 groups), 15 general places × 4 hours + 7 story sets, 126 props, 32 effects + 11 ambience beds.

## 3D model library and props on the Stage
`desktop/stage-assets/models/` holds 854 glTF models in 8 packs (KayKit by Kay Lousberg, CC0 1.0; licence texts included),
with `index.json` (id, size in metres, per-pack scale so a door is door-sized beside a person). `src/stage/models.ts` loads
them; a Stage set holds them as `{kind: "model"}` items. Commands: `stage.models {q, pack}`, `stage.prop {sceneId, model, at, rot, scale, id}`.
Any `.glb` / `.gltf` file on the PC works as `model` too.

## Blender forge
`desktop/forge/` (`run.py`, `forge_lib.py`, `recipes.py`) + `desktop/electron/forge.cjs`: the person's own Blender, run without a
window, builds an asset and saves a `.glb` into `~/Videos/VideoStudio/Models`. Commands: `forge.status`, `forge.make {recipe, params}`
(rock, rocks, tree, bush, terrain, mountain, building, lathe, text, crystal, stairs, arch, fence, planet, cloud, furniture),
`forge.script {name, code}` (Blender Python with the `F` helpers), `forge.convert {from}` (fbx, obj, stl, ply, dae, usd, blend → glb),
`forge.list`. Blender is found on PATH, in the usual places, as a Flatpak, or through `VIDEOSTUDIO_BLENDER`.

## Building
`npm install --prefix desktop/film` (the engine's canvas library) before `npm run dist` in `desktop/`; `build/afterPack.cjs`
copies it into the packaged app.
