import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {EXACT_CAMERA_ORBIT_CAPABILITIES,nativeCinematic3dParameters} from '../src/cinematic3d-contract.js';
import {nativeStoryboardParameters,requireStoryboardNativeVersion} from '../src/storyboard-contract.js';
import {nativeStudioCanonicalAction,STUDIO_ALIAS_ACTIONS} from '../src/studio-workbench-contract.js';
const orbit={target:[.25,1.1,-.5],radius:5.2,startAzimuthDegrees:25,endAzimuthDegrees:385,startElevationDegrees:0,endElevationDegrees:0};
const edit={projectId:'project',programId:'program',sourceProjectUpdatedAt:1,expectedProgramSha256:'a'.repeat(64),operations:[{type:'set_camera_orbit',shotId:'shot',orbit}]};
test('exact native camera fields and aliases preserve source-bound requests and their new floor',()=>{
 for(const action of ['cinematic_3d_edit_program','studio_cinematic_3d_edit_program','studio_cinematic_3d_edit_program_v4103']){
  assert.deepEqual(nativeStoryboardParameters(action,edit),edit);assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.10.2'},action,edit),/4\.10\.3/);assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.10.3'},action,edit));
 }
 const legacy={...edit,operations:[{type:'clear_camera_keys',shotId:'shot'}]};assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.3.0'},'cinematic_3d_edit_program',legacy));
 assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.10.2'},'studio_cinematic_3d_edit_program_v4103',legacy),/4\.10\.3/);
});
test('degree orbit schema rejects coercion, excessive angles, unknowns and unsafe targets',()=>{
 for(const patch of [{radius:'5.2'},{radius:0},{radius:31},{target:[0,1]},{target:[10000,0,0]},{startElevationDegrees:91},{endAzimuthDegrees:3601},{foo:true}])assert.throws(()=>nativeCinematic3dParameters('cinematic_3d_edit_program',{...edit,operations:[{...edit.operations[0],orbit:{...orbit,...patch}}]}));
 assert.throws(()=>nativeCinematic3dParameters('cinematic_3d_edit_program',{...edit,operations:[{type:'clear_camera_orbit',shotId:'shot',orbit}]}));
});
test('sampleFrame requires an exact current program and cannot be an unverified edit-only read',()=>{
 const request={projectId:'project',programId:'program',expectedProgramSha256:'a'.repeat(64),sampleFrame:25};assert.deepEqual(nativeStoryboardParameters('studio_cinematic_3d_get_program_v4103',request),request);
 for(const patch of [{expectedProgramSha256:undefined},{sampleFrame:'25'},{sampleFrame:12000},{editOnly:true}])assert.throws(()=>nativeCinematic3dParameters('cinematic_3d_get_program',{...request,...patch}));
 assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.10.2'},'cinematic_3d_get_program',request),/4\.10\.3/);
});
test('capabilities match native exactly and compatibility aliases are closed',()=>{
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/CinematicCameraOrbit.java',import.meta.url),'utf8');const literal=source.match(/return new JSONObject\(("(?:\\.|[^"\\])*")\);/)[1];assert.deepEqual(JSON.parse(JSON.parse(literal)),EXACT_CAMERA_ORBIT_CAPABILITIES);
 for(const [alias,action]of Object.entries(EXACT_CAMERA_ORBIT_CAPABILITIES.compatibilityAliases)){assert.equal(nativeStudioCanonicalAction(alias),action);assert.equal(STUDIO_ALIAS_ACTIONS.has(alias),false);}
 for(const unknown of ['studio_cinematic_3d_edit_program_v4104','studio_gallery_v4103','studio_studio_cinematic_3d_get_program_v4103','toString'])assert.equal(nativeStudioCanonicalAction(unknown),unknown);
 const index=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');assert.match(index,/import \{[^\n]*EXACT_CAMERA_ORBIT_CAPABILITIES[^\n]*from '\.\/studio-workbench-contract\.js'/);
});
