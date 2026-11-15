// Another take of a spoken line: node devtests/takes.mjs        (exit 1 when a rule is broken)
// script.json can ask for a line to be spoken again in one language (take_hi: 1, then 2 …) when a take lost or
// changed a word: that language's line is spoken afresh, the others keep the takes they have.
import * as PJ from '../kit/project.js';
const { voiceSpec } = PJ;

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const script = { cast: { Sena: { en: ['af_heart', {}], hi: ['hf_alpha', {}] } } };
const ln = { id: 'P4', who: 'Sena', en: 'Make a world that deserves her smile.', hi: 'ऐसी दुनिया बनाओ।', tone: { like: 'loving', rate: 0.8 } };
const en0 = voiceSpec(script, ln, 'en'), hi0 = voiceSpec(script, ln, 'hi');
ok(en0.tone.take === undefined, 'a line is its first take unless another is asked for', en0.tone.take);
const again = { ...ln, take_hi: 2 }, en1 = voiceSpec(script, again, 'en'), hi1 = voiceSpec(script, again, 'hi');
ok(hi1.tone.take === 2 && hi1.key !== hi0.key, 'take_hi asks for another take of the Hindi line: it is spoken again', [hi1.tone.take, hi1.key, hi0.key]);
ok(en1.key === en0.key && en1.tone.take === undefined, 'and the English line keeps the take it has', [en1.key, en0.key]);
ok(voiceSpec(script, { ...ln, take: 1 }, 'en').tone.take === 1 && voiceSpec(script, { ...ln, take: 1 }, 'hi').tone.take === 1, 'take asks for one in every language', voiceSpec(script, { ...ln, take: 1 }, 'en').tone.take);
ok(voiceSpec(script, { ...ln, take: 1, take_hi: 3 }, 'hi').tone.take === 3, 'a language\'s own take comes before it', voiceSpec(script, { ...ln, take: 1, take_hi: 3 }, 'hi').tone.take);
ok(voiceSpec(script, { ...ln, take_en: -1 }, 'en').key === en0.key && voiceSpec(script, { ...ln, take_en: 0 }, 'en').key === en0.key && voiceSpec(script, { ...ln, take_en: 1.5 }, 'en').key === en0.key, 'nonsense is no take', 0);

// ---- a line that was spoken is never spoken again because the build was stopped before the takes were fitted to
// each other (a long script is hours of speaking: a build that is stopped, or a PC that is shut down, must cost nothing)
{
  const st = typeof PJ.voiceState === 'function' ? PJ.voiceState : null;
  ok(!!st, 'voiceState({ rawKey, key, raw, fitted, listed }) says what a line still needs', Object.keys(PJ).filter((k) => /voice/i.test(k)));
  if (st) {
    ok(st({ rawKey: 'k1', key: 'k1', raw: true, fitted: true, listed: true }) === 'have', 'spoken and fitted: nothing to do', st({ rawKey: 'k1', key: 'k1', raw: true, fitted: true, listed: true }));
    ok(st({ rawKey: 'k1', key: 'k1', raw: true, fitted: false, listed: false }) === 'fit', 'spoken but not yet fitted (the build was stopped): only the fitting is left', st({ rawKey: 'k1', key: 'k1', raw: true, fitted: false, listed: false }));
    ok(st({ rawKey: 'k1', key: 'k1', raw: true, fitted: true, listed: false }) === 'fit', 'fitted file there but not in the list of fitted lines: fitted again, not spoken', st({ rawKey: 'k1', key: 'k1', raw: true, fitted: true, listed: false }));
    ok(st({ rawKey: 'k0', key: 'k1', raw: true, fitted: true, listed: true }) === 'speak', 'the line changed (its text, voice, tone or take): it is spoken again', st({ rawKey: 'k0', key: 'k1', raw: true, fitted: true, listed: true }));
    ok(st({ rawKey: 'k1', key: 'k1', raw: false, fitted: true, listed: true }) === 'speak' && st({ key: 'k1', raw: false, fitted: false, listed: false }) === 'speak', 'no take on disk: it is spoken', st({ key: 'k1', raw: false, fitted: false, listed: false }));
  }
}

// ---- a performance solved by an older Stage is solved again: its key knows which solver made it
{
  const PJ = await import('../kit/project.js'), crypto = await import('node:crypto');
  ok(typeof PJ.bakeKey === 'function' && Number.isInteger(PJ.SOLVER_REV) && PJ.SOLVER_REV >= 7, 'project.js gives bakeKey(spec) and SOLVER_REV (raised whenever the Stage solves or builds bodies differently)', [typeof PJ.bakeKey, PJ.SOLVER_REV]);
  if (typeof PJ.bakeKey === 'function') {
    const spec = { name: 's00', seconds: 12.3, actors: [{ id: 'v', preset: 'swordsman' }] }, plain = crypto.createHash('md5').update(JSON.stringify(spec)).digest('hex');
    ok(PJ.bakeKey(spec) === PJ.bakeKey(JSON.parse(JSON.stringify(spec))), 'the same performance has the same key', [PJ.bakeKey(spec), PJ.bakeKey(JSON.parse(JSON.stringify(spec)))]);
    ok(PJ.bakeKey(spec) !== PJ.bakeKey({ ...spec, seconds: 12.4 }), 'a changed performance has another', 0);
    ok(PJ.bakeKey(spec) !== plain, 'a key written before the solver changed (the plain hash of the performance) no longer matches: the performance is solved again', PJ.bakeKey(spec));
    ok(/^[0-9a-f]{32}$/.test(PJ.bakeKey(spec)), 'a key is still a 32-digit hash', PJ.bakeKey(spec));
  }
}

// ---- looking closely: a part of the picture enlarged, and where bodies are in numbers
{
  const T = await import('../runner/tools.mjs').catch((e) => ({ error: String(e) }));
  ok(typeof T.cropRect === 'function' && typeof T.jointsOf === 'function', 'runner/tools.mjs gives cropRect and jointsOf', T.error ?? [typeof T.cropRect, typeof T.jointsOf]);
  if (typeof T.cropRect === 'function' && typeof T.jointsOf === 'function') {
    ok(JSON.stringify(T.cropRect(1920, 1080, [0.25, 0.5, 0.5, 0.25])) === JSON.stringify([480, 540, 960, 270]), 'a crop is given in parts of the picture: [left, top, width, height] from 0 to 1', T.cropRect(1920, 1080, [0.25, 0.5, 0.5, 0.25]));
    ok(JSON.stringify(T.cropRect(1920, 1080, [0.9, 0.9, 0.5, 0.5])) === JSON.stringify([1728, 972, 192, 108]), 'a crop that runs off the picture is cut at its edge', T.cropRect(1920, 1080, [0.9, 0.9, 0.5, 0.5]));
    ok(T.cropRect(1920, 1080, null) === null && T.cropRect(1920, 1080, [0, 0, 1, 1]) === null, 'no crop, or the whole picture, is no crop', [T.cropRect(1920, 1080, null), T.cropRect(1920, 1080, [0, 0, 1, 1])]);
    let threw = ''; try { T.cropRect(1920, 1080, [0.2, 0.2, 0, 0.5]); } catch (e) { threw = String(e.message); }
    ok(/left, top, width, height/.test(threw), 'a crop with no width says how a crop is written', threw);
    const pose = { j: { head: [1.23456, 1.7, -2], root: [1.2, 0.93, -2], 'wrist.R': [0.9, 1.0, -1.8] } };
    ok(JSON.stringify(T.jointsOf(pose, ['head', 'wrist.R'])) === JSON.stringify({ head: [1.235, 1.7, -2], 'wrist.R': [0.9, 1, -1.8] }), 'jointsOf(pose, names): where those joints are, to the millimetre', T.jointsOf(pose, ['head', 'wrist.R']));
    ok(JSON.stringify(Object.keys(T.jointsOf(pose))) === JSON.stringify(['head', 'root']), 'with no names: the head and the hips', T.jointsOf(pose));
    let t2 = ''; try { T.jointsOf(pose, ['elbow']); } catch (e) { t2 = String(e.message); }
    ok(/elbow/.test(t2) && /head/.test(t2) && /wrist\.R/.test(t2), 'a joint that is not there is refused with the names that are', t2);
  }
}

if (fails.length) { console.log(`takes: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`takes: ${checks} rules hold`);
