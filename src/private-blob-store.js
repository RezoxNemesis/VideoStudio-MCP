import {createHash} from 'node:crypto';

// The existing SQLite Durable Object is globally addressable. Cache API entries
// are POP-local and must never be the source of an acknowledged private upload.
export const PRIVATE_BLOB_LIMITS=Object.freeze({chunkBytes:64*1024,blobBytes:250*1024*1024,totalBytes:512*1024*1024,uploads:16,staging:2,ttlMs:20*60*1000,idleTimeoutMs:30000,uploadTimeoutMs:10*60*1000});
const legacyLedgerKey='private-blobs:ledger:v1',ledgerPrefix='private-blobs:ledger:v2:',cleanupCursorKey='private-blobs:cleanup-cursor:v2';
const manifestKey=id=>'private-blob:'+id;
const chunkKey=(id,index)=>'private-blob-chunk:'+id+':'+index;
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const ownerLedgerKey=deviceId=>ledgerPrefix+digest(new TextEncoder().encode(deviceId));
const failure=(message,status=400)=>Object.assign(new Error(message),{status});
const validId=id=>typeof id==='string'&&/^[a-f0-9-]{36}$/.test(id);
const binary=value=>value instanceof ArrayBuffer?new Uint8Array(value):ArrayBuffer.isView(value)?new Uint8Array(value.buffer,value.byteOffset,value.byteLength):null;
async function ledger(storage,key){
  const value=await storage.get(key);
  if(value===undefined)return [];
  if(!Array.isArray(value)||value.length>PRIVATE_BLOB_LIMITS.uploads||new Set(value.map(row=>row?.id)).size!==value.length||value.some(row=>!row||!validId(row.id)||typeof row.deviceId!=='string'||!row.deviceId||key!==legacyLedgerKey&&(ownerLedgerKey(row.deviceId)!==key||!['staging','ready'].includes(row.status))||!Number.isSafeInteger(row.reservedBytes)||row.reservedBytes<1||row.reservedBytes>PRIVATE_BLOB_LIMITS.blobBytes||!Number.isSafeInteger(row.expiresAt)))
    throw failure('Private upload quota proof is corrupt',503);
  return value;
}
function validateManifest(value){
  if(!value||value.version!==1||!validId(value.id)||typeof value.deviceId!=='string'||!value.deviceId||!/^[a-f0-9]{64}$/.test(value.ownerHash)
    ||!['staging','ready','deleting'].includes(value.status)||!Number.isSafeInteger(value.expiresAt)
    ||!Number.isSafeInteger(value.reservedBytes)||value.reservedBytes<1||value.reservedBytes>PRIVATE_BLOB_LIMITS.blobBytes
    ||typeof value.name!=='string'||value.name.length>180||typeof value.mime!=='string'||value.mime.length>120||/[\r\n]/.test(value.mime))
    throw failure('Private upload manifest is corrupt',503);
  if(value.status==='ready'&&(!Number.isSafeInteger(value.size)||value.size<1||value.size>value.reservedBytes
    ||value.chunks!==Math.ceil(value.size/PRIVATE_BLOB_LIMITS.chunkBytes)||!/^[a-f0-9]{64}$/.test(value.sha256)))
    throw failure('Private upload content proof is corrupt',503);
  if(value.quotaKey!==undefined&&value.quotaKey!==ownerLedgerKey(value.deviceId))throw failure('Private upload owner quota proof is corrupt',503);
  return value;
}

// Keep the reservation until all chunks are removed. A crash during deletion is
// resumed by the alarm instead of leaving unaccounted persistent private bytes.
export async function deletePrivateBlob(storage,id,quotaKey){
  if(!validId(id))throw failure('Invalid private upload identity');
  const reservation=await storage.transaction(async transaction=>{
    const manifest=await transaction.get(manifestKey(id));
    const key=quotaKey||manifest?.quotaKey||legacyLedgerKey;
    const rows=await ledger(transaction,key),row=rows.find(item=>item.id===id);
    if(!row)return null;
    if(manifest?.quotaKey&&manifest.quotaKey!==key)throw failure('Private upload reservation differs from owner quota',503);
    if(manifest){validateManifest(manifest);await transaction.put(manifestKey(id),{...manifest,status:'deleting'});}
    return {...row,quotaKey:key};
  });
  if(!reservation)return;
  const chunks=Math.ceil(reservation.reservedBytes/PRIVATE_BLOB_LIMITS.chunkBytes);
  for(let index=0;index<chunks;index+=64)
    await storage.delete(Array.from({length:Math.min(64,chunks-index)},(_,offset)=>chunkKey(id,index+offset)));
  await storage.transaction(async transaction=>{
    await transaction.delete(manifestKey(id));
    const rows=(await ledger(transaction,reservation.quotaKey)).filter(row=>row.id!==id);
    if(rows.length)await transaction.put(reservation.quotaKey,rows);else await transaction.delete(reservation.quotaKey);
    // Source URL handoffs use the same key but never share this random identity.
    await transaction.delete('app-handoff:'+reservation.deviceId+':'+id);
  });
}

export async function cleanupPrivateBlobs(storage,current=Date.now(),quotaKey){
  const clean=async key=>{
    for(const row of await ledger(storage,key))if(row.expiresAt<=current)await deletePrivateBlob(storage,row.id,key);
    const rows=await ledger(storage,key);
    return rows.length?Math.min(...rows.map(row=>row.expiresAt)):null;
  };
  if(quotaKey)return clean(quotaKey);
  let next=await clean(legacyLedgerKey);
  const cursor=await storage.get(cleanupCursorKey);
  if(cursor?.nextExpiry!==null&&Number.isSafeInteger(cursor?.nextExpiry))next=next===null?cursor.nextExpiry:Math.min(next,cursor.nextExpiry);
  const page=await storage.list({prefix:ledgerPrefix,limit:64,...(cursor?.after?{startAfter:cursor.after}:{})});
  for(const key of page.keys()){
    const expiry=await clean(key);if(expiry!==null)next=next===null?expiry:Math.min(next,expiry);
  }
  if(page.size===64){
    await storage.put(cleanupCursorKey,{after:[...page.keys()].at(-1),nextExpiry:next});
    return Math.min(next??Infinity,current+1000);
  }
  await storage.delete(cleanupCursorKey);
  return next;
}

/** Streams bounded values; only the final transaction publishes bytes + command. */
export async function receivePrivateBlob(storage,body,options,publish){
  if(!body)throw failure('A private upload body is required');
  const declared=options.declaredSize;
  if(!Number.isSafeInteger(declared)||declared<0)throw failure('Invalid Content-Length');
  if(declared>PRIVATE_BLOB_LIMITS.blobBytes)throw failure('Upload exceeds 250 MB private relay limit',413);
  if(options.expectedSha256&&!/^[a-f0-9]{64}$/.test(options.expectedSha256))throw failure('Invalid upload SHA-256');
  const idleTimeoutMs=options.idleTimeoutMs??PRIVATE_BLOB_LIMITS.idleTimeoutMs,uploadTimeoutMs=options.uploadTimeoutMs??PRIVATE_BLOB_LIMITS.uploadTimeoutMs;
  if(!Number.isSafeInteger(idleTimeoutMs)||idleTimeoutMs<1||idleTimeoutMs>PRIVATE_BLOB_LIMITS.idleTimeoutMs||!Number.isSafeInteger(uploadTimeoutMs)||uploadTimeoutMs<1||uploadTimeoutMs>PRIVATE_BLOB_LIMITS.uploadTimeoutMs)throw failure('Invalid private upload deadline');
  if(options.signal?.aborted)throw failure('Private upload cancelled',499);
  const quotaKey=ownerLedgerKey(options.deviceId),uploadDeadline=Date.now()+uploadTimeoutMs;
  await cleanupPrivateBlobs(storage,Date.now(),quotaKey);
  const id=crypto.randomUUID(),reservedBytes=declared||PRIVATE_BLOB_LIMITS.blobBytes;
  const staging=validateManifest({version:1,id,deviceId:options.deviceId,ownerHash:options.ownerHash,name:options.name,mime:options.mime,
    quotaKey,status:'staging',reservedBytes,expiresAt:Date.now()+PRIVATE_BLOB_LIMITS.ttlMs});
  await storage.transaction(async transaction=>{
    const rows=await ledger(transaction,quotaKey);
    if(rows.length>=PRIVATE_BLOB_LIMITS.uploads||rows.reduce((total,row)=>total+row.reservedBytes,0)+reservedBytes>PRIVATE_BLOB_LIMITS.totalBytes)
      throw failure('Private upload capacity is full; wait for existing handoffs to expire',429);
    if(rows.filter(row=>row.status==='staging').length>=PRIVATE_BLOB_LIMITS.staging)throw failure('Private upload concurrency limit reached for this owner',429);
    // Reservation and cleanup wakeup commit together, including the first upload
    // after an empty object. A crash cannot leave staged quota without an alarm.
    const previous=await transaction.getAlarm();
    if(previous===null||previous>staging.expiresAt)await transaction.setAlarm(staging.expiresAt);
    await transaction.put(manifestKey(id),staging);
    await transaction.put(quotaKey,[...rows,{id,deviceId:options.deviceId,status:'staging',reservedBytes,expiresAt:staging.expiresAt}]);
  });
  const reader=body.getReader(),hasher=createHash('sha256');
  let total=0,index=0,used=0,buffer=new Uint8Array(PRIVATE_BLOB_LIMITS.chunkBytes);
  let lastProgressAt=Date.now();
  const checkDeadline=()=>{
    if(options.signal?.aborted)throw failure('Private upload cancelled',499);
    if(Date.now()>=uploadDeadline)throw failure('Private upload total deadline exceeded',408);
  };
  const read=async()=>{
    checkDeadline();let timeout,abort;
    const remainingIdle=idleTimeoutMs-(Date.now()-lastProgressAt),remainingTotal=uploadDeadline-Date.now();
    if(remainingIdle<=0)throw failure('Private upload body stalled',408);
    try{return await Promise.race([reader.read(),new Promise((_,reject)=>{
      timeout=setTimeout(()=>reject(failure(remainingTotal<=remainingIdle?'Private upload total deadline exceeded':'Private upload body stalled',408)),Math.min(remainingIdle,remainingTotal));
      abort=()=>reject(failure('Private upload cancelled',499));options.signal?.addEventListener('abort',abort,{once:true});
      if(options.signal?.aborted)abort();
    })]);}finally{clearTimeout(timeout);if(abort)options.signal?.removeEventListener('abort',abort);}
  };
  const flush=async()=>{
    checkDeadline();
    const bytes=buffer.slice(0,used);
    await storage.transaction(async transaction=>{
      const current=await transaction.get(manifestKey(id));
      if(!current||current.status!=='staging'||current.expiresAt<=Date.now())throw failure('Private upload expired during transfer',410);
      await transaction.put(chunkKey(id,index),{bytes,sha256:digest(bytes)});
    });
    index++;
    used=0;
  };
  try{
    while(true){
      const item=await read();if(item.done)break;
      const bytes=binary(item.value);if(!bytes)throw failure('Invalid private upload stream');
      if(bytes.byteLength)lastProgressAt=Date.now();
      total+=bytes.byteLength;
      if(total>reservedBytes||total>PRIVATE_BLOB_LIMITS.blobBytes)throw failure('Private upload exceeds its declared or maximum byte limit',413);
      hasher.update(bytes);
      for(let offset=0;offset<bytes.byteLength;){
        const length=Math.min(buffer.byteLength-used,bytes.byteLength-offset);
        buffer.set(bytes.subarray(offset,offset+length),used);used+=length;offset+=length;
        if(used===buffer.byteLength)await flush();
      }
    }
    if(!total||declared&&total!==declared)throw failure('Private upload length differs from Content-Length');
    if(used)await flush();
    const sha256=hasher.digest('hex');
    if(options.expectedSha256&&sha256!==options.expectedSha256)throw failure('Private upload SHA-256 differs from supplied content proof',422);
    const ready={...staging,status:'ready',reservedBytes:total,size:total,chunks:index,sha256};
    const result=await storage.transaction(async transaction=>{
      checkDeadline();
      const current=await transaction.get(manifestKey(id));
      if(!current||current.status!=='staging'||current.expiresAt<=Date.now())throw failure('Private upload expired before publication',410);
      const result=await publish(transaction,ready);
      checkDeadline();
      await transaction.put(manifestKey(id),ready);
      await transaction.put(quotaKey,(await ledger(transaction,quotaKey)).map(row=>row.id===id?{...row,status:'ready',reservedBytes:total}:row));
      return result;
    });
    return {manifest:ready,result};
  }catch(error){
    // An uncooperative source's cancel promise must not hold its quota hostage.
    void reader.cancel(error).catch(()=>{});
    await deletePrivateBlob(storage,id,quotaKey);
    throw error;
  }finally{reader.releaseLock();}
}

/** Each chunk is verified before release; completion also verifies the whole SHA. */
export async function privateBlobResponse(storage,id,deviceId){
  if(!validId(id))throw failure('Invalid private upload identity');
  const original=await storage.get(manifestKey(id));
  if(!original)return null;
  const manifest=validateManifest(original);
  if(manifest.deviceId!==deviceId||manifest.status!=='ready'||manifest.expiresAt<=Date.now())return null;
  let index=0;const hasher=createHash('sha256');
  const body=new ReadableStream({async pull(controller){
    try{
      if(manifest.expiresAt<=Date.now())throw failure('Private upload expired during download',410);
      const part=await storage.get(chunkKey(id,index)),bytes=binary(part?.bytes);
      const expected=Math.min(PRIVATE_BLOB_LIMITS.chunkBytes,manifest.size-index*PRIVATE_BLOB_LIMITS.chunkBytes);
      if(!bytes||bytes.byteLength!==expected||part.sha256!==digest(bytes))throw failure('Private upload chunk is missing or corrupt',503);
      hasher.update(bytes);index++;
      if(index===manifest.chunks&&hasher.digest('hex')!==manifest.sha256)throw failure('Private upload content checksum differs from its durable proof',503);
      controller.enqueue(bytes);
      if(index===manifest.chunks)controller.close();
    }catch(error){controller.error(error);}
  }},{highWaterMark:0});
  return new Response(body,{headers:{'content-type':manifest.mime||'application/octet-stream','content-length':String(manifest.size),
    'cache-control':'private, no-store','x-content-type-options':'nosniff','x-content-sha256':manifest.sha256,'etag':'"'+manifest.sha256+'"',
    'content-disposition':'attachment; filename="'+manifest.name.replace(/[\r\n"\\]/g,'_')+'"'}});
}
