import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';

// Execute production functions, including the real command dispatcher, without an SDK.
const html=fs.readFileSync(new URL('../src/app.html',import.meta.url),'utf8');
const between=(start,end)=>html.slice(html.indexOf(start),html.indexOf(end,html.indexOf(start)));
function fixture({kind='video',duration=12,saveFails=false}={}){
  const project={id:'test-project',assets:[{id:'owned-source',kind,duration}],
    timeline:[{clipId:'original-clip',assetId:'owned-source',inPoint:0,outPoint:2,speed:1}],
    settings:{speed:1,mute:false,aspect:'9:16',quality:'720p',transition:'none'}};
  let saved=structuredClone(project),receipt,saves=0;
  const context={crypto:webcrypto,Number,Math,String,Boolean,Error,Array,JSON,structuredClone,
    app:{project,projects:[project]},
    syncProject:async patch=>{
      if(saveFails)throw new Error('Persistence unavailable');
      saves++;saved={...saved,...structuredClone(patch)};
      context.app.project=structuredClone(saved);
    },
    renderProject(){},previewSelected:async()=>{},changeSetting:async(k,v)=>{
      context.app.project.settings[k]=v;
      await context.syncProject({settings:context.app.project.settings});
    },addLog(){},completeCommand:async(c,result,status)=>{receipt={result,status};}};
  vm.createContext(context);
  vm.runInContext(between('  const clamp = (v, min, max)', '  function drawFrame(')+
    between('  async function applyCommand(', '  async function pollCommands('),context);
  return {context,get saved(){return saved;},get receipt(){return receipt;},get saves(){return saves;},
    async run(action,parameters){await context.applyCommand({id:'test-command',projectId:project.id,action,parameters});return receipt;}};
}
const clip=(extra={})=>({assetId:'owned-source',clipId:'cut-a',start:1,end:5,...extra});
const rich=()=>clip({speed:.25,volume:0,audioFadeIn:.7,audioFadeOut:1.2,title:'A precise cut',
  titleStyle:{position:'top',size:1.3,background:true},zoomStart:1.15,zoomEnd:1.8,
  panXStart:-.6,panXEnd:.4,panYStart:.2,panYEnd:-.4,rotate:8,
  brightness:1.2,contrast:1.4,saturation:.8});

test('autonomous and replacement commands retain the exact same editable settings',async()=>{
  const direct=fixture(),automatic=fixture();
  await direct.run('replace_timeline',{clips:[rich()]});
  await automatic.run('autonomous_request',{clips:[rich()],render:false});
  assert.equal(automatic.receipt.result.ok,true);
  assert.deepEqual(automatic.saved.timeline,direct.saved.timeline);
  assert.equal(automatic.saved.timeline[0].volume,0);
  assert.equal(automatic.saved.timeline[0].speed,.25);
});
test('4x speed survives autonomous application and later color changes',async()=>{
  const f=fixture();await f.run('autonomous_request',{clips:[rich(),clip({clipId:'cut-b',speed:4})]});
  assert.equal(f.saved.timeline[1].speed,4);
  await f.run('set_clip_effects',{index:0,effects:{brightness:1.5}});
  assert.equal(f.saved.timeline[0].brightness,1.5);
  assert.equal(f.saved.timeline[0].volume,0);
  assert.equal(f.saved.timeline[0].zoomEnd,1.8);
});
test('speed command uses the same 0.25–4 range and preserves other settings',async()=>{
  const f=fixture();await f.run('replace_timeline',{clips:[rich()]});
  await f.run('set_clip_speed',{index:0,speed:4});assert.equal(f.saved.timeline[0].speed,4);
  assert.equal(f.saved.timeline[0].panXEnd,.4);
  await f.run('set_clip_speed',{index:0,speed:.25});assert.equal(f.saved.timeline[0].speed,.25);
});
for(const action of ['replace_timeline','autonomous_request']){
  test(action+' validates all clips before changing the current timeline',async()=>{
    const f=fixture(),before=structuredClone(f.context.app.project.timeline);
    await f.run(action,{clips:[rich(),clip({clipId:'bad',assetId:'missing'})]});
    assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
    assert.deepEqual(f.context.app.project.timeline,before);
  });
  test(action+' refuses oversized plans instead of silently dropping cuts',async()=>{
    const f=fixture();await f.run(action,{clips:Array.from({length:81},(_,n)=>clip({clipId:'cut-'+n}))});
    assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
  });
  test(action+' supports all 80 requested cuts',async()=>{
    const f=fixture();await f.run(action,{clips:Array.from({length:80},(_,n)=>clip({clipId:'cut-'+n}))});
    assert.equal(f.receipt.result.ok,true);assert.equal(f.saved.timeline.length,80);
  });
  test(action+' rejects duplicate stable clip identifiers',async()=>{
    const f=fixture();await f.run(action,{clips:[clip(),clip()]});
    assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
  });
  test(action+' keeps prior in-memory and durable timelines if persistence fails',async()=>{
    const f=fixture({saveFails:true}),before=structuredClone(f.context.app.project.timeline);
    await f.run(action,{clips:[rich()]});assert.equal(f.receipt.result.ok,false);
    assert.deepEqual(f.context.app.project.timeline,before);assert.deepEqual(f.saved.timeline,before);
  });
}
for(const value of [NaN,Infinity,'fast',0])test('invalid speed '+String(value)+' is rejected before save',async()=>{
  const f=fixture();await f.run('autonomous_request',{clips:[clip({speed:value})]});
  assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
});
test('image holds can exceed the default three-second duration',async()=>{
  const f=fixture({kind:'image',duration:3});await f.run('replace_timeline',{clips:[clip({start:0,end:8})]});
  assert.equal(f.receipt.result.ok,true);assert.equal(f.saved.timeline[0].outPoint,8);
});
test('a video trim beyond its imported source is rejected',async()=>{
  const f=fixture();await f.run('replace_timeline',{clips:[clip({end:13})]});
  assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
});
test('an invalid explicit autonomous setting does not partially commit a timeline',async()=>{
  const f=fixture();await f.run('autonomous_request',{clips:[rich()],mute:'false'});
  assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
});
test('stable clip IDs address the intended cut independently of its position',async()=>{
  const f=fixture();await f.run('replace_timeline',{clips:[clip(),clip({clipId:'cut-b',speed:2})]});
  await f.run('set_clip_speed',{clipId:'cut-b',speed:4});
  assert.equal(f.saved.timeline[0].speed,1);assert.equal(f.saved.timeline[1].speed,4);
  const before=structuredClone(f.saved.timeline);
  await f.run('set_clip_speed',{index:100,speed:1});assert.equal(f.receipt.result.ok,false);
  assert.deepEqual(f.saved.timeline,before);
});
test('unsupported browser effect settings cannot produce a false success receipt',async()=>{
  const f=fixture();await f.run('set_clip_effects',{index:0,effects:{preset:'unimplemented'}});
  assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
});
test('non-finite visual parameters cannot be persisted',async()=>{
  const f=fixture();await f.run('set_clip_effects',{index:0,effects:{brightness:Infinity}});
  assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
});
test('a short valid cut can reference media longer than 24 hours',async()=>{
  const f=fixture({duration:90000});await f.run('replace_timeline',{clips:[clip()]});
  assert.equal(f.receipt.result.ok,true);assert.equal(f.saved.timeline[0].outPoint,5);
});
test('native effects and unimplemented keyframes cannot be silently stripped by the browser fallback',async()=>{
  for(const extra of [{effects:{brightness:.2}},{keyframes:[{timeMs:0,x:0}]}]){
    const f=fixture();await f.run('autonomous_request',{clips:[clip(extra)]});
    assert.equal(f.receipt.result.ok,false);assert.equal(f.saves,0);
  }
});
