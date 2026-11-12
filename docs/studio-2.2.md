# VideoStudio 2.2: animation, music and voices

What is new in the desktop app, where to find it, and the commands behind it. Everything below is a JSON command
too (`{"op":"help"}` lists them), so Claude can do each step and you can undo each step.

## 3D page (real characters, motion capture, fights, cameras)

A scene on the timeline that holds a 3D stage: human characters sculpted from one base body, 2,500+ captured
performances, walking with planted feet, sword and unarmed fights, cameras that frame like an operator, and a
cinematic or an anime (cel and ink) look.

- **Director's view**: drag to orbit, right-drag to pan, wheel to zoom; Top, Front and Side views; "Camera" shows
  the film as it will render. The view never changes the film.
- **Cast**: add characters (look, kit, start place, facing). Click one in the view or the list to select it.
- **Add at the cursor**: a motion from the library (search, then look at a strip of it before using it), walk to
  (pick the tool, then click the ground), stance, idle, sit, kneel, fall, a spoken line with lip-sync, look at,
  an expression, a fight, a shot, an effect.
- **Fight**: write one beat per line, for example `a jab miss slip`, `a cross hit`, `b hook miss duck`,
  `a highkick hit ko`. Both fighters take stances with planted feet; hits get hit-stop, impact frames, sparks,
  shake and a cut plan.
- **Timeline**: shots, each character's actions, overlays and lines, and effects. Drag to retime, drag the right
  edge to change length, Delete removes, double-click goes to its start.
- **Check** finds sliding feet, bodies inside each other, subjects out of frame and time without a shot;
  **Contact sheet** shows the film at eight moments.

Commands: `stage.new`, `stage.actor`, `stage.motions`, `stage.motion`, `stage.do`, `stage.overlay`, `stage.say`,
`stage.cam`, `stage.fx`, `stage.fight`, `stage.edit`, `stage.delete`, `stage.sheet`, `stage.check`, `stage.trace`.

## Anime action for 2D (Animate → Action)

Works on every 2D layer: drawings, rigs and characters, cut-outs, living pictures, 3D layers.

- **Moves** in one tap at the current frame: impact, dash, power-up, beam, clash, slash.
- **Impact beat**: hit-stop (the drawing freezes, effects keep moving), camera shake, zoom punch, white or
  inverted flash.
- **Effects inside the scene**, in front of or behind a character: focus lines, rush lines, action background,
  lightning, beam, aura, plus the earlier fire, sparks, dust, debris, shockwave and slash.
- **Per layer**: poses on twos or threes, afterimages behind fast motion, an aura around the figure.

Commands: `anime.move`, `anime.beat`, `anime.fx`, `anime.layer`, `anime.sheet`, `anime.get`, `anime.clear`.

## Music page

- **Compose** from a brief: 25 styles (cinematic, action, anime rock, lo-fi, Bollywood romantic and dance, Indian
  classical, devotional, garba, Japanese and more), 15 moods, any key, western scales and 23 ragas, eight taals.
- **Instruments**: 261 instruments and 13 drum kits (General MIDI bank). Pick per track; volume, pan, reverb,
  mute and solo.
- **Piano roll**: add, move, resize and delete notes; audition; grid and triplets.
- **Songs with words**: type lyrics (Hindi, Hinglish or English), choose a singer; the tune is written to the
  words and sung over the band. `music.sing` gives words to an existing melody.
- **MIDI** in and out. **To timeline** renders the song and places it in the edit.

Commands: `music.styles`, `music.instruments`, `music.generate`, `music.new`, `music.track`, `music.notes`,
`music.edit`, `music.get`, `music.sing`, `music.render`, `music.exportMidi`, `music.importMidi`.

## VolTune voices

- **Hinglish**: Hindi typed in English letters is read as Hindi; English words inside it stay English.
  `voice.hinglish` shows how a line will be read.
- **Indian English**: Hindi voices keep English lines and say them with Indian sounds.
- **Tones**: neutral, warm, happy, excited, sad, angry, stern, calm, afraid, tired, question, whispery,
  commanding, playful, indian; or set pitch, range, rate, energy and the line ending yourself; stress chosen words.
- **Singing**: the same voices hold notes. Women's voices stay in tune from E3 to E4, men's from G2 to C4; tunes
  are moved into that range.

Commands: `voice.tones`, `voice.say`, `voice.hinglish`, and `tone` / `emphasis` on `stage.say`.

## Assets and building

The app bundles four things that are not in the repository: the body pack (MakeHuman base body, CC0 assets), the
motion library (CMU Graphics Lab motion capture, free for all uses), the instrument bank (GeneralUser GS, free for
any use) and the voice model (Kokoro, Apache-2.0, split so timing and pitch can be set).

```bash
npm ci
pip install numpy onnx
tools/build_stage_assets.sh        # → desktop/stage-assets (about 390 MB, built once)
cd desktop && npm ci && npm run dist
```

CI does the same and keeps the assets between runs.

## Limits to know

- The singer is a speaking voice held on pitch: clear words and a steady tune, a limited range, no belting.
- Tabla and dholak are played with the bank's hand drums (congas and bongos); there is no sampled tabla.
- Unarmed strikes are solved (hand or foot driven to its target), not captured; sword work, walks, falls and the
  library motions are captured.
- The 3D preview needs a graphics card to be smooth. Without one the page lowers the preview quality by itself;
  renders are not affected.
