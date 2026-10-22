import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';

const stroke=sampling=>({type:'paint_stroke',objectId:'cel',strokeId:'clone',points:[[.2,.4,1],[.7,.6,1]],brush:{mode:'clone',color:'#FF808080',sizePx:8},cloneSource:{objectId:'owned',sha256:'a'.repeat(64),...(sampling===undefined?{}:{sampling})}});
const edit=op=>({sketchId:'drawing',expectedRevision:2,operationId:'clone_stroke',operations:[op]});

test('clone sampling strictly accepts explicit nearest/bilinear and preserves omitted request bytes',()=>{
 for(const sampling of ['nearest','bilinear']){
  const request=edit(stroke(sampling));assert.deepEqual(contract.nativeStudioParameters('sketch_edit',request),request);
 }
 const old=edit(stroke()),normalized=contract.nativeStudioParameters('sketch_edit',old);assert.deepEqual(normalized,old);
 // Captured by importing exact c01f0f2's contract before production edits; key order is normalized by the schema.
 assert.equal(createHash('sha256').update(JSON.stringify(normalized)).digest('hex'),'c3bacc694b185b41eea5de46b680b4ac23718d2cb65a6a4fb75c10c07e40fe4e');
 assert.equal(Object.hasOwn(contract.nativeStudioParameters('sketch_edit',old).operations[0].cloneSource,'sampling'),false);
 for(const bad of [null,true,false,0,1,{},[],'Bilinear','linear',''])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(stroke(bad))));
 const unknown=stroke('nearest');unknown.cloneSource.unknown=true;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(unknown)));
});
test('sampling stays inside cloneSource and clone mode, preserving affine source mappings',()=>{
 const op=stroke('bilinear');op.cloneSource.mapping=[1,0,0,1,.125,-.25];assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 for(const mode of ['paint','erase']){const invalid=stroke('bilinear');invalid.brush.mode=mode;assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(invalid)));}
 const brush=stroke();brush.brush.sampling='bilinear';assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(brush)));
});
test('every explicit sampler including nearest gates nested ordinary routes without changing old floors',()=>{
 for(const sampling of ['nearest','bilinear'])for(const action of ['sketch_edit','studio_sketch_edit','sketch_live_apply','sketch_correction_propose','sketch_story_create','sketch_story_preview']){
  const parameters={nested:{operations:[stroke(sampling)]}};
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.13'},action,parameters),/4\.10\.14/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.14'},action,parameters));
 }
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},'sketch_edit',edit(stroke())));
});
test('native and MCP advertise the same bounded sampler and ordinary painting capability',()=>{
 const expected={minimumNativeVersion:'4.10.14',operation:'paint_stroke',field:'cloneSource.sampling',choices:['nearest','bilinear'],omittedBehavior:'nearest',explicitNearestRequiresUpgrade:true,sourceMaxAxis:2048,targetMaxPixels:1048576,manualControls:'Sketch Studio > Paint studio > Paint settings > Clone sampling'};
 assert.deepEqual(contract.CLONE_SAMPLING_CAPABILITIES,expected);
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCloneSourceSampling.java',import.meta.url),'utf8');
 const body=source.match(/static JSONObject capabilities\(\)[^{]*\{([\s\S]*?)^\s*\}/m);assert.ok(body,'Native sampler exposes its public metadata');
 const native={};for(const match of body[1].matchAll(/\.put\("([^"]+)",("(?:\\.|[^"\\])*"|true|false|\d+|MAX_TARGET_PIXELS|new JSONArray\(new String\[\]\{[^}]*\}\))\)/g)){
  const value=match[2];native[match[1]]=value==='MAX_TARGET_PIXELS'?Number(source.match(/MAX_TARGET_PIXELS\s*=\s*(\d+)/)[1]):value.startsWith('new JSONArray')?JSON.parse('['+value.match(/\{([^}]*)\}/)[1]+']'):JSON.parse(value);
 }
 assert.deepEqual(native,expected);
 const index=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');assert.match(index,/painting\s*:\s*isV3[^\n]*cloneSampling\s*:\s*CLONE_SAMPLING_CAPABILITIES/);
});
