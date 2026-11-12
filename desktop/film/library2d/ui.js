// Lettering and screen design: speech balloons, title frames, lower thirds, caption boxes, arrows and transition wipes.
import { Doc, rng, P, smooth, burst, blob, ellipse, rrect, shade, mix, INK, f } from './svg.js';

export function ui() {
  const out = [], A = (id, name, sub, d, tags = [], o = {}) => out.push({ id: `ui_${id}`, name, cat: 'ui', sub, tags: ['ui', 'text', sub, ...tags], w: d.w, h: d.h, svg: d.svg(), ...o });
  // speech balloons (empty: text goes on top)
  const balloons = {
    round: (d) => d.ink(`${ellipse(400, 230, 330, 170)} M300 380 L250 470 L380 395Z`, '#ffffff', 7),
    oval_tail_left: (d) => d.ink(`${ellipse(420, 220, 320, 160)} M220 330 L120 440 L300 360Z`, '#ffffff', 7),
    box: (d) => d.ink(`${rrect(90, 70, 620, 300, 24)} M260 370 L220 460 L330 370Z`, '#ffffff', 7),
    shout: (d) => d.ink(burst(400, 230, 18, 200, 300, 0.25, 4), '#ffffff', 7),
    shout_yellow: (d) => d.ink(burst(400, 230, 16, 190, 300, 0.3, 9), '#fff27a', 7),
    thought: (d) => { const lobes = []; for (let i = 0; i < 12; i++) { const a = (i / 12) * Math.PI * 2; lobes.push(ellipse(400 + Math.cos(a) * 270, 220 + Math.sin(a) * 130, 80, 70)); } lobes.push(ellipse(400, 220, 290, 150)); d.ink(lobes.join(' '), '#ffffff', 6); d.path(ellipse(400, 220, 290, 150), '#ffffff'); for (const [x, y, r] of [[230, 410, 30], [180, 460, 18]]) d.ink(ellipse(x, y, r, r), '#ffffff', 6); },
    whisper: (d) => d.path(`${ellipse(400, 230, 320, 160)}`, '#ffffff', { stroke: INK, sw: 6, op: 1 }).raw(`<path d="${ellipse(400, 230, 320, 160)}" fill="none" stroke="#ffffff" stroke-width="10" stroke-dasharray="26 18"/>`),
    scared: (d) => { const R = rng(3), pts = []; for (let i = 0; i < 60; i++) { const a = (i / 60) * Math.PI * 2; pts.push([400 + Math.cos(a) * (320 + (R() - 0.5) * 30), 230 + Math.sin(a) * (160 + (R() - 0.5) * 20)]); } d.ink(P(pts), '#ffffff', 5); },
    narration: (d) => d.ink(rrect(80, 120, 640, 200, 6), '#fff6dc', 6),
    robot: (d) => d.ink(`M100 80 H700 V360 H460 L400 450 L380 360 H100Z`, '#d8f6ff', 6),
  };
  for (const [k, fn] of Object.entries(balloons)) { const d = new Doc(800, 500); fn(d); A(`balloon_${k}`, `Speech balloon — ${k.replace(/_/g, ' ')}`, 'balloons', d, ['speech', 'bubble', 'balloon', 'comic', 'manga']); }
  // title frames (for chapter cards)
  const frames = [['ink_brush', '#ffffff', '#1a1820'], ['gold_line', '#1a1420', '#e3b23c'], ['red_seal', '#f4ecdc', '#b0202e'], ['neon', '#0a0a18', '#3ee6c4'], ['scroll', '#efe2c0', '#6a4526'], ['paper_tear', '#ffffff', '#2a2433'], ['manga_panel', '#ffffff', '#111111'], ['cinema', '#000000', '#ffffff']];
  for (const [k, bg, fg] of frames) {
    const d = new Doc(1920, 1080), R = rng(k.length);
    if (k === 'ink_brush') { d.path(`M0 0H1920V1080H0Z`, bg); for (let i = 0; i < 3; i++) d.path(smooth([[240, 470 + i * 30], [700, 430 + i * 20], [1300, 470 - i * 10], [1700, 440 + i * 20], [1680, 620 - i * 10], [1100, 640], [500, 610 + i * 10], [220, 640]], true, 0.6), fg, { op: 0.92 - i * 0.25 }); }
    else if (k === 'gold_line') { d.path(`M0 0H1920V1080H0Z`, bg); d.path(rrect(160, 160, 1600, 760, 0), 'none', { stroke: fg, sw: 6 }); d.path(rrect(190, 190, 1540, 700, 0), 'none', { stroke: fg, sw: 2 }); for (const [x, y] of [[160, 160], [1760, 160], [160, 920], [1760, 920]]) d.path(burst(x, y, 4, 8, 34, 0, 1), fg); }
    else if (k === 'red_seal') { d.path(`M0 0H1920V1080H0Z`, bg); d.path(rrect(1500, 640, 220, 220, 18), fg); d.path(rrect(1525, 665, 170, 170, 10), 'none', { stroke: bg, sw: 8 }); }
    else if (k === 'neon') { d.path(`M0 0H1920V1080H0Z`, bg); d.path(rrect(260, 300, 1400, 480, 40), 'none', { stroke: fg, sw: 26, op: 0.25, filter: d.blur(14) }); d.path(rrect(260, 300, 1400, 480, 40), 'none', { stroke: fg, sw: 8 }); }
    else if (k === 'scroll') { d.path(`M0 0H1920V1080H0Z`, '#2a1f1a'); d.ink(rrect(300, 200, 1320, 680, 10), bg, 8); d.ink(rrect(260, 170, 1400, 50, 24), fg, 6); d.ink(rrect(260, 860, 1400, 50, 24), fg, 6); }
    else if (k === 'paper_tear') { d.path(`M0 0H1920V1080H0Z`, '#2a2433'); const pts = [[180, 260]]; for (let x = 180; x <= 1740; x += 40) pts.push([x, 250 + R() * 30]); for (let x = 1740; x >= 180; x -= 40) pts.push([x, 830 + R() * 30]); d.path(P(pts), bg); }
    else if (k === 'manga_panel') { d.path(`M0 0H1920V1080H0Z`, '#f4f4f4'); for (const r of [[60, 60, 1100, 560], [1200, 60, 660, 560], [60, 660, 760, 360], [860, 660, 1000, 360]]) d.path(rrect(...r, 0), bg, { stroke: fg, sw: 10 }); }
    else { d.path(`M0 0H1920V1080H0Z`, bg); d.path(`M0 0H1920V130H0Z M0 950H1920V1080H0Z`, '#000000'); d.path(rrect(560, 470, 800, 4, 0), fg, { op: 0.8 }); }
    A(`title_${k}`, `Title card — ${k.replace(/_/g, ' ')}`, 'titles', d, ['title', 'card', 'chapter', 'frame'], { fullscreen: true });
  }
  // lower thirds (name plates)
  const lt = [['clean', '#ffffff', '#3ee6c4'], ['dark', '#16181f', '#ffbf5e'], ['anime', '#2a2433', '#ff4a6a'], ['news', '#b0202e', '#ffffff'], ['glass', '#ffffff', '#8a7dff'], ['brush', '#1a1820', '#e3b23c'], ['minimal', '#000000', '#ffffff'], ['game', '#203060', '#7fe0ff']];
  for (const [k, bg, ac] of lt) { const d = new Doc(1200, 240); if (k === 'brush') d.path(smooth([[20, 120], [300, 60], [900, 70], [1180, 110], [1100, 190], [400, 200], [40, 180]], true, 0.5), bg, { op: 0.92 }); else d.path(rrect(20, 60, 1000, 130, k === 'glass' ? 30 : 6), bg, { op: k === 'glass' ? 0.25 : 0.95, stroke: k === 'glass' ? '#ffffff' : undefined, sw: 2 }); d.path(rrect(20, 60, 14, 130, 2), ac); d.path(rrect(60, 150, 300, 8, 2), ac, { op: 0.9 }); A(`lowerthird_${k}`, `Lower third — ${k}`, 'lowerthirds', d, ['name', 'plate', 'lower third', 'caption']); }
  // caption boxes and arrows
  for (const [k, c] of [['yellow', '#fff27a'], ['white', '#ffffff'], ['black', '#16141c'], ['red', '#ff4a5c']]) { const d = new Doc(800, 200); d.ink(rrect(20, 30, 760, 140, 4), c, 6); A(`caption_${k}`, `Caption box — ${k}`, 'captions', d); }
  for (const [k, c] of [['right', '#ffbf5e'], ['curved', '#3ee6c4'], ['double', '#ffffff'], ['hand', '#ff4a6a']]) { const d = new Doc(600, 300); if (k === 'right') d.ink('M40 120 H420 V60 L560 150 L420 240 V180 H40Z', c, 6); else if (k === 'curved') { d.path('M60 240 Q300 20 500 150', 'none', { stroke: INK, sw: 30 }); d.path('M60 240 Q300 20 500 150', 'none', { stroke: c, sw: 18 }); d.ink('M470 90 L560 170 L440 200Z', c, 6); } else if (k === 'double') d.ink('M40 150 L160 60 V110 H440 V60 L560 150 L440 240 V190 H160 V240Z', c, 6); else { d.path(smooth([[60, 200], [200, 120], [380, 180], [480, 110]], false), 'none', { stroke: c, sw: 12 }); d.path('M440 70 L520 100 L470 170', 'none', { stroke: c, sw: 12 }); } A(`arrow_${k}`, `Arrow — ${k}`, 'arrows', d, ['arrow', 'pointer']); }
  // transition wipes (a mask shape that grows: use as a matte)
  const wipes = [['circle', (d) => d.path(ellipse(960, 540, 700, 700), '#ffffff')], ['diagonal', (d) => d.path('M0 1080 L1300 0 L1920 0 L1920 1080Z', '#ffffff')], ['star', (d) => d.path(burst(960, 540, 5, 280, 760, 0, 1, -Math.PI / 2), '#ffffff')], ['heart', (d) => d.path('M960 980 C500 700 260 520 260 330 A270 270 0 0 1 960 250 A270 270 0 0 1 1660 330 C1660 520 1420 700 960 980Z', '#ffffff')], ['blinds', (d) => { for (let y = 0; y < 1080; y += 120) d.path(rrect(0, y, 1920, 80, 0), '#ffffff'); }], ['brush', (d) => { for (let i = 0; i < 5; i++) d.path(smooth([[-50, 100 + i * 210], [700, 60 + i * 210], [1400, 140 + i * 210], [1990, 90 + i * 210], [1990, 250 + i * 210], [1200, 300 + i * 210], [400, 260 + i * 210], [-50, 300 + i * 210]], true, 0.5), '#ffffff'); }]];
  for (const [k, fn] of wipes) { const d = new Doc(1920, 1080); fn(d); A(`wipe_${k}`, `Transition wipe — ${k}`, 'transitions', d, ['transition', 'wipe', 'matte', 'mask'], { fullscreen: true }); }
  return out;
}
