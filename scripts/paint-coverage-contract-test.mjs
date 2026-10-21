import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as contract from '../src/studio-workbench-contract.js';

const stroke=coverage=>({type:'paint_stroke',objectId:'cel',strokeId:'soft_lid',points:[[.2,.5,1],[.8,.5,1]],brush:{mode:'paint',color:'#FFC0B8B0',sizePx:11,hardness:0,opacity:.58,...(coverage===undefined?{}:{coverage})}});
const edit=op=>({sketchId:'sketch',expectedRevision:2,operationId:'soft',operations:[op]});

test('raster coverage is an explicit strict choice and omission injects no journal defaults',()=>{
 for(const coverage of ['maximum','accumulate'])for(const mode of ['paint','erase','clone']){
  const op=stroke(coverage);op.brush.mode=mode;
  if(mode==='clone')op.cloneSource={objectId:'ref',sha256:'a'.repeat(64)};
  assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(op)),edit(op));
 }
 const old=edit(stroke());assert.deepEqual(contract.nativeStudioParameters('sketch_edit',old),old);
 assert.equal(Object.hasOwn(contract.nativeStudioParameters('sketch_edit',old).operations[0].brush,'coverage'),false);
});
test('explicit coverage is gated on ordinary alias correction live and nested routes only when present',()=>{
 for(const coverage of ['maximum','accumulate'])for(const action of ['sketch_edit','studio_sketch_edit','sketch_live_apply','sketch_correction_propose','sketch_animation_save']){
  const parameters=edit(stroke(coverage));
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.10'},action,parameters),/4\.10\.11/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.11'},action,parameters));
 }
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.1.0'},'sketch_edit',edit(stroke())));
});
test('raster option does not pretend to affect vector fitted pressure brushes',()=>{
 for(const coverage of [null,true,0,{},[],'Maximum','heal'])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(stroke(coverage))));
 const vector={type:'precision_contour',layerId:'layer_1',objectId:'lid',points:[[.2,.3,1],[.3,.4,1]],brush:{preset:'graphite',coverage:'maximum'}};
 assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit(vector)));
});
test('native and MCP expose the same bounded raster semantics without claiming healing',()=>{
 const capabilities=contract.PAINT_COVERAGE_CAPABILITIES;
 assert.ok(capabilities,'Raster maximum-coverage metadata must be available to ordinary ChatGPT');
 assert.equal(capabilities.minimumNativeVersion,'4.10.11');
 assert.deepEqual(capabilities.choices,['maximum','accumulate']);
 assert.equal(capabilities.operation,'paint_stroke');
 assert.equal(capabilities.omittedBehavior,'accumulate');
 assert.equal(capabilities.automaticToneMatching,false);
 const file=new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchPaintCoverageCapabilities.java',import.meta.url);
 assert.ok(fs.existsSync(file),'Shared native coverage metadata must exist');
 const source=fs.readFileSync(file,'utf8'),literal=source.match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 assert.deepEqual(JSON.parse(JSON.parse(literal)),capabilities);
});
