import test from 'node:test';
import assert from 'node:assert/strict';
import * as contract from '../src/studio-workbench-contract.js';
const pen={type:'precision_contour',layerId:'refinement',objectId:'hair_mark',points:[[.1,.2,.1],[.3,.15,.9],[.4,.4,.15]],color:'#FF332B32',width:.004,tolerancePx:.25,tension:.7};
const brush={preset:'graphite',opacity:.7,hardness:.65,pressure:true,angle:0};
const edit=op=>({sketchId:'owned',expectedRevision:4,operationId:'draw',operations:[op]});
test('fitted pressure brush reaches ordinary MCP and aliases with its own version floor',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){const args=edit({...pen,brush});assert.deepEqual(contract.nativeStudioParameters(action,args),args);assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.9'},action,args),/4\.10\.1/);assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.1'},action,args));assert.deepEqual(contract.nativeStudioParameters(action,edit(pen)),edit(pen));assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.1'},action,edit(pen)));}
});
test('fitted brush rejects filled or closed substitutes and malformed brush parameters',()=>{
 for(const patch of [{closed:true},{fill:true},{pressureOutline:true},{brush:null},{brush:'graphite'},{brush:{...brush,opacity:'0.7'}},{brush:{...brush,sourceImage:'guide'}}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({...pen,brush,...patch})));
 for(const patch of [{closed:false},{fill:false},{pressureOutline:false}])assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit({...pen,brush,...patch})),edit({...pen,brush,...patch}));
});
test('native and Node advertise the same source-free, bounded brush fitting',async()=>{
 const fs=await import('node:fs/promises');const source=await fs.readFile(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayeredDrawingCapabilities.java',import.meta.url),'utf8');const native=JSON.parse(JSON.parse('"'+source.match(/new JSONObject\("(.*)"\)/)[1]+'"'));assert.deepEqual(contract.LAYERED_DRAWING_CAPABILITIES,native);assert.equal(native.precisionBrush.minimumNativeVersion,'4.10.1');assert.equal(native.precisionBrush.maximumStoredPoints,8192);assert.equal(native.precisionBrush.sourcePixels,false);
});
