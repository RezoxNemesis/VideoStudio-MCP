// Contact sheets of every place (two hours each) and of every prop.
import fs from 'node:fs';
import { createCanvas, GlobalFonts } from '@napi-rs/canvas';
const B = '/home/claude/rezoxnemesis/videostudio-mcp/desktop/film/';
const { ShotRun } = await import(B + 'engine/shot.js');
const kit = await import(B + 'kit/index.js');
for (const f of fs.readdirSync(B + 'assets/fonts')) GlobalFonts.registerFromPath(B + 'assets/fonts/' + f);
const shot = (sc, cam) => ({ id: 'x', seq: 'x', dur: 1, t0: 0, set: () => sc, actors: [], cam: () => cam, dof: null, fx: () => [], dyn: null, under: null, over: null, top: null, post: {}, warm: 0, settle: 0 });
const draw = (sc, cam, W = 480, H = 270) => new ShotRun(shot(sc, cam), {}, { W, H, fps: 30, noGrain: true }).draw(0.5);
const which = process.argv[2] ?? 'places', OUT = '/home/claude/vs2-notes/tests/out/';
if (which === 'places') {
  const names = Object.keys(kit.PLACES), hours = ['day', 'dusk', 'night', 'dawn'];
  const cols = 4, W = 480, H = 270, cv = createCanvas(cols * W, Math.ceil(names.length * 2 / cols) * H), g = cv.getContext('2d');
  let i = 0;
  for (const n of names) for (const hr of [hours[0], hours[1 + (names.indexOf(n) % 3)]]) {
    const t0 = Date.now(); let im;
    try { im = draw(kit.PLACES[n]({ time: hr }), n === 'room' ? { pos: [0.4, 1.6, 6.5], target: [0, 1.2, -2], fov: 46 } : n === 'rooftop' ? { pos: [-3, 1.7, 8], target: [2, 1.2, -4], fov: 46 } : { pos: [1.5, 1.7, 9], target: [0, 1.4, 0], fov: 42 }); } catch (e) { console.log('FAILED', n, hr, String(e.stack).split('\n').slice(0, 4).join(' | ')); i++; continue; }
    g.drawImage(im, (i % cols) * W, Math.floor(i / cols) * H);
    g.fillStyle = '#000a'; g.fillRect((i % cols) * W, Math.floor(i / cols) * H, 150, 20); g.fillStyle = '#fff'; g.font = '13px Inter'; g.fillText(`${n} · ${hr} · ${Date.now() - t0}ms`, (i % cols) * W + 5, Math.floor(i / cols) * H + 14);
    i++;
  }
  fs.writeFileSync(OUT + 'places.jpg', cv.toBuffer('image/jpeg', 84));
} else {
  const names = Object.keys(kit.PROPS).filter((n) => (which === 'props1' ? true : true)), cols = 9, W = 210, H = 170, from = which === 'props2' ? 63 : 0, list = names.slice(from, from + 63);
  const cv = createCanvas(cols * W, Math.ceil(list.length / cols) * H), g = cv.getContext('2d');
  list.forEach((n, i) => {
    const sc = kit.studio({ color: '#aeb7c4', floor: '#c4cad4' });
    let size = 2.2;
    try { const made = kit.PROPS[n]([0, 0], {}); for (const m of Array.isArray(made) ? made : [made]) { sc.add('world', m); size = Math.max(size, (m.r ?? 1) * 1.7, (m.o?.h ?? 0)); } }
    catch (e) { console.log('FAILED', n, String(e.stack).split('\n').slice(0, 3).join(' | ')); return; }
    const big = /building|tower|barn|bus|hut|tree|pine|flag|tent.large|lamp|traffic/.test(n), d = /building/.test(n) ? 44 : /tower|tree.big|pine.tall|barn/.test(n) ? 26 : big ? 15 : /car|boat|stall|cart|bus stop|fountain|well|sofa|bed|shelf|table.long|bench/.test(n) ? 6.5 : 3.6;
    let im; try { im = draw(sc, { pos: [d * 0.55, d * 0.42, d * 0.8], target: [0, d * 0.19, 0], fov: 36 }, W, H); } catch (e) { console.log('FAILED draw', n, String(e.stack).split('\n').slice(0, 3).join(' | ')); return; }
    g.drawImage(im, (i % cols) * W, Math.floor(i / cols) * H);
    g.fillStyle = '#000a'; g.fillRect((i % cols) * W, Math.floor(i / cols) * H + H - 18, W, 18); g.fillStyle = '#fff'; g.font = '12px Inter'; g.fillText(n, (i % cols) * W + 5, Math.floor(i / cols) * H + H - 5);
  });
  fs.writeFileSync(OUT + which + '.jpg', cv.toBuffer('image/jpeg', 84));
}
