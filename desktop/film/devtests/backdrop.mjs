// A 3D set with painted distance round it: node devtests/backdrop.mjs place bg out.jpg
import fs from 'node:fs';
import { createCanvas } from '@napi-rs/canvas';
import { makeCam } from '../engine/camera.js';
import * as K from '../kit/index.js';
const [place = 'rooftop', bg = 'bg_city_dusk', out = '/tmp/l2t/backdrop.jpg', time = 'dusk'] = process.argv.slice(2);
const sc = K.backdrop(K[place]({ time }), bg);
const W = 960, H = 540, sheet = createCanvas(W * 2, H * 2), sg = sheet.getContext('2d');
const views = [{ pos: [0, 1.6, 6], target: [0, 1.6, 0], fov: 40 }, { pos: [0, 1.6, -6], target: [0, 1.7, 0], fov: 40 }, { pos: [5, 1.4, 2], target: [-2, 2.2, -3], fov: 30 }, { pos: [-3, 0.6, 3], target: [1, 2.6, -2], fov: 44 }];
views.forEach((v, i) => { const cv = createCanvas(W, H), g = cv.getContext('2d'), cam = makeCam(v, W, H), t0 = Date.now(); sc.draw(g, cam, 0, []); console.log('view', i, Date.now() - t0, 'ms'); sg.drawImage(cv, (i % 2) * W, Math.floor(i / 2) * H); });
fs.writeFileSync(out, sheet.toBuffer('image/jpeg', 88));
