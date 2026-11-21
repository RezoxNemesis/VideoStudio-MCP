# A clay film

A stop-motion film written as code and drawn by VideoStudio's clay engine on the graphics card.

- `film.js` — the film: its shots, in order. The first line `// @engine clay` chooses the engine.
- `audio.wav` — the finished sound (voices, music, effects); `cine.render` lays it under the picture.
- `lips.json` — mouth shapes for the spoken lines, made by `cine.tool {film, script: "lips.py", args: ["lines.json", "lips.json"]}`.

Read `clay.help` first. Look before you render: `cine.frame {film, shot, t, width: 720, height: 1280}`,
`cine.sheet {film, shot, times, cell: 360, height: 640}`. Then `cine.render {film, width: 2160, height: 3840}`.
