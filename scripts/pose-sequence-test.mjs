import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import POSE from '../src/studio-pose-sequence.js';

function engine(){
  const context={window:{},console};
  vm.runInNewContext(POSE,context,{timeout:1000});
  return context.window.VideoStudioPoseSequence;
}

test('new pose-to-pose engine loads as real browser JavaScript',()=>{
  assert.doesNotThrow(()=>new vm.Script(POSE));
  const r=engine();
  assert.equal(r.version,'1.0.0');
  assert.equal(r.maxAnchors,12);
  assert.equal(typeof r.render,'function');
});

test('pose mode refuses a single still, repeated IDs and missing project assets',()=>{
  const r=engine();
  const project={assets:[{id:'a',kind:'image'},{id:'b',kind:'image'},{id:'c',kind:'video'}]};
  assert.throws(()=>r.verifyAnchors(['a'],project),/2–12/);
  assert.throws(()=>r.verifyAnchors(['a','a'],project),/Repeated/);
  assert.throws(()=>r.verifyAnchors(['a','c'],project),/every keyframe/i);
  assert.deepEqual(Array.from(r.verifyAnchors(['a','b'],project),a=>a.id),['a','b']);
});

test('pose timeline has bounded increasing keyframe times with default distribution',()=>{
  const r=engine();
  const a=r.validateTimes(undefined,4);
  assert.equal(a[0],0);assert.equal(a[3],1);
  assert.ok(Math.abs(a[2]-.6666667)<.001);
  assert.throws(()=>r.validateTimes([0,.8,.7,1],4),/strictly increasing/);
  assert.throws(()=>r.validateTimes([0,.001,1],3),/strictly increasing/);
  assert.throws(()=>r.validateTimes([0,2],2),/normalized/);
  assert.throws(()=>r.validateTimes([0,.5],3),/match/);
});

test('brightness flow estimates distinct local object displacement, not a single camera zoom',()=>{
  const r=engine();const W=96,H=160;
  const make=(shift)=>{
    const a=new Uint8Array(W*H);
    for(let y=0;y<H;y++)for(let x=0;x<W;x++){
      let k=(x*7+y*5)%12+12;
      if(Math.abs(x-(43+shift))<15&&Math.abs(y-72)<19)k=70+Math.floor((x+shift)%12)*10;
      a[y*W+x]=k;
    }
    return a;
  };
  const a=make(0),b=make(6),flow=r.estimateFlow(a,b);
  assert.equal(flow.packed.length,12*20*4);
  assert.ok(r.difference(a,b)>12);
  assert.ok(flow.ratio>0.02,'motion field should contain moving patches');
  const xs=[];
  for(let y=6;y<13;y++)for(let x=3;x<8;x++){
    const i=(y*12+x)*4;
    xs.push((flow.packed[i]/255-.5)*2);
  }
  assert.ok(xs.some(dx=>dx>0.08),'center subject patch should exhibit real rightward movement');
});

test('site wires render provider, typed MCP anchor lists and truthful capability',async()=>{
  const [worker,studio]=await Promise.all([
    readFile(new URL('../src/index.js',import.meta.url),'utf8'),
    readFile(new URL('../src/studio-runtime.js',import.meta.url),'utf8')
  ]);
  assert.match(worker,/registerTool\("animate_pose_sequence"/);
  assert.match(worker,/mode:z\.enum\(\["prompt_scene","image_motion","character_action","pose_sequence"/);
  assert.match(worker,/studio-pose-sequence\.js/);
  assert.match(studio,/VideoStudioPoseSequence/);
  assert.match(studio,/engine\.render\(project/);
  assert.match(studio,/vsPoseAnchors/);
  assert.ok(!POSE.includes('fetch('),'core GPU compositor must never fetch user media remotely');
});
