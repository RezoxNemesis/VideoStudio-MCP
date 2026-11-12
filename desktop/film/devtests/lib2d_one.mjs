// one cut-out character large, in three poses: node devtests/lib2d_one.mjs id out.jpg
import fs from 'node:fs';
import { createCanvas } from '@napi-rs/canvas';
import { drawCutout, CUTOUT_POSES, walkPose } from '../kit/library2d.js';
const id = process.argv[2] ?? 'char_mira', W = 1800, H = 1100, cv = createCanvas(W, H), g = cv.getContext('2d');
g.fillStyle = '#ece6dc'; g.fillRect(0, 0, W, H);
[CUTOUT_POSES.stand, CUTOUT_POSES.wave, walkPose(0.3), CUTOUT_POSES.think].forEach((p, i) => drawCutout(g, id, 225 + i * 450, 1060, 1000, p));
fs.writeFileSync(process.argv[3] ?? '/tmp/l2t/one.jpg', cv.toBuffer('image/jpeg', 92));
