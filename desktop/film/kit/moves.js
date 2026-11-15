// THE CODE COLLECTION — every movement that has been worked out, kept ready to use.
// One entry = one move: what it is for, the rules that make it read, the captured clips with their moments, the kit
// functions / commands / tools it needs, the code to paste, and how to check it (a number or a strip, never an
// impression). Ask before working a move out again:
//   in a film:   import { MOVES, findMoves } from '../vs.js'
//   from Claude: {"op":"code.moves","q":"pick up a bag"}        on the command line: node devtests/moves.mjs pick up a bag
// Add a move only when it has been built, looked at in motion and measured; devtests/moves.mjs holds every entry to
// that (its clips exist, its moments are inside them, nothing starts on a capture's T-pose frame, its code uses what it lists).

export const MOVES = [
  {
    id: 'run', name: 'A run', tags: ['run', 'jog', 'runner', 'legs', 'arms'],
    use: 'Someone runs from one place to another at an ordinary running speed (3 to 4 m/s).',
    rules: [
      'A run has a moment in the air in every step; the foot lands under the body, not far ahead of it; the trunk leans a little forward from the ankles.',
      'The arms are bent about a right angle and swing from the shoulder against the legs, hand from hip to chest, elbows close to the ribs: they never open sideways.',
      'About 180 steps a minute (a step every third of a second); the head stays level.',
      'A body at speed cannot start or stop in a frame: let the film find him already running, or use brake.',
    ],
    clips: [{ clip: '09_05', in: 0.05, out: 0.8, note: 'one whole stride at 3.4 m/s; the Stage loops it' }],
    needs: ['stage.do', 'swingArms'],
    code: `{ op: 'stage.do', actor: 'a', actions: [{ t: 0, go: [x, z], gait: 'run', clip: '09_05', speed: 3.6 }] }
// captured joggers hold their hands high: in actors(), fit: (pose) => swingArms(pose, { amount: 1, deg: 40 }) gives the arms their swing`,
    check: ['wrists between 0.3 and 0.55 m apart through the whole run (the judge reports arm-flare if they fly out)', 'a strip of two strides at 12 frames a second', 'no foot-slide finding'],
  },
  {
    id: 'sprint', name: 'A very fast run', tags: ['run', 'fast', 'sprint', 'dash', 'chase'],
    use: 'Someone runs as fast as he can (about 5 m/s): a chase, being late, an attack.',
    rules: [
      'The fastest HONEST runs in the library are 4.3 to 5 m/s at 176–198 steps a minute, which is what real runners do. Do not ask a clip for much more than it was run at: the legs then spin (above 1.4× it shows).',
      'More lean, a harder arm drive, a longer stride and a longer moment in the air than a run; the arms still close to the ribs.',
      'He covers 5 metres a second: the lens must travel with him (beside and a little ahead) or stand far off; a shot of 2 seconds needs 10 metres of ground.',
      'Start the shot when he is already at speed, or show the brake: nobody is at 5 m/s one frame after standing.',
      'Check it by numbers (gait.mjs): steps a minute near what is usual for his legs, in the air a third of the time or more, hands under 0.6 m apart.',
    ],
    clips: [{ clip: '16_46', in: 0.26, out: 0.94, note: 'captured at 4.26 m/s, 176 steps a minute: the cleanest fast stride in the library' }, { clip: '127_08', in: 0.17, out: 0.78, note: '4.5 m/s, 198 steps a minute; he speeds up through it' }],
    needs: ['stage.do', 'gaitReport', 'gait.mjs'],
    code: `{ op: 'stage.do', actor: 'a', actions: [{ t: 0, go: [x + 60, z], gait: 'run', clip: '16_46', speed: 5.0 }] }   // (or '127_08'); the shot begins at 0.7 s, when he is at speed
// camera beside him: cam: (t, ctx) => { const p = at(ctx, 'a', 'hips'); return { pos: [p[0] + 3.4, 1.2, z + 5.4], target: [p[0] + 0.5, 1.0, z], fov: 40 }; }
// node devtests/gait.mjs <film> <seq> a 0.7 2.4      (gaitReport: steps a minute, time in the air, lean, arms against legs)`,
    check: ['gait.mjs has nothing a coach would remark on', 'the background passes at his speed in a strip of the tracking shot'],
  },
  {
    id: 'brake', name: 'A run to a quick stop', tags: ['stop', 'brake', 'run', 'halt', 'slow'],
    use: 'A runner stops: before picking something up, before a lord, at a cliff edge.',
    rules: [
      'Stopping takes about two seconds and two or three steps: the body sinks, the trunk comes upright and back, the arms go out for balance.',
      'The capture veers and travels on (about 2.4 m, 0.7 m to one side): solve once, read where he ends, and move the way-point by the measured error.',
    ],
    clips: [{ clip: '127_05', in: 0.45, out: 2.35, note: 'Run to Quick Stop: the brake is 0.45 to 2.35 s' }],
    needs: ['stage.do', 'where.mjs'],
    code: `{ t: tStop, clip: '127_05', in: 0.45, out: 2.35, blend: 0.2 }    // after a go … gait: 'run'; then node devtests/where.mjs <film> shot@t a:root to see where he stands`,
    check: ['where.mjs: he stands where the next action needs him', 'no foot-slide MUST in the judge'],
  },
  {
    id: 'pick-up', name: 'Picking something up from the ground', tags: ['pick', 'up', 'take', 'grab', 'lift', 'bag', 'box', 'prop', 'reach'],
    use: 'Someone walks or runs up to a thing on the ground, bends, takes it and goes on with it in the hand.',
    rules: [
      'A thing never jumps into a hand: show the approach, the bend, the hand closing on it and the lift in ONE shot.',
      'Chain captured performances on the one body (run, brake, pick-up, run on) instead of keying a reach over a run.',
      'This capture takes the thing with the LEFT hand, lowest 1.18 s after its start here; after 4.1 s it turns back the way it came, so it is cut there. A real pick-up takes two seconds: do not hurry it.',
      'Put the thing exactly where the hand comes down: solve, read the wrist at that moment, set the thing there.',
    ],
    clips: [{ clip: '143_10', in: 2.0, out: 4.1, note: 'Walk And Pick Up Tool Box: two steps, bend, grab at clip 3.18 s, upright by 4.1' }],
    needs: ['stage.do', 'carried', 'where.mjs'],
    code: `{ t: tPick, clip: '143_10', in: 2.0, out: 4.1, blend: 0.3 }, { t: tPick + 2.1, go: [x, z], gait: 'run', clip: '09_05', speed: 3.8, blend: 0.35 }
const TG = tPick + 1.24;                                              // the hand closes
const bag = carried({ rest: [bx, 0, bz], by: 'a', hand: 'L', from: TG, hang: 0.2, make: (p, yaw) => new Mesh(box(p, 0.3, 0.22, 0.12, yaw, { side: '#b5382e' })) });
// dyn: (ts, ctx) => [bag(ts, ctx)]      rest = wrist.L at TG from: node devtests/where.mjs <film> shot@t a:wrist.L`,
    check: ['the wrist is within 5 cm of the thing at the grab (where.mjs)', 'a strip of the one shot: brake, step, bend, grab, lift'],
  },
  {
    id: 'carry', name: 'A thing carried in a hand', tags: ['carry', 'hold', 'thing', 'prop', 'bag', 'hand', 'set', 'down'],
    use: 'A bag, a cup, a book: it lies somewhere, a hand takes it, it goes where the hand goes, it is set down.',
    rules: [
      'Before the hand closes the thing does not move; after, it hangs from the wrist and swings with it; set down, it stays.',
      'It is drawn across into the hand in a few frames (take: 0.07 s), never in one.',
    ],
    needs: ['carried'],
    code: `const cup = carried({ rest: [x, 0.8, z], by: 'a', hand: 'R', from: 3.2, to: 7.5, drop: [x2, 0.8, z2], hang: 0.1, make: (p, yaw) => new Mesh(box(p, 0.08, 0.1, 0.08, yaw, { side: '#ddd' })) });
// dyn: (ts, ctx) => [cup(ts, ctx)]`,
    check: ['a strip across from and to: the thing is in one place before and after, and at the wrist between'],
  },
  {
    id: 'sit-alive', name: 'Sitting, alive', tags: ['sit', 'seated', 'chair', 'desk', 'class', 'wait', 'idle', 'fidget'],
    use: 'Someone sits on a chair through a scene (a pupil, a listener) and must not look like a photograph.',
    rules: [
      'A held pose is a statue. Play a captured SEATED performance slowed down (rate 0.35 to 0.55): small shifts of weight, hands that move.',
      'Key the acting over it: the look with eyesLead, a lean, a twist; keep the film\'s life on (breath, blinks).',
      'The Stage pose "sit" is a seat on the GROUND. This capture sits on a low stool: lift the hips 7 cm for a chair.',
    ],
    clips: [{ clip: '13_05', in: 5.0, out: 10.3, note: 'chin on the hands, thinking' }, { clip: '13_05', in: 32.4, out: 36.6, note: 'upright, hands on the knees' }],
    needs: ['stage.do', 'stage.overlay', 'eyesLead'],
    code: `{ op: 'stage.do', actor: 'a', actions: [{ t: 0, clip: '13_05', in: 5.0, out: 10.3, rate: 0.42, at: { pos: SEAT, yaw: 180 } }] },
{ op: 'stage.overlay', actor: 'a', add: [{ t0: 0, t1: END + 1, fade: 0, keys: [{ on: 'hips', keys: [[0, [0, 0.07, 0]], [END + 1, [0, 0.07, 0]]] }] }, ...eyesLead(beats, { end: END }).head] }`,
    check: ['no statue finding from the judge', 'the hips are at the seat (root y about 0.45 m)'],
  },
  {
    id: 'sit-bench', name: 'Sitting on a bench or chair, by measure', tags: ['sit', 'seated', 'bench', 'chair', 'seat', 'legs', 'depressed', 'sad', 'tired', 'wait', 'platform'],
    use: 'Someone sits on a real seat (a bench, a chair) and it must look right: on the seat, not in the air before it or sunk into it, the legs natural, the seat not painted over him.',
    rules: [
      'Measure, do not guess: the seat\'s top (y) and its front and back edge. Then solve once and read the body with where.mjs: the HIP JOINT (upperleg01) belongs 10 cm over the seat top, the hips over the seat (well behind its front edge), the knees beyond the front edge at about their standing height, the ankles at theirs (0.07 m) with the shins near upright.',
      'A captured seat is rarely the height of yours. Set the whole body lower or higher with the action\'s own height — at: { pos: [x, y, z] } — and hold both feet where they stood on the floor with foot keys in world space: the knees fold to suit and the thighs come level.',
      'A seat is drawn in PIECES (0.4 m): the seat and what is under it anchored where the sitter\'s hips are, with bias 0.3, so they are always drawn before him; the back anchored on itself. A bench drawn as one thing is sorted by its middle and paints over whoever sits on it.',
      'Bent forward with the forearms on the thighs he sits on the middle of the seat; upright and tired, back near the backrest. Play the capture slowly (rate 0.2 to 0.4) so he is alive.',
    ],
    clips: [{ clip: '22_03', in: 1.0, out: 5.2, note: 'bent forward, elbows on the knees, face towards the hands: broken' }, { clip: '114_05', in: 11.9, out: 13.6, note: 'upright on a chair, hands on the thighs' }, { clip: '113_15', in: 3.3, out: 4.6, note: 'upright; sits down at 1.8, gets up from 4.6' }],
    needs: ['stage.do', 'stage.overlay'],
    code: `// the seat's top is 0.47 m; the capture's hip joint sat at 0.67: the body goes 0.10 lower, the feet stay
{ op: 'stage.do', actor: 'a', actions: [{ t: 0, clip: '22_03', in: 1.0, out: 5.2, rate: 0.4, at: { pos: [X, -0.1, SIT_Z - 0.1], yaw: 180 } }] },
{ op: 'stage.overlay', actor: 'a', add: [{ t0: 0, t1: END + 1, fade: 0, keys: [
  { on: 'foot.L', space: 'world', keys: [[0, FOOT_L], [END + 1, FOOT_L]] }, { on: 'foot.R', space: 'world', keys: [[0, FOOT_R], [END + 1, FOOT_R]] }] }] }
// FOOT_L / FOOT_R: where.mjs <film> shot@t a:foot.L,foot.R before the body was lowered
// upright instead: clip: '114_05', in: 11.9, out: 13.6, rate: 0.18 (3 cm lower); sitting down and getting up: '113_15' (down at 1.8, up from 4.6)`,
    check: ['where.mjs: upperleg01 y = seat top + 0.10 (within 2 cm); foot y 0.07; knee y within 5 cm of standing', 'a frame from the front, the side and three-quarter: the seat is behind and under him in all three'],
  },
  {
    id: 'fall', name: 'Struck down: a fall', tags: ['fall', 'knockout', 'ko', 'struck', 'hit', 'die', 'collapse', 'fight'],
    use: 'Someone is struck and goes down: the end of a fight, a blow, a shot.',
    rules: [
      'A fall is losing balance, going over and landing: about 0.6 s to the ground for a body knocked backwards, more when the knees give first. Never a reversed get-up, never under a third of a second.',
      'Show it in one shot that began before the blow; slow time from just before the blade lands until the body is down.',
      'Struck from the front the body goes over backwards (the default); prone: true is the knees giving and a pitch onto the face.',
    ],
    clips: [{ clip: '90_18', in: 0.45, out: 2.7, note: 'over backwards onto the back; on the ground at 1.55' }, { clip: '90_16', in: 2.25, out: 5.2, note: 'the knees give, onto the face; on the ground at 4.0' }],
    needs: ['stage.do', 'stage.fight', 'ramps'],
    code: `{ op: 'stage.do', actor: 'b', actions: [{ t: tHit + 0.04, fall: {} }] }          // plays 90_18; { fall: { prone: true } } plays 90_16
// in a stage.fight the last beat { do: 'rising', hit: true, ko: true } writes it. The shot: warp: ramps(dur, [{ at: tHit - 0.15 - t0, slow: 0.35, len: 3.0, rec: 2.6 }])`,
    check: ['the hips are still above 70 % of standing height a sixth of the way through, and under 0.2 m at the end (never below the floor)', 'a strip at ten frames a second: rocked, off balance, going over, landing'],
  },
  {
    id: 'kneel', name: 'Going down on one knee', tags: ['kneel', 'bow', 'knee', 'report', 'lord'],
    use: 'A messenger before his lord, a knight, a proposal.',
    rules: [
      'The whole way down must be seen: weight onto the front leg, the back knee to the ground, the trunk settles, the head bows last.',
      'Hold the kneel by slowing the same clip with warp, not with a held pose (a pose slides the feet).',
    ],
    clips: [{ clip: '23_03', in: 0.45, out: 2.6, note: 'down between 0.45 and 1.9 s' }],
    needs: ['stage.do'],
    code: `{ t: tDown, clip: '23_03', in: 0.45, out: 2.6, blend: 0.35, warp: [[0, 0.45], [1.45, 1.9], [hold, 2.6]] }   // hold: seconds the kneel lasts after he is down`,
    check: ['foot-slide under 6 cm in the judge', 'a strip of the whole kneel in one shot'],
  },
  {
    id: 'look', name: 'A look: the eyes lead, the head follows', tags: ['look', 'eyes', 'gaze', 'glance', 'head', 'turn', 'blink'],
    use: 'Someone looks at someone or something; every change of attention in a scene.',
    rules: [
      'The eyes go first and fast (a dart of two or three frames); the head sets off about 0.2 s later and moves slower; a big change of gaze takes a blink with it.',
      'How much head: 0 = the eyes alone (careful, shy, sneaking), 0.4 to 0.5 = guarded, 0.9 = open. Nobody turns eyes and head in one move.',
      'A head turn travels on an arc with a small dip, never on a straight line; beyond about 60 degrees the shoulders and then the hips join in.',
    ],
    needs: ['eyesLead', 'stage.overlay'],
    code: `const L = eyesLead([{ t: 0, at: [x, y, z], head: 0.9 }, { t: 2.0, at: 'b', head: 0.5 }, { t: 6, at: 'b', head: 0 }], { end: T.dur });
// actors: { id: 'a', look: L.look, … }        steps: { op: 'stage.overlay', actor: 'a', add: L.head }`,
    check: ['a strip at 12 frames a second across each change: eyes first, a blink on the big ones, then the head'],
  },
  {
    id: 'caught-looking', name: 'Caught looking: the shy glance away', tags: ['shy', 'glance', 'crush', 'caught', 'blush', 'embarrassed', 'eyes', 'love'],
    use: 'Someone watches another in secret, is seen, and looks away; then dares to look again.',
    rules: [
      'Watching: eyes on her, the head only half turned, hardly a blink (someone who stares forgets to).',
      'Caught: the eyes go away AT ONCE, the head ducks 0.08 s later with a short fade, the colour comes half a second after.',
      'The second look is with the eyes alone (head: 0); the head comes up only when he dares.',
    ],
    needs: ['eyesLead', 'stage.overlay'],
    code: `const E = eyesLead([{ t: 0, at: BOOK, head: 0.9 }, { t: LOOK, at: 'girl', head: 0.5 }, { t: CAUGHT, at: BOOK, head: 0.95, lead: 0.08, fade: 0.16 },
  { t: PEEK, at: 'girl', head: 0 }, { t: RISE, at: 'girl', head: 0.45, fade: 0.7 }], { end: END });
// emote: [[CAUGHT, { like: 'shock', blush: 0.5 }, 0.12], [CAUGHT + 0.7, { like: 'afraid', blush: 0.95 }, 0.5], [RISE + 0.3, { like: 'happy', blush: 0.8 }, 0.7]]`,
    check: ['cut to the close-up ON the moment he is caught, so the dart away opens the shot', 'a strip of the close-up at 12 frames a second'],
  },
  {
    id: 'wait', name: 'Standing and waiting, alive', tags: ['stand', 'wait', 'idle', 'soldier', 'guard', 'crowd', 'weight'],
    use: 'Anyone who only stands in a shot: guards, a crowd, someone listening.',
    rules: [
      'Nobody alive holds still: the weight goes from one leg to the other, the head looks about, the chest breathes.',
      'Give each body a different capture, or the same one from a different moment, so no two move together.',
    ],
    clips: [{ clip: '139_02', in: 1.0, note: 'shifting weight' }, { clip: '137_41', in: 2 }, { clip: '40_10', in: 4 }],
    needs: ['stage.do'],
    code: `{ op: 'stage.do', actor: 'g1', actions: [{ t: 0, idle: true }, { t: 0.3, idle: true, clip: '139_02', in: 1.0, blend: 0.6 }] }   // others: '137_41' in 2, '40_10' in 4`,
    check: ['no statue finding from the judge'],
  },
  {
    id: 'duel-ending', name: 'The blow that ends a fight', tags: ['fight', 'duel', 'sword', 'blow', 'final', 'slow', 'motion', 'combat'],
    use: 'The last exchange of a sword or fist fight: the audience must see exactly how the loser was beaten.',
    rules: [
      'One shot from before the feint to the stillness after: wind-up, the cut, the blow landing, the stagger, the fall. No cut between the blow and the fall.',
      'Side on, both bodies in full, the lens on one side of the line between them.',
      'Read the solved beat times when they are needed, not when the film file is loaded.',
    ],
    needs: ['stage.fight', 'fightBeats', 'ramps', 'combat', 'fightEvents'],
    code: `let _k = null; const K = () => (_k && _k.FB.beats.length ? _k : (_k = (() => { const FB = fightBeats('duel'); return { FB, FX: combat(fightEvents(FB.beats, () => ['hero', 'foe'])), LAST: FB.beats.at(-1)?.apex ?? 7 }; })()));
{ id: 'end', t0: CUT, t1: END, warp: ramps(END - CUT, [{ at: K().LAST - 0.15 - CUT, slow: 0.35, len: 3.0, rec: 2.6 }]), cam: … }`,
    check: ['a strip a tenth of a second apart across the blow: contact, rocked, going over, down'],
  },
  {
    id: 'dance-on-beat', name: 'A dance laid on its music', tags: ['dance', 'beat', 'music', 'rhythm', 'tempo', 'count', 'bounce', 'groove', 'twist'],
    use: 'Someone dances to the film\'s music, and the bounces, the accents and the cuts must fall on the beats.',
    rules: [
      'Poses HIT on the beat and travel between beats; the knees give on every beat or half beat (the bounce IS the pulse); a big accent is a drop of the whole body on the first beat of a bar.',
      'The weight is always on one leg: the hip goes out over it and the shoulders tilt the other way; arms never mirror each other; hands stay away from the trunk so the outline reads.',
      'Take the tempo FROM the dancer: measure his bounce, make the music\'s half beat that long at a beat that is a whole number of frames; then lock every bounce to the grid (a person drifts) — never more than 15 % faster or slower.',
      'The lens stands still: a lens that follows the hips cancels the bounce. Cuts fall on a bar or half a bar; a new take begins ON a cut with blend: 0, at in = dip − (beat moment − t) so its first bounce is on the grid by itself.',
      'Measure the music cue once too: its onsets must be where the grid says, and the bar must begin where the accent lands.',
    ],
    clips: [
      { clip: '15_05', in: 104.53, out: 114.7, note: 'stands, the arm goes out, the Dive (lowest at 107.33), rises, the Twist (bounces from 109.92, every 0.35 s)' },
      { clip: '15_05', in: 135.53, out: 141.6, note: 'the Cabbage Patch (bounces from 135.88), then he stops, turns to his right and stands' },
    ],
    needs: ['beatGrid', 'bounceTimes', 'beatMatch', 'beatLock', 'steps.mjs', 'stage.do'],
    code: `const BPM = 60 / 0.7, BEAT = 0.7;                    // beatGrid(BPM, 30).whole: a beat is 21 frames; the bar is 4 beats
// 1) play '15_05' plain, solve, then: node devtests/steps.mjs <film> <seq> <actor> 85.7   → the dips (bounceTimes) and, by beatMatch, how sure the pulse is
const DIVE = 107.33, TWIST = [109.92, 110.25, 110.58, 110.92, 111.28, 111.63, 111.98, 112.37, 112.70, 113.08, 113.55, 113.88, 114.28];   // CLIP seconds
const P1 = beatLock([DIVE, ...TWIST], { bpm: BPM, in: DIVE - 4 * BEAT, out: 114.7, t: 0 });   // the Dive lands on beat 4: the one of bar two
{ op: 'stage.do', actor: 'dan', actions: [{ t: 0, clip: '15_05', in: DIVE - 4 * BEAT, out: 114.7, warp: P1.warp, at: { pos: [0, 0], yaw: 0 } },
  { t: CUT - 0.001, clip: '15_05', in: 135.88 - (CUT + 0.35 - (CUT - 0.001)), out: 141.6, warp: P2.warp, at: { pos: [0, 0], yaw: 0 }, blend: 0 }] }   // a new take on the cut`,
    check: ['every dip of the hips within 20 ms of a half beat (measured: 23 of 23, the Dive on the first beat of bar two)', 'the judge has no must on the dance (a pop means a take was blended in sight: start it on the cut)', 'the music cue\'s onsets measured against the grid'],
  },
  {
    id: 'hand-clear', name: 'A hand that brushes the body: open the arm for a moment', tags: ['hand', 'arm', 'body', 'through', 'clip', 'penetrate', 'overlay', 'shoulder', 'elbow', 'wrist'],
    use: 'A captured move on another body puts a hand a few centimetres inside the hip or the clothes for a moment (the judge reports arm-in-body).',
    rules: [
      'Change as little as possible: a bone overlay ADDS degrees to the captured pose for a moment, with a fade each side; the performance stays the performer\'s.',
      'For the LEFT upper arm z+ opens the arm sideways (15° moves the wrist 10 cm out) and x+ swings it back (15° = 7 cm); measure the other side before trusting a sign.',
      'Look where the wrist is against the hips first: beside them, open the arm (z); before them, bring it forward (x−).',
    ],
    needs: ['stage.overlay', 'sensei.mjs', 'where.mjs'],
    code: `{ op: 'stage.overlay', actor: 'dan', add: [{ t0: 11.3, t1: 12.0, fade: 0.25, keys: [{ on: 'upperarm01.L', keys: [[0, [0, 0, 8]], [0.7, [0, 0, 8]]] }] }] }
// the moment and the depth come from: SHOW=arm-in-body node devtests/sensei.mjs <film> <shot>`,
    check: ['the judge no longer reports arm-in-body', 'the wrist moved the way meant (where.mjs before and after)'],
  },
  {
    id: 'solar-system', name: 'A solar system', tags: ['space', 'planet', 'orbit', 'sun', 'moon', 'solar', 'star'],
    use: 'Planets going round a sun; any set of things that orbit.',
    rules: [
      'The inner planets race and the outer ones crawl (a round takes distance to the power 1.5): that is what makes it read as a system and not a carousel.',
      'Every ball is lit from the sun\'s side only: between us and the sun it is a crescent.',
      'Hang the sun high and put the lens below it looking up, so no horizon is in the picture; one slow move, no cuts.',
    ],
    needs: ['solarSystem', 'space'],
    code: `const S = solarSystem({ sun: [0, 35, -24], year: 9, planets: [{ name: 'earth', ring: 6.9, r: 0.4, color: '#4f8fd6', land: '#5fae6a', moon: true, year: true }, { name: 'saturn', ring: 16, r: 0.95, color: '#e3cf9a', hoop: '#cfc09a' }] });
// set: () => space({ moon: false })      shot: over: (g, cam, sc, t) => S.draw(g, cam, t)`,
    check: ['in a strip two seconds apart the inner planets have moved several times as far as the outer ones'],
  },
  {
    id: 'wave', name: 'A wave of the hand', tags: ['wave', 'hello', 'greet', 'hand', 'gesture'],
    use: 'Someone greets someone they see coming.',
    rules: [
      'She sees him first (the eyes, then the head), then the arm goes up; the wave is from the elbow with a loose wrist.',
      'It settles: the arm comes down slower than it went up.',
    ],
    clips: [{ clip: '143_25', in: 0.4, out: 5.2 }],
    needs: ['stage.do'],
    code: `{ t: 2.4, clip: '143_25', in: 0.4, out: 5.2, at: { yaw: -90 }, blend: 0.4 }, { t: 7.2, idle: true, blend: 0.5 }`,
    check: ['a strip of the wave: the hand is above the shoulder and clear of the head'],
  },
];

const wordsOf = (s) => s.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length > 1);
/** The moves that fit some words ("pick up a bag", "shy glance"), the best first; without words, all of them. */
export function findMoves(q) {
  if (!q || !String(q).trim()) return [...MOVES];
  const want = wordsOf(String(q));
  return MOVES.map((m) => {
    const id = wordsOf(m.id), tags = m.tags ?? [], name = wordsOf(m.name), use = wordsOf(m.use);
    let score = 0;
    for (const w of want) score += (id.includes(w) ? 4 : 0) + (tags.includes(w) ? 3 : 0) + (name.includes(w) ? 2 : 0) + (use.includes(w) ? 1 : 0);
    return { m, score };
  }).filter((x) => x.score > 0).sort((a, b) => b.score - a.score).map((x) => x.m);
}
/** A move as one page of text. */
export function moveText(m) {
  return [
    `${m.name.toUpperCase()}  (${m.id})`, m.use, '', 'RULES', ...m.rules.map((r) => `  - ${r}`),
    ...(m.clips?.length ? ['', 'CLIPS', ...m.clips.map((c) => `  ${c.clip}  ${c.in ?? 0}${c.out !== undefined ? ` to ${c.out}` : ' on'} s${c.note ? `  — ${c.note}` : ''}`)] : []),
    ...(m.needs?.length ? ['', `NEEDS  ${m.needs.join(', ')}`] : []),
    '', 'CODE', ...m.code.split('\n').map((l) => `  ${l}`), '', 'CHECK', ...m.check.map((c) => `  - ${c}`),
  ].join('\n');
}
