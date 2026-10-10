import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import {readFile} from "node:fs/promises";
import TIMELINE from "../src/studio-action-timeline.js";
import CHARACTER from "../src/studio-character.js";

const runtime=()=>{
  const context={window:{},console};
  vm.runInNewContext(TIMELINE+"\n"+CHARACTER,context,{timeout:1000});
  return context.window;
};

test("both browser animation modules compile and initialize",()=>{
  assert.doesNotThrow(()=>new vm.Script(TIMELINE));
  assert.doesNotThrow(()=>new vm.Script(CHARACTER));
  assert.equal(runtime().VideoStudioCharacter.version,"2.0.0");
  assert.equal(runtime().VideoStudioActionTimeline.version,"2.0.0");
});

test("duel uses independent character pose tracks with measurable strike and recoil",()=>{
  const {VideoStudioActionTimeline:director}=runtime();
  const plan=director.compile({motionPreset:"duel"});
  assert.equal(plan.count,8);
  const anticipation=director.sample(plan,.1,.6,1);
  const strike=director.sample(plan,.51,3.06,1);
  const recover=director.sample(plan,.90,5.4,1);
  assert.ok(strike.entries[1].dx>anticipation.entries[1].dx+.065,"goku fist lunges forward through real pose keys");
  assert.ok(strike.entries[5].dx<0,"saitama upper body recoils backward");
  assert.ok(Math.abs(strike.entries[6].dx)>Math.abs(anticipation.entries[6].dx),"cape has independent following motion");
  assert.equal(strike.phase,"strike");
  assert.equal(recover.phase,"recovery");
  assert.equal(plan.phases.length,6);
});

test("portrait plan is not duel and animation is deterministic",()=>{
  const {VideoStudioActionTimeline:director}=runtime();
  const plan=director.compile({motionPreset:"portrait"});
  assert.equal(plan.preset,"portrait");
  const a=director.sample(plan,.35,1.2,.8);
  const b=director.sample(plan,.35,1.2,.8);
  assert.deepEqual(JSON.parse(JSON.stringify(a)),JSON.parse(JSON.stringify(b)));
  assert.ok(a.entries.every(e=>Math.abs(e.rotation)<.5));
});

test("custom pose keyframes reject out-of-order, invalid counts and unsafe bounds",()=>{
  const {VideoStudioActionTimeline:director}=runtime();
  const good=[[0,0,0,0],[.5,.04,0,.12],[1,0,0,0]];
  const plan=director.compile({poseTracks:[good]});
  assert.equal(plan.preset,"custom");
  assert.equal(plan.count,1);
  const half=director.sample(plan,.5,2,1);
  assert.ok(half.entries[0].dx>.039);
  assert.throws(()=>director.compile({poseTracks:[[[.5,0,0,0],[1,0,0,0]]]}),/begin at 0/);
  assert.throws(()=>director.compile({poseTracks:[[[0,0,0,0],[.5,1,0,0],[1,0,0,0]]]}),/safe timeline bounds/);
  assert.throws(()=>director.compile({poseTracks:Array.from({length:9},()=>good)}),/1–8 tracks/);
  assert.throws(()=>director.compile({poseTracks:[[[0,0,0,0],[0,.02,0,0],[1,0,0,0]]]}),/increasing times/);
});

test("rig uses true keyframes when Timeline is loaded, preserves explicit image selection",()=>{
  const {VideoStudioCharacter:animation}=runtime();
  const rig=animation.selectRig({motionPreset:"duel"});
  assert.equal(rig.plan.count,8);
  assert.equal(rig.regions.length,8);
  assert.match(CHARACTER,/uPose\[8\]/);
  assert.match(CHARACTER,/poseValues/);
  assert.match(CHARACTER,/options\.assetId/);
  assert.match(CHARACTER,/poseTracks:options\.poseTracks/);
  assert.match(CHARACTER,/studio-web-puppet-keyframe-action-v2/);
});

test("site provides separate still-animation API and forbids confusing portal with character mode",async()=>{
  const worker=await readFile(new URL("../src/index.js",import.meta.url),"utf8");
  const runtimeSource=await readFile(new URL("../src/studio-runtime.js",import.meta.url),"utf8");
  const cinematic=await readFile(new URL("../src/studio-cinematic.js",import.meta.url),"utf8");
  assert.match(worker,/registerTool\("animate_still_character"/);
  assert.match(worker,/registerTool\("studio_executor_status"/);
  assert.match(worker,/\/studio-action-timeline\.js/);
  assert.match(worker,/poseTracks:z\.array/);
  assert.match(runtimeSource,/vsStillActionRoute/);
  assert.match(runtimeSource,/character_action/);
  assert.match(runtimeSource,/keyframed:!!output\.keyframed/);
  assert.match(cinematic,/segmentProgress=pos-idx;/);
  assert.match(cinematic,/drawWorld\(primary,1-mix,sceneIndex,segmentProgress\)/);
  assert.ok(!cinematic.includes("drawWorld(primary,1-mix,sceneIndex,local)"));
});
