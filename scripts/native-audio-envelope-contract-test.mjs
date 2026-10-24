import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {nativeStoryboardParameters,requireStoryboardNativeVersion,STORYBOARD_NATIVE_ACTIONS} from '../src/storyboard-contract.js';

const request=()=>({mode:'audio_envelope',projectId:'owned-project',expectedUpdatedAt:123,assetId:'selected-audio',expectedSha256:'a'.repeat(64),sourceMs:0,durationMs:24000,buckets:576});

test('explicit audio mode requires future4.10.25 without changing the existing canonical action set',()=>{
  assert.throws(()=>requireStoryboardNativeVersion({appVersion:'4.10.24'},'analyse_media',request()),/4\.10\.25/);
  assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'4.10.25'},'analyse_media',request()));
  assert.equal(STORYBOARD_NATIVE_ACTIONS.has('analyse_media'),false,'Existing analyse_media must not become a duplicate Studio canonical action');
});

test('strict explicit audio mode rejects coercions, missing CAS fields, mixed video arguments and unknown fields',()=>{
  const invalid=[];
  for(const mode of [null,true,7,'audio','visual'])invalid.push({...request(),mode});
  for(const sourceMs of [null,'0',false,-1,.5,86400001])invalid.push({...request(),sourceMs});
  for(const durationMs of [null,'1000',true,99,30001,100.5])invalid.push({...request(),durationMs});
  for(const buckets of [null,'4',true,3,721,4.5])invalid.push({...request(),buckets});
  for(const key of ['frames','start','end','startMs','endMs','includeAudio','uri','unknown'])invalid.push({...request(),[key]:1});
  for(const key of ['projectId','expectedUpdatedAt','assetId','expectedSha256','sourceMs','durationMs','buckets']){const missing=request();delete missing[key];invalid.push(missing);}
  for(const input of invalid)assert.throws(()=>nativeStoryboardParameters('analyse_media',input),/audio|envelope|mode|field|integer|source|hash|SHA|project|bucket|duration/i,JSON.stringify(input));
});

test('valid explicit audio request retains every authored integer and expected source identity',()=>{
  const authored=request(),before=JSON.stringify(authored);const parsed=nativeStoryboardParameters('analyse_media',authored);
  assert.deepEqual(parsed,authored);assert.equal(JSON.stringify(authored),before);assert.equal('frames' in parsed,false);assert.equal('start' in parsed,false);
});

test('omitted mode retains exact legacy visual parameter bytes and old version admission',()=>{
  const legacy={assetId:'old-video',frames:8,start:0,end:12.5};const before=JSON.stringify(legacy);
  assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'3.7.0'},'analyse_media',legacy));
  const parsed=nativeStoryboardParameters('analyse_media',legacy);assert.equal(JSON.stringify(parsed),before);
  assert.equal(createHash('sha256').update(JSON.stringify(parsed)).digest('hex'),createHash('sha256').update(before).digest('hex'));
  assert.equal('mode' in parsed,false);assert.equal('expectedSha256' in parsed,false);assert.deepEqual(legacy,{assetId:'old-video',frames:8,start:0,end:12.5});
});
