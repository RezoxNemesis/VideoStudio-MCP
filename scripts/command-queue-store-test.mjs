import {test} from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {readCommandQueue,mutateCommandQueue,requireCommandQueueAdmission,COMMAND_QUEUE_LIMITS,terminalCommandUpdate,readCommandReceipt,acknowledgePrunedCommand,canonicalCommandResultSha256,mergeCommandReceiptsInTransaction,writeCommandQueueInTransaction,readCommandQueueInTransaction} from '../src/command-queue-store.js';
if(!globalThis.crypto)globalThis.crypto=webcrypto;

// Authored future regressions, unrun during product implementation. Serializable
// transactions with rollback; no Worker deployment or network is involved.
function fixture(initial=[]){
 let values=new Map([['app-v3-cl:device',structuredClone(initial)]]),tail=Promise.resolve(),failWrites=false;
 const storage={async get(key){return structuredClone(values.get(key));},
  async put(key,value){values.set(key,structuredClone(value));},async delete(key){return values.delete(key);},
  async list({prefix,limit}){return new Map([...values].filter(([key])=>key.startsWith(prefix)).slice(0,limit));},
  async transaction(callback){const prior=tail;let release;tail=new Promise(resolve=>release=resolve);await prior;
   const draft=structuredClone(values),transaction={async get(key){return structuredClone(draft.get(key));},
    async put(key,value){if(failWrites&&key.includes(':chunks:'))throw new Error('simulated chunk storage failure');draft.set(key,structuredClone(value));},
    async list({prefix,limit}){return new Map([...draft].filter(([key])=>key.startsWith(prefix)).slice(0,limit));},
    async delete(key){return draft.delete(key);}};
   try{const result=await callback(transaction);values=draft;return result;}finally{release();}}
 };
 return {storage,values:()=>values,fail:()=>{failWrites=true;},key:'app-v3-cl:device'};
}
const row=(seq,status='queued',text='')=>({id:'command-'+seq,seq,status,action:'cel_create',parameters:{drawing:{text}},result:status==='queued'||status==='claimed'?null:{ok:true},completedAt:status==='queued'||status==='claimed'?null:new Date(Date.UTC(2026,0,1)+seq).toISOString()});
const append=(f,next)=>mutateCommandQueue(f.storage,f.key,rows=>{requireCommandQueueAdmission(rows,next);rows.push(next);return{rows,result:next};});

test('large UTF-8 drawing command migrates atomically to bounded chunks with identical public JSON',async()=>{
 const f=fixture([row(1,'queued','🎨'.repeat(45000))]);
 await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
 const read=await readCommandQueue(f.storage,f.key);
 assert.equal(read[0].parameters.drawing.text,'🎨'.repeat(45000));
 assert.equal(await f.storage.get(f.key),undefined);
 const chunks=[...f.values()].filter(([key])=>key.includes(':chunks:'));
 assert.ok(chunks.length>=3);assert.ok(chunks.every(([,bytes])=>bytes.byteLength<=64*1024));
});

test('interrupted migration preserves the whole legacy array and no partial versioned queue',async()=>{
 const f=fixture([row(1)]);f.fail();
 await assert.rejects(append(f,row(2)),/storage failure/);
 assert.deepEqual(await f.storage.get(f.key),[row(1)]);
 assert.equal(await f.storage.get(f.key+':manifest:v1'),undefined);
 assert.equal([...f.values().keys()].some(key=>key.includes(':chunks:')),false);
});

test('concurrent mutations retain both accepted commands and replace only the old chunk generation',async()=>{
 const f=fixture();await Promise.all([append(f,row(1)),append(f,row(2))]);
 const read=await readCommandQueue(f.storage,f.key);assert.deepEqual(read.map(item=>item.id),['command-1','command-2']);
 const manifest=await f.storage.get(f.key+':manifest:v1');
 assert.equal([...f.values().keys()].filter(key=>key.includes(':chunks:')).length,manifest.chunks);
});

test('legacy active overflow is preserved for draining and new admission refuses without dropping work',async()=>{
 const f=fixture(Array.from({length:80},(_,i)=>row(i+1)));
 await mutateCommandQueue(f.storage,f.key,rows=>{rows[0].status='claimed';return{rows,result:null};});
 assert.equal((await readCommandQueue(f.storage,f.key)).length,80);
 await assert.rejects(append(f,row(81)),/queue is full/);
 assert.equal((await readCommandQueue(f.storage,f.key)).length,80);
});

test('terminal pruning keeps every older active command at the 64-command bound',async()=>{
 const f=fixture([row(1),...Array.from({length:100},(_,i)=>row(i+2,'completed'))]);
 await append(f,row(102));const read=await readCommandQueue(f.storage,f.key);
 assert.equal(read.length,64);assert.ok(read.some(item=>item.id==='command-1'));assert.ok(read.some(item=>item.id==='command-102'));
});

test('missing chunk, checksum mismatch and missing manifest all fail closed before mutation',async()=>{
 for(const damage of ['missing','changed','manifest']){
  const f=fixture([row(1)]);await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
  const manifest=await f.storage.get(f.key+':manifest:v1'),chunk=f.key+':chunks:v1:'+manifest.generation+':0';
  if(damage==='missing')await f.storage.delete(chunk);
  if(damage==='changed')await f.storage.put(chunk,new Uint8Array(manifest.bytes).fill(1));
  if(damage==='manifest')await f.storage.delete(f.key+':manifest:v1');
  await assert.rejects(readCommandQueue(f.storage,f.key),/missing|checksum/);
  await assert.rejects(append(f,row(2)),/missing|checksum/);
 }
});

test('mixed old-worker legacy writes and new manifest retain both ledgers and require explicit recovery',async()=>{
 const f=fixture([row(1)]);await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
 await f.storage.put(f.key,[row(2)]);
 await assert.rejects(readCommandQueue(f.storage,f.key),/Mixed legacy/);
 assert.deepEqual(await f.storage.get(f.key),[row(2)]);assert.ok(await f.storage.get(f.key+':manifest:v1'));
});

test('individual JSON and aggregate active budgets reject before an atomic replacement',async()=>{
 const f=fixture([row(1)]);
 await assert.rejects(append(f,row(2,'queued','x'.repeat(COMMAND_QUEUE_LIMITS.commandBytes))),/512 KiB/);
 assert.equal((await readCommandQueue(f.storage,f.key)).length,1);
 const aggregate=fixture(Array.from({length:8},(_,i)=>row(i+1,'queued','x'.repeat(480*1024))));
 await mutateCommandQueue(aggregate.storage,aggregate.key,rows=>({rows,result:null}));
 await assert.rejects(append(aggregate,row(9,'queued','x'.repeat(480*1024))),/result-reserved JSON byte budget/);
 assert.equal((await readCommandQueue(aggregate.storage,aggregate.key)).length,8);
});

test('legacy drain retains a compact proof for a lost ack after the next claim prunes its result',async()=>{
 const f=fixture(Array.from({length:160},(_,i)=>row(i+1)));
 const result={ok:true,outputUri:'content://private/visible/42',contactSheet:{base64:'private-media',mimeType:'image/jpeg'}};
 await mutateCommandQueue(f.storage,f.key,async rows=>{rows[0]=await terminalCommandUpdate(rows[0],result,'completed');return{rows,result:rows[0]};});
 assert.equal((await readCommandQueue(f.storage,f.key)).length,160);
 await mutateCommandQueue(f.storage,f.key,rows=>{rows[1].status='claimed';return{rows,result:null};});
 assert.equal((await readCommandQueue(f.storage,f.key)).some(item=>item.id==='command-1'),false);
 const receipt=await readCommandReceipt(f.storage,f.key,'command-1');
 assert.equal(receipt.resultExpired,true);assert.match(receipt.resultSha256,/^[a-f0-9]{64}$/);
 assert.equal(JSON.stringify(receipt).includes('content://'),false);assert.equal(JSON.stringify(receipt).includes('private-media'),false);
 const replay=await f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',{contactSheet:{mimeType:'image/jpeg',base64:'private-media'},outputUri:'content://private/visible/42',ok:true},'completed'));
 assert.equal(replay.id,'command-1');assert.equal(replay.resultReplayed,true);assert.deepEqual(replay.result,result);
 await assert.rejects(f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',{ok:false},'completed')),/Conflicting/);
 await assert.rejects(f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,f.key,'command-1',result,'failed')),/Conflicting/);
 assert.equal(await readCommandReceipt(f.storage,f.key,'unknown'),null);
});

test('canonical terminal replay ignores object key order and never reopens terminal work',async()=>{
 const accepted=row(1),result={z:[{b:2,a:1}],a:true};
 const terminal=await terminalCommandUpdate(accepted,result,'completed');
 const replay=await terminalCommandUpdate(terminal,{a:true,z:[{a:1,b:2}]},'completed');
 assert.equal(replay.completedAt,terminal.completedAt);assert.equal(replay.resultSha256,terminal.resultSha256);
 assert.equal(await canonicalCommandResultSha256(result),await canonicalCommandResultSha256({a:true,z:[{a:1,b:2}]}));
 await assert.rejects(terminalCommandUpdate(terminal,result,'queued'),/known terminal/);
 await assert.rejects(terminalCommandUpdate(terminal,{a:true,z:[{a:2,b:1}]},'completed'),/Conflicting/);
});

test('near-limit immutable request can claim and complete with separate bounded result/proof metadata',async()=>{
 const f=fixture([row(1,'queued','x'.repeat(COMMAND_QUEUE_LIMITS.commandBytes-512))]);
 await mutateCommandQueue(f.storage,f.key,rows=>{rows[0]={...rows[0],status:'claimed',claimedAt:new Date().toISOString(),leaseUntil:Date.now()+60000,claimCount:1};return{rows,result:null};});
 await mutateCommandQueue(f.storage,f.key,async rows=>{rows[0]=await terminalCommandUpdate(rows[0],{ok:true,data:'y'.repeat(1024)},'completed');return{rows,result:rows[0]};});
 const completed=(await readCommandQueue(f.storage,f.key))[0];assert.equal(completed.status,'completed');assert.match(completed.resultSha256,/^[a-f0-9]{64}$/);
});

test('compacted receipt ledger remains bounded and keeps the newest terminal proofs',async()=>{
 const f=fixture(Array.from({length:160},(_,i)=>row(i+1,'completed')));
 await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
 for(let seq=161;seq<=340;seq++)await mutateCommandQueue(f.storage,f.key,rows=>{rows.push(row(seq,'completed'));return{rows,result:null};});
 const ledger=await f.storage.get(f.key+':terminal-receipts:v1');
 assert.ok(ledger.records.length<=COMMAND_QUEUE_LIMITS.receipts);
 assert.ok(new TextEncoder().encode(JSON.stringify(ledger)).byteLength<=COMMAND_QUEUE_LIMITS.receiptBytes);
 assert.equal(await readCommandReceipt(f.storage,f.key,'command-1'),null);
 assert.ok(await readCommandReceipt(f.storage,f.key,'command-275'));
});

test('unknown or damaged compact receipt evidence blocks mutation and is never replaced as empty',async()=>{
 for(const damage of ['checksum','missing','lost-pair','version']){
  const f=fixture(Array.from({length:100},(_,i)=>row(i+1,'completed')));
  await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
  const key=f.key+':terminal-receipts:v1',ledger=await f.storage.get(key);
  if(damage==='checksum'){ledger.sha256='0'.repeat(64);await f.storage.put(key,ledger);}
  if(damage==='missing')await f.storage.delete(key);
  if(damage==='lost-pair'){await f.storage.delete(key);await f.storage.delete(f.key+':terminal-receipts:format');}
  if(damage==='version'){ledger.version=2;await f.storage.put(key,ledger);}
  const manifest=await f.storage.get(f.key+':manifest:v1');
  await assert.rejects(readCommandReceipt(f.storage,f.key,'command-1'),/receipt ledger/);
  await assert.rejects(append(f,row(101)),/receipt ledger/);
  assert.deepEqual(await f.storage.get(f.key+':manifest:v1'),manifest);
 }
});

test('chunk failure rolls back receipt compaction and preserves the original legacy commands',async()=>{
 const original=Array.from({length:100},(_,i)=>row(i+1,'completed')),f=fixture(original);f.fail();
 await assert.rejects(mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null})),/storage failure/);
 assert.deepEqual(await f.storage.get(f.key),original);
 assert.equal(await f.storage.get(f.key+':terminal-receipts:v1'),undefined);
 assert.equal(await f.storage.get(f.key+':terminal-receipts:format'),undefined);
});

test('terminal compact proof prevents a migration from resurrecting an already completed command',async()=>{
 const f=fixture(Array.from({length:100},(_,i)=>row(i+1,'completed')));
 await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
 const original=(await readCommandQueue(f.storage,f.key)).map(item=>item.id);
 await assert.rejects(append(f,{...row(101),id:'command-1'}),/prohibits reopening/);
 assert.deepEqual((await readCommandQueue(f.storage,f.key)).map(item=>item.id),original);
});

test('a syntactically valid cached digest cannot authorize a changed retained result at any proof boundary',async()=>{
 const terminal=await terminalCommandUpdate(row(1),{ok:true},'completed');
 const stale={...terminal,result:{ok:false}};
 await assert.rejects(terminalCommandUpdate(stale,{ok:true},'completed'),/receipt checksum/);
 const compaction=fixture([stale,...Array.from({length:99},(_,index)=>row(index+2,'completed'))]);
 await assert.rejects(mutateCommandQueue(compaction.storage,compaction.key,rows=>({rows,result:null})),/receipt checksum/);
 assert.deepEqual((await compaction.storage.get(compaction.key))[0],stale);
 assert.equal(await compaction.storage.get(compaction.key+':terminal-receipts:v1'),undefined);
 const known=fixture(Array.from({length:100},(_,index)=>row(index+1,'completed')));
 await mutateCommandQueue(known.storage,known.key,rows=>({rows,result:null}));
 const receipt=await readCommandReceipt(known.storage,known.key,'command-1');
 const manifest=await known.storage.get(known.key+':manifest:v1');
 await assert.rejects(mutateCommandQueue(known.storage,known.key,rows=>{
  rows.push({...row(1,'completed'),result:{ok:false},resultSha256:receipt.resultSha256});return{rows,result:null};
 }),/receipt checksum/);
 assert.deepEqual(await known.storage.get(known.key+':manifest:v1'),manifest);
 assert.deepEqual(await readCommandReceipt(known.storage,known.key,'command-1'),receipt);
});

test('depth, cycles and non-JSON numbers reject before canonical or queue serialization and preserve legacy bytes',async()=>{
 const nested=depth=>{let value=true;for(let index=0;index<depth;index++)value={child:value};return value;};
 await canonicalCommandResultSha256(nested(COMMAND_QUEUE_LIMITS.jsonDepth));
 await assert.rejects(canonicalCommandResultSha256(nested(COMMAND_QUEUE_LIMITS.jsonDepth+1)),/nesting depth/);
 const accepted=fixture([row(1)]),tooDeep={...row(2),parameters:nested(COMMAND_QUEUE_LIMITS.jsonDepth)};
 await assert.rejects(append(accepted,tooDeep),/nesting depth/);
 assert.deepEqual(await accepted.storage.get(accepted.key),[row(1)]);
 const legacy=fixture([tooDeep]);
 await assert.rejects(readCommandQueue(legacy.storage,legacy.key),/nesting depth/);
 await assert.rejects(mutateCommandQueue(legacy.storage,legacy.key,rows=>({rows,result:null})),/nesting depth/);
 assert.deepEqual(await legacy.storage.get(legacy.key),[tooDeep]);
 assert.equal(await legacy.storage.get(legacy.key+':manifest:v1'),undefined);
 const cycle={};cycle.self=cycle;
 await assert.rejects(canonicalCommandResultSha256(cycle),/cycle/);
 for(const invalid of [NaN,Infinity,-Infinity,1n,()=>true,new Date()])
  await assert.rejects(canonicalCommandResultSha256({invalid}),/finite|plain JSON|plain objects/);
 await assert.rejects(canonicalCommandResultSha256(undefined),/plain JSON/);
 await assert.rejects(canonicalCommandResultSha256([undefined]),/plain JSON/);
 const valid={flag:true,text:'false',number:1.5,array:[null,0,false]};
 assert.equal(await canonicalCommandResultSha256(valid),await canonicalCommandResultSha256({array:[null,0,false],number:1.5,text:'false',flag:true}));
 const terminal=await terminalCommandUpdate(row(1),nested(COMMAND_QUEUE_LIMITS.jsonDepth),'completed'),boundary=fixture([terminal]);
 await mutateCommandQueue(boundary.storage,boundary.key,rows=>({rows,result:null}));
 assert.deepEqual((await readCommandQueue(boundary.storage,boundary.key))[0].result,terminal.result);
});

test('ordinary undefined optional object properties are omitted without blocking default MCP commands',async()=>{
 const f=fixture(),command={...row(1),parameters:{projectId:'project',expectedRevision:undefined,frameRate:undefined,includeAudio:true,options:{codec:undefined}}};
 await append(f,command);
 assert.deepEqual((await readCommandQueue(f.storage,f.key))[0].parameters,{projectId:'project',includeAudio:true,options:{}});
 assert.equal(await canonicalCommandResultSha256({ok:true,optional:undefined}),await canonicalCommandResultSha256({ok:true}));
});

test('alias receipt migration preserves old lost-ack proof and chooses canonical accepted sequence',async()=>{
 const f=fixture(Array.from({length:100},(_,index)=>row(index+1,'completed'))),target='app-v3-cl:canonical';
 await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
 const original=await readCommandReceipt(f.storage,f.key,'command-1');
 await f.storage.put(target,[{...row(1,'completed'),seq:500}]);
 const migrated=await f.storage.transaction(async transaction=>{
  const receipt=await mergeCommandReceiptsInTransaction(transaction,target,f.key);
  const rows=await readCommandQueueInTransaction(transaction,target);
  await writeCommandQueueInTransaction(transaction,target,rows,new Set(rows.map(row=>row.id)));
  return receipt;
 });
 assert.equal(migrated.highestSeq,500);
 assert.equal((await readCommandReceipt(f.storage,target,'command-1')).seq,500);
 assert.deepEqual(await readCommandReceipt(f.storage,f.key,'command-1'),original);
 const replay=await f.storage.transaction(transaction=>acknowledgePrunedCommand(transaction,target,'command-2',{ok:true},'completed'));
 assert.equal(replay.id,'command-2');assert.equal(replay.resultReplayed,true);
});

test('alias receipt migration rejects active IDs, changed proof and proof-budget overflow without losing either ledger',async()=>{
 for(const conflict of ['active','result','metadata','overflow']){
  const f=fixture(Array.from({length:100},(_,index)=>row(index+1,'completed'))),target='app-v3-cl:canonical';
  await mutateCommandQueue(f.storage,f.key,rows=>({rows,result:null}));
  if(conflict==='overflow'){
   await f.storage.put(target,Array.from({length:160},(_,index)=>row(index+201,'completed')));
   await mutateCommandQueue(f.storage,target,rows=>({rows,result:null}));
   for(let seq=361;seq<=425;seq++)await mutateCommandQueue(f.storage,target,rows=>{rows.push(row(seq,'completed'));return{rows,result:null};});
  }else await f.storage.put(target,[conflict==='active'?row(1):{...row(1,'completed'),...(conflict==='result'?{result:{ok:false}}:{action:'different_action'})}]);
  const before=structuredClone(f.values());
  await assert.rejects(f.storage.transaction(transaction=>mergeCommandReceiptsInTransaction(transaction,target,f.key)),/conflicts|proof budget/);
  assert.deepEqual(f.values(),before);
 }
});
