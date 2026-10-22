import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {LAYERED_DRAWING_CAPABILITIES,nativeStudioParameters,requireStudioVersion,STUDIO_NATIVE_ACTIONS,studioToolSchemas} from '../src/studio-workbench-contract.js';

const operation={type:'duplicate_cel',layerId:'actor_construction',fromFrame:0,toFrame:1,idPrefix:'next'};
const request={sketchId:'owned',expectedRevision:7,operationId:'copy_owned',operations:[operation]};
// Exact bytes observed from the pre-feature519 parser, including its established field order.
const legacyNormalizedBytes='{"sketchId":"owned","operationId":"copy_owned","expectedRevision":7,"operations":[{"type":"duplicate_cel","layerId":"actor_construction","fromFrame":0,"toFrame":1,"idPrefix":"next"}]}';

test('authored cel reuse has matching native capabilities and keeps the existing action surface',()=>{
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchLayeredDrawingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/new JSONObject\(("(?:\\.|[^"\\])*")\)/)[1];
 assert.deepEqual(JSON.parse(JSON.parse(literal)),LAYERED_DRAWING_CAPABILITIES);
 const c=LAYERED_DRAWING_CAPABILITIES.authoredCelReuse;
 assert.equal(c.minimumNativeVersion,'4.10.17');assert.equal(c.action,'sketch_edit');assert.equal(c.operation,'duplicate_cel');
 assert.equal(c.retainsReferenceProtection,true);assert.equal(c.preservesSourcePixelsAndGeometry,true);assert.equal(c.newCanonicalAction,false);assert.equal(c.legacyRequestFloorUnchanged,true);
 assert.match(c.source,/complete source-free.*ancestry/);assert.match(c.source,/clone descendants.*reject/);assert.match(c.destination,/frozen drawing clock/);assert.match(c.manualControls,/Copy → next/);
 assert.equal(STUDIO_NATIVE_ACTIONS.has('sketch_authored_cel_reuse'),false);assert.equal(STUDIO_NATIVE_ACTIONS.size,Object.keys(studioToolSchemas).length);
});
test('ordinary and aliased cel copy requests retain pinned legacy normalized bytes without injected flags',()=>{
 for(const action of ['sketch_edit','studio_sketch_edit']){
  assert.deepEqual(nativeStudioParameters(action,request),request);assert.equal(JSON.stringify(nativeStudioParameters(action,request)),legacyNormalizedBytes);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.1'},action,request));assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.10.17'},action,request));
  assert.throws(()=>requireStudioVersion({appVersion:'3.9.0'},action,request),/3\.9\.1/);
 }
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.8.4'},action,{}));
});
test('copy inputs cannot coerce frame indexes or add a reference-copy escape flag',()=>{
 for(const patch of [{fromFrame:'0'},{toFrame:1.5},{toFrame:null},{layerId:'../guide'},{idPrefix:'x'.repeat(73)},{allowReference:true},{cloneSource:{objectId:'guide'}},{sourceStage:'reference'}]){
  assert.throws(()=>nativeStudioParameters('sketch_edit',{...request,operations:[{...operation,...patch}]}));
 }
});
test('relay guidance requires the new native capability only for protected authored reuse',()=>{
 const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 assert.match(source,/Native4\.10\.17 enables duplicate_cel/);assert.match(source,/Call app_status and require4\.10\.17 for that protected workflow/);
 assert.match(source,/Legacy duplicate requests and version floors are unchanged/);
 assert.equal(LAYERED_DRAWING_CAPABILITIES.minimumNativeVersion,'4.9.2');
 assert.equal(LAYERED_DRAWING_CAPABILITIES.stagePromotion.minimumNativeVersion,'4.9.6');
 assert.equal(LAYERED_DRAWING_CAPABILITIES.automaticallyDrawsReference,false);
});
