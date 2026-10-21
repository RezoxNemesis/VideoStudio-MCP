import test from 'node:test';
import './dialogue-phrases-contract-test.mjs';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';
const request=()=>({projectId:'film',sourceProjectUpdatedAt:1,requestId:'dialogue',backend:'kokoro',assetIds:[],controls:{language:'hi-IN',durationMs:120000,lines:[{lineId:'one',characterId:'kael',voice:'hm_omega',text:'मैं लड़ना नहीं चाहता।',startMs:1000,slotMs:5000}]}});
test('dialogue tools retain explicit language, casting and timing behind the native version gate',()=>{
 const p=request();assert.deepEqual(nativeStudioParameters('open_pipeline_export_request',p),p);
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.2'},'open_pipeline_export_request',p),/4\.7\.0/);
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.2'},'studio_open_pipeline_export_request',p),/4\.7\.0/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.0'},'open_pipeline_export_request',p));
});
test('dialogue rejects voice substitution, source injection, overlaps and changed character casting',()=>{
 for(const mutate of [p=>p.controls.lines[0].voice='am_adam',p=>p.assetIds.push('image'),p=>p.controls.lines[0].slotMs=120000,p=>p.controls.lines[0].text='a'.repeat(241),p=>p.controls.lines.push({...p.controls.lines[0],lineId:'two',startMs:5000}),p=>p.controls.lines.push({...p.controls.lines[0],lineId:'two',startMs:7000,voice:'hf_alpha'}),p=>p.controls.lines[0].download='https://example.test']){
  const p=request();mutate(p);assert.throws(()=>nativeStudioParameters('open_pipeline_export_request',p));
 }
});
