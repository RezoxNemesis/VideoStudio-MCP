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

if (fails.length) { console.log(`takes: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`takes: ${checks} rules hold`);
