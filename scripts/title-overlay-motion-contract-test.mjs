import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';

const title=(motionKeyframes=[{timeMs:0,opacity:0,easing:'ease_out'},{timeMs:1000,x:.2,y:-.1,scaleX:1.4,scaleY:.8,rotate:15,opacity:1}])=>({id:'subtitle',type:'title',text:'Why not you?',startMs:1250,durationMs:1000,position:2,fontSize:42,fontFamily:'monospace',fontStyle:'bold_italic',textAlign:'center',color:0xffeeddcc,backgroundColor:0xff112233,motionKeyframes});
const request=layer=>({projectId:'edit',expectedUpdatedAt:7,studioLayers:[layer]});
const parse=layer=>contract.nativeStudioParameters('set_studio_layers',request(layer));
const image=()=>({id:'panel',type:'image',assetId:'owned-image',durationMs:1000,motionKeyframes:[]});

test('TITLE motion uses the existing action and alias without assets or injected key defaults',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers']){
  const p=request(title());assert.deepEqual(contract.nativeStudioParameters(action,p),p);
  assert.equal(Object.hasOwn(contract.nativeStudioParameters(action,p).studioLayers[0],'assetId'),false);
  for(const easing of ['linear','ease_in','ease_out','ease_in_out','hold']){
   const p=request(title([{timeMs:0,easing},{timeMs:1000,x:-2,y:2,scale:.1,scaleX:8,scaleY:.1,rotate:-360,opacity:0}]));
   assert.deepEqual(contract.nativeStudioParameters(action,p),p);
  }
 }
 assert.deepEqual(parse(title([{timeMs:0},{timeMs:500,x:.1},{timeMs:1000}])),request(title([{timeMs:0},{timeMs:500,x:.1},{timeMs:1000}])));
 assert.deepEqual(parse(title([])),request(title([])));
});

test('every explicit TITLE field including clear requires4.10.23 on both real lane pathways',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers'])for(const key of ['studioLayers','layers'])for(const motionKeyframes of [[],[{timeMs:0}],null,false]){
  const p={projectId:'edit',expectedUpdatedAt:7,[key]:[title(motionKeyframes)]};
  for(const appVersion of [undefined,'garbage','3.9.0','4.10.19','4.10.20','4.10.22'])
   assert.throws(()=>contract.requireStudioVersion({appVersion},action,p),/4\.10\.23/);
  for(const appVersion of ['4.10.23','4.10.24','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,p));
 }
});

test('strict TITLE keys reject coercion unknown channels finite overflows and malformed arrays',()=>{
 assert.doesNotThrow(()=>parse(title()));
 for(const patch of [{timeMs:'0'},{timeMs:null},{timeMs:.1},{timeMs:-1},{timeMs:Infinity},{timeMs:NaN},{x:'0'},{x:null},{x:NaN},{x:-2.001},{x:2.001},{y:Infinity},{y:true},{scale:0},{scale:.099},{scale:8.001},{scaleX:'1'},{scaleY:null},{scaleX:0},{rotate:360.1},{rotate:-360.1},{rotate:'0'},{opacity:-.1},{opacity:1.1},{opacity:false},{easing:'bezier'},{easing:null},{anchorX:.5},{anchorY:.5},{width:.5},{text:'different'},{assetId:'image'},{unknown:true}])
  assert.throws(()=>parse(title([{timeMs:0,...patch}])));
 for(const motionKeyframes of [null,false,0,'[]',{},[null],[false],[[]],[{}]])assert.throws(()=>parse(title(motionKeyframes)));
 for(const type of ['audio','unknown'])assert.throws(()=>parse({...title([]),type,assetId:'sound'}));
});

test('TITLE keys retain exact local duration default2500 ordering and64-key limit',()=>{
 const valid64=title(Array.from({length:64},(_,timeMs)=>({timeMs})));assert.deepEqual(parse(valid64),request(valid64));
 assert.throws(()=>parse(title(Array.from({length:65},(_,timeMs)=>({timeMs})))));
 for(const times of [[0,0],[100,0],[0,1001]])assert.throws(()=>parse(title(times.map(timeMs=>({timeMs})))));
 const implicit=title([{timeMs:2500}]);delete implicit.durationMs;assert.deepEqual(parse(implicit),request(implicit));
 const beyond=title([{timeMs:2501}]);delete beyond.durationMs;assert.throws(()=>parse(beyond));
 const offset=title([{timeMs:1000}]);offset.startMs=15000;assert.deepEqual(parse(offset),request(offset));
});

test('normalization detaches TITLE recipes and preserves original text typography and timing',()=>{
 const p=request(title()),parsed=contract.nativeStudioParameters('set_studio_layers',p);
 p.studioLayers[0].motionKeyframes[1].x=1;p.studioLayers[0].text='Changed later';
 assert.equal(parsed.studioLayers[0].motionKeyframes[1].x,.2);assert.equal(parsed.studioLayers[0].text,'Why not you?');
 assert.equal(parsed.studioLayers[0].fontSize,42);assert.equal(parsed.studioLayers[0].durationMs,1000);assert.equal(parsed.studioLayers[0].startMs,1250);
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...p,studioLayers:[title([]),title([])]}));
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...p,studioLayers:Array.from({length:33},(_,i)=>({...title([]),id:'t'+i}))}));
});

test('IMAGE4.10.20 AUDIO rejection legacy TITLE bytes and opaque archive floors are preserved',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers']){
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.19'},action,request(image())),/4\.10\.20/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.20'},action,request(image())));
  const p=request(title());delete p.studioLayers[0].motionKeyframes;
  const normalized=contract.nativeStudioParameters(action,p);assert.equal(Object.hasOwn(normalized.studioLayers[0],'motionKeyframes'),false);
  assert.equal(createHash('sha256').update(JSON.stringify(normalized)).digest('hex'),'31b56428746f5448788c1c2df30c54ce3d031de8890d92b627359df97f499d18');
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.3.0'},action,p));
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.3.0'},action,{...p,unrelated:{type:'title',motionKeyframes:[]}}));
 }
 const plain={projectId:'edit',expectedUpdatedAt:7,studioLayers:[{type:'title',text:'Older static title'}]};
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},'set_studio_layers',plain));
 assert.throws(()=>parse({...image(),type:'audio',motionKeyframes:[]}));
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{opaque:{type:'title',motionKeyframes:[]}}));
});

test('native and Node capabilities advertise exact whole-title scope and preserve the action inventory',()=>{
 const cap=contract.EDITOR_EDITING_CAPABILITIES;
 assert.equal(cap.registryMinimumNativeVersion,'4.10.25');assert.equal(cap.titleOverlayMotion.minimumNativeVersion,'4.10.23');assert.equal(cap.imageOverlayMotion.minimumNativeVersion,'4.10.20');
 assert.equal(cap.titleOverlayMotion.type,'title');assert.equal(cap.titleOverlayMotion.field,'motionKeyframes');assert.equal(cap.titleOverlayMotion.sourceAssetRequired,false);
 assert.equal(cap.titleOverlayMotion.maximumKeys,64);assert.equal(cap.titleOverlayMotion.clock,'layer-local milliseconds');assert.equal(cap.titleOverlayMotion.pivot,'fixed center');
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/StudioEditingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal);assert.deepEqual(JSON.parse(JSON.parse(literal[1])),cap);
 assert.ok(cap.unavailable.includes('automatic_word_highlighting'));assert.ok(cap.unavailable.includes('cross_dissolve'));assert.ok(cap.unavailable.includes('speed_ramp'));assert.ok(cap.unavailable.includes('video_overlay_source_audio'));
 assert.match(cap.titleOverlayMotion.limitations,/no automatic transcription, word alignment, per-word highlighting/);
 assert.equal(createHash('sha256').update([...contract.STUDIO_NATIVE_ACTIONS].sort().join('\n')).digest('hex'),'42e3cf3dac926f3c60760d13a15094297c0689b6073c6c837d76dff68b2f9f7f');
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);
 for(const action of ['studio_title_motion','title_overlay_motion','set_title_motion'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
});

test('ordinary ChatGPT catalogue and existing tool explain separate TITLE and IMAGE floors honestly',async()=>{
 const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 const start=source.indexOf('  s.registerTool("app_catalog"'),end=source.indexOf('  s.registerTool("app_state"',start);assert.ok(start>=0&&end>start);
 let handler;const s={registerTool:(name,options,fn)=>{assert.equal(name,'app_catalog');handler=fn;}};
 Function('s','out','isV3','EDITOR_EDITING_CAPABILITIES',source.slice(start,end))(s,value=>value,true,contract.EDITOR_EDITING_CAPABILITIES);
 const catalog=await handler();assert.equal(catalog.studioEditing.titleOverlayMotion.minimumNativeVersion,'4.10.23');assert.deepEqual(catalog.textAnimations,['none','motion_keyframes']);
 assert.equal(catalog.legacyPlanningCatalog.executable,false);assert.match(source,/native4\.10\.23/i);assert.match(source,/native4\.10\.20/i);
 assert.match(source,/whole.title/i);assert.match(source,/not.*(?:automatic transcription|word alignment|per-word)/i);
});
