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
    async () => cine().help(),
  );
  registerCommand("cine.list", "The Cine films on this PC, where they are kept, and whether the shared assets (bodies, painted textures, fonts) are in place.", async () => cine().list());
  registerCommand("cine.new", "{name} Start a Cine film: a folder with a small working film.js to change.", async (_st, a) => cine().create(a.str("name")));
  registerCommand("cine.open", "{film: name or folder} A Cine film: its shots in order with their lengths, its files, how many five-second pieces are already drawn.", async (_st, a) => cine().open(a.str("film")));
  registerCommand("cine.read", "{film, file} Read one of the film's files (film.js, moves.py, audio.py …).", async (_st, a) => ({ file: a.str("file"), text: await cine().read(a.str("film"), a.str("file")) }));
  registerCommand(
    "cine.write",
    "{film, file, text} Write a file of the film (new or whole replacement). After film.js the answer says whether the code loads, and the mistake if not. A film may carry its own engine.js / kit.js: the film's file is used before the app's.",
    async (_st, a) => cine().write(a.str("film"), a.str("file"), a.str("text")),
  );
  registerCommand(
    "cine.frame",
    "{film, shot, t? (seconds into the shot), width?=1280} One frame of a shot exactly as it will be in the film (everything before it is simulated first, so cloth, particles and feet are where they will be). Look at full-size frames of faces, hands and every low-angle shot before a long render.",
    async (_st, a) => { const r = await cine().frame(a.str("film"), a.str("shot"), a.num("t", 0), a.optNum("width"), a.optNum("height")); return { jpeg: r.jpeg, shot: r.shot, t: r.t, width: r.width, height: r.height, file: r.file }; },
  );
  registerCommand(
    "cine.sheet",
    "{film, shot, times: [s…], cols?=4, cell?=640} Several moments of a shot in one picture: the way to judge movement, staging and the camera's path.",
    async (_st, a) => { const t = a.any("times"); const r = await cine().sheet(a.str("film"), a.str("shot"), Array.isArray(t) ? t.map(Number) : [], a.optNum("cols"), a.optNum("cell")); return { jpeg: r.jpeg, shot: r.shot, times: r.times, cols: r.cols, rows: r.rows, file: r.file }; },
  );
  registerCommand(
    "cine.render",
    "{film, shots?: [ids] (draw these again), width?=2560, height?=1440, fps?=30, crf?=15, force?=false, name?, maxMB? (also make a copy that fits this size, for sending in a chat)} Draw the film on the graphics card in five-second pieces (a piece already drawn is kept), join them and lay the film's audio.wav under at -14 LUFS. It runs on: ask cine.progress. The finished film is in the film's out folder and in Videos/VideoStudio.",
    async (_st, a) => {
      const p: Record<string, unknown> = {};
      for (const k of ["width", "height", "fps", "crf", "maxMB", "piece"]) if (a.optNum(k) !== undefined) p[k] = a.num(k);
      if (Array.isArray(a.any("shots"))) p.shots = a.any("shots");
      if (a.any("force") !== undefined) p.force = a.bool("force");
      if (a.optStr("name")) p.name = a.str("name");
      if (a.optStr("preset")) p.preset = a.str("preset");
      return cine().render(a.str("film"), p);
    },
  );
  registerCommand("cine.progress", "The Cine render that is running or just finished: pieces done, frames a second, time left, the finished file, or the mistake.", async () => cine().progress());
  registerCommand("cine.join", "{film, maxMB?, name?} Join the drawn pieces again and lay the sound under (after changing only audio.wav).", async (_st, a) => cine().join(a.str("film"), { maxMB: a.optNum("maxMB"), name: a.optStr("name") }));
  registerCommand("cine.stop", "Stop the Cine render (pieces already drawn are kept).", async () => cine().stop());
  registerCommand(
    "cine.tool",
    "{film?, script (mh.py | mocap.py | paint.py | audio_kit.py, or a .py of the film's own such as moves.py or audio.py), args?: [..]} Run one of the engine's Python tools on this PC (bodies, performances from captured motion, painted textures, synthesised sound). Needs python3 with numpy, scipy, opencv and pillow; if this PC has none, run the tool in a workspace and put the data into the film's folder.",
    async (_st, a) => { const args = a.any("args"); return cine().tool(a.optStr("film"), a.str("script"), Array.isArray(args) ? args.map(String) : []); },
  );
}
