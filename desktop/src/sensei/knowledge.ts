/**
 * What Sensei knows without any language model: every finding it can make (what it is, why it matters, how it was
 * measured, how to fix it in VideoStudio), the principles of animation, the grammar of the camera, anime craft and
 * sound targets — plus the Code Studio reference (API.md), searched by meaning-bearing words.
 */
export interface Know { id: string; title: string; text: string; tags: string }

export const FINDINGS: Record<string, { title: string; why: string; how: string; fix: string }> = {
  "foot-slide": { title: "Skating feet", why: "A planted foot carries the body's weight; if it slides, the character looks like it is on ice and the weight is fake.", how: "Each foot's contact flag from the solved performance; while planted, the foot's horizontal travel is measured frame by frame (limit 6 cm, MUST above 15 cm).", fix: "Match the walk speed to the clip, shorten blends between actions, or pin the step with a pose or keys overlay. stage.trace shows the feet around the time." },
  "foot-sinks": { title: "Feet through the ground", why: "Feet that sink read as a broken floor or a floating camera.", how: "Toe and ankle height of planted feet against the ground.", fix: "Check the actor's ground (stairs need ground: (pose) => height) or the clip's height." },
  "foot-floats": { title: "Hovering feet", why: "A standing foot above the floor makes the body weightless.", how: "Planted feet higher than 7 cm.", fix: "Lower xf.pos[1] / fix ground, or let the jump land before the next action blends in." },
  "self-intersect": { title: "Body through itself", why: "A hand inside the chest or a forearm through the belly breaks the illusion of a solid body; in close shots it looks like a drawing mistake.", how: "The body as capsules (torso, head, arms, hands, legs, feet); pairs that should not overlap are tested every frame; depth beyond 5.5 cm for 0.12 s is reported.", fix: "Move the limb clear with a keys overlay on the hand, add a lean, or choose another clip for those seconds." },
  "bodies-overlap": { title: "Two bodies through each other", why: "Characters standing inside each other is the most visible staging error.", how: "Capsules of two actors overlapping more than 6 cm for 0.15 s.", fix: "Start them further apart, stop the walk earlier ('go' target), or offset xf.pos." },
  "grip-lost": { title: "Prop leaves the hand", why: "A sword that floats away from the fingers looks broken; viewers notice hands.", how: "Distance from the hand's grip point to the prop while the prop is otherwise held.", fix: "Keep the prop in the hand through the move, or hide it while it is not held." },
  "blade-through-self": { title: "Blade through own body", why: "A sword passing through its holder kills a fight scene's believability.", how: "The blade segment against the holder's torso, head and legs.", fix: "Angle the blade away (wrist keys overlay) or swap the clip." },
  "wrist-limit": { title: "Broken wrist", why: "A wrist bent past ~100° looks fractured. Hands are what viewers read for intent.", how: "Angle between forearm and hand (knuckle line), skipped while the hand holds a weapon.", fix: "Ease the hand's IK key toward the forearm line and let the elbow do the reaching." },
  "knee-back": { title: "Knee bends backwards", why: "Hyperextended knees look broken and robotic.", how: "Knee behind the hip–ankle line on a straight leg.", fix: "Keep a soft knee: lower the hips a little or use a bent-knee stance." },
  "neck-twist": { title: "Owl neck", why: "Heads turned beyond ~80° from the chest look like a horror effect.", how: "Yaw between head and chest directions.", fix: "Turn the body toward what it looks at and let the head do the last 40°." },
  "spine-twist": { title: "Over-twisted waist", why: "The chest turned far from the hips looks wrung out.", how: "Yaw between hips and chest.", fix: "Turn the feet/hips with the chest." },
  pop: { title: "Pop", why: "A body part that jumps in one frame is the most obvious motion glitch.", how: "A frame whose speed is 4× the frames around it and 2× its neighbours (a jump, not a fast swing).", fix: "Longer blend into the next action, start it from the pose the last one ends in, or cut on that frame." },
  teleport: { title: "Teleport", why: "A character jumping metres in one frame while on screen.", how: "Hip speed in one frame above 20 m/s.", fix: "Hide the actor across an 'at' jump or an xf change, or cut there." },
  "no-arm-swing": { title: "Dead arms while walking", why: "Walking arms swing opposite the legs; still arms read as robotic or injured.", how: "Wrist travel along the walking direction (relative to the hips) under 7 cm while walking faster than 0.7 m/s.", fix: "Use a walking gait/clip with arm swing, or remove an overlay that freezes the arms." },
  twinning: { title: "Twinning", why: "Both arms doing the same mirrored move is a classic sign of mechanical animation.", how: "Mirrored correlation of both wrists' motion above 0.93 while standing.", fix: "Offset one arm by 3–6 frames or give it a smaller, different action." },
  "no-arc": { title: "Straight-line motion", why: "Living motion travels in arcs (a principle of animation); straight lines look mechanical.", how: "Big fast gestures whose path deviates less than 3.5% from the straight line.", fix: "Add a middle key that swings the hand off the straight path." },
  "off-balance": { title: "Off balance", why: "A still body whose weight is outside its feet would fall; it reads as weightless.", how: "Centre of mass (weighted joints) against the support polygon of both feet, standing still for 0.6 s.", fix: "Widen the stance or move the hips over the feet." },
  "too-fast": { title: "Superhuman speed", why: "Faster than any runner breaks the scale of the world unless it is a power.", how: "Hip speed above 10.5 m/s.", fix: "Check the actor's rate or xf." },
  "camera-inside-body": { title: "Camera inside a body", why: "The picture fills with a smear of skin or cloth for a few frames.", how: "Distance from the lens to the body capsules.", fix: "Move the camera back or hide that actor in the shot." },
  "camera-too-close": { title: "Camera too close", why: "Near the lens a body slices or fills the frame.", how: "Lens within 35 cm of a body.", fix: "Pull back or hide the actor." },
  "camera-underground": { title: "Camera under the ground", why: "The world disappears or turns inside out.", how: "Camera height below the ground.", fix: "Raise the camera." },
  "cut-at-knees": { title: "Crop at the knees", why: "Joints are awkward crop lines (they amputate the figure); crop between joints.", how: "The frame's bottom edge within 3% of the knees for half the shot.", fix: "Frame at mid-thigh or show the feet." },
  "cut-at-ankles": { title: "Crop at the ankles", why: "Feet cut off at the ankles look like a mistake.", how: "Bottom edge at the ankles.", fix: "Lower the target or pull back so the feet are in." },
  "head-cut": { title: "Head cut", why: "In wider shots the top of the frame must not cut through the eyes.", how: "Eyes above the frame in MS/MLS/LS.", fix: "Tilt up." },
  headroom: { title: "Too much headroom", why: "Empty space above the head makes the figure sink.", how: "Gap above the head in medium/close shots over 24% of the frame.", fix: "Eyes near the upper third." },
  "no-lead-room": { title: "No lead room", why: "A face looking into the near edge feels cramped; leave space in front of the gaze.", how: "Face turned sideways toward the edge it sits at.", fix: "Pan so the face sits on the far third." },
  "face-hidden": { title: "Face hidden", why: "A speaker hidden behind someone loses the scene.", how: "Head behind a nearer torso/head in the picture for 60% of the shot.", fix: "Move the camera sideways or hide the blocker." },
  "never-seen": { title: "Drawn for nothing", why: "Actors in a shot that never appear in the picture cost render time.", how: "Shown but never inside the frame.", fix: "hide: [ids] on the shot." },
  "tiny-subject": { title: "Subject too small", why: "A lone figure under 6% of the frame height cannot act.", how: "Main figure height in the picture.", fix: "Longer lens (lower fov) or move in." },
  "camera-whip": { title: "Whip-fast camera", why: "At 24–30 fps fast pans strobe.", how: "Camera turning speed above 140°/s.", fix: "Slow the move or cut; whip-pans need motion blur." },
  "camera-jerk": { title: "Jerky camera", why: "A camera that changes speed in one frame jolts the viewer.", how: "Spikes in the camera path's acceleration.", fix: "Ease moves; follow a smoothed point." },
  "camera-steps": { title: "Camera steps with a held drawing", why: "When characters are held on twos and the camera follows them, the whole background judders on twos.", how: "Regular camera jerks every hold period.", fix: "Follow the smooth performance or set hold: 1 on the followed actor." },
  "crosses-line": { title: "180° line crossed", why: "Two people swap sides across a cut, so they seem to turn around; the audience loses who looks where.", how: "Left/right order of two actors at the end of one shot vs the start of the next.", fix: "Keep cameras on one side of the line, or cross it on screen within a shot." },
  "jump-cut": { title: "Jump cut", why: "Same subject, same size, similar angle: the cut looks like a skip.", how: "Angle change under 20° and size ratio under 1.3.", fix: "Change angle ≥ 30° or the shot size by a step." },
  "shot-short": { title: "Very short shot", why: "Under 0.6 s the eye cannot read it unless it is a flash cut.", how: "Shot length.", fix: "Lengthen or merge." },
  "shot-long": { title: "Long static shot", why: "A locked camera for 12+ s tires the eye.", how: "Length and camera motion.", fix: "Cut to a reaction or let the camera drift." },
  "too-dark": { title: "Too dark", why: "On phones in daylight dark scenes read as black.", how: "Average picture value.", fix: "Light the subject: rim light, lantern key, higher ambient." },
  "too-bright": { title: "Washed out", why: "No shadows means no form.", how: "Average picture value.", fix: "Lower exposure, add shadow." },
  "blown-highlights": { title: "Blown highlights", why: "Pure white loses detail and looks cheap.", how: "Share of pure-white pixels.", fix: "Tone down glows and washes." },
  "crushed-blacks": { title: "Crushed blacks", why: "Pure black loses detail.", how: "Share of pure-black pixels.", fix: "Lift shadows or add fill." },
  "blends-in": { title: "Figure blends into the background", why: "Figure–ground separation is the first rule of readable staging (silhouette).", how: "Colour difference between the figure's middle and the ring around it.", fix: "Value/colour contrast: rim light, darker/lighter costume or background, soft background." },
  "colour-jump": { title: "Colour jump at a cut", why: "Same scene, different look: continuity breaks.", how: "Average colour of the last frame vs the next shot's first.", fix: "Match the grade/light between the shots." },
  loudness: { title: "Loudness off target", why: "Platforms normalise loudness; a film mixed too loud/quiet gets changed after you mixed it.", how: "Integrated loudness (EBU R128).", fix: "Mix to the target (YouTube −14 LUFS, broadcast −24)." },
  "true-peak": { title: "Peaks too high", why: "Peaks near 0 dBTP distort after encoding.", how: "True peak.", fix: "Lower effects or limit." },
  "dialogue-buried": { title: "Dialogue buried", why: "Words lost under music on phone speakers.", how: "Each line's level against the music+effects bed under it (needs ≥ 8 dB).", fix: "Lower or duck the music under those lines." },
  "voice-levels": { title: "Uneven voices", why: "Lines jumping in loudness distract.", how: "Spread of line levels.", fix: "Even the lines." },
  "dead-air": { title: "Dead air", why: "Silence sounds like a drop-out unless meant.", how: "Stretches below −52 dBFS for 3 s.", fix: "Ambience or music." },
  "no-bed": { title: "No sound bed", why: "A scene without ambience or music feels empty.", how: "No music/ambience cue over the sequence.", fix: "Add an ambience bed or music." },
  "no-establishing": { title: "No establishing shot", why: "Viewers need to see where they are before the close-ups.", how: "First shot of a sequence is not a wide.", fix: "Open on a wide of the place." },
  "monotone-sizes": { title: "Same shot size everywhere", why: "Variety of sizes is the rhythm of editing.", how: "Share of the most common size per sequence.", fix: "Mix wides, mediums, close-ups and inserts." },
  "subs-too-fast": { title: "Subtitles too fast", why: "More than ~17 characters a second cannot be read.", how: "Characters per second of each line.", fix: "Shorten or give more time." },
  "shot-error": { title: "Shot does not draw", why: "The shot crashes.", how: "Sensei could not draw it.", fix: "code.frame on it shows the error." },
};

const PRINCIPLES: [string, string, string][] = [
  ["Fixing a shot by painting over it", "Animate → Fix a film shot (or Fix by painting on a finding): draw the better version once — a hand, a face, a fold — and the fix holds on to the body part you drew on, moving, turning and sizing with it on every frame of its span. Erase picture takes out what is wrong: the film draws what is behind the person there, or fills the hole from around it. Each fix switches on and off any time; the shot is drawn again with it (only that shot). Agents: paint.parts {shot, t} gives where every part is, paint.add paints, paint.set / paint.on switch.", "paint over fix hand face erase draw correct frame by frame follow part animate paint.add paint.parts"],
  ["Squash and stretch", "Deformation shows weight and flexibility: a ball squashes on impact and stretches in fast motion; keep the volume constant.", "principles squash stretch weight volume"],
  ["Anticipation", "A small move the opposite way before the main action prepares the viewer (crouch before a jump, wind-up before a strike).", "principles anticipation wind up strike jump prepare"],
  ["Staging", "Present one idea clearly: silhouette, figure–ground contrast, camera placement and timing all point at what matters.", "principles staging silhouette clarity composition"],
  ["Straight ahead and pose to pose", "Plan key poses first (pose to pose) for control; straight-ahead for fluid effects like fire and water.", "principles keys poses planning"],
  ["Follow through and overlapping action", "Parts keep moving after the body stops (hair, cloth, arms); different parts move at different rates.", "principles follow through overlap drag hair cloth"],
  ["Slow in and slow out", "Motion eases out of and into poses; only impacts and mechanical things are linear.", "principles ease slow in out spacing"],
  ["Arcs", "Natural motion travels along arcs; heads, hands and swords trace curves.", "principles arcs path curve"],
  ["Secondary action", "Small actions supporting the main one (a hand tapping while talking) add life without stealing focus.", "principles secondary action gesture"],
  ["Timing", "Number of frames per action gives weight and mood; holds (on twos/threes) give anime its rhythm; fast action on ones.", "principles timing frames holds twos ones"],
  ["Exaggeration", "Push poses and timing beyond reality to read clearly — but keep the style consistent.", "principles exaggeration push poses"],
  ["Solid drawing", "Forms with volume, weight and balance; avoid twinning and flat, symmetrical poses.", "principles solid drawing volume balance twinning"],
  ["Appeal", "Clear, pleasing design: simple shapes, readable faces, contrast of big/small, straight/curved.", "principles appeal design character"],
  ["Shot sizes", "ELS/LS for geography, MS for action and body language, MCU/CU for emotion, ECU for detail; vary them.", "camera shot sizes wide close up medium framing"],
  ["The 180° line", "Keep the camera on one side of the line between two characters so screen directions stay consistent.", "camera 180 line axis continuity cut"],
  ["The 30° rule", "Change the camera angle by at least 30° (or the size by a step) at a cut on the same subject, else it's a jump cut.", "camera 30 degree rule jump cut"],
  ["Headroom and lead room", "Eyes near the upper third; leave space in front of a face or a moving subject.", "framing headroom lead room nose room thirds"],
  ["Crop lines", "Never crop at joints (ankles, knees, wrists, elbows, neck); crop between them.", "framing crop joints knees ankles"],
  ["Anime holds", "Characters are often held for 2–3 frames (on twos/threes) while cameras and effects move on ones; action peaks go on ones.", "anime holds twos threes limited animation"],
  ["Impact frames", "At a hit: 1–3 frames of a high-contrast or inverted 'impact frame', a hit-stop, speed lines, a camera shake.", "anime impact frame hit stop speed lines shake fight"],
  ["Smears", "In very fast motion draw smeared or multiple limbs for 1–2 frames instead of motion blur.", "anime smear multiples fast motion"],
  ["Fight choreography", "Clear lines of action, readable silhouettes, beats of anticipation–action–reaction, and pauses between exchanges.", "fight choreography action beats reaction"],
  ["Lip sync", "Mouth shapes lead the sound by about a frame; closed shapes on M/B/P, wide on vowels; don't flap every syllable.", "lip sync mouth visemes dialogue"],
  ["Eyes and blinks", "Blinks every 2–10 s, on head turns and thought changes; eyes lead the head when changing focus.", "eyes blink look gaze acting"],
  ["Hands", "Hands carry intent: avoid flat 'mitten' hands, vary finger curls, keep thumbs readable, avoid both hands identical.", "hands fingers grip fist pose acting"],
  ["Weight shift", "A standing body moves its weight between the feet; the hip over the supporting foot drops on the other side (contrapposto).", "weight balance contrapposto stance"],
  ["Colour script", "Each scene has a colour key (warm dusk, cool night); match grades within a scene, shift them between scenes on purpose.", "colour script grade palette mood"],
  ["Figure–ground", "Separate characters from backgrounds by value first, then colour and focus (rim light, darker/lighter costumes, depth of field).", "contrast figure ground value rim light silhouette"],
  ["Loudness targets", "YouTube/Instagram ~ −14 LUFS integrated, true peak ≤ −1 dBTP; broadcast −23/−24 LUFS; dialogue 8–12 dB above the music.", "sound loudness lufs peak mix dialogue music"],
  ["Subtitles", "At most ~17 characters per second, two lines, 1–7 s on screen.", "subtitles reading speed captions"],
  ["Pacing", "Shots of 1.5–6 s for dialogue, shorter in action; vary rhythm; hold on emotion.", "pacing editing rhythm shot length"],
];

export function baseKnowledge(): Know[] {
  const out: Know[] = [];
  for (const [id, f] of Object.entries(FINDINGS)) out.push({ id: `finding:${id}`, title: f.title, text: `${f.why} Measured: ${f.how} Fix: ${f.fix}`, tags: `${id.replace(/-/g, " ")} ${f.title}` });
  PRINCIPLES.forEach(([title, text, tags], i) => out.push({ id: `rule:${i}`, title, text, tags }));
  return out;
}
/** Split the Code Studio reference into searchable sections. */
export function apiSections(md: string): Know[] {
  const out: Know[] = []; let cur: Know | null = null;
  for (const line of md.split("\n")) {
    const m = line.match(/^#{1,3}\s+(.*)/);
    if (m) { if (cur) out.push(cur); cur = { id: `api:${out.length}`, title: m[1]!, text: "", tags: "api code " + m[1]!.toLowerCase() }; continue; }
    if (cur) cur.text += line + "\n";
  }
  if (cur) out.push(cur);
  // long sections are split by paragraph so an answer stays short
  return out.flatMap((s) => { if (s.text.length < 1800) return [s]; const parts = s.text.split(/\n\n+/); const res: Know[] = []; let buf = ""; for (const p of parts) { if ((buf + p).length > 1400 && buf) { res.push({ ...s, id: `${s.id}.${res.length}`, text: buf }); buf = ""; } buf += p + "\n\n"; } if (buf) res.push({ ...s, id: `${s.id}.${res.length}`, text: buf }); return res; });
}
const STOP = new Set("a an the is are was were be to of in on at for and or with how do does i my me it this that what why when where which can could should would will you your we our from by as into about there their them they he she his her not no yes please make get set use using".split(" "));
export const words = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}_.\s-]/gu, " ").split(/\s+/).map((w) => w.replace(/^[.-]+|[.-]+$/g, "")).filter((w) => w.length > 1 && !STOP.has(w));
/** The best matching pieces of knowledge for a question. */
export function search(kb: Know[], q: string, n = 3): Know[] {
  const qw = words(q); if (!qw.length) return [];
  const df = new Map<string, number>();
  const docs = kb.map((k) => { const ws = new Set(words(`${k.title} ${k.tags} ${k.text}`)); for (const w of ws) df.set(w, (df.get(w) ?? 0) + 1); return ws; });
  const N = kb.length;
  const scored = kb.map((k, i) => { let s = 0; const ws = docs[i]!, title = words(`${k.title} ${k.tags}`); for (const w of qw) { const stem = w.replace(/(ing|ed|es|s)$/, ""); const hit = ws.has(w) || [...ws].some((x) => x.startsWith(stem) && stem.length > 3); if (hit) s += Math.log(1 + N / (df.get(w) ?? 1)) * (title.includes(w) ? 2.2 : 1); } return { k, s }; });
  return scored.filter((x) => x.s > 0).sort((a, b) => b.s - a.s).slice(0, n).map((x) => x.k);
}
