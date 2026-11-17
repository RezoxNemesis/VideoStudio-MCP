// The dialogue chain (kit/sound.js voiceChain): node devtests/voicechain.mjs   (exit 1 when a rule is broken)
import { voiceChain } from '../kit/sound.js';
const SR = 48000, fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const tone = (f, a, secs = 1) => { const n = SR * secs, l = new Float32Array(n); for (let i = 0; i < n; i++) l[i] = a * Math.sin(2 * Math.PI * f * i / SR); return { l, r: l.slice(), n }; };
const rms = (x, from = 0.3) => { let s = 0, c = 0; for (let i = Math.round(from * SR); i < x.n; i++) { s += x.l[i] * x.l[i]; c++; } return Math.sqrt(s / c); };
const db = (a, b) => 20 * Math.log10(a / b);
const lo = voiceChain(tone(40, 0.02)), mid = voiceChain(tone(1000, 0.02)), loud = voiceChain(tone(1000, 0.5));
ok(db(rms(lo), rms(tone(40, 0.02))) < -6, 'rumble at 40 Hz is cut', db(rms(lo), rms(tone(40, 0.02))));
ok(Math.abs(db(rms(mid), rms(tone(1000, 0.02)))) < 2.5, 'a quiet 1 kHz word passes', db(rms(mid), rms(tone(1000, 0.02))));
ok(db(rms(loud), rms(tone(1000, 0.5))) < -4, 'a loud word is brought in (compressed)', db(rms(loud), rms(tone(1000, 0.5))));
ok([lo, mid, loud].every((x) => x.l.every(Number.isFinite)), 'no NaN or infinity', null);
if (fails.length) { console.log(`voicechain: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`voicechain: all ${checks} rules hold`);
