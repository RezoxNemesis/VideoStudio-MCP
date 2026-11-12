// Looking at every frame of a finished video for mistakes a viewer would see for an instant: a part of the picture
// that changes for a few frames and comes back (a smear across the lens, a popped object).
import { spawn } from 'node:child_process';
import { ENV } from '../engine/env.js';

/** → [{ from, to, frames, part (share of the picture), white (an intended white flash) }] (frame numbers). */
export function flashScan(file, o = {}) {
  const W = 160, H = 90, thr = o.threshold ?? 0.018, need = W * H;
  return new Promise((resolve, reject) => {
    const ff = spawn(ENV.ffmpeg, ['-loglevel', 'error', '-i', file, '-vf', `scale=${W}:${H}`, '-pix_fmt', 'gray', '-f', 'rawvideo', '-'], { stdio: ['ignore', 'pipe', 'inherit'] });
    const hist = []; // the last 16 frames
    let carry = Buffer.alloc(0), k = 0, open = null; const out = [];
    const frame = (f) => {
      const prev = hist[hist.length - 1];
      if (open) {
        // has the changed region gone back to what it was before the change?
        let diff = 0; for (let i = 0; i < open.idx.length; i++) if (Math.abs(f[open.idx[i]] - open.base[open.idx[i]]) > 28) diff++;
        if (diff / open.idx.length < 0.25) { out.push({ from: open.at, to: k - 1, frames: k - open.at, part: +open.part.toFixed(3), white: open.white }); open = null; }
        else if (k - open.at >= 14) open = null;
      }
      if (!open && prev) {
        const idx = []; for (let i = 0; i < need; i++) if (Math.abs(f[i] - prev[i]) > 28) idx.push(i);
        if (idx.length / need > thr) { let br = 0; for (const i of idx) if (f[i] > 215) br++; open = { at: k, base: prev, idx, part: idx.length / need, white: br / idx.length > 0.7 }; }
      }
      hist.push(f); if (hist.length > 2) hist.shift(); k++;
    };
    ff.stdout.on('data', (d) => { carry = carry.length ? Buffer.concat([carry, d]) : d; while (carry.length >= need) { frame(Uint8Array.prototype.slice.call(carry, 0, need)); carry = carry.subarray(need); } });
    ff.on('error', reject);
    ff.on('close', (c) => (c === 0 ? resolve({ frames: k, hits: out }) : reject(new Error(`ffmpeg exit ${c}`))));
  });
}
