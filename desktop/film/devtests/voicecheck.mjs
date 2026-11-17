// Voices belong to their bodies (sensei/voice.js): node devtests/voicecheck.mjs   (exit 1 when a rule is broken)
// A father once spoke with a woman's voice for a whole film (17 Nov 2026); the judge now measures every line's pitch.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { wavPitch, pitchRange, voiceChecks, voiceGender } from '../sensei/voice.js';
const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
// a voiced test tone with harmonics, 1.5 s, 24 kHz
const wav = (file, hz) => { const sr = 24000, n = sr * 1.5, b = Buffer.alloc(44 + n * 2); b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVE', 8); b.write('fmt ', 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) { const t = i / sr, v = 0.3 * Math.sin(2 * Math.PI * hz * t) + 0.15 * Math.sin(4 * Math.PI * hz * t) + 0.08 * Math.sin(6 * Math.PI * hz * t); b.writeInt16LE(Math.round(v * 32767), 44 + i * 2); } fs.writeFileSync(file, b); };
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vc-')); fs.mkdirSync(path.join(dir, 'en'));
for (const hz of [98, 179, 260]) { wav(path.join(dir, `t${hz}.wav`), hz); const got = wavPitch(path.join(dir, `t${hz}.wav`)); ok(got && Math.abs(got - hz) / hz < 0.04, `pitch of a ${hz} Hz voice`, got); }
ok(voiceGender('af_heart*0.6+af_aoede*0.4').f === 1 && voiceGender('am_puck*0.6+af_sky*0.4').m > 0.5, 'a blend\'s gender from its names', null);
ok(pitchRange('man')[1] < 179 && pitchRange('woman')[0] < 179 && pitchRange('girl')[0] > 179, 'a man does not speak at 179 Hz, a woman may, a girl does higher', [pitchRange('man'), pitchRange('woman'), pitchRange('girl')]);
// the film case: Papa (man) at 179 Hz is flagged, Mini (girl) at 260 is not; Papa at 98 is not
wav(path.join(dir, 'en', 'F01.wav'), 260); wav(path.join(dir, 'en', 'F02.wav'), 179);
fs.writeFileSync(path.join(dir, 'en.json'), JSON.stringify({ F01: { who: 'Mini', voice: 'af_sky*0.5+af_bella*0.5' }, F02: { who: 'Papa', voice: 'af_heart*0.6+af_aoede*0.4' } }));
const P = { D: { vo: dir }, FILM: [{ cast: [{ id: 'papa', who: 'Papa', stage: { body: 'man', age: 38 } }, { id: 'mini', who: 'Mini', stage: { body: 'girl', age: 6 } }] }] };
let iss = voiceChecks(P, 'en'); ok(iss.length === 1 && iss[0].actor === 'papa' && iss[0].sev === 'must', 'a woman\'s voice on the father is a must, the girl is fine', iss.map((x) => x.actor));
wav(path.join(dir, 'en', 'F02.wav'), 98); fs.writeFileSync(path.join(dir, 'en.json'), JSON.stringify({ F01: { who: 'Mini', voice: 'af_sky*0.5+af_bella*0.5' }, F02: { who: 'Papa', voice: 'bm_fable*0.55+am_fenrir*0.45' } })); iss = voiceChecks(P, 'en'); ok(iss.length === 0, 'a man\'s voice on the father passes', iss.map((x) => x.msg));
fs.rmSync(dir, { recursive: true, force: true });
if (fails.length) { console.log(`voicecheck: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`voicecheck: all ${checks} rules hold`);
