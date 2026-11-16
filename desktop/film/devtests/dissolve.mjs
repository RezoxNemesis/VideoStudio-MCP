// A cut can be a dissolve: node devtests/dissolve.mjs        (exit 1 when a rule is broken)
// A shot that asks for `post: { dissolve: seconds }` opens on the last picture of the shot before it and lets it go
// over that time (a film's changes of day and of memory: never a flash, never a jump of light). The Papa film came
// back for its abrupt night → day → night → memory cuts (16 Nov 2026).
import { ShotRun } from '../engine/shot.js';
import { Scene, sky } from '../engine/scene.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };

const W = 192, H = 108, fps = 30, o = { lang: 'en', W, H, fps, noGrain: true, look: 'classic' };
const place = (top, low) => () => { const sc = new Scene({ post: { vignette: 0, grain: 0 } }); sc.add('sky', sky({ stops: [[0, top], [1, low]] })); return sc; };
const cam = () => ({ pos: [0, 1.5, -6], target: [0, 1.4, 0], fov: 40 });
const A = { id: 'a', dur: 2, set: place('#ff7a3a', '#ffd9a0'), actors: [], cam, post: {} };
const mkB = (post, into = 0) => ({ id: 'b', dur: 3 - into, into, set: place('#0a1440', '#22306a'), actors: [], cam, post, prev: (w, h, oo) => new ShotRun(A, {}, { ...o, ...oo, W: w, H: h }).draw(0) });
const px = (sh) => new ShotRun(sh, {}, o).draw(0).getContext('2d').getImageData(0, 0, W, H).data;
const diff = (a, b) => { let s = 0; for (let i = 0; i < a.length; i += 4) s += Math.abs(a[i] - b[i]) + Math.abs(a[i + 1] - b[i + 1]) + Math.abs(a[i + 2] - b[i + 2]); return s / (a.length / 4) / 3; };
const lastA = px(A), plain = px(mkB({}));
ok(diff(lastA, plain) > 30, 'the two shots are different pictures (else the test shows nothing)', diff(lastA, plain));
const d0 = px(mkB({ dissolve: 1 })), dh = px(mkB({ dissolve: 1 }, 0.5)), d1 = px(mkB({ dissolve: 1 }, 1.2));
ok(diff(d0, lastA) < 1.5, 'a dissolve opens on the last picture of the shot before', diff(d0, lastA));
ok(Math.abs(diff(dh, plain) - diff(dh, lastA)) < 6 && diff(dh, plain) > 10, 'half-way both pictures are in it, half and half', [diff(dh, plain), diff(dh, lastA)]);
ok(diff(d1, plain) < 0.01, 'after its time the shot is itself', diff(d1, plain));
const none = { ...mkB({ dissolve: 1 }), prev: null };
ok(diff(px(none), plain) < 0.01, 'a shot with nothing before it (the first of a film) is drawn as it is', diff(px(none), plain));
// the way through is eased: a tenth of the way in, the old picture is still nearly all of it
ok(diff(px(mkB({ dissolve: 1 }, 0.1)), lastA) < diff(lastA, plain) * 0.06, 'it starts gently (eased, not a straight ramp)', diff(px(mkB({ dissolve: 1 }, 0.1)), lastA));

// ---- every way a shot is drawn knows the shot before it: the film's own renderer takes its shots from the project
// (P.shots), not from shotFrom — the first revised Papa draft had no dissolves at all, which the flash detector showed
// as jumps of light at 25.7 s and 68.7 s (16 Nov 2026)
{
  const { linkDissolves } = await import('../kit/project.js');
  const P = { config: { fps: 30 }, CAST: {}, FILM: [{ id: 's', shotList: [{ id: 'a', t0: 0, t1: 2 }, { id: 'b', t0: 2, t1: 5, post: { dissolve: 1 } }] }, { id: 'r', shotList: [{ id: 'a', t0: 0, t1: 3, post: { dissolve: 1.5 } }, { id: 'b', t0: 3, t1: 4 }] }],
    shots: [{ id: 's_a', post: {} }, { id: 's_b', post: { dissolve: 1 } }, { id: 'r_a', post: { dissolve: 1.5 } }, { id: 'r_b', post: {} }] };
  linkDissolves(P);
  ok(typeof P.shots[1].prev === 'function', 'a shot of the project that asks for a dissolve can draw the shot before it', typeof P.shots[1].prev);
  ok(typeof P.shots[2].prev === 'function', 'also when that shot is the last of another sequence', typeof P.shots[2].prev);
  ok(P.shots[0].prev === undefined && P.shots[3].prev === undefined, 'a shot that asks for none is left alone', [typeof P.shots[0].prev, typeof P.shots[3].prev]);
}

// ---- the shot before is really DRAWN through the kit (not only named): the first revised Papa draft had jump cuts at
// every dissolve because the kit's helper could not make the picture and the engine kept quiet about it (16 Nov 2026)
{
  const { shotFrom } = await import('../kit/project.js');
  const seq = { id: 's', T: 5, actorList: [], cues: [], bakeName: null, set: place('#ff7a3a', '#ffd9a0'), shotList: [{ id: 'a', t0: 0, t1: 2, cam }, { id: 'b', t0: 2, t1: 5, cam, post: { dissolve: 1 } }] };
  const night = { ...seq, id: 'n', set: place('#0a1440', '#22306a'), shotList: [{ id: 'a', t0: 0, t1: 3, cam, post: { dissolve: 1 } }] };
  const P = { dir: '/nonexistent', config: { fps }, CAST: {}, FILM: [seq, night] };
  const draw = (sh) => new ShotRun(sh, {}, o).draw(0).getContext('2d').getImageData(0, 0, W, H).data;
  let first = null, err = null;
  try { const day = draw(shotFrom(P, 's_a', 2 - 1 / fps)); first = draw(shotFrom(P, 'n_a', 0)); ok(diff(first, day) < 1.5, 'a shot taken from the project opens on the last picture of the shot before it (across sequences)', diff(first, day)); }
  catch (e) { err = String(e?.message ?? e); }
  ok(!err, 'the kit can draw the shot before', err);
  // a dissolve that cannot get its first picture says so: a film is never finished with a silent jump cut in it
  let said = null; try { new ShotRun({ ...mkB({ dissolve: 1 }), prev: () => { throw new Error('boom'); } }, {}, o).draw(0); } catch (e) { said = String(e?.message ?? e); }
  ok(!!said && /dissolve/i.test(said) && /boom/.test(said), 'a dissolve whose first picture cannot be drawn stops the render and says why', said);
}

if (fails.length) { console.error(`dissolve: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`dissolve: all ${checks} rules hold`);
