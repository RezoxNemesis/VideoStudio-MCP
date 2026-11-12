// Pixel art: little sprites made of real pixels (each one a square), with walk cycles laid out as sprite sheets —
// heroes and monsters, items, and tiles for a side-scroller.
import { Doc } from './svg.js';

/** a sprite from rows of characters; pal maps a character to a colour ('.' = clear) */
function sprite(rows, pal, px = 8) { const w = rows[0].length, h = rows.length, d = new Doc(w * px, h * px); for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const c = pal[rows[y][x]]; if (c) d.raw(`<rect x="${x * px}" y="${y * px}" width="${px}" height="${px}" fill="${c}"/>`); } return d; }
/** frames side by side (a sprite sheet) */
function sheet(frames, pal, px = 8) { const w = frames[0][0].length, h = frames[0].length, d = new Doc(w * px * frames.length, h * px); frames.forEach((rows, k) => { for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const c = pal[rows[y][x]]; if (c) d.raw(`<rect x="${(k * w + x) * px}" y="${y * px}" width="${px}" height="${px}" fill="${c}"/>`); } }); return d; }
const HERO = ['....kkkk....', '...khhhhk...', '..khhhhhhk..', '..kssssssk..', '..ksesseskk.', '..kssssssk..', '...kssssk...', '..kbbbbbbk..', '.kbbbbbbbbk.', '.ksbbbbbbsk.', '.ksbbbbbbsk.', '..kllkkllk..', '..kllk.kllk.', '..kffk.kffk.', '..kkkk.kkkk.', '............'];
const legs = (a, b) => [a, b];
function walk(base) {
  // four frames: legs apart / together, a bob of one pixel
  const f = base.slice(), down = (rows) => ['............', ...rows.slice(0, -1)];
  const A = f.slice(0, 11).concat(['..kllkkllk..', '.kllk...kllk', '.kffk...kffk', '.kkkk...kkkk', '............']);
  const B = f.slice(0, 11).concat(['...kllll....', '...kllk.....', '...kfffk....', '...kkkkk....', '............']);
  const C = f.slice(0, 11).concat(['..kllkkllk..', 'kllk.....llk', 'kffk.....ffk', 'kkkk.....kkk', '............']);
  void legs; return [A, down(B), C, down(B)];
}
const PALS = {
  knight: { k: '#1a1822', h: '#b9c2cc', s: '#f2c8a0', e: '#1a1822', b: '#3a5ea8', l: '#4a4a5a', f: '#6a4a32' },
  mage: { k: '#1a1822', h: '#6a3ad9', s: '#f2c8a0', e: '#1a1822', b: '#4a2a8a', l: '#2a2a3a', f: '#3a2a22' },
  archer: { k: '#1a1822', h: '#3a7a3a', s: '#e3ad86', e: '#1a1822', b: '#2a6a3a', l: '#5a4a32', f: '#4a3222' },
  ninja: { k: '#0e0e14', h: '#22222e', s: '#f2c8a0', e: '#e8e8f0', b: '#22222e', l: '#22222e', f: '#22222e' },
  princess: { k: '#1a1822', h: '#e8c46a', s: '#f6d7bf', e: '#3a7ad9', b: '#ff7aa8', l: '#ffb0cc', f: '#e8e8f0' },
  robot: { k: '#1a1822', h: '#9aa2ac', s: '#c9ced6', e: '#3ee6c4', b: '#6a7480', l: '#4a525c', f: '#3a3f48' },
};
export function pixel() {
  const out = [], A = (id, name, sub, d, o = {}) => out.push({ id: `px_${id}`, name, cat: 'pixel', sub, tags: ['pixel', '8bit', 'retro', 'game', sub, ...id.split('_')], w: d.w, h: d.h, svg: d.svg(), pixel: true, ...o });
  for (const [k, pal] of Object.entries(PALS)) {
    A(`${k}_idle`, `${k[0].toUpperCase() + k.slice(1)} — idle`, 'heroes', sprite(HERO, pal));
    A(`${k}_walk`, `${k[0].toUpperCase() + k.slice(1)} — walk (4 frames)`, 'heroes', sheet(walk(HERO), pal), { frames: 4, fps: 8 });
  }
  const monsters = {
    slime: [['............', '............', '....kkkk....', '..kkggggkk..', '.kgggggggggk', 'kggwggggwggk', 'kggkggggkggk', 'kgggggggggg k'.replace(' ', ''), '.kkkkkkkkkk.', '............'], { k: '#123a12', g: '#5ad16a', w: '#ffffff' }],
    bat: [['k..........k', 'kk...kk...kk', 'kpk.kppk.kpk', 'kppkpppkppk.', '.kpppwpwppk.', '..kpppppppk.', '...kkpppkk..', '.....kkk....'], { k: '#1a1022', p: '#6a3a8a', w: '#ff3a4a' }],
    ghost: [['...kkkkk....', '..kwwwwwk...', '.kwwkwwkwk..', '.kwwwwwwwk..', '.kwwwkkwwk..', '.kwwwwwwwk..', '.kwwwwwwwk..', '.kwkwwkwwk..', '.kk.kk.kkk..'], { k: '#4a4a6a', w: '#f2f4ff' }],
    skull: [['...kkkkkk...', '..kwwwwwwk..', '.kwwwwwwwwk.', '.kwkkwwkkwk.', '.kwkkwwkkwk.', '.kwwwkkwwwk.', '..kwwwwwwk..', '..kwkwkwkk..', '...kkkkkk...'], { k: '#1a1822', w: '#e8e2d6' }],
    mushroom: [['...kkkkkk...', '..krrwrrrk..', '.krwwrrrwrk.', '.krrrrrwwrk.', '.kkkkkkkkkk.', '...kssssk...', '...kseesk...', '...kssssk...', '...kkkkkk...'], { k: '#2a1a1a', r: '#d9343a', w: '#ffffff', s: '#f2e0c0', e: '#1a1822' }],
    dragon: [['.......kk...', '......kggk..', '..kk.kgggwk.', '.kggkggggggk', 'kggggggkkkk.', 'kgggggggk...', '.kggyggggk..', '..kggggggk..', '..kgk..kgk..', '..kk....kk..'], { k: '#123a1a', g: '#3aa65a', w: '#ffffff', y: '#ffd23a' }],
  };
  for (const [k, [rows, pal]] of Object.entries(monsters)) A(`monster_${k}`, `Monster — ${k}`, 'monsters', sprite(rows, pal));
  const items = {
    coin: [['..kkkk..', '.kyyyyk.', 'kyywyyyk', 'kywyyyyk', 'kyyyyyyk', 'kyyyyyyk', '.kyyyyk.', '..kkkk..'], { k: '#5a3a0a', y: '#ffd23a', w: '#fff6c0' }],
    heart: [['.kk..kk.', 'krrkkrrk', 'krwrrrrk', 'krrrrrrk', '.krrrrk.', '..krrk..', '...kk...'], { k: '#3a0a12', r: '#ff3a5a', w: '#ffc0d0' }],
    potion: [['...kk...', '...kk...', '..kwwk..', '.krrrrk.', 'krrwrrrk', 'krrrrrrk', 'krrrrrrk', '.kkkkkk.'], { k: '#1a1822', w: '#e8f2f8', r: '#d9343a' }],
    key: [['.kkk....', 'kyyyk...', 'kyky....', 'kyyykkkk', '.kkyyyyk', '....kyky', '....kk.k'], { k: '#3a2a0a', y: '#e3b23c' }],
    sword: [['......kk', '.....kwk', '....kwk.', '...kwk..', 'k.kwk...', '.kbk....', '.kkbk...', 'k...k...'], { k: '#1a1822', w: '#d9e2ec', b: '#8a5a34' }],
    chest: [['.kkkkkk.', 'kbbbbbbk', 'kbbybbbk', 'kkkyykkk', 'kbbbbbbk', 'kbbbbbbk', '.kkkkkk.'], { k: '#2a1a0a', b: '#8a5a34', y: '#ffd23a' }],
    gem: [['..kkkk..', '.kccwck.', 'kccccwck', 'kcccccck', '.kcccck.', '..kcck..', '...kk...'], { k: '#0a2a2a', c: '#2ec4b6', w: '#e0fffb' }],
    star: [['...kk...', '...ky...', 'kkkyykkk', '.kyyyyk.', '..kyyk..', '.kyk.yk.', 'kk....kk'], { k: '#5a3a0a', y: '#ffd23a' }],
    mushroom_green: [['..kkkk..', '.kgwggk.', 'kgggwwgk', 'kkkkkkkk', '..kssk..', '..kssk..', '..kkkk..'], { k: '#123a12', g: '#3ac05a', w: '#ffffff', s: '#f2e0c0' }],
    bomb: [['......k.', '.....kwk', '..kkkk..', '.kbbbbk.', 'kbwbbbbk', 'kbbbbbbk', '.kbbbbk.', '..kkkk..'], { k: '#0a0a0a', b: '#2a2a3a', w: '#ffb03a' }],
  };
  for (const [k, [rows, pal]] of Object.entries(items)) A(`item_${k}`, `Item — ${k.replace('_', ' ')}`, 'items', sprite(rows, pal, 12));
  const tiles = {
    grass: [['gggggggg', 'gGgggGgg', 'dddddddd', 'dDddddDd', 'dddDdddd', 'ddddddDd', 'dDdddddd', 'dddddDdd'], { g: '#5ad16a', G: '#3a9a4a', d: '#8a5a34', D: '#6a4226' }],
    dirt: [['dddddddd', 'dDddddDd', 'dddDdddd', 'ddddddDd', 'dDdddddd', 'dddddDdd', 'ddDddddd', 'dddddddD'], { d: '#8a5a34', D: '#6a4226' }],
    brick: [['kkkkkkkk', 'rrrkrrrr', 'rrrkrrrr', 'kkkkkkkk', 'rkrrrrkr', 'rkrrrrkr', 'kkkkkkkk', 'rrrkrrrr'], { k: '#4a1a12', r: '#b0482e' }],
    stone: [['ssssssss', 'sSssSsss', 'ssssssSs', 'kkkkkkkk', 'ssSsssss', 'sssssSss', 'Sssssss s'.replace(' ', ''), 'kkkkkkkk'], { s: '#9a978e', S: '#7a776e', k: '#4a4844' }],
    water: [['wwwwwwww', 'wWwwwWww', 'wwwwwwww', 'wwWwwwwW', 'wwwwwwww', 'Wwwwwwww', 'wwwWwwww', 'wwwwwwWw'], { w: '#3a8ac0', W: '#8ad0f0' }],
    wood: [['bbbbbbbb', 'bBbbbbbb', 'kkkkkkkk', 'bbbbBbbb', 'bbbbbbbb', 'kkkkkkkk', 'bbBbbbbb', 'bbbbbbBb'], { b: '#9a6a3e', B: '#7a4e2a', k: '#5a3a1e' }],
    lava: [['oooooooo', 'oyoooyoo', 'ooooroo o'.replace(' ', ''), 'rooooyoo', 'oooroooo', 'ooooooyo', 'oyoooooo', 'ooooroo o'.replace(' ', '')], { o: '#ff6a1a', y: '#ffd23a', r: '#c0301a' }],
    ice: [['iiiiiiii', 'iIiiiiWi', 'iiiiiiii', 'iiWiiiIi', 'iiiiiiii', 'Iiiiiiii', 'iiiiWiii', 'iiiiiiIi'], { i: '#bfe8ff', I: '#8ac8f0', W: '#ffffff' }],
    cloud: [['..wwww..', '.wwwwww.', 'wwwwwwww', 'wwwWwwww', 'wwwwwwww', '.wWwwww.', '........', '........'], { w: '#ffffff', W: '#d8e8ff' }],
    tree: [['..gggg..', '.gGggGg.', 'gggggggg', 'gGgggggg', '.gggggg.', '...bb...', '...bb...', '..bbbb..'], { g: '#3a9a4a', G: '#5ad16a', b: '#6a4226' }],
  };
  for (const [k, [rows, pal]] of Object.entries(tiles)) A(`tile_${k}`, `Tile — ${k}`, 'tiles', sprite(rows, pal, 16), { tile: true });
  return out;
}
