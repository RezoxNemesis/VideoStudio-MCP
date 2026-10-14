import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto,createHash} from 'node:crypto';
import {receivePrivateBlob,privateBlobResponse,cleanupPrivateBlobs,PRIVATE_BLOB_LIMITS} from '../src/private-blob-store.js';
globalThis.crypto??=webcrypto;
const hash=value=>createHash('sha256').update(value).digest('hex');
function fixture({onCommit}={}){
  let values=new Map(),alarm=null,largest=0,fault=null,tail=Promise.resolve();
  const reader=map=>({
    async get(key){return structuredClone(map.get(key));},
    async put(key,value){
      if(fault?.(key,value))throw new Error('injected storage failure');
      const bytes=value?.bytes?.byteLength||new TextEncoder().encode(JSON.stringify(value)).byteLength;
      largest=Math.max(largest,bytes);assert(bytes+256<128*1024,'real per-value SQLite limit');map.set(key,structuredClone(value));
    },
    async delete(keys){for(const key of Array.isArray(keys)?keys:[keys])map.delete(key);},
    async list({prefix='',limit=Infinity,startAfter}={}){return new Map([...map].filter(([key])=>key.startsWith(prefix)&&(!startAfter||key>startAfter)).sort(([a],[b])=>a.localeCompare(b)).slice(0,limit));}
  });
  const storage={...reader(values),transaction(callback){const operation=tail.then(async()=>{
    const copy=structuredClone(values);let draftAlarm=alarm;
    const transaction={...reader(copy),async getAlarm(){return draftAlarm;},async setAlarm(value){draftAlarm=value;}};
    const result=await callback(transaction);values.clear();for(const entry of copy)values.set(...entry);alarm=draftAlarm;onCommit?.(values,alarm);return result;
  });tail=operation.catch(()=>{});return operation;},
    async getAlarm(){return alarm;},async setAlarm(value){alarm=value;}};
  return {storage,values,largest:()=>largest,fail:predicate=>{fault=predicate;}};
}
const options={deviceId:'synthetic-device',ownerHash:'a'.repeat(64),name:'synthetic.mp4',mime:'video/mp4',declaredSize:0};
const stream=bytes=>new ReadableStream({start(controller){for(let offset=0;offset<bytes.length;offset+=111111)controller.enqueue(bytes.slice(offset,offset+111111));controller.close();}});
const publish=async(transaction,manifest)=>{await transaction.put('test-command',{id:manifest.id,sha256:manifest.sha256});return {id:manifest.id};};
const quotaKey=deviceId=>'private-blobs:ledger:v2:'+hash(deviceId);

test('the first staged reservation commits its cleanup alarm atomically and preserves earlier alarms',async()=>{
  for(const earlier of [false,true]){
    let stagedCommit=false;
    const f=fixture({onCommit(values,alarm){
      const staged=[...values].find(([key,value])=>key.startsWith('private-blob:')&&value.status==='staging');
      if(staged){stagedCommit=true;assert.ok(alarm!==null&&alarm<=staged[1].expiresAt,'staged reservation must never commit before its cleanup wakeup');}
    }});
    const earlierAlarm=Date.now()+1000;if(earlier)await f.storage.setAlarm(earlierAlarm);
    const body=new ReadableStream({pull(controller){assert.equal(stagedCommit,true);controller.enqueue(new Uint8Array([1]));controller.close();}},{highWaterMark:0});
    await receivePrivateBlob(f.storage,body,{...options,declaredSize:1},publish);
    assert.equal(stagedCommit,true);if(earlier)assert.equal(await f.storage.getAlarm(),earlierAlarm);
  }
});

test('large upload is durable, bounded, hash checked and streams byte-identical content',async()=>{
  const f=fixture(),bytes=new Uint8Array(14700000);for(let index=0;index<bytes.length;index++)bytes[index]=index%251;
  const {manifest}=await receivePrivateBlob(f.storage,stream(bytes),{...options,declaredSize:bytes.length,expectedSha256:hash(bytes)},publish);
  assert.equal(manifest.size,bytes.length);assert.equal(manifest.sha256,hash(bytes));assert(f.largest()<=64*1024);
  const response=await privateBlobResponse(f.storage,manifest.id,options.deviceId);
  assert.equal(response.headers.get('x-content-sha256'),hash(bytes));
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()),bytes);
  assert.equal(await privateBlobResponse(f.storage,manifest.id,'other-device'),null);
  assert.equal((await f.storage.get('test-command')).id,manifest.id);
});

test('length, hash, stream and transaction failures publish no handoff and reclaim reservations',async()=>{
  for(const reason of ['length','hash','stream','transaction']){
    const f=fixture(),bytes=new Uint8Array(200000);
    const body=reason==='stream'?new ReadableStream({start(controller){controller.enqueue(bytes);controller.error(new Error('interrupted upload'));}}):stream(bytes);
    await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize:reason==='length'?bytes.length+1:bytes.length,
      expectedSha256:reason==='hash'?'f'.repeat(64):''},async(transaction,manifest)=>{
      await publish(transaction,manifest);if(reason==='transaction')throw new Error('command admission failed');
    }));
    assert.equal(await f.storage.get('test-command'),undefined);
    assert.equal([...f.values.keys()].some(key=>key.startsWith('private-blob:')||key.startsWith('private-blob-chunk:')),false);
    assert.equal(await f.storage.get(quotaKey(options.deviceId)),undefined);
  }
});

test('corrupt and missing chunks fail the download instead of completing with bad bytes',async()=>{
  for(const missing of [false,true]){
    const f=fixture(),bytes=new Uint8Array(160000);
    const {manifest}=await receivePrivateBlob(f.storage,stream(bytes),{...options,declaredSize:bytes.length},publish);
    const key='private-blob-chunk:'+manifest.id+':1';
    if(missing)await f.storage.delete(key);
    else {const part=await f.storage.get(key);part.bytes[0]=99;await f.storage.put(key,part);}
    const response=await privateBlobResponse(f.storage,manifest.id,options.deviceId);
    await assert.rejects(response.arrayBuffer(),/missing or corrupt/);
  }
});

test('expiry removes private chunks, metadata and handoffs and releases bounded quota',async()=>{
  const f=fixture(),bytes=new Uint8Array(100000);
  const {manifest}=await receivePrivateBlob(f.storage,stream(bytes),{...options,declaredSize:bytes.length},async(transaction,manifest)=>{
    await transaction.put('app-handoff:'+options.deviceId+':'+manifest.id,{blobId:manifest.id});return {};
  });
  assert.equal(await cleanupPrivateBlobs(f.storage,manifest.expiresAt),null);
  assert.equal(await privateBlobResponse(f.storage,manifest.id,options.deviceId),null);
  assert.deepEqual([...f.values.keys()],[]);
});

test('oversized and malformed declared input rejects before consuming any bytes',async()=>{
  for(const declaredSize of [-1,NaN,PRIVATE_BLOB_LIMITS.blobBytes+1]){
    const f=fixture();let pulled=false;
    const body=new ReadableStream({pull(){pulled=true;}},{highWaterMark:0});
    await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize},publish));
    assert.equal(pulled,false);assert.equal(f.values.size,0);
  }
});

test('one owner filling all16 upload slots cannot block another authenticated owner',async()=>{
  const f=fixture();
  for(let index=0;index<PRIVATE_BLOB_LIMITS.uploads;index++)await receivePrivateBlob(f.storage,stream(new Uint8Array([index])),{...options,declaredSize:1},publish);
  const rows=await f.storage.get(quotaKey(options.deviceId));assert.equal(rows.length,16);let pulled=false;
  const body=new ReadableStream({pull(){pulled=true;}},{highWaterMark:0});
  await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize:1},publish),/capacity is full/);
  assert.equal(pulled,false);assert.deepEqual(await f.storage.get(quotaKey(options.deviceId)),rows);
  const other={...options,deviceId:'other-authenticated-owner',ownerHash:'b'.repeat(64),declaredSize:1};
  const {manifest}=await receivePrivateBlob(f.storage,stream(new Uint8Array([42])),other,publish);
  assert.equal((await privateBlobResponse(f.storage,manifest.id,other.deviceId)).status,200);
  assert.equal((await f.storage.get(quotaKey(other.deviceId))).length,1);
});

test('expiry cleanup during an active upload cannot create late orphan chunks',async()=>{
  const f=fixture();let index=0;
  const body=new ReadableStream({async pull(controller){
    if(index++===0){controller.enqueue(new Uint8Array(PRIVATE_BLOB_LIMITS.chunkBytes));return;}
    await cleanupPrivateBlobs(f.storage,Date.now()+PRIVATE_BLOB_LIMITS.ttlMs+1);
    controller.enqueue(new Uint8Array(PRIVATE_BLOB_LIMITS.chunkBytes));controller.close();
  }},{highWaterMark:0});
  await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize:2*PRIVATE_BLOB_LIMITS.chunkBytes},publish),/expired during transfer/);
  assert.deepEqual([...f.values.keys()],[]);
});

test('idle timeout cancels an uncooperative body without holding its chunks or owner quota',async()=>{
  const f=fixture();let cancelled=false;
  const body=new ReadableStream({start(controller){controller.enqueue(new Uint8Array(PRIVATE_BLOB_LIMITS.chunkBytes));},cancel(){cancelled=true;return new Promise(()=>{});}},{highWaterMark:0});
  await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize:2*PRIVATE_BLOB_LIMITS.chunkBytes,idleTimeoutMs:20},publish),error=>error.status===408&&/body stalled/.test(error.message));
  assert.equal(cancelled,true);assert.deepEqual([...f.values.keys()],[]);
});

test('continuous trickle bytes cannot bypass the total upload deadline',async()=>{
  const f=fixture();let timer;
  const body=new ReadableStream({start(controller){timer=setInterval(()=>controller.enqueue(new Uint8Array([1])),5);},cancel(){clearInterval(timer);}},{highWaterMark:0});
  try{
    await assert.rejects(receivePrivateBlob(f.storage,body,{...options,declaredSize:1000,idleTimeoutMs:100,uploadTimeoutMs:35},publish),error=>error.status===408&&/total deadline/.test(error.message));
    assert.deepEqual([...f.values.keys()],[]);
  }finally{clearInterval(timer);}
});

test('staging concurrency is isolated per owner and explicit cancellation releases all partial bytes',async()=>{
  const f=fixture(),controllers=[new AbortController(),new AbortController()];
  const pending=controllers.map(controller=>receivePrivateBlob(f.storage,new ReadableStream({start(stream){stream.enqueue(new Uint8Array(PRIVATE_BLOB_LIMITS.chunkBytes));}},{highWaterMark:0}),
    {...options,declaredSize:2*PRIVATE_BLOB_LIMITS.chunkBytes,signal:controller.signal,idleTimeoutMs:1000},publish));
  const rejected=pending.map(operation=>assert.rejects(operation,error=>error.status===499&&/cancelled/.test(error.message)));
  try{
    for(let attempt=0;attempt<50;attempt++){
      if((await f.storage.get(quotaKey(options.deviceId)))?.length===PRIVATE_BLOB_LIMITS.staging)break;
      await new Promise(resolve=>setTimeout(resolve,2));
    }
    assert.equal((await f.storage.get(quotaKey(options.deviceId))).length,PRIVATE_BLOB_LIMITS.staging);
    await assert.rejects(receivePrivateBlob(f.storage,stream(new Uint8Array([1])),{...options,declaredSize:1},publish),/concurrency limit/);
    await receivePrivateBlob(f.storage,stream(new Uint8Array([2])),{...options,deviceId:'independent-owner',ownerHash:'b'.repeat(64),declaredSize:1},publish);
  }finally{for(const controller of controllers)controller.abort();await Promise.all(rejected);}
  assert.equal(await f.storage.get(quotaKey(options.deviceId)),undefined);
  assert.equal([...f.values.keys()].some(key=>key.startsWith('private-blob-chunk:')&&f.values.get(key).bytes.length>1),false);
});

test('expiry cleanup retains compatibility with already-issued global-ledger handoffs',async()=>{
  const f=fixture(),id=crypto.randomUUID();
  await f.storage.put('private-blobs:ledger:v1',[{id,deviceId:options.deviceId,reservedBytes:1,expiresAt:Date.now()-1}]);
  await f.storage.put('private-blob-chunk:'+id+':0',{bytes:new Uint8Array([1]),sha256:hash(new Uint8Array([1]))});
  assert.equal(await cleanupPrivateBlobs(f.storage),null);assert.deepEqual([...f.values.keys()],[]);
});
