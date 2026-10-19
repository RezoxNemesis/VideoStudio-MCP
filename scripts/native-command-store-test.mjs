import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto,createHash} from 'node:crypto';
import {
  COMMAND_QUEUE_LIMITS,readCommandQueue,readCommandQueueInTransaction,
  writeCommandQueueInTransaction,mutateCommandQueue,requireCommandQueueAdmission,
  commandIsPending,terminalCommandUpdate,canonicalCommandResultSha256,
  readCommandReceipt,acknowledgePrunedCommand,mergeCommandReceiptsInTransaction,
  readNativeDevice,readNativeDeviceInTransaction,writeNativeDevice,writeNativeDeviceInTransaction,NATIVE_DEVICE_LIMITS
} from '../src/native-command-store.js';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const encoder=new TextEncoder(),VALUE_LIMIT=128*1024;

// The fixture enforces the real per-value ceiling and serialized transactional
// rollback. It never calls a Worker endpoint or represents native device QA.
function fixture(initial=[]){
  const key='app-v3-cl:device',sequence='app-v3-seq:device';
  let values=new Map([[key,structuredClone(initial)]]),tail=Promise.resolve(),fault=null,largestValue=0;const reads=[];
  const valueBytes=value=>value instanceof ArrayBuffer?value.byteLength:ArrayBuffer.isView(value)?value.byteLength:encoder.encode(JSON.stringify(value)).byteLength;
  const put=(target,name,value)=>{
    const bytes=valueBytes(value);if(bytes>VALUE_LIMIT)throw new Error('Durable Object value exceeds 128 KiB');
    if(fault?.(name,value))throw new Error('injected atomic storage failure');
    largestValue=Math.max(largestValue,bytes);target.set(name,structuredClone(value));
  };
  if(valueBytes(initial)>VALUE_LIMIT)throw new Error('Legacy fixture must fit the existing Durable Object value');
  const reader=target=>({
    async get(name){reads.push(name);return structuredClone(target.get(name));},
    async put(name,value){put(target,name,value);},async delete(name){return target.delete(name);},
    async list({prefix,limit}){return new Map([...target].filter(([name])=>name.startsWith(prefix)).slice(0,limit));}
  });
  const storage={
    async get(name){return reader(values).get(name);},async put(name,value){return reader(values).put(name,value);},
    async delete(name){return values.delete(name);},async list(options){return reader(values).list(options);},
    async transaction(callback){
      const prior=tail;let release;tail=new Promise(resolve=>{release=resolve;});await prior;
      const draft=structuredClone(values);
      try{const result=await callback(reader(draft));values=draft;return result;}finally{release();}
    }
  };
  return {storage,key,sequence,state:()=>structuredClone(values),fail:predicate=>{fault=predicate;},largest:()=>largestValue,reads:()=>[...reads]};
}
const row=(seq,status='queued',text='')=>({
  id:'command-'+seq,seq,protocolVersion:3,deviceId:'device',action:'storyboard_program_put',
  parameters:{sourceProjectId:'project-owned',program:{text}},status,
  createdAt:'2026-10-12T00:00:00.000Z',
  completedAt:commandIsPending({status})?null:new Date(Date.UTC(2026,9,12)+seq).toISOString(),
  result:commandIsPending({status})?null:{ok:true}
});
const append=(f,next)=>mutateCommandQueue(f.storage,f.key,rows=>{
  requireCommandQueueAdmission(rows,next);rows.push(next);return {rows,result:next};
});
const rewrite=f=>mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));

test('a full UTF-8 program and result cross 128 KiB while each stored value stays bounded',async()=>{
  const f=fixture(),command=row(1,'queued','🎨'.repeat(90_000));
  await assert.rejects(f.storage.put('direct-too-large',command),/128 KiB/);
  await append(f,command);
  const claimed=await mutateCommandQueue(f.storage,f.key,rows=>{
    rows[0]={...rows[0],status:'claimed',claimedAt:'2026-10-12T00:01:00.000Z',leaseUntil:12345,claimCount:1};
    return {rows,result:rows[0]};
  });
  assert.equal(claimed.id,command.id);assert.equal(claimed.seq,command.seq);
  const result={ok:true,program:{text:'原画'.repeat(70_000)},contactSheet:{base64:'preview'.repeat(25_000)}};
  await mutateCommandQueue(f.storage,f.key,async rows=>{rows[0]=await terminalCommandUpdate(rows[0],result,'completed');return {rows,result:rows[0]};});
  const completed=(await readCommandQueue(f.storage,f.key))[0];
  assert.deepEqual(completed.parameters,command.parameters);assert.deepEqual(completed.result,result);
  assert.equal(completed.id,command.id);assert.equal(completed.seq,1);
  assert.equal(completed.resultSha256,await canonicalCommandResultSha256(result));
  assert.equal(await f.storage.get(f.key),undefined);assert.ok(f.largest()<=64*1024);
  const manifest=await f.storage.get(f.key+':manifest:v1');assert.ok(manifest.chunks>3);
  assert.equal([...f.state().keys()].filter(name=>name.includes(':chunks:')).length,manifest.chunks);
});

test('legacy migration failure rolls back chunks, receipts and the sequence together',async()=>{
  for(const boundary of ['chunks:',':manifest:v1',':format']){
    const f=fixture([row(1)]);await f.storage.put(f.sequence,1);const before=f.state();
    f.fail(name=>name.includes(boundary));
    await assert.rejects(mutateCommandQueue(f.storage,f.key,async(rows,transaction)=>{
      const next=row(2,'queued','x'.repeat(180_000));requireCommandQueueAdmission(rows,next);rows.push(next);
      await transaction.put(f.sequence,2);return {rows,result:next};
    }),/injected/);
    assert.deepEqual(f.state(),before);
  }
});

test('failed replacement retains the old complete generation and immutable terminal result',async()=>{
  const f=fixture();await append(f,row(1,'queued','x'.repeat(150_000)));await rewrite(f);
  const before=f.state();let chunks=0;f.fail(name=>name.includes(':chunks:')&&++chunks===2);
  await assert.rejects(mutateCommandQueue(f.storage,f.key,async rows=>{
    rows[0]=await terminalCommandUpdate(rows[0],{ok:true,payload:'y'.repeat(200_000)},'completed');return {rows,result:null};
  }),/injected/);
  assert.deepEqual(f.state(),before);assert.equal((await readCommandQueue(f.storage,f.key))[0].status,'queued');
});

test('concurrent admission allocates exact distinct sequences inside the queue transaction',async()=>{
  const f=fixture();const admit=id=>mutateCommandQueue(f.storage,f.key,async(rows,transaction)=>{
    const seq=Math.max((await transaction.get(f.sequence))||0,...rows.map(item=>item.seq),0)+1;
    const next={...row(seq),id};requireCommandQueueAdmission(rows,next);rows.push(next);await transaction.put(f.sequence,seq);
    return {rows,result:next};
  });
  const admitted=await Promise.all([admit('first-native-command'),admit('second-native-command')]);
  assert.deepEqual(admitted.map(item=>item.seq),[1,2]);
  assert.deepEqual((await readCommandQueue(f.storage,f.key)).map(item=>[item.id,item.seq]),[['first-native-command',1],['second-native-command',2]]);
  assert.equal(await f.storage.get(f.sequence),2);
});

test('all160 accepted active commands remain claimable and new admission cannot truncate them',async()=>{
  const original=Array.from({length:160},(_,index)=>row(index+1)),f=fixture(original);
  await mutateCommandQueue(f.storage,f.key,rows=>{rows[0]={...rows[0],status:'claimed',leaseUntil:0};return {rows,result:rows[0]};});
  const claimed=await readCommandQueue(f.storage,f.key);assert.equal(claimed.length,160);
  assert.deepEqual(claimed.map(item=>item.id),original.map(item=>item.id));
  await assert.rejects(append(f,row(161)),/queue is full/);
  assert.equal((await readCommandQueue(f.storage,f.key)).length,160);
});

test('terminal history pruning keeps locator-free proof and exact canonical lost-ack replay',async()=>{
  const f=fixture(),result={ok:true,outputUri:'content://private/visible/42',contactSheet:{base64:'private-preview'.repeat(12_000),mimeType:'image/jpeg'}};
  await append(f,row(1));
  await mutateCommandQueue(f.storage,f.key,async rows=>{
    rows[0]=await terminalCommandUpdate(rows[0],result,'completed');
    rows.push(...Array.from({length:159},(_,index)=>row(index+2,'completed')));return {rows,result:null};
  });
  await append(f,row(161));assert.equal((await readCommandQueue(f.storage,f.key)).length,160);
  const receipt=await readCommandReceipt(f.storage,f.key,'command-1');assert.equal(receipt.resultExpired,true);
  assert.equal(receipt.resultSha256,await canonicalCommandResultSha256(result));
  assert.equal(JSON.stringify(receipt).includes('content://'),false);assert.equal(JSON.stringify(receipt).includes('private-preview'),false);
  const replay=await f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',
    {contactSheet:{mimeType:'image/jpeg',base64:result.contactSheet.base64},outputUri:result.outputUri,ok:true},'completed'));
  assert.equal(replay.resultReplayed,true);assert.equal(replay.id,'command-1');
  await assert.rejects(f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',{ok:false},'completed')),/Conflicting/);
  await assert.rejects(f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',result,'failed')),/Conflicting/);
  const before=f.state();await assert.rejects(append(f,row(1)),/prohibits reopening/);assert.deepEqual(f.state(),before);
});

test('terminal replay cannot expire a thumbnail or trust a stale cached result digest',async()=>{
  assert.equal(await canonicalCommandResultSha256({z:[{b:2,a:1}],a:true}),
    createHash('sha256').update('{"a":true,"z":[{"a":1,"b":2}]}','utf8').digest('hex'));
  const result={ok:true,contactSheet:{base64:'immutable-preview',mimeType:'image/png'}};
  const terminal=await terminalCommandUpdate(row(1),result,'completed');
  const replay=await terminalCommandUpdate(terminal,{contactSheet:{mimeType:'image/png',base64:'immutable-preview'},ok:true},'completed');
  assert.equal(replay.completedAt,terminal.completedAt);assert.equal(replay.resultSha256,terminal.resultSha256);
  await assert.rejects(terminalCommandUpdate(terminal,{ok:true,contactSheet:{expired:true}},'completed'),/Conflicting/);
  await assert.rejects(terminalCommandUpdate({...terminal,result:{ok:false}},result,'completed'),/receipt checksum/);
  await assert.rejects(terminalCommandUpdate(terminal,result,'failed'),/Conflicting/);
});

test('missing or corrupt durable proof fails closed and retains all remaining original bytes',async()=>{
  for(const damage of ['chunk','checksum','manifest','receipts','mixed']){
    const f=fixture([row(1)]);await rewrite(f);
    const manifest=await f.storage.get(f.key+':manifest:v1'),chunk=f.key+':chunks:v1:'+manifest.generation+':0';
    if(damage==='chunk')await f.storage.delete(chunk);
    if(damage==='checksum'){
      const original=await f.storage.get(chunk),text=new TextDecoder().decode(original);
      // Change a valid same-length command ID; parsing alone cannot detect it.
      await f.storage.put(chunk,encoder.encode(text.replace('command-1','command-9')));
    }
    if(damage==='manifest')await f.storage.delete(f.key+':manifest:v1');
    if(damage==='receipts')await f.storage.delete(f.key+':terminal-receipts:v1');
    if(damage==='mixed')await f.storage.put(f.key,[row(2)]);
    const before=f.state();await assert.rejects(readCommandQueue(f.storage,f.key),/missing|checksum|corrupt|Mixed/);
    await assert.rejects(append(f,row(3)),/missing|checksum|corrupt|Mixed/);assert.deepEqual(f.state(),before);
  }
});

test('near2MiB request and separate proof metadata can claim and acknowledge; oversize fails before replacement',async()=>{
  const f=fixture(),request=row(1,'queued','x'.repeat(COMMAND_QUEUE_LIMITS.commandBytes-1024));
  await append(f,request);
  await mutateCommandQueue(f.storage,f.key,rows=>{rows[0]={...rows[0],status:'claimed',leaseUntil:1,claimCount:1};return {rows,result:null};});
  await mutateCommandQueue(f.storage,f.key,async rows=>{rows[0]=await terminalCommandUpdate(rows[0],{ok:true},'completed');return {rows,result:null};});
  assert.equal((await readCommandQueue(f.storage,f.key))[0].parameters.program.text,request.parameters.program.text);
  const before=f.state();await assert.rejects(append(f,row(2,'queued','x'.repeat(COMMAND_QUEUE_LIMITS.commandBytes))),/2 MiB/);assert.deepEqual(f.state(),before);
  await assert.rejects(canonicalCommandResultSha256({payload:'x'.repeat(COMMAND_QUEUE_LIMITS.resultBytes)}),/receipt budget/);
});

test('aggregate admission reserves result capacity without dropping pending source programs',async()=>{
  const f=fixture();for(let seq=1;seq<=6;seq++)await append(f,row(seq,'queued','x'.repeat(2*1024*1024-1024)));
  const before=f.state();await assert.rejects(append(f,row(7,'queued','x'.repeat(2*1024*1024-1024))),/result-reserved JSON byte budget/);
  assert.deepEqual(f.state(),before);assert.equal((await readCommandQueue(f.storage,f.key)).length,6);
});

test('depth and non-JSON values reject safely while default undefined object options omit normally',async()=>{
  const nested=depth=>{let value=true;for(let index=0;index<depth;index++)value={child:value};return value;};
  await canonicalCommandResultSha256(nested(COMMAND_QUEUE_LIMITS.jsonDepth));
  await assert.rejects(canonicalCommandResultSha256(nested(COMMAND_QUEUE_LIMITS.jsonDepth+1)),/nesting depth/);
  const f=fixture([row(1)]),before=f.state();
  await assert.rejects(append(f,{...row(2),parameters:nested(COMMAND_QUEUE_LIMITS.jsonDepth)}),/nesting depth/);assert.deepEqual(f.state(),before);
  const cycle={};cycle.self=cycle;await assert.rejects(canonicalCommandResultSha256(cycle),/cycle/);
  for(const invalid of [NaN,Infinity,1n,new Date(),()=>true])await assert.rejects(canonicalCommandResultSha256({invalid}),/finite|plain JSON|plain objects/);
  await assert.rejects(canonicalCommandResultSha256([undefined]),/plain JSON/);
  await append(f,{...row(2),parameters:{projectId:'project-owned',expectedRevision:undefined,options:{codec:undefined}}});
  assert.deepEqual((await readCommandQueue(f.storage,f.key))[1].parameters,{projectId:'project-owned',options:{}});
});

test('canonical alias migration retains compact terminal proof without a missing-manifest window',async()=>{
  const f=fixture(Array.from({length:160},(_,index)=>row(index+1,'completed'))),target='app-v3-cl:canonical';
  await append(f,row(161));const original=await readCommandReceipt(f.storage,f.key,'command-1');
  await f.storage.transaction(async transaction=>{
    const targetRows=await readCommandQueueInTransaction(transaction,target);
    const migrated=await mergeCommandReceiptsInTransaction(transaction,target,f.key);assert.equal(migrated.highestSeq,1);
    await writeCommandQueueInTransaction(transaction,target,targetRows);
  });
  assert.deepEqual(await readCommandReceipt(f.storage,target,'command-1'),original);
  assert.deepEqual(await readCommandReceipt(f.storage,f.key,'command-1'),original);
  const replay=await f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,target,'command-1',{ok:true},'completed'));
  assert.equal(replay.resultReplayed,true);assert.deepEqual(await readCommandQueue(f.storage,target),[]);
});

test('large native registration preserves all project metadata and owner identity across reload',async()=>{
  const f=fixture(),key='app-device:device',record={deviceId:'device',ownerHash:'b'.repeat(64),appGeneration:17,
    projects:Array.from({length:20},(_,index)=>({id:'project-'+index,sourcePrompt:'原画'.repeat(2500),assetCount:23,clipCount:13}))};
  await assert.rejects(f.storage.put(key,record),/128 KiB/);
  await writeNativeDevice(f.storage,key,record);assert.deepEqual(await readNativeDevice(f.storage,key),record);
  assert.equal(await f.storage.get(key),undefined);assert.ok(f.largest()<=64*1024);
  const manifest=await f.storage.get(key+':device-json:manifest:v1');assert.ok(manifest.chunks>2);
  await f.storage.transaction(async transaction=>{
    const existing=await readNativeDeviceInTransaction(transaction,key);
    await writeNativeDeviceInTransaction(transaction,key,{...existing,lastSeenAt:'2026-10-12T12:00:00.000Z'});
  });
  const reloaded=await readNativeDevice(f.storage,key);assert.deepEqual(reloaded.projects,record.projects);
  assert.equal(reloaded.ownerHash,record.ownerHash);assert.equal(reloaded.deviceId,record.deviceId);assert.equal(reloaded.appGeneration,17);
});

test('native registration migration and replacement faults preserve exact old owner metadata',async()=>{
  for(const versioned of [false,true])for(const boundary of [':device-json:chunks:',':device-json:manifest:v1',':device-json:format']){
    const f=fixture(),key='app-device:device',old={deviceId:'device',ownerHash:'c'.repeat(64),projects:[{id:'source-original',assetCount:23}]};
    if(versioned)await writeNativeDevice(f.storage,key,old);else await f.storage.put(key,old);
    const before=f.state();f.fail(name=>name.includes(boundary));
    await assert.rejects(writeNativeDevice(f.storage,key,{...old,projects:[{id:'source-original',sourcePrompt:'x'.repeat(200_000)}]}),/injected/);
    assert.deepEqual(f.state(),before);assert.deepEqual(await readNativeDevice(f.storage,key),old);
  }
});

test('missing or corrupt native record proof never becomes a new empty registration',async()=>{
  for(const damage of ['chunk','checksum','manifest','sentinel','mixed','identity']){
    const f=fixture(),key='app-device:device',record={deviceId:'device',ownerHash:'d'.repeat(64),projects:[]};
    await writeNativeDevice(f.storage,key,record);const manifest=await f.storage.get(key+':device-json:manifest:v1');
    const chunk=key+':device-json:chunks:v1:'+manifest.generation+':0';
    if(damage==='chunk')await f.storage.delete(chunk);
    if(damage==='checksum'){
      const original=await f.storage.get(chunk),text=new TextDecoder().decode(original);
      // Keep JSON and identity valid while changing a retained owner proof byte.
      await f.storage.put(chunk,encoder.encode(text.replace('d'.repeat(64),'e'.repeat(64))));
    }
    if(damage==='manifest')await f.storage.delete(key+':device-json:manifest:v1');
    if(damage==='sentinel')await f.storage.delete(key+':device-json:format');
    if(damage==='mixed')await f.storage.put(key,record);
    if(damage==='identity'){manifest.deviceId='other-device';await f.storage.put(key+':device-json:manifest:v1',manifest);}
    const before=f.state();await assert.rejects(readNativeDevice(f.storage,key),/missing|checksum|integrity|Mixed/);
    await assert.rejects(writeNativeDevice(f.storage,key,record),/missing|checksum|integrity|Mixed/);assert.deepEqual(f.state(),before);
  }
  const fresh=fixture();assert.equal(await readNativeDevice(fresh.storage,'app-device:device'),null);
});

test('native record byte, depth and exact identity bounds reject without replacing retained registration',async()=>{
  const f=fixture(),key='app-device:device',record={deviceId:'device',ownerHash:'e'.repeat(64),projects:[]};
  await writeNativeDevice(f.storage,key,record);const before=f.state();
  await assert.rejects(writeNativeDevice(f.storage,key,{...record,sourcePrompt:'x'.repeat(NATIVE_DEVICE_LIMITS.bytes)}),/2 MiB/);
  await assert.rejects(writeNativeDevice(f.storage,key,{...record,deviceId:'different-device'}),/storage identity/);
  let nested=true;for(let index=0;index<NATIVE_DEVICE_LIMITS.jsonDepth;index++)nested={child:nested};
  await assert.rejects(writeNativeDevice(f.storage,key,{...record,nested}),/nesting depth/);assert.deepEqual(f.state(),before);
});

// Exact producer format from 71d9502074d43e347612e3f7c5a49445e8e9057f,
// src/native-payload-storage.js:21-31: JSON string sliced by 64000 UTF-16
// code units, six-field reference, and app-v3-blob namespace. No live credentials.
async function historicalField(f,command,field,value,originDeviceId='device'){
  const encoded=JSON.stringify(value),parts=Math.ceil(encoded.length/64000);
  for(let part=0;part<parts;part++)await f.storage.put(`app-v3-blob:${originDeviceId}:${command.id}:${field}:${part}`,encoded.slice(part*64000,(part+1)*64000));
  return {__nativePayloadVersion:1,originDeviceId,commandId:command.id,field,parts,length:encoded.length};
}
const legacyError=/[Ll]egacy native|[Aa]rchived native|nesting depth|2 MiB|bounded.*budget/;

test('historic UTF-16 pointers hydrate actual queued parameters and terminal results without changing stored proof',async()=>{
  const f=fixture(),pending=row(1,'waiting_native'),done=row(2,'completed');
  // Make a surrogate pair straddle the historical 64000-code-unit boundary.
  pending.parameters={text:'x'.repeat(63990)+'🎨'.repeat(4000),sourceProjectId:'project-owned'};
  done.result={ok:true,contactSheet:{base64:'archive-thumbnail'.repeat(12000)}};
  done.resultSha256=await canonicalCommandResultSha256(done.result);
  const packed=[{...pending,parameters:await historicalField(f,pending,'parameters',pending.parameters)},
    {...done,result:await historicalField(f,done,'result',done.result)}];
  await f.storage.put(f.key,packed);const before=f.state();
  const actual=await readCommandQueue(f.storage,f.key);assert.deepEqual(actual,[pending,done]);assert.deepEqual(f.state(),before);
  actual[0].parameters.text='caller mutation';assert.deepEqual(await readCommandQueue(f.storage,f.key),[pending,done]);
});

test('historic pointer claim and migration are atomic and current chunks contain hydrated data',async()=>{
  for(const fail of [false,true]){
    const f=fixture(),original=row(1,'queued','old-script-'.repeat(15000));
    const packed={...original,parameters:await historicalField(f,original,'parameters',original.parameters)};
    await f.storage.put(f.key,[packed]);await f.storage.put(f.sequence,1);const before=f.state();
    if(fail)f.fail(name=>name.endsWith(':manifest:v1'));
    const claim=()=>mutateCommandQueue(f.storage,f.key,async(rows,transaction)=>{
      assert.deepEqual(rows,[original]);rows[0]={...rows[0],status:'claimed',leaseUntil:12345,claimCount:1};
      await transaction.put(f.sequence,2);return {rows,result:rows[0]};
    });
    if(fail){await assert.rejects(claim(),/injected/);assert.deepEqual(f.state(),before);}
    else {
      assert.deepEqual((await claim()).parameters,original.parameters);assert.equal(await f.storage.get(f.key),undefined);
      const migrated=(await readCommandQueue(f.storage,f.key))[0];assert.equal(migrated.id,original.id);assert.equal(migrated.seq,1);assert.deepEqual(migrated.parameters,original.parameters);
      assert.equal(migrated.status,'claimed');assert.equal(await f.storage.get(f.sequence),2);
      assert.equal([...f.state().keys()].some(key=>key.startsWith('app-v3-blob:')),true,'Old blob proof is retained, not blindly deleted');
      await mutateCommandQueue(f.storage,f.key,async rows=>{rows[0]=await terminalCommandUpdate(rows[0],{ok:true},'completed');return {rows,result:null};});
      assert.equal((await readCommandQueue(f.storage,f.key))[0].status,'completed');
    }
  }
});

test('historic pointers already copied into the current manifest format also hydrate',async()=>{
  const f=fixture(),original=row(1,'queued','carried-pointer'.repeat(12000));
  const packed={...original,parameters:await historicalField(f,original,'parameters',original.parameters)};
  const bytes=encoder.encode(JSON.stringify([packed])),generation=crypto.randomUUID(),chunks=Math.ceil(bytes.length/COMMAND_QUEUE_LIMITS.chunkBytes);
  for(let i=0;i<chunks;i++)await f.storage.put(`${f.key}:chunks:v1:${generation}:${i}`,bytes.slice(i*COMMAND_QUEUE_LIMITS.chunkBytes,(i+1)*COMMAND_QUEUE_LIMITS.chunkBytes));
  await f.storage.put(f.key+':manifest:v1',{version:1,generation,bytes:bytes.length,chunks,count:1,sha256:createHash('sha256').update(bytes).digest('hex')});
  await f.storage.put(f.key+':format',1);await f.storage.delete(f.key);
  assert.deepEqual(await readCommandQueue(f.storage,f.key),[original]);await rewrite(f);assert.deepEqual(await readCommandQueue(f.storage,f.key),[original]);
});

test('historic untrusted pointers cannot cross device command field protocol or delimiter namespaces',async()=>{
  const mutations=[
    (c,p)=>{p.originDeviceId='foreign-device';},(c,p)=>{p.commandId='other-command';},(c,p)=>{p.field='result';},
    (c,p)=>{c.deviceId='foreign-device';},(c,p)=>{c.protocolVersion=2;},(c,p)=>{p.originDeviceId='device:command-1';},
    (c,p)=>{p.originDeviceId='foreign-device';c.migratedFromDeviceId='foreign-device';},
    (c,p)=>{p.__nativePayloadVersion=2;},(c,p)=>{p.parts=1.5;},(c,p)=>{p.parts=512;},
    (c,p)=>{p.length=COMMAND_QUEUE_LIMITS.commandBytes+1;},(c,p)=>{delete p.length;},(c,p)=>{p.extra='untrusted';}
  ];
  for(const mutate of mutations){
    const f=fixture(),original=row(1),pointer=await historicalField(f,original,'parameters',{text:'x'.repeat(2000)}),command={...original,parameters:pointer};
    mutate(command,pointer);await f.storage.put(f.key,[command]);const before=f.state();
    await assert.rejects(readCommandQueue(f.storage,f.key),legacyError);assert.deepEqual(f.state(),before);
    assert.equal(f.reads().some(key=>key.startsWith('app-v3-blob:')),false,'Reject untrusted pointer before reading any blob');
  }
  const f=fixture(),command=row(1);command.parameters=await historicalField(f,command,'parameters',{text:'x'.repeat(2000)});
  await f.storage.put('app-cl:device',[command]);await assert.rejects(readCommandQueue(f.storage,'app-cl:device'),legacyError);
});

test('historic alias-carried pointers require registered ownership and supersession proof, not just row metadata',async()=>{
  for(const damage of [null,'aliases','supersession','owner','origin-identity']){
    const f=fixture(),original={...row(1),migratedFromDeviceId:'origin-device'},ownerHash='a'.repeat(64);
    original.parameters={program:{text:'old-origin'.repeat(1000)}};
    const packed={...original,parameters:await historicalField(f,original,'parameters',original.parameters,'origin-device')};
    const origin={deviceId:'origin-device',ownerHash,supersededByDeviceId:'device'},canonical={deviceId:'device',ownerHash:'b'.repeat(64),ownerAliases:[ownerHash]};
    if(damage==='aliases')canonical.ownerAliases=[];if(damage==='supersession')origin.supersededByDeviceId='other-device';if(damage==='owner')delete origin.ownerHash;if(damage==='origin-identity')origin.deviceId='wrong-origin';
    await f.storage.put('app-device:origin-device',origin);await f.storage.put('app-device:device',canonical);await f.storage.put(f.key,[packed]);const before=f.state();
    if(damage){await assert.rejects(readCommandQueue(f.storage,f.key),legacyError);assert.equal(f.reads().some(key=>key.startsWith('app-v3-blob:')),false);}
    else assert.deepEqual(await readCommandQueue(f.storage,f.key),[original]);
    assert.deepEqual(f.state(),before);
  }
});

test('historic missing corrupt deep and UTF-8-oversize blobs reject without losing any queue bytes',async()=>{
  for(const damage of ['missing','type','short','json','depth','utf8','nested-pointer']){
    const f=fixture(),original=row(1);let value={text:'x'.repeat(2000)};
    if(damage==='depth'){value=true;for(let i=0;i<COMMAND_QUEUE_LIMITS.jsonDepth+1;i++)value={child:value};}
    if(damage==='nested-pointer')value={__nativePayloadVersion:1,originDeviceId:'foreign-device',commandId:'other',field:'parameters',parts:1,length:2000};
    // UTF-8 exceeds 2 MiB although UTF-16 length fits; stored parts remain <128 KiB.
    if(damage==='utf8')value={text:'🎨'.repeat(COMMAND_QUEUE_LIMITS.commandBytes/4+1)};
    original.parameters=await historicalField(f,original,'parameters',value);const key='app-v3-blob:device:command-1:parameters:0';
    if(damage==='missing')await f.storage.delete(key);if(damage==='type')await f.storage.put(key,{text:'not a string'});
    if(damage==='short')await f.storage.put(key,'{}');if(damage==='json')await f.storage.put(key,'!'.repeat(original.parameters.length));
    await f.storage.put(f.key,[original]);const before=f.state();
    await assert.rejects(readCommandQueue(f.storage,f.key),legacyError);await assert.rejects(rewrite(f),legacyError);assert.deepEqual(f.state(),before);
  }
});

test('historic hydration preserves the aggregate current queue budget and terminal result checksum',async()=>{
  const f=fixture(),packed=[];
  for(let n=1;n<=10;n++){const command=row(n,'queued','x'.repeat(1800000));packed.push({...command,parameters:await historicalField(f,command,'parameters',command.parameters)});}
  await f.storage.put(f.key,packed);const before=f.state();await assert.rejects(readCommandQueue(f.storage,f.key),legacyError);assert.deepEqual(f.state(),before);
  const g=fixture(),done=row(1,'completed');done.result={ok:true,text:'original-result'.repeat(1000)};done.resultSha256=await canonicalCommandResultSha256(done.result);
  const stored={...done,result:await historicalField(g,done,'result',done.result)};await g.storage.put(g.key,[stored]);
  const reloaded=(await readCommandQueue(g.storage,g.key))[0];assert.deepEqual(await terminalCommandUpdate(reloaded,done.result,'completed'),done);
  await g.storage.put('app-v3-blob:device:command-1:result:0',JSON.stringify(done.result).replace('original','modified'));const damaged=g.state();
  await assert.rejects(readCommandQueue(g.storage,g.key),/receipt checksum/);assert.deepEqual(g.state(),damaged);
});

test('current queue writes reject new legacy pointers while ordinary nested tag-shaped JSON remains data',async()=>{
  const f=fixture(),command=row(1);command.parameters=await historicalField(f,command,'parameters',{text:'x'.repeat(2000)});const before=f.state();
  assert.throws(()=>requireCommandQueueAdmission([],command),/Legacy native/);
  await assert.rejects(f.storage.transaction(transaction=>writeCommandQueueInTransaction(transaction,f.key,[command])),/Legacy native/);assert.deepEqual(f.state(),before);
  const ordinary=row(2);ordinary.parameters={metadata:{__nativePayloadVersion:1,originDeviceId:'not-a-pointer-at-field-root'}};await append(f,ordinary);assert.deepEqual((await readCommandQueue(f.storage,f.key))[0],ordinary);
});
