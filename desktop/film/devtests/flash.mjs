// Flashes and jumps of light in a finished film: node devtests/flash.mjs <video> [--json]      (exit 1 when a flash is found)
// A film's brightness is read frame by frame (a small grey copy). Reported:
//   flash  — one or two frames far brighter or darker than the frames on both sides of them (a glitch, an accidental
//            white or black frame): never right;
//   jump   — a cut at which the whole picture changes brightness by more than a fifth of the scale (day ↔ night, into
//            white): right only when it is meant; a change of time or place wants a dissolve or a fade (post.dissolve);
//   ramp   — the steepest second of a fade, so a fade that is too quick to read (under half a second from end to end) shows.
// The Papa film came back for "a white flash followed by an abrupt dark transition" and abrupt day/night cuts (16 Nov 2026).
import { spawnSync } from 'node:child_process';
const [file, flag] = process.argv.slice(2);
if (!file) { console.error('usage: node devtests/flash.mjs <video> [--json]'); process.exit(2); }
const W = 64, H = 36;
const fpsTxt = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=r_frame_rate', '-of', 'csv=p=0', file], { encoding: 'utf8' }).stdout.trim().split('/');
const fps = fpsTxt.length === 2 ? +fpsTxt[0] / +fpsTxt[1] : +fpsTxt[0] || 30;
const raw = spawnSync('ffmpeg', ['-v', 'error', '-i', file, '-vf', `scale=${W}:${H},format=gray`, '-f', 'rawvideo', '-'], { maxBuffer: 1 << 30 }).stdout;
const n = Math.floor(raw.length / (W * H)), L = new Float32Array(n);
for (let f = 0; f < n; f++) { let s = 0; for (let i = 0; i < W * H; i++) s += raw[f * W * H + i]; L[f] = s / (W * H); }
export function findFlashes(L, fps, o = {}) {
  const flash = [], jump = [], spike = o.spike ?? 22, cut = o.cut ?? 55;
  for (let f = 1; f < L.length - 2; f++) {
    for (const len of [1, 2]) { // a run of `len` frames that stands out from the frame before it and the frame after it, the same way
      const a = L[f - 1], b = L[f + len]; if (b === undefined) continue; let lo = 1e9, hi = -1e9; for (let k = 0; k < len; k++) { lo = Math.min(lo, L[f + k]); hi = Math.max(hi, L[f + k]); }
      const up = lo - Math.max(a, b), down = Math.min(a, b) - hi;
      if ((up > spike || down > spike) && Math.abs(a - b) < spike) { flash.push({ t: f / fps, frames: len, by: Math.round(up > spike ? up : -down) }); f += len; break; }
    }
  }
  for (let f = 1; f < L.length; f++) if (Math.abs(L[f] - L[f - 1]) > cut) jump.push({ t: f / fps, from: Math.round(L[f - 1]), to: Math.round(L[f]) });
  let ramp = { t: 0, by: 0 }; const w = Math.round(fps); for (let f = w; f < L.length; f++) { const d = Math.abs(L[f] - L[f - w]); if (d > ramp.by) ramp = { t: (f - w) / fps, by: Math.round(d) }; }
  return { flash, jump, ramp };
}
const r = findFlashes(L, fps);
if (flag === '--json') console.log(JSON.stringify({ frames: n, fps, ...r }));
else {
  console.log(`${file}: ${n} frames at ${fps.toFixed(2)}/s`);
  for (const x of r.flash) console.log(`  ✗ flash at ${x.t.toFixed(2)} s: ${x.frames} frame${x.frames > 1 ? 's' : ''} ${x.by > 0 ? 'brighter' : 'darker'} by ${Math.abs(x.by)} than the frames round ${x.frames > 1 ? 'them' : 'it'}`);
  for (const x of r.jump) console.log(`  ! jump of light at ${x.t.toFixed(2)} s: ${x.from} → ${x.to} from one frame to the next (meant? else dissolve or fade)`);
  console.log(`  steepest second: ${r.ramp.by} of 255 from ${r.ramp.t.toFixed(2)} s`);
  console.log(r.flash.length ? `flash: ${r.flash.length} found` : `flash: none (${r.jump.length} jump${r.jump.length === 1 ? '' : 's'} of light at cuts)`);
}
process.exit(r.flash.length ? 1 : 0);
