/**
 * Commands for Cine 3D: films staged in real depth (painted layers on true distance, toon bodies driven by captured
 * motion, one real lens) and drawn on this PC's graphics card. A Cine film is a folder with a film.js; the engine and
 * its guide come with the app (film/cine). See electron/cine.cjs.
 */
import type { Studio } from "../../src/app/studio";
import { registerCommand } from "../../src/agent/api";
import { need } from "./bridge";

function cine() {
  const c = need().cine;
  if (!c) throw new Error("This version of VideoStudio Desktop has no Cine 3D engine.");
  return c;
}

export function registerCineCommands(_s: Studio): void {
  registerCommand(
    "cine.help",
    "Cine 3D, the engine for 2.5D films with real depth, captured body movement and a directed camera (rack focus, cranes, orbits, dolly-zoom): the whole guide — how a film folder is laid out, the engine's and kit's functions, how bodies and performances are made, every light and colour number of the finished film \"The Long Road to VI\", the camera moves, the checks, and the mistakes already paid for. Read it before making a Cine film.",
    async (_st, a) => cine().help(a.optStr("topic")),
  );
  registerCommand(
    "clay.help",
    "CLAY, the stop-motion studio of Cine: films of clay puppets (talking mouths with teeth and tongue, bead eyes, felt clothes) in miniature sets (a whole train car, a model city at night) shot through a shallow-focus lens, drawn on the graphics card at up to 4K. The guide: how a clay film is laid out, the engine, puppets, sets and props, lips from a voice, the method (look sheet first, vertical framing, hands that follow the thing held), the checks, the mistakes already paid for. A clay film is a Cine film whose film.js begins with `// @engine clay`; every cine.* command works on it. Start one with cine.new {name, kind: \"clay\"}.",
    async () => cine().help("clay"),
  );
  registerCommand("cine.list", "The Cine films on this PC, where they are kept, and whether the shared assets (bodies, painted textures, fonts) are in place.", async () => cine().list());
  registerCommand("cine.new", "{name, kind?: \"clay\" (a stop-motion film of clay puppets and model sets; see clay.help)} Start a Cine film: a folder with a small working film.js to change.", async (_st, a) => cine().create(a.str("name"), a.optStr("kind")));
  registerCommand("cine.open", "{film: name or folder} A Cine film: its shots in order with their lengths, its files, how many five-second pieces are already drawn.", async (_st, a) => cine().open(a.str("film")));
  registerCommand("cine.read", "{film, file} Read one of the film's files (film.js, moves.py, audio.py …).", async (_st, a) => ({ file: a.str("file"), text: await cine().read(a.str("film"), a.str("file")) }));
  registerCommand(
    "cine.write",
    "{film, file, text} Write a file of the film (new or whole replacement). After film.js the answer says whether the code loads, and the mistake if not. A film may carry its own engine.js / kit.js: the film's file is used before the app's.",
    async (_st, a) => cine().write(a.str("film"), a.str("file"), a.str("text")),
  );
  registerCommand(
    "cine.frame",
    "{film, shot, t? (seconds into the shot), width?=1280, height? (give both for a vertical film, e.g. 720 × 1280)} One frame of a shot exactly as it will be in the film (everything before it is simulated first, so cloth, particles and feet are where they will be). Look at full-size frames of faces, hands and every low-angle shot before a long render.",
    async (_st, a) => { const r = await cine().frame(a.str("film"), a.str("shot"), a.num("t", 0), a.optNum("width"), a.optNum("height")); return { jpeg: r.jpeg, shot: r.shot, t: r.t, width: r.width, height: r.height, file: r.file }; },
  );
  registerCommand(
    "cine.sheet",
    "{film, shot, times: [s…], cols?=4, cell?=640 (a cell's width), height? (a cell's height; give it for a vertical film, e.g. cell 360, height 640)} Several moments of a shot in one picture: the way to judge movement, staging and the camera's path.",
    async (_st, a) => { const t = a.any("times"); const r = await cine().sheet(a.str("film"), a.str("shot"), Array.isArray(t) ? t.map(Number) : [], a.optNum("cols"), a.optNum("cell"), a.optNum("height")); return { jpeg: r.jpeg, shot: r.shot, times: r.times, cols: r.cols, rows: r.rows, file: r.file }; },
  );
  registerCommand(
    "cine.render",
    "{film, shots?: [ids] (draw these again), width?=2560, height?=1440 (a vertical 4K film: 2160 × 3840), fps?=30, crf?=15, encoder?: gpu | x264 (left out: the graphics card encodes (NVENC) when this PC can, so the processor stays free), force?=false, name?, maxMB? (also make a copy that fits this size, for sending in a chat)} Draw the film on the graphics card in five-second pieces (a piece already drawn is kept), join them and lay the film's audio.wav under at -14 LUFS. It runs on: ask cine.progress. The finished film is in the film's out folder and in Videos/VideoStudio.",
    async (_st, a) => {
      const p: Record<string, unknown> = {};
      for (const k of ["width", "height", "fps", "crf", "maxMB", "piece"]) if (a.optNum(k) !== undefined) p[k] = a.num(k);
      if (Array.isArray(a.any("shots"))) p.shots = a.any("shots");
      if (a.any("force") !== undefined) p.force = a.bool("force");
      if (a.optStr("name")) p.name = a.str("name");
      if (a.optStr("preset")) p.preset = a.str("preset");
      if (a.optStr("encoder")) p.encoder = a.str("encoder");
      return cine().render(a.str("film"), p);
    },
  );
  registerCommand("cine.progress", "The Cine render that is running or just finished: pieces done, frames a second, time left, the finished file, or the mistake.", async () => cine().progress());
  registerCommand("cine.join", "{film, maxMB?, name?} Join the drawn pieces again and lay the sound under (after changing only audio.wav).", async (_st, a) => cine().join(a.str("film"), { maxMB: a.optNum("maxMB"), name: a.optStr("name") }));
  registerCommand("cine.stop", "Stop the Cine render (pieces already drawn are kept).", async () => cine().stop());
  registerCommand(
    "cine.studio",
    "{film?: name or folder; \"@library\" for the CodeScape library (people, hands, holds, placing, speech)} Open Cine Studio, a window where the film plays live on the graphics card (real time, with its sound), with the code of the shot on screen beside it; a change to any file of the film (cine.write, or by hand) is loaded by itself. Its Library tab lists everything the film can use (the film's own modules and its engine's: rig, people, motion), its Check button runs the deep review, and a render that is running is shown as it is drawn. The owner watches work here.",
    async (_st, a) => { const c = cine(); if (!c.studio) throw new Error("This version of VideoStudio Desktop has no Cine Studio."); return c.studio(a.optStr("film")); },
  );
  registerCommand(
    "cine.review",
    "{film, shot?} The deep review of a Cine / CodeScape film, measured without drawing a frame (seconds): for every shot, how much the picture shakes (a lens tied to a talking head or a trembling hand: limit 3 px at 720×1280), the nearest body or prop to the lens (limit 12 cm), and every hold the film notes in c.rep (reach under 92 % of the arm, wrist under 60°, finger pads within 2 mm, the thumb long enough). → { ok, must: [what to fix], text }. Run it after every change, before any frame is looked at; then look at full-size frames. A CodeScape film (`// @engine codescape`) gets engine.js, kit.js, rig.js, people.js, face2.js and motion.js (hands that hold: pinchSheet; speech movement: speechPlan/speechHead; steady cameras: steady/anchorAt) from the app.",
    async (_st, a) => { const c = cine(); if (!c.review) throw new Error("This version of VideoStudio Desktop has no review."); const r = await c.review(a.str("film"), a.optStr("shot")); return { ok: r.ok, must: r.must, text: r.text, shots: r.shots }; },
  );
  registerCommand(
    "cine.mocap",
    "{film, video: its whole path, or a file in the film's folder, name?, from?, to? (seconds)} Motion capture from an ordinary video of a person (no suit, no markers): finds the body in every frame (MediaPipe pose on this PC's Python 3; the first run sets up its own private tools, a few minutes) and writes <name>.json into the film's folder, a clip the CodeScape rig plays with body.js clipOf + playClip (arms, spine, head, and the legs when the whole body was in the picture). The Library film's \"mocap\" shot shows one.",
    async (_st, a) => { const c = cine(); if (!c.mocap) throw new Error("This version of VideoStudio Desktop has no motion capture."); return c.mocap(a.str("film"), a.str("video"), { name: a.optStr("name"), from: a.optNum("from"), to: a.optNum("to") }); },
  );
  registerCommand("cine.unpack", "{file: a .tar.gz lying in the Cine folder} Unpack many files at once into the Cine folder (shared assets, a film's performances): the way data made in a workspace arrives.", async (_st, a) => cine().unpack(a.str("file")));
  registerCommand(
    "cine.tool",
    "{film?, script (mh.py | paint.py | qa.py | lips.py (mouth shapes from a spoken line: args [lines.json, lips.json]), or a .py of the film's own such as moves.py or audio.py), args?: [..]} Run one of the engine's Python tools on this PC (bodies, performances from captured motion, painted textures, synthesised sound). Needs python3 with numpy, scipy, opencv and pillow; if this PC has none, run the tool in a workspace and put the data into the film's folder.",
    async (_st, a) => { const args = a.any("args"); return cine().tool(a.optStr("film"), a.str("script"), Array.isArray(args) ? args.map(String) : []); },
  );
}
