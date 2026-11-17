// Lip-sync rules from the animation books (study of 17 Nov 2026): node devtests/lip.mjs   (exit 1 when a rule is broken)
// The mouth leads the sound by a frame or two; M/B/P shut fully for at least two frames; a mouth never snaps from wide
// open to shut in one frame at the end of a phrase (it eases through a half-open drawing, closing 2–3 frames into the
// silence); vowels are held, the track stays lively (several changes a second).
import { LIP, VO, mouthAt } from '../kit/acting.js';
import { setLook } from '../engine/look.js';
const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
setLook({ style: 'anime' });
// a made-up line at 60 samples a second: "ma-ma", a pause, "ah"
const seg = []; const put = (n, v) => { for (let i = 0; i < n; i++) seg.push(v); };
put(24, 0.8); put(6, 0.0); put(24, 0.8); put(40, 0.0); put(24, 0.6); put(10, 0.0);
// a voiced run wobbles like a real voice
const open = seg.map((v, i) => v ? v * (0.75 + 0.25 * Math.sin(i * 0.9)) : 0);
LIP.en.t1 = { n: open.length, open, wide: open.map(() => 0) }; VO.en.t1 = { seconds: open.length / 60, who: 'a' };
const cues = [{ id: 't1', who: 'a', t0: 1 }], fps = 24;
const at = (s) => mouthAt(cues, 'a', 1 + s / 60, 'en')?.open ?? null;   // s: the sample (60 a second) the frame shows
const frames = []; for (let t = 1 - 0.2; t < 1 + open.length / 60 + 0.3; t += 1 / fps) frames.push({ t, s: (t - 1) * 60, o: mouthAt(cues, 'a', t, 'en')?.open ?? null });
// 1. the mouth is already open a frame before the first sound
ok(at(-60 / fps) > 0.2, 'opens a frame before the sound starts', at(-60 / fps));
// 2. the 0.1 s closure between "ma" and "ma" (samples 24–30) shows a shut mouth on at least two frames in a row
{ const w = frames.filter((f) => f.s > 14 && f.s < 34); let run = 0, best = 0; for (const f of w) { run = f.o !== null && f.o < 0.05 ? run + 1 : 0; best = Math.max(best, run); }
  ok(best >= 2, 'M/B/P: shut on two frames or more', w.map((f) => f.o?.toFixed(2))); }
// 3. into the pause (sound stops at sample 54): no frame goes from wide open straight to shut; it shuts within 4 frames
{ const w = frames.filter((f) => f.s > 40 && f.s < 80); let snap = false, shutAt = null;
  for (let i = 1; i < w.length; i++) { if (w[i - 1].o > 0.45 && w[i].o !== null && w[i].o < 0.05) snap = true; if (shutAt === null && w[i].s > 54 && w[i].o !== null && w[i].o < 0.05) shutAt = w[i].s; }
  ok(!snap, 'end of a phrase: eases through a half-open drawing', w.map((f) => f.o?.toFixed(2)));
  ok(shutAt !== null && shutAt - 54 <= 4 * 60 / fps, 'end of a phrase: shut within 4 frames of the silence', shutAt);
  ok(shutAt !== null && shutAt - 54 >= 1, 'end of a phrase: shuts a little after the sound, not on it', shutAt); }
// 4. every drawing is held at least two frames (a twelfth of a second), except the ease into a pause
{ let short = 0; const v = frames.filter((f) => f.o !== null).map((f) => f.o.toFixed(2)); let run = 1; for (let i = 1; i < v.length; i++) { if (v[i] === v[i - 1]) run++; else { if (run < 2 && i > 1) short++; run = 1; } }
  ok(short <= 2, 'drawings held two frames', v); }
// 5. lively: in the voiced parts the drawing changes several times a second
{ const v = frames.filter((f) => f.s >= 0 && f.s < 54).map((f) => f.o); let ch = 0; for (let i = 1; i < v.length; i++) if (Math.abs(v[i] - v[i - 1]) > 0.05) ch++;
  ok(ch / (v.length / fps) >= 4, 'lively: 4+ changes a second', ch / (v.length / fps)); }
// 6. never wider than the speech maximum
ok(frames.every((f) => f.o === null || f.o <= 0.75), 'never past 0.75', Math.max(...frames.map((f) => f.o ?? 0)));
if (fails.length) { console.log(`lip: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`lip: all ${checks} rules hold`);
