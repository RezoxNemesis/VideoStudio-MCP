// The craft the practice reel proved: node devtests/craft.mjs        (exit 1 when a rule is broken)
//   DANCE  a captured dance is laid on its music: its steps are found, and the clip is retimed so they land on the beats.
//   EYES   the eyes go first and fast (a dart of two or three frames, most of it in one), the head follows later;
//          a big change of gaze takes a blink with it; a look with the eyes alone leaves the head where it was.
//   THINGS a thing lies somewhere until a hand takes it, then goes where the hand goes; set down, it stays.
//   ORBS   a ball lit from one side shows the right phase; planets go round by Kepler's law.
import { createCanvas } from '@napi-rs/canvas';
import { makeCam } from '../engine/camera.js';
import { beatGrid, countAt, stepTimes, beatMatch, bounceTimes, beatLock } from '../kit/dance.js';
import { dartTo, gazeBlink, eyesLead, gazeOf } from '../kit/acting.js';
import { carried } from '../kit/things.js';
import { asList } from '../kit/seq.js';
import { armFlares } from '../sensei/motion.js';
import { ball, onRing, keplerTurns, solarSystem } from '../kit/orbs.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const near = (a, b, tol, what) => ok(Math.abs(a - b) <= tol, `${what} (want ${b})`, a);

/* ---------------------------------------------------------------- DANCE */
{
  const g = beatGrid(120, 30);
  ok(g.frames === 15 && g.whole === true, '120 beats a minute at 30 fps is a beat every 15 frames, a whole number', g);
  near(g.beat, 0.5, 1e-9, 'a beat at 120 is half a second'); near(g.eight, 4, 1e-9, 'a count of eight at 120 is four seconds');
  const h = beatGrid(100, 24);
  ok(h.whole === false && Math.abs(h.frames - 14.4) < 1e-9, '100 beats a minute at 24 fps does not fall on whole frames', h);
  ok(h.suggest === 96 || h.suggest === 102.9, 'it names the nearest tempo that does (96: 15 frames)', h.suggest);
  // counting in eights: the first beat of every eight is the accent
  const c0 = countAt(0, 120), c1 = countAt(0.5, 120), c8 = countAt(4.0, 120), c3 = countAt(1.24, 120, { start: 0.2 });
  ok(c0.count === 1 && c0.accent && c1.count === 2 && !c1.accent && c8.count === 1 && c8.eight === 1, 'beats are counted 1 to 8 and the 1 is the accent', [c0, c1, c8]);
  ok(c3.count === 3 && Math.abs(c3.phase - 0.08) < 1e-6, 'the music may start late: counting starts where it starts', c3);

  // foot plants out of a contact track (bit 1 = left foot down, bit 2 = right foot down), at 60 frames a second
  const con = new Uint8Array(240);
  for (let i = 0; i < 240; i++) { const t = i / 60; con[i] = ((t % 1) < 0.55 ? 1 : 0) | (((t + 0.5) % 1) < 0.55 ? 2 : 0); }
  const st = stepTimes(con, 60);
  ok(st.length >= 6 && st.every((x, i) => i === 0 || Math.abs(x - st[i - 1] - 0.5) < 0.02), 'a foot coming down is a step: one every half second here', st.slice(0, 6));

  // a dance whose steps are 0.46 s apart is laid on music at 120 (0.5 s): played a little slower, shifted onto the beat
  const steps = Array.from({ length: 20 }, (_, i) => 0.3 + i * 0.46);
  const m = beatMatch(steps, 120);
  near(m.rate, 0.92, 0.004, 'the clip is slowed so a step is as long as a beat');
  ok(m.error < 0.012, 'the retimed steps land on the beats', m.error);
  const land = steps.map((s) => (s - m.in) / m.rate).filter((x) => x >= -1e-6);
  ok(land.length >= 18 && land.every((x) => Math.abs(x / 0.5 - Math.round(x / 0.5)) < 0.03), 'played from `in` at `rate`, every step falls on a beat', land.slice(0, 4));
  ok(m.in >= 0 && m.in < 0.5, 'the clip starts at most a beat in', m.in);
  // steps that wander: it stays inside what looks natural and says how well it fits
  const loose = steps.map((s, i) => s + (i % 3 === 0 ? 0.06 : -0.03));
  const m2 = beatMatch(loose, 120, { rates: [0.85, 1.15] });
  ok(m2.rate >= 0.85 && m2.rate <= 1.15 && m2.error > m.error && m2.error < 0.06, 'a loose dancer is fitted as well as can be, inside the range given', m2);
  // twice as many steps as beats: stepping on the half beats is on the music too
  const fast = Array.from({ length: 30 }, (_, i) => 0.1 + i * 0.26);
  const m3 = beatMatch(fast, 120);
  ok(Math.abs(m3.rate - 1.04) < 0.01 && m3.per === 0.5, 'quick steps go on the half beats', m3);
  // a real dancer (salsa: quick, quick, quick, pause) leaves steps out and taps in between: the pulse is still found
  const salsa = []; for (let i = 0; i < 40; i++) { if (i % 4 === 3) continue; salsa.push(0.4 + i * 0.31 + (i % 5 === 0 ? 0.02 : -0.01)); if (i % 7 === 2) salsa.push(0.4 + i * 0.31 + 0.11); }
  const m4 = beatMatch(salsa.sort((a, b) => a - b), 120, { rates: [0.7, 1.4] });
  ok(Math.abs(m4.rate - 1.24) < 0.03 && m4.per === 0.5 && m4.error < 0.04, 'steps left out and taps between do not hide the pulse', m4);
  ok(m4.sure > 0.6 && beatMatch([0.1, 0.37, 0.52, 0.91, 1.3, 1.38, 1.99, 2.2, 2.31, 2.9], 120).sure < 0.5, 'it says how sure it is: a dancer with no pulse gets a low number', [m4.sure]);
}

{
  // a dancer who grooves on the spot hardly lifts a foot: the pulse is in the BOUNCE — the hips come down on every beat
  const fps = 60, ys = Array.from({ length: 360 }, (_, i) => 0.95 - 0.04 * Math.abs(Math.sin((Math.PI * (i / fps - 0.12)) / 0.5)) + 0.002 * Math.sin(i * 1.7));
  const b = bounceTimes(ys, fps);
  ok(b.length >= 10 && b.every((t, i) => i === 0 || Math.abs(t - b[i - 1] - 0.5) < 0.04), 'the moments the hips are lowest are found: one every half second here', b.slice(0, 5));
  ok(Math.abs(((b[0] - 0.37 + 0.25) % 0.5) - 0.25) < 0.04, 'and they are the low points (0.37 s, 0.87 s …), not the high ones', b[0]);
  ok(bounceTimes(Array.from({ length: 200 }, () => 0.95), fps).length === 0, 'a body that does not bounce has none', 0);
  ok(bounceTimes(Array.from({ length: 300 }, (_, i) => 0.95 + 0.003 * Math.sin(i * 0.9)), fps).length === 0, 'a tremble of a few millimetres is not a bounce', 0);
  const m = beatMatch(b, 100);
  ok(Math.abs(m.rate - 0.5 / 0.6) < 0.02 && m.sure > 0.8, 'the bounce is matched to a tempo like steps are', m);
}

{
  // A person is not a metronome: a captured dancer's pulse drifts (measured: 0.33 to 0.38 s a bounce, and a stumble
  // now and then). beatLock moves every dip onto the music's grid by playing the stretch before it a little faster or slower.
  const step = 0.35, marks = [10.0, 10.33, 10.69, 11.05, 11.40, 11.87, 12.20, 12.60];     // clip seconds; a stumble at 11.87
  const L = beatLock(marks, { bpm: 60 / 0.7, in: 8.6, out: 13.5, t: 2.0 });
  const W = L.warp, last = W[W.length - 1];
  ok(W[0][0] === 0 && W[0][1] === 8.6 && last[1] === 13.5, 'the warp runs from the clip\'s in to its out', [W[0], last]);
  ok(W.every((k, i) => i === 0 || (k[0] > W[i - 1][0] && k[1] > W[i - 1][1])), 'time only goes forward, in the film and in the clip', W);
  const onGrid = W.slice(1, -1).every((k) => Math.abs((2.0 + k[0]) / step - Math.round((2.0 + k[0]) / step)) < 1e-6);
  ok(onGrid && L.locked === W.length - 2, 'every dip it keeps plays exactly on a half beat of the music (which starts at 0)', W.slice(1, -1).map((k) => +(2.0 + k[0]).toFixed(3)));
  ok(L.locked >= 6 && L.skipped <= 2 && L.locked + L.skipped === marks.length, 'a stumble is skipped, the rest are kept', [L.locked, L.skipped]);
  const rates = W.slice(1).map((k, i) => (k[1] - W[i][1]) / (k[0] - W[i][0]));
  ok(rates.every((r) => r > 0.849 && r < 1.151) && L.rates[0] >= 0.849 && L.rates[1] <= 1.151, 'no stretch is played more than 15 % faster or slower: it must not show', rates.map((r) => +r.toFixed(2)));
  ok(Math.abs(L.dur - last[0]) < 1e-9 && Math.abs(L.dur - (13.5 - 8.6)) < 0.5, 'it says how long the action now lasts', L.dur);
  // a dancer already on the grid is left as he is
  const even = beatLock([1.05, 1.4, 1.75, 2.1], { bpm: 60 / 0.7, in: 0.35, out: 2.45, t: 0.7 });
  ok(even.warp.slice(1).every((k, i) => Math.abs((k[1] - even.warp[i][1]) / (k[0] - even.warp[i][0]) - 1) < 1e-6), 'a dancer already on the beat is left as he is', even.warp);
  const none = beatLock([], { bpm: 100, in: 3, out: 5, t: 0 });
  ok(none.warp.length === 2 && none.dur === 2 && none.locked === 0, 'nothing to lock: the clip plays as it is', none.warp);
  // dips on whole beats only (per: 1)
  const whole = beatLock([4.0, 4.72, 5.38, 6.1], { bpm: 60 / 0.7, in: 2.6, out: 7, t: 0, per: 1 });
  ok(whole.warp.slice(1, -1).every((k) => Math.abs(k[0] / 0.7 - Math.round(k[0] / 0.7)) < 1e-6), 'per: 1 puts them on whole beats', whole.warp);
}

/* ---------------------------------------------------------------- EYES */
{
  ok(dartTo(-0.01) === 0 && dartTo(0) === 0 && dartTo(0.2) === 1, 'a dart begins at its moment and is over in a fraction of a second', [dartTo(0), dartTo(0.2)]);
  ok(dartTo(1 / 30) > 0.6, 'most of a dart happens in one frame', dartTo(1 / 30));
  ok(dartTo(3 / 30) > 0.99, 'it is done within three frames', dartTo(3 / 30));
  ok(dartTo(0.5 / 30) < dartTo(1 / 30), 'and it does not jump: half a frame in, it is on its way', [dartTo(0.5 / 30), dartTo(1 / 30)]);
  // a blink goes with a big change of gaze, not with a small one
  const big = gazeBlink(0.06, 0.9), small = gazeBlink(0.06, 0.2);
  ok(big > 0.8 && small === 0, 'a big change of gaze takes a blink with it; a small one does not', [big, small]);
  ok(gazeBlink(-0.01, 0.9) === 0 && gazeBlink(0.3, 0.9) === 0, 'the blink is short and begins with the change', [gazeBlink(-0.01, 0.9), gazeBlink(0.3, 0.9)]);
  ok(gazeBlink(0.06, 0.2, true) > 0.8 && gazeBlink(0.06, 0.9, false) === 0, 'the author can ask for one or forbid it', [gazeBlink(0.06, 0.2, true), gazeBlink(0.06, 0.9, false)]);

  // the eyes lead, the head follows: one list of beats gives the eyes' keys and the head's overlays
  const L = eyesLead([{ t: 0, at: [0, 0.8, 1.6], head: 0.9 }, { t: 2, at: 'girl', head: 0.5 }, { t: 5.5, at: [0, 0.8, 1.6], head: 0.95, lead: 0.08, fade: 0.16 }, { t: 7.6, at: 'girl', head: 0 }, { t: 9, at: 'girl', head: 0.45 }], { end: 12 });
  ok(JSON.stringify(L.look) === JSON.stringify([[0, [0, 0.8, 1.6]], [2, 'girl'], [5.5, [0, 0.8, 1.6]], [7.6, 'girl'], [9, 'girl']]), 'the eyes change on the beat', L.look);
  ok(L.head.length === 4, 'a look with the eyes alone (head: 0) moves no head', L.head.length);
  near(L.head[1].t0, 2.22, 1e-9, 'the head sets off a fifth of a second after the eyes');
  near(L.head[2].t0, 5.58, 1e-9, 'unless the beat says how late (caught: almost at once)');
  ok(L.head[2].t1 === L.head[3].t0 && Math.abs(L.head[3].t0 - 9.22) < 1e-9, 'while he only peeks, the head keeps to where it was', [L.head[2].t1, L.head[3].t0]);
  ok(L.head[3].t1 === 12 && L.head[0].t0 === 0 && L.head[0].look[2] === 1.6 && L.head[1].look === 'girl' && L.head[1].head === 0.5, 'the overlays join end to end up to the end of the scene', L.head);
  ok(L.head[2].fade === 0.16 && L.head[1].fade > 0.3, 'a head that follows moves slower than the eyes (its own fade)', [L.head[1].fade, L.head[2].fade]);
}

{
  // the film's look keys, played the way eyes move: [[t, target, { blink? }], …]; aim(target) → where that is for these eyes
  const aim = (tg) => (tg === 'girl' ? [0.8, 0.1] : tg === 'book' ? [0, -0.7] : tg === 'near' ? [0.7, 0.1] : null);
  const keys = [[0, 'book'], [2, 'girl'], [4, 'near'], [6, 'book', { blink: false }], [8, 'ghost']];
  const g1 = gazeOf(keys, 1, aim), g2 = gazeOf(keys, 2 + 1 / 30, aim), g3 = gazeOf(keys, 2.3, aim), g4 = gazeOf(keys, 4.03, aim), g6 = gazeOf(keys, 6.05, aim);
  ok(g1.look[0] === 0 && g1.look[1] === -0.7 && g1.blink === 0, 'eyes that rest on something stay on it, open', g1);
  ok(g2.look[0] > 0.48 && g2.look[0] < 0.8 && g2.blink > 0.5, 'a frame after a big change they are most of the way there, the lids coming down', g2);
  ok(g3.look[0] === 0.8 && g3.blink === 0, 'a third of a second later they are there and open', g3);
  ok(g4.blink === 0 && g4.look[0] < 0.8 && g4.look[0] > 0.7, 'a small change is a dart without a blink', g4);
  ok(g6.blink === 0 && g6.look[1] < -0.3, 'a key can forbid the blink (a stare that drops)', g6);
  ok(gazeOf(keys, -1, aim).look === null && gazeOf(keys, 9, aim).look === null && gazeOf(null, 1, aim).look === null, 'no key yet, a target that is not there, or no keys: the eyes are free', [gazeOf(keys, -1, aim), gazeOf(keys, 9, aim)]);
}

/* ---------------------------------------------------------------- THINGS */
{
  const ctx = (hand) => ({ poses: { boy: { j: { 'wrist.R': hand, 'wrist.L': [9, 9, 9] } } } });
  const made = [];
  const bag = carried({ rest: [1, 0, 2], by: 'boy', hand: 'R', from: 2.7, hang: 0.3, make: (p, yaw) => { made.push([p, yaw]); return { draw() {} }; }, yaw: 25, yawHeld: 90 });
  const a = bag(1.0, ctx([5, 1, 5]));
  ok(a.p[0] === 1 && a.p[1] === 0 && a.p[2] === 2, 'before it is taken, the thing lies where it was put', a.p);
  const b = bag(3.5, ctx([5, 1, 5]));
  ok(Math.abs(b.p[0] - 5) < 1e-9 && Math.abs(b.p[1] - 0.7) < 1e-9 && Math.abs(b.p[2] - 5) < 1e-9, 'once taken it hangs from the hand, wherever the hand goes', b.p);
  const c = bag(2.7 + 0.03, ctx([1.2, 0.2, 2.1]));
  ok(c.p[0] > 1 && c.p[0] < 1.2, 'it does not jump into the hand: it is drawn across in a few frames', c.p);
  a.draw({}, {}, {}); b.draw({}, {}, {});
  ok(made.length === 2 && made[0][1] === 25 && made[1][1] === 90, 'it is made where it is, turned the way it lies or the way it is held', made.map((x) => x[1]));
  const cup = carried({ rest: [0, 0.8, 0], by: 'boy', hand: 'L', from: 1, to: 4, drop: [2, 0.8, 0], make: () => ({ draw() {} }) });
  ok(cup(5, ctx([0, 0, 0])).p[0] === 2 && cup(2, ctx([0, 0, 0])).p[0] === 9, 'set down, it stays where it was set; held in the left hand it follows the left', [cup(5, ctx([0, 0, 0])).p, cup(2, ctx([0, 0, 0])).p]);
}

{
  // effects may be given as one system or as a list (fx: FX.fx from combat() is one; fx: (ctx) => [rain(box)] is a list)
  const one = { step() {}, items() { return []; } };
  ok(asList(one).length === 1 && asList(one)[0] === one && asList([one, one]).length === 2 && asList(null).length === 0 && asList(undefined).length === 0, 'a sequence takes its effects as one system or as a list of them', [asList(one).length, asList([one, one]).length]);
}

/* ---------------------------------------------------------------- THE JUDGE: ARMS THAT FLY OUT */
{
  // The fault the user saw in two films: once every stride a runner's arms went out sideways for a fifth of a second
  // (measured: wrists 0.41 m apart → 1.28 m → back in one frame, every 0.70 s). The judge must find it by itself.
  const dt = 1 / 30, swing = (t) => 0.41 + 0.09 * Math.sin((2 * Math.PI * t) / 0.7);
  const bad = Array.from({ length: 120 }, (_, i) => { const t = i * dt, u = (t % 0.7) / 0.7; return u > 0.72 ? swing(t) + (1.28 - 0.41) * ((u - 0.72) / 0.28) : swing(t); });
  const found = armFlares(bad, dt);
  ok(found.length >= 4 && found.length <= 6, 'arms that fly out once a stride are found, each time', found.length);
  ok(found.every((f) => f.peak > 1.0 && f.base < 0.55 && (f.i1 - f.i0 + 1) * dt <= 0.4), 'with how far out they went and what is normal around it', found[0]);
  const gaps = found.slice(1).map((f, k) => (f.i0 - found[k].i0) * dt);
  ok(gaps.every((g) => Math.abs(g - 0.7) < 0.07), 'and they are a stride apart: the fault of a loop', gaps);
  ok(armFlares(Array.from({ length: 120 }, (_, i) => swing(i * dt)), dt).length === 0, 'a runner whose arms stay in their swing is left alone', 0);
  // arms opened on purpose (a gesture held for a second and a half) are not a fault
  const gesture = Array.from({ length: 150 }, (_, i) => { const t = i * dt; return t > 1.5 && t < 3.0 ? 1.3 : 0.45; });
  ok(armFlares(gesture, dt).length === 0, 'arms held out on purpose are a pose, not a flare', armFlares(gesture, dt).length);
  // a quick REAL gesture (measured on a captured dancer: the arms open from 0.5 m to 0.93 m and close again in under
  // half a second, never faster than 2.4 m/s) is not a fault: an arm cannot snap, so a flare needs a snap on one side
  const d60 = 1 / 60, flourish = Array.from({ length: 240 }, (_, i) => { const t = i * d60, u = (t - 1.8) / 0.47; return 0.48 + (u > 0 && u < 1 ? 0.45 * Math.sin(Math.PI * u) ** 2 : 0); });
  ok(armFlares(flourish, d60).length === 0, 'a dancer who opens and closes the arms fast, but as arms move, is left alone', armFlares(flourish, d60));
  const snap = Array.from({ length: 240 }, (_, i) => (i >= 130 && i <= 138 ? 1.3 : 0.48));
  ok(armFlares(snap, d60).length === 1, 'the same arms out in one frame and back in one frame are a flare', armFlares(snap, d60).length);
  // a body that is not in the picture for a while breaks nothing
  const holes = bad.map((v, i) => (i > 40 && i < 60 ? null : v));
  ok(armFlares(holes, dt).length >= 2, 'a body out of the picture for a while breaks nothing', armFlares(holes, dt).length);
}

/* ---------------------------------------------------------------- ORBS */
{
  const W = 240, cam = makeCam({ pos: [0, 0, 10], target: [0, 0, 0], fov: 30 }, W, W);
  const litOf = (light) => {
    const cv = createCanvas(W, W), g = cv.getContext('2d'); g.fillStyle = '#000'; g.fillRect(0, 0, W, W);
    ball(g, cam, [0, 0, 0], 1.6, '#ffffff', light, { dark: '#000000', flat: true });
    const d = g.getImageData(0, 0, W, W).data; let lit = 0, sx = 0, sy = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] > 128) { lit++; sx += (i / 4) % W; sy += Math.floor(i / 4 / W); }
    return { lit, cx: lit ? sx / lit - W / 2 : 0, cy: lit ? sy / lit - W / 2 : 0 };
  };
  const full = litOf([0, 0, 100]), half = litOf([100, 0, 0]), thin = litOf([60, 0, -100]), none = litOf([0, 0, -100]), up = litOf([0, 100, 0]);
  ok(full.lit > 1000 && Math.abs(full.cx) < 1.5, 'lit from behind the lens, the whole disc is bright', full);
  near(half.lit / full.lit, 0.5, 0.04, 'lit from the side, half of it is');
  ok(half.cx > 10 && Math.abs(half.cy) < 2, 'the bright half is on the light\'s side', half);
  ok(thin.lit / full.lit < 0.3 && thin.lit / full.lit > 0.02 && thin.cx > half.cx, 'with the light almost behind it, it is a crescent on the light\'s side', [thin.lit / full.lit, thin.cx]);
  ok(none.lit / full.lit < 0.02, 'with the light right behind it, it is dark', none.lit / full.lit);
  ok(up.cy < -10 && Math.abs(up.cx) < 2, 'lit from above, the top is bright (the picture\'s y goes down)', up);
  // far away it is still there
  const cv = createCanvas(W, W), g = cv.getContext('2d'); g.fillStyle = '#000'; g.fillRect(0, 0, W, W);
  ball(g, cam, [0, 0, 0], 0.004, '#ffffff', [0, 0, 100]);
  const d = g.getImageData(W / 2 - 2, W / 2 - 2, 4, 4).data; let seen = 0; for (let i = 0; i < d.length; i += 4) seen = Math.max(seen, d[i]);
  ok(seen > 60, 'a ball smaller than a pixel is still a point of light', seen);

  // rings and Kepler
  const p0 = onRing([0, 5, -20], 4, 0, 0), p1 = onRing([0, 5, -20], 4, 0.25, 0), p2 = onRing([0, 5, -20], 4, 0.25, 0.5);
  ok(Math.abs(p0[0] - 4) < 1e-9 && Math.abs(p1[2] + 16) < 1e-9 && Math.abs(p1[1] - 5) < 1e-9, 'a ring lies flat round its centre', [p0, p1]);
  ok(p2[1] < 5 && p2[2] > -20 && p2[2] < -16, 'a tilted ring leans towards the lens: its near side is lower', p2);
  near(keplerTurns(8, 2, 2, 8), 1, 1e-9, 'the planet the year is set by goes round once in a year');
  near(keplerTurns(8, 4, 2, 8), 1 / Math.pow(2, 1.5), 1e-9, 'twice as far out, a round takes 2.83 times as long');
  const S = solarSystem({ sun: [0, 5, -24], year: 9, planets: [{ name: 'inner', ring: 3 }, { name: 'earth', ring: 6, year: true }, { name: 'outer', ring: 12 }] });
  const ang = (name, t) => { const p = S.placeOf(name, t); return Math.atan2(p[2] + 24, p[0]); };
  const turn = (name) => { let a = 0, prev = ang(name, 0); for (let t = 0.05; t <= 9.0001; t += 0.05) { let dlt = ang(name, t) - prev; if (dlt < -Math.PI) dlt += 2 * Math.PI; if (dlt > Math.PI) dlt -= 2 * Math.PI; a += dlt; prev = ang(name, t); } return Math.abs(a) / (2 * Math.PI); };
  near(turn('earth'), 1, 0.01, 'in a system, the year planet goes round once a year');
  ok(turn('inner') > 2.7 && turn('outer') < 0.37, 'the inner planets race, the outer ones crawl', [turn('inner'), turn('outer')]);
  ok(typeof S.draw === 'function' && S.names.join() === 'inner,earth,outer', 'the system draws itself and knows its planets', S.names);
  const cv2 = createCanvas(W, W), g2 = cv2.getContext('2d'); g2.fillStyle = '#000'; g2.fillRect(0, 0, W, W);
  S.draw(g2, makeCam({ pos: [0, 14, 6], target: [0, 5, -24], fov: 50 }, W, W), 2.0);
  const d2 = g2.getImageData(0, 0, W, W).data; let bright = 0; for (let i = 0; i < d2.length; i += 4) if (d2[i] + d2[i + 1] + d2[i + 2] > 300) bright++;
  ok(bright > 150, 'drawn, there is a sun and there are planets in the picture', bright);
}

console.log(`${checks} rules checked, ${fails.length} broken`);
for (const f of fails) console.log('  ✗ ' + f);
process.exit(fails.length ? 1 : 0);
