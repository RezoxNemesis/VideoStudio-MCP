import test from 'node:test';
import assert from 'node:assert/strict';
import * as contract from '../src/studio-workbench-contract.js';
const fill={type:'paint_fill',objectId:'face_paint',strokeId:'face_base',contourObjectId:'face_outline',expectedContourSha256:'a'.repeat(64),color:'#FFFFE8CC'};
const edit=op=>({sketchId:'owned',expectedRevision:5,operationId:'fill_face',operations:[op]});
test('explicit own-contour placement survives ordinary and alias MCP without injected defaults',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit'])for(const placement of ['under_contour','layer_back','preserve']){
  const args=edit({...fill,placement});assert.deepEqual(contract.nativeStudioParameters(action,args),args);
  assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.9'},action,args),/4\.10\.1/);
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.1'},action,args));
 }
 assert.deepEqual(contract.nativeStudioParameters('sketch_edit',edit(fill)),edit(fill));
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.9.4'},'sketch_edit',edit(fill)));
 assert.throws(()=>contract.requireStudioVersion({appVersion:'4.9.3'},'sketch_edit',edit(fill)),/4\.9\.4/);
});
test('placement is strict and does not open copied-pixel or unchecked-order shortcuts',()=>{
 for(const placement of [null,0,true,'front','',{},['under_contour']])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({...fill,placement})));
 for(const patch of [{index:0},{afterObjectId:'guide'},{sourceImage:'guide'}])assert.throws(()=>contract.nativeStudioParameters('sketch_edit',edit({...fill,placement:'under_contour',...patch})));
});
test('native and Node capabilities describe the same bounded fill placement',async()=>{
 const fs=await import('node:fs/promises');const source=await fs.readFile(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayeredDrawingCapabilities.java',import.meta.url),'utf8');
 const encoded=source.match(/new JSONObject\("(.*)"\)/)[1];const native=JSON.parse(JSON.parse('"'+encoded+'"'));
 assert.deepEqual(contract.LAYERED_DRAWING_CAPABILITIES,native);
 assert.equal(native.contourFillPlacement.minimumNativeVersion,'4.10.1');
 assert.deepEqual(native.contourFillPlacement.choices,['layer_back','under_contour','preserve']);
 assert.equal(native.contourFillPlacement.onlyFreshCelsMove,true);
});
