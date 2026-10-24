import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';

const layer=(motionKeyframes=[{timeMs:0,x:0,y:0,scale:1,rotate:0,opacity:0,easing:'ease_out'},{timeMs:1000,x:.2,y:-.1,scaleX:1.4,scaleY:.8,rotate:15,opacity:1}])=>({id:'panel',type:'image',assetId:'source',startMs:200,durationMs:2000,x:.5,y:.5,width:.3,height:.4,rotation:12,opacity:.7,motionKeyframes});
const request=l=>({projectId:'edit',expectedUpdatedAt:7,studioLayers:[l]});
const parse=l=>contract.nativeStudioParameters('set_studio_layers',request(l));

// This request's normalized bytes were recorded from original signed522 before implementation.
const legacyRequest=()=>{const l=layer();delete l.motionKeyframes;return request(l);};

test('image-overlay motion reaches existing layer action and alias without injecting key defaults',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers']){
  const p=request(layer());assert.deepEqual(contract.nativeStudioParameters(action,p),p);
  for(const easing of ['linear','ease_in','ease_out','ease_in_out','hold']){
   const l=layer([{timeMs:0,easing},{timeMs:2000,x:-2,y:2,scale:.1,scaleX:8,scaleY:.1,rotate:-360,opacity:0}]);
   assert.deepEqual(contract.nativeStudioParameters(action,request(l)),request(l));
  }
 }
 const omitted=layer([{timeMs:0},{timeMs:1000,x:.1}]);assert.deepEqual(parse(omitted),request(omitted));
});

test('image keys honor explicit local duration and default2500 without extending track time',()=>{
 const l=layer([{timeMs:0},{timeMs:2000}]);assert.deepEqual(parse(l),request(l));
 assert.throws(()=>parse(layer([{timeMs:2001}])));
 const defaults=layer([{timeMs:2500}]);delete defaults.durationMs;assert.deepEqual(parse(defaults),request(defaults));
 const beyond=layer([{timeMs:2501}]);delete beyond.durationMs;assert.throws(()=>parse(beyond));
 assert.deepEqual(parse(layer([])),request(layer([])),'Explicit empty array clears motion and is not dropped');
});

test('image key fields and scalars reject coercion, anchors, unknowns, malformed arrays and all overflow',()=>{
 assert.doesNotThrow(()=>parse(layer()));
 for(const patch of [{timeMs:'0'},{timeMs:null},{timeMs:.1},{timeMs:-1},{timeMs:Infinity},{timeMs:NaN},{x:'0'},{x:null},{x:NaN},{x:-2.001},{x:2.001},{y:Infinity},{y:true},{scale:0},{scale:.099},{scale:8.001},{scaleX:'1'},{scaleY:null},{scaleX:0},{rotate:360.1},{rotate:-360.1},{rotate:'0'},{opacity:-.1},{opacity:1.1},{opacity:false},{easing:'bezier'},{easing:null},{anchorX:.5},{anchorY:.5},{curve:[0,0,1,1]},{unknown:true}]){
  assert.throws(()=>parse(layer([{timeMs:0,...patch}])));
 }
 for(const motionKeyframes of [null,false,0,'[]',{},[null],[false],[[]],[{}]])assert.throws(()=>parse(layer(motionKeyframes)));
 for(const frames of [[0,0],[100,0],[0,2001]])assert.throws(()=>parse(layer(frames.map(timeMs=>({timeMs})))));
 const valid64=layer(Array.from({length:64},(_,timeMs)=>({timeMs})));assert.deepEqual(parse(valid64),request(valid64));
 assert.throws(()=>parse(layer(Array.from({length:65},(_,timeMs)=>({timeMs})))));
});

test('motion keeps AUDIO rejection and does not add actions or relax existing track count limits',()=>{
 for(const type of ['audio']){
  const l={id:'track',type,assetId:'sound',text:'A title',motionKeyframes:[]};assert.throws(()=>parse(l));
 }
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,76);assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);
 for(const action of ['studio_image_motion','image_overlay_motion','set_image_motion'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
 const images=Array.from({length:8},(_,i)=>({...layer([]),id:'panel_'+i}));assert.doesNotThrow(()=>contract.nativeStudioParameters('set_studio_layers',{...request(layer([])),studioLayers:images}));
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...request(layer([])),studioLayers:[...images,{...layer([]),id:'panel_8'}]}));
});

test('every explicit motion field including empty clear requires4.10.20 on existing layer pathways',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers'])for(const motionKeyframes of [[],[{timeMs:0}],null,false]){
  const p=request(layer(motionKeyframes));
  for(const appVersion of [undefined,'garbage','3.9.0','4.10.19'])assert.throws(()=>contract.requireStudioVersion({appVersion},action,p),/4\.10\.20/);
  for(const appVersion of ['4.10.20','4.10.21','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,p));
 }
 const legacy={projectId:'edit',expectedUpdatedAt:7,layers:[layer([])]};assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.19'},'set_studio_layers',legacy),/4\.10\.20/);
});

test('omitted image motion keeps old normalized bytes, feature floors and opaque archive floors',()=>{
 const p=legacyRequest();
 for(const action of ['set_studio_layers','studio_set_studio_layers']){
  const normalized=contract.nativeStudioParameters(action,p);assert.equal(Object.hasOwn(normalized.studioLayers[0],'motionKeyframes'),false);
  assert.equal(createHash('sha256').update(JSON.stringify(normalized)).digest('hex'),'9483d801a80acb487712badf08055bee66327f1501b4b34beaafe61a7949d445');
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},action,p));
 }
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{}));
 // Only an actual supported structured layer field gates: unrelated metadata is not reinterpreted.
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},'set_studio_layers',{...p,unrelated:{motionKeyframes:[]}}));
});

test('Node and native share exact truthful editor registry with old feature floors retained',()=>{
 const cap=contract.EDITOR_EDITING_CAPABILITIES;assert.ok(cap,'Truthful native editing registry has matching Node export');
 assert.equal(cap.registryMinimumNativeVersion,'4.10.23');assert.equal(cap.imageOverlayMotion.minimumNativeVersion,'4.10.20');
 assert.equal(cap.precisionEdits.minimumNativeVersion,'3.9.0');assert.equal(cap.layerTracks.imageMinimumNativeVersion,'3.9.0');
 assert.equal(cap.precisionEdits.audioFadesMinimumNativeVersion,'4.2.0');assert.equal(cap.precisionEdits.stereoMinimumNativeVersion,'4.3.0');
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/StudioEditingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal,'Native registry is one auditable JSON literal');assert.deepEqual(JSON.parse(JSON.parse(literal[1])),cap);
 assert.deepEqual(cap.renderedTransitions,['none','cut']);assert.deepEqual(cap.renderedEffectPresets,['none','gaussian_blur','dream']);
 for(const name of ['cross_dissolve','speed_ramp','reverse','freeze','motion_blur','video_overlay_lanes','beat_detection','automatic_word_highlighting'])assert.ok(cap.unavailable.includes(name));
 assert.equal(cap.sourceAnalysis.exactFrameTimes,false);assert.equal(cap.sourceAnalysis.beatDetection,false);
});

test('ordinary ChatGPT gets executable catalogue lists and separately labeled non-executable planning names',async()=>{
 const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 const start=source.indexOf('  s.registerTool("app_catalog"'),end=source.indexOf('  s.registerTool("app_state"',start);assert.ok(start>=0&&end>start);
 let handler;const s={registerTool:(name,options,fn)=>{assert.equal(name,'app_catalog');handler=fn;}};
 Function('s','out','isV3','EDITOR_EDITING_CAPABILITIES',source.slice(start,end))(s,value=>value,true,contract.EDITOR_EDITING_CAPABILITIES);
 const catalog=await handler();assert.deepEqual(catalog.transitions,['none','cut']);assert.deepEqual(catalog.effects,['none','gaussian_blur','dream']);assert.deepEqual(catalog.textAnimations,['none','motion_keyframes']);
 assert.equal(catalog.legacyPlanningCatalog.executable,false);assert.ok(catalog.legacyPlanningCatalog.transitions.includes('film_burn'));
 assert.ok(catalog.legacyPlanningCatalog.aiTools.includes('beat_sync'));assert.ok(!catalog.aiTools.includes('beat_sync'));assert.deepEqual(catalog.studioEditing,contract.EDITOR_EDITING_CAPABILITIES);
 assert.match(source,/studioEditing\s*:\s*isV3[^\n]*EDITOR_EDITING_CAPABILITIES/);
 assert.match(source,/motionKeyframes/);assert.match(source,/layer-local milliseconds/);assert.match(source,/native4\.10\.20/i);assert.match(source,/not.*(?:cross-dissolve|speed ramp|video overlay)/i);
});
