import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';

test('revision-bound or stable-ID timeline plans require the native implementation that enforces them',()=>{
 for(const action of ['apply_edit_plan','autonomous_edit']){
  for(const parameters of [{expectedUpdatedAt:12,clips:[{assetId:'source'}]},{clips:[{assetId:'source',clipId:'cut-a'}]}]){
   assert.throws(()=>requireStudioVersion({appVersion:'4.5.11'},action,parameters),/4\.5\.12/);
   assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.5.12'},action,parameters));
  }
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.3.0'},action,{clips:[{assetId:'source'}]}));
 }
});

test('encrypted media admission binds one project and immutable manifest with no supplied URL or credential',()=>{
 const parameters={projectId:'project',attachmentId:'12345678-1234-4234-8234-123456789abc',manifestSha256:'a'.repeat(64)};
 assert.deepEqual(nativeStudioParameters('import_mailbox_media',parameters),parameters);
 for(const extra of [{sourceUrl:'https://example.com/file'},{ownerKey:'secret'},{file:'/tmp/source'}])assert.throws(()=>nativeStudioParameters('import_mailbox_media',{...parameters,...extra}));
 for(const bad of [{attachmentId:'../other'},{manifestSha256:'wrong'}])assert.throws(()=>nativeStudioParameters('import_mailbox_media',{...parameters,...bad}));
 assert.throws(()=>requireStudioVersion({appVersion:'4.1.1'},'import_mailbox_media',parameters),/4\.2\.0/);
 assert.throws(()=>requireStudioVersion({appVersion:'4.1.1'},'studio_import_mailbox_media',parameters),/4\.2\.0/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.2.0'},'import_mailbox_media',parameters));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.2.0'},'studio_import_mailbox_media',parameters));
});
test('clip fades retain integral milliseconds and require the engine version that renders them',()=>{
 const parameters={projectId:'project',expectedUpdatedAt:1,operations:[{clipId:'clip',set:{audioFadeInMs:300,audioFadeOutMs:800,volume:1.2}}]};
 assert.deepEqual(nativeStudioParameters('precision_edit',parameters),parameters);
 assert.throws(()=>requireStudioVersion({appVersion:'4.1.1'},'precision_edit',parameters),/4\.2\.0/);
 assert.throws(()=>requireStudioVersion({appVersion:'4.1.1'},'studio_precision_edit',parameters),/4\.2\.0/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.2.0'},'precision_edit',parameters));
 for(const bad of [-1,.5,Infinity,86400001])assert.throws(()=>nativeStudioParameters('precision_edit',{...parameters,operations:[{clipId:'clip',set:{audioFadeInMs:bad}}]}));
});
