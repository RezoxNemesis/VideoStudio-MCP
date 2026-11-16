// Pictures on surfaces: node devtests/pictures.mjs        (exit 1 when a rule is broken)
// A photograph in a hand, a poster on a wall, a face in the sky: a film can load a picture file and draw it on a face
// of a Mesh (lit like the face) or over the frame. The Papa film's photograph was "a generic icon … two simplified
// silhouettes" because the kit could only draw shapes (16 Nov 2026).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createCanvas } from '@napi-rs/canvas';
import { picture, onFace } from '../kit/pictures.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };

// a picture file: left half red, right half blue, 200 × 100
const src = createCanvas(200, 100), sg = src.getContext('2d'); sg.fillStyle = '#ff0000'; sg.fillRect(0, 0, 100, 100); sg.fillStyle = '#0000ff'; sg.fillRect(100, 0, 100, 100);
const file = path.join(os.tmpdir(), `vs_picture_test_${process.pid}.png`); fs.writeFileSync(file, src.toBuffer('image/png'));
const img = await picture(file);
ok(img.width === 200 && img.height === 100, 'a picture file is loaded with its size', [img.width, img.height]);
ok((await picture(file)) === img, 'the same file is loaded once', 0);
// a face 0.2 m × 0.2 m drawn as a 100 px square at (50, 50) of a canvas (v runs up)
const cv = createCanvas(200, 200), g = cv.getContext('2d'); g.fillStyle = '#00ff00'; g.fillRect(0, 0, 200, 200);
const F = (lit) => ({ w: 0.2, h: 0.2, uv: (u, v) => [50 + u * 100, 150 - v * 100], col: () => lit });
onFace(g, F('#ffffff'), img, 0, 0, 1, 1);
const at = (x, y) => [...g.getImageData(x, y, 1, 1).data.slice(0, 3)];
ok(at(60, 100)[0] > 240 && at(60, 100)[2] < 15, 'the left of the picture is on the left of the face', at(60, 100));
ok(at(140, 100)[2] > 240 && at(140, 100)[0] < 15, 'the right of it on the right', at(140, 100));
ok(at(20, 100)[1] > 240 && at(100, 20)[1] > 240, 'nothing is drawn beyond the face', [at(20, 100), at(100, 20)]);
// cover: a 2:1 picture on a square face is cropped at its sides, not squeezed: at a quarter of the face it is still red
ok(at(75, 100)[0] > 240, 'it is cropped to fill the face, not squeezed', at(75, 100));
onFace(g, F('#404060'), img, 0, 0, 1, 1);
ok(at(60, 100)[0] < 90 && at(60, 100)[0] > 30, 'it is lit like the face it is on (a photograph at night is dark)', at(60, 100));
onFace(g, F('#ffffff'), img, 0.5, 0, 1, 1, { fit: 'fill' });
ok(at(110, 100)[0] > 240 && at(140, 100)[2] > 240 && at(60, 100)[0] < 90, 'it can cover a part of the face, stretched to it', [at(110, 100), at(140, 100)]);
fs.unlinkSync(file);

if (fails.length) { console.error(`pictures: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`pictures: all ${checks} rules hold`);
