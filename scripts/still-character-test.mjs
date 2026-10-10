import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import CHARACTER from '../src/studio-character.js';
import RUNTIME from '../src/studio-runtime.js';

function characterRuntime() {
  const context = {window:{},console};
  vm.runInNewContext(CHARACTER,context,{timeout:1000});
  assert.equal(typeof context.window.VideoStudioCharacter.animateImage,'function');
  return context.window.VideoStudioCharacter;
}

test('Character animation is an executable browser script with a bounded render API',()=>{
  assert.doesNotThrow(()=>new vm.Script(CHARACTER));
  assert.doesNotThrow(()=>new vm.Script(RUNTIME));
  assert.equal(characterRuntime().neural,false);
});

test('Anime duel provides independent deformation regions for both actors and a cape',()=>{
  const rig=characterRuntime().selectRig({prompt:'Goku and Saitama anime battle'});
  assert.equal(rig.preset,'duel');
  assert.equal(rig.regions.length,8);
  assert.ok(rig.regions[1].dx>.05,'forward fist drives into clash');
  assert.ok(rig.regions[5].dy<0,'cape has an independent motion track');
  assert.ok(rig.regions.every(r=>r.x>=0&&r.x<=1&&r.rx>=.025&&r.ry>=.025));
});

test('Portrait preset and explicit motion maps work without deciding by user prompt alone',()=>{
  const runtime=characterRuntime();
  const portrait=runtime.selectRig({prompt:'quiet rainy portrait'});
  assert.equal(portrait.preset,'portrait');
  assert.ok(portrait.regions.length>=5);
  const custom=runtime.selectRig({motionPreset:'duel',regions:[{x:.4,y:.3,rx:.08,ry:.11,dx:.05,dy:.02}]});
  assert.equal(custom.preset,'custom');
  assert.equal(custom.regions.length,1);
  assert.equal(custom.regions[0].frequency,3);
});

test('Malformed or excessive rig regions are rejected instead of breaking WebGL uniforms',()=>{
  const runtime=characterRuntime();
  const region={x:.5,y:.5,rx:.1,ry:.1,dx:0,dy:0};
  assert.throws(()=>runtime.selectRig({regions:Array.from({length:9},()=>region)}),/At most eight/);
  assert.throws(()=>runtime.selectRig({regions:[{...region,x:'bad'}]}),/Invalid motion region x/);
  const clamped=runtime.selectRig({regions:[{...region,x:100,dx:3}]}).regions[0];
  assert.equal(clamped.x,1);
  assert.equal(clamped.dx,.10);
});

test('Character mode and MCP parameters are exposed without overwriting existing generation modes',async()=>{
  const worker=await readFile(new URL('../src/index.js',import.meta.url),'utf8');
  const personal=await readFile(new URL('../src/personal-ui.js',import.meta.url),'utf8');
  assert.match(worker,/studio-character\.js/);
  assert.match(worker,/mode:z\.enum\(\["prompt_scene","image_motion","character_action"/);
  assert.match(worker,/motionPreset:z\.enum\(\["duel","portrait","custom"\]\)/);
  assert.match(worker,/regions:z\.array\(z\.object/);
  assert.match(RUNTIME,/window\.VideoStudioCharacter/);
  assert.match(RUNTIME,/actionIntent=mode==="character_action"/);
  assert.match(RUNTIME,/commandId:options\.commandId/);
  assert.match(RUNTIME,/if\(command\.status==="queued"&&!\(await handleRuntimeCommand\(command\)\)\)break;/);
  assert.match(worker,/c\.status==="queued"\|\|c\.seq>Number\(after\|\|0\)/);
  assert.match(personal,/\/\?generation=character_action/);
});

test('No hidden-gallery or external image upload is introduced by the render engine',()=>{
  assert.ok(!CHARACTER.includes('navigator.mediaDevices'));
  assert.ok(!CHARACTER.includes('fetch('));
  assert.ok(!CHARACTER.includes('getUserMedia'));
  assert.ok(!CHARACTER.includes('FileReader'));
  assert.match(CHARACTER,/loadImageAsset\(project,asset\)/);
});
