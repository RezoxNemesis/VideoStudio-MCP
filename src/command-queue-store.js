// Durable JSON commands only. Attachment bytes never enter this store.
export const COMMAND_QUEUE_LIMITS=Object.freeze({chunkBytes:64*1024,commandBytes:512*1024,resultBytes:512*1024,metadataBytes:8*1024,totalBytes:4*1024*1024,commands:64,legacyCommands:160,receiptBytes:64*1024,receipts:160,jsonDepth:64});
const encoder=new TextEncoder(),decoder=new TextDecoder("utf-8",{fatal:true});
const pending=new Set(["queued","claimed","waiting_native","running"]);
export const commandIsPending=command=>pending.has(command?.status);
const manifestKey=key=>key+":manifest:v1";
const formatKey=key=>key+":format";
const receiptKey=key=>key+":terminal-receipts:v1";
const receiptFormatKey=key=>key+":terminal-receipts:format";
const chunkKey=(key,generation,index)=>key+":chunks:v1:"+generation+":"+index;
const digest=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes)),byte=>byte.toString(16).padStart(2,"0")).join("");
// Validate without recursion or JSON coercion. A bounded depth does not alone
// prevent cycles or exponentially expanded shared subtrees, so visits are also
// bounded by the largest queue byte budget. Each stack frame advances one child.
function requireBoundedJson(value,maximumDepth=COMMAND_QUEUE_LIMITS.jsonDepth){
  const stack=[],active=new WeakSet();let visited=0;
  const visit=(item,parentDepth)=>{
    if(++visited>COMMAND_QUEUE_LIMITS.totalBytes)throw new Error("Command JSON exceeds its bounded value count");
    if(item===null||typeof item==="string"||typeof item==="boolean")return;
    if(typeof item==="number"){
      if(!Number.isFinite(item))throw new Error("Command JSON numbers must be finite; values cannot be silently coerced");
      return;
    }
    if(typeof item!=="object")throw new Error("Command JSON requires only plain JSON values");
    const depth=parentDepth+1;
    if(depth>maximumDepth)throw new Error("Command JSON exceeds the bounded 64-level nesting depth");
    if(active.has(item))throw new Error("Command JSON contains a cycle");
    const array=Array.isArray(item),prototype=Object.getPrototypeOf(item);
    if(array?prototype!==Array.prototype:prototype!==Object.prototype&&prototype!==null)
      throw new Error("Command JSON requires plain objects and arrays");
    const hook=Object.getOwnPropertyDescriptor(item,"toJSON");
    if(Object.getOwnPropertySymbols(item).length||(hook&&(!Object.hasOwn(hook,"value")||typeof hook.value==="function")))
      throw new Error("Command JSON cannot contain serialization hooks or symbol properties");
    const keys=array?null:Object.keys(item),length=array?item.length:keys.length;
    if(length>COMMAND_QUEUE_LIMITS.totalBytes-visited)throw new Error("Command JSON exceeds its bounded value count");
    if(array&&Object.keys(item).length!==length)throw new Error("Command JSON arrays must contain only dense indexed values");
    active.add(item);stack.push({item,array,keys,length,index:0,depth});
  };
  visit(value,0);
  while(stack.length){
    const frame=stack[stack.length-1];
    if(frame.index===frame.length){active.delete(frame.item);stack.pop();continue;}
    const key=frame.array?String(frame.index++):frame.keys[frame.index++];
    const descriptor=Object.getOwnPropertyDescriptor(frame.item,key);
    if(!descriptor||!Object.hasOwn(descriptor,"value"))throw new Error("Command JSON cannot contain sparse arrays or accessor properties");
    // Internally authored optional object properties use ordinary JSON omission.
    // HTTP JSON cannot carry undefined. Top-level/array undefined still rejects.
    if(!frame.array&&descriptor.value===undefined)continue;
    visit(descriptor.value,frame.depth);
  }
}
// A queue array and its command row are the two known wrappers around a result.
// Requests/results receive their stricter 64-level check in validateRows below.
const jsonOf=value=>{requireBoundedJson(value,COMMAND_QUEUE_LIMITS.jsonDepth+2);return JSON.stringify(value);};
const bytesOf=value=>encoder.encode(jsonOf(value));
const canonical=(value,depth=0)=>{
  if(depth>COMMAND_QUEUE_LIMITS.jsonDepth)throw new Error("Command result exceeds the bounded canonical JSON depth");
  return Array.isArray(value)?value.map(item=>canonical(item,depth+1)):value&&typeof value==="object"?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key],depth+1)])):value;
};
const terminalStatuses=new Set(["completed","failed","cancelled","denied","expired"]);
const mutableFields=new Set(["status","completedAt","leaseUntil","claimedAt","claimCount","waitingReason","resultSha256"]);
const immutableRequest=row=>Object.fromEntries(Object.entries(row).filter(([key])=>key!=="result"&&!mutableFields.has(key)));
const safeIdentifier=value=>typeof value==="string"&&/^[A-Za-z0-9_.:-]{1,128}$/.test(value);
const validDigest=value=>typeof value==="string"&&/^[a-f0-9]{64}$/.test(value);
const validTimestamp=value=>typeof value==="string"&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
  &&Number.isFinite(Date.parse(value))&&new Date(value).toISOString()===value;

/** SHA of UTF-8 JSON with recursively sorted object keys and original array order. */
export async function canonicalCommandResultSha256(result){
  requireBoundedJson(result);
  if(result===undefined||bytesOf(result).byteLength>COMMAND_QUEUE_LIMITS.resultBytes)
    throw new Error("One command result exceeds its bounded JSON receipt budget");
  return digest(bytesOf(canonical(result)));
}

// A generation checksum proves serialized bytes, not that an independently
// cached result digest still describes those bytes. Verify the retained payload
// before it can authorize a replay or become locator-free compact proof.
async function retainedCommandResultSha256(command){
  const actual=await canonicalCommandResultSha256(command.result);
  if(command.resultSha256!==undefined&&command.resultSha256!==actual)
    throw new Error("Retained command result does not match its receipt checksum; original proof is retained");
  return actual;
}

export async function terminalCommandUpdate(command,result,status,parameters=command.parameters){
  if(!terminalStatuses.has(status))throw new Error("Command acknowledgement requires a known terminal status");
  const resultSha256=await canonicalCommandResultSha256(result);
  if(!commandIsPending(command)){
    const original=await retainedCommandResultSha256(command);
    if(!validDigest(original)||command.status!==status||original!==resultSha256)
      throw new Error("Conflicting terminal command acknowledgement was rejected; the original receipt is retained");
    return {...command,resultSha256:original};
  }
  return {...command,parameters,status,completedAt:new Date().toISOString(),leaseUntil:0,result,resultSha256};
}

function validateReceipt(record){
  requireBoundedJson(record);
  const allowed=new Set(["id","seq","action","status","completedAt","projectId","resultSha256","resultExpired"]);
  if(!record||typeof record!=="object"||Array.isArray(record)||Object.keys(record).some(key=>!allowed.has(key))
    ||!safeIdentifier(record.id)||!Number.isSafeInteger(record.seq)||record.seq<1
    ||!terminalStatuses.has(record.status)||record.resultExpired!==true||!validDigest(record.resultSha256)
    ||!validTimestamp(record.completedAt)
    ||(record.action!==undefined&&!safeIdentifier(record.action))||(record.projectId!==undefined&&!safeIdentifier(record.projectId)))
    throw new Error("Terminal command receipt integrity is invalid; no retained proof was discarded");
  return record;
}

async function readTerminalLedger(storage,key){
  const ledger=await storage.get(receiptKey(key)),format=await storage.get(receiptFormatKey(key));
  if(ledger===undefined&&format===undefined){
    const manifest=await storage.get(manifestKey(key));
    if(manifest?.receiptVersion===1)throw new Error("Terminal command receipt ledger is missing; its queue manifest requires retained proof");
    return [];
  }
  if(ledger!==undefined)requireBoundedJson(ledger);
  if(format!==1||!ledger||typeof ledger!=="object"||Array.isArray(ledger)||ledger.version!==1
    ||Object.keys(ledger).some(key=>!["version","records","sha256"].includes(key))
    ||!Array.isArray(ledger.records)||ledger.records.length>COMMAND_QUEUE_LIMITS.receipts
    ||bytesOf(ledger).byteLength>COMMAND_QUEUE_LIMITS.receiptBytes||!validDigest(ledger.sha256))
    throw new Error("Terminal command receipt ledger is missing or corrupt; no retained proof was discarded");
  const ids=new Set();
  for(const record of ledger.records){validateReceipt(record);if(ids.has(record.id))throw new Error("Terminal command receipt IDs are duplicated");ids.add(record.id);}
  if(await digest(bytesOf(canonical(ledger.records)))!==ledger.sha256)
    throw new Error("Terminal command receipt ledger checksum differs from its retained proof");
  return ledger.records;
}

async function compactReceipt(command){
  if(commandIsPending(command)||!terminalStatuses.has(command.status))throw new Error("An active command cannot become an expired receipt");
  const resultSha256=await retainedCommandResultSha256(command);
  const record={id:command.id,seq:command.seq,status:command.status,completedAt:command.completedAt,resultSha256,resultExpired:true};
  // Lookup metadata never carries provider/content URIs, attachment handoffs,
  // arbitrary parameters or result strings. Retain only safe original labels.
  if(safeIdentifier(command.action))record.action=command.action;
  if(safeIdentifier(command.projectId))record.projectId=command.projectId;
  return validateReceipt(record);
}

async function writeTerminalLedger(storage,key,existing,pruned){
  if(!pruned.length&&await storage.get(receiptFormatKey(key))===1)return;
  const byId=new Map(existing.map(record=>[record.id,record]));
  for(const command of pruned){
    const record=await compactReceipt(command),prior=byId.get(record.id);
    if(prior&&(prior.status!==record.status||prior.resultSha256!==record.resultSha256))
      throw new Error("Conflicting compact terminal receipt was rejected; original proof is retained");
    byId.set(record.id,record);
  }
  // Completion time keeps a late background result ahead of older receipts,
  // even when its accepted command sequence was smaller.
  const records=[...byId.values()].sort((a,b)=>Date.parse(a.completedAt)-Date.parse(b.completedAt)||a.seq-b.seq||a.id.localeCompare(b.id));
  while(records.length>COMMAND_QUEUE_LIMITS.receipts)records.shift();
  const size=()=>bytesOf({version:1,records,sha256:"0".repeat(64)}).byteLength;
  while(size()>COMMAND_QUEUE_LIMITS.receiptBytes){
    if(records.length<=1)throw new Error("One terminal receipt exceeds the bounded proof ledger budget");
    records.shift();
  }
  await storage.put(receiptKey(key),{version:1,records,sha256:await digest(bytesOf(canonical(records)))});
  await storage.put(receiptFormatKey(key),1);
}

/** Locator-free metadata for a compacted terminal result; no completion is fabricated. */
export async function readCommandReceipt(storage,key,id){
  return storage.transaction(transaction=>readCommandReceiptInTransaction(transaction,key,id));
}

export async function readCommandReceiptInTransaction(transaction,key,id){
  const record=(await readTerminalLedger(transaction,key)).find(item=>item.id===id);
  return record?{...record}:null;
}

/** Alias migration can validate one bounded ledger, then map all IDs in memory. */
export async function readCommandReceiptsInTransaction(transaction,key){
  return (await readTerminalLedger(transaction,key)).map(record=>({...record}));
}

/** Alias migration: caller writes its merged queue in this same transaction. */
export async function mergeCommandReceiptsInTransaction(transaction,targetKey,sourceKey){
  const target=await readTerminalLedger(transaction,targetKey);
  const source=targetKey===sourceKey?target:await readTerminalLedger(transaction,sourceKey);
  const current=await readCommandQueueInTransaction(transaction,targetKey),rows=new Map(current.map(row=>[row.id,row]));
  const merged=new Map(target.map(record=>[record.id,record]));
  const requireSameProof=(left,right)=>{
    if(left.id!==right.id||left.status!==right.status||left.resultSha256!==right.resultSha256
      ||left.action!==right.action||left.projectId!==right.projectId)
      throw new Error("Alias command receipt conflicts with canonical terminal proof; both queues are retained");
  };
  for(const original of source){
    let record=original;const row=rows.get(record.id),prior=merged.get(record.id);
    if(row){
      if(commandIsPending(row))throw new Error("Alias terminal receipt conflicts with an active canonical command; both queues are retained");
      const canonicalRecord=await compactReceipt(row);
      requireSameProof(record,canonicalRecord);
      // Canonical sequence order may have been rebased by an earlier alias
      // migration. Its accepted full-row sequence owns the canonical lookup.
      record={...record,seq:canonicalRecord.seq};
    }
    if(prior){
      requireSameProof(prior,record);
      if(row&&prior.seq!==record.seq)merged.set(record.id,{...prior,seq:record.seq});
      continue;
    }
    merged.set(record.id,record);
  }
  const records=[...merged.values()].sort((a,b)=>Date.parse(a.completedAt)-Date.parse(b.completedAt)||a.seq-b.seq||a.id.localeCompare(b.id));
  const highestSeq=records.reduce((maximum,record)=>Math.max(maximum,record.seq),0);
  if(records.length>COMMAND_QUEUE_LIMITS.receipts
    ||bytesOf({version:1,records,sha256:"0".repeat(64)}).byteLength>COMMAND_QUEUE_LIMITS.receiptBytes)
    throw new Error("Alias command receipt migration exceeds its proof budget; both queues are retained without eviction");
  if(source.length&&targetKey!==sourceKey){
    await transaction.put(receiptKey(targetKey),{version:1,records,sha256:await digest(bytesOf(canonical(records)))});
    await transaction.put(receiptFormatKey(targetKey),1);
  }
  return {receiptCount:records.length,highestSeq};
}

/** Invoke in the same storage transaction as full-row completion lookup. */
export async function acknowledgePrunedCommand(transaction,key,id,result,status){
  if(!terminalStatuses.has(status))throw new Error("Command acknowledgement requires a known terminal status");
  const record=(await readTerminalLedger(transaction,key)).find(item=>item.id===id);
  if(!record)return null;
  if(record.status!==status||record.resultSha256!==await canonicalCommandResultSha256(result))
    throw new Error("Conflicting pruned command acknowledgement was rejected; original receipt proof is retained");
  return {...record,result,resultReplayed:true};
}

function validateRows(rows,maximum=COMMAND_QUEUE_LIMITS.legacyCommands){
  if(!Array.isArray(rows)||rows.length>maximum)throw new Error("Command queue count is invalid");
  const ids=new Set();
  for(const row of rows){
    // The row is one known wrapper around its separately bounded result. Check
    // it before Object.entries can read any foreign accessor or unsupported type.
    requireBoundedJson(row,COMMAND_QUEUE_LIMITS.jsonDepth+1);
    if(!row||typeof row!=="object"||Array.isArray(row)||typeof row.id!=="string"||!row.id||ids.has(row.id)
      ||!Number.isSafeInteger(row.seq)||row.seq<1||(!pending.has(row.status)&&!terminalStatuses.has(row.status)))throw new Error("Command queue identity or status is invalid");
    ids.add(row.id);
    if(row.resultSha256!==undefined&&!validDigest(row.resultSha256))throw new Error("Command result receipt checksum is invalid");
    const result=row.result,request=immutableRequest(row);
    requireBoundedJson(request);
    if(result!==undefined)requireBoundedJson(result);
    const metadata=Object.fromEntries(Object.entries(row).filter(([key])=>mutableFields.has(key)));
    requireBoundedJson(metadata);
    if(bytesOf(request).byteLength>COMMAND_QUEUE_LIMITS.commandBytes)throw new Error("One command request exceeds the 512 KiB JSON budget");
    if(result!==undefined&&bytesOf(result).byteLength>COMMAND_QUEUE_LIMITS.resultBytes)throw new Error("One command result exceeds the 512 KiB JSON budget");
    if(bytesOf(metadata).byteLength>COMMAND_QUEUE_LIMITS.metadataBytes)
      throw new Error("Command lease metadata exceeds its 8 KiB budget");
  }
  return rows;
}

/** Must run inside the caller's storage transaction when sharing multiple queues. */
export async function readCommandQueueInTransaction(storage,key){
  const manifest=await storage.get(manifestKey(key)),legacy=await storage.get(key),format=await storage.get(formatKey(key));
  if(format!==undefined&&format!==1)throw new Error("Command queue format proof is invalid");
  if(manifest!==undefined&&legacy!==undefined)throw new Error("Mixed legacy and versioned command queues require recovery; neither is discarded");
  if(manifest===undefined){
    if(format===1)throw new Error("Versioned command queue manifest is missing; accepted work cannot be treated as empty");
    if(legacy===undefined){
      if(typeof storage.list!=="function")throw new Error("Command queue recovery requires bounded orphan-chunk inspection");
      const orphan=await storage.list({prefix:key+":chunks:v1:",limit:1});
      if(orphan.size)throw new Error("Orphan command queue chunks require explicit recovery; accepted work cannot be treated as empty");
      if(await storage.get(receiptFormatKey(key))!==undefined||await storage.get(receiptKey(key))!==undefined)
        throw new Error("Command queue metadata is missing beside retained terminal proofs; accepted work cannot be treated as empty");
      return [];
    }
    const rows=validateRows(legacy);
    if(bytesOf(rows).byteLength>COMMAND_QUEUE_LIMITS.totalBytes)throw new Error("Legacy command queue exceeds its bounded storage budget");
    return JSON.parse(jsonOf(rows)).sort((a,b)=>a.seq-b.seq||a.id.localeCompare(b.id));
  }
  if(format!==1)throw new Error("Command queue format proof is missing");
  if(manifest?.receiptVersion!==undefined&&manifest.receiptVersion!==1)throw new Error("Command queue receipt format proof is invalid");
  if(!manifest||typeof manifest!=="object"||Array.isArray(manifest)||manifest.version!==1||typeof manifest.generation!=="string"||!/^[a-f0-9-]{36}$/.test(manifest.generation)
    ||!Number.isSafeInteger(manifest.bytes)||manifest.bytes<2||manifest.bytes>COMMAND_QUEUE_LIMITS.totalBytes
    ||!Number.isSafeInteger(manifest.chunks)||manifest.chunks!==Math.ceil(manifest.bytes/COMMAND_QUEUE_LIMITS.chunkBytes)
    ||!Number.isSafeInteger(manifest.count)||manifest.count<0||manifest.count>COMMAND_QUEUE_LIMITS.legacyCommands
    ||typeof manifest.sha256!=="string"||!/^[a-f0-9]{64}$/.test(manifest.sha256))throw new Error("Command queue manifest integrity is invalid");
  if(manifest.receiptVersion===1)await readTerminalLedger(storage,key);
  const bytes=new Uint8Array(manifest.bytes);let offset=0;
  for(let index=0;index<manifest.chunks;index++){
    const blob=await storage.get(chunkKey(key,manifest.generation,index));
    const chunk=blob instanceof ArrayBuffer?new Uint8Array(blob):ArrayBuffer.isView(blob)?new Uint8Array(blob.buffer,blob.byteOffset,blob.byteLength):null;
    const expected=Math.min(COMMAND_QUEUE_LIMITS.chunkBytes,manifest.bytes-offset);
    if(!chunk||chunk.byteLength!==expected)throw new Error("Command queue chunk is missing or truncated");
    bytes.set(chunk,offset);offset+=chunk.byteLength;
  }
  if(offset!==manifest.bytes||await digest(bytes)!==manifest.sha256)throw new Error("Command queue checksum differs from its durable manifest");
  let rows;try{rows=JSON.parse(decoder.decode(bytes));}catch{throw new Error("Command queue JSON is corrupt");}
  validateRows(rows);
  if(rows.length!==manifest.count)throw new Error("Command queue count differs from its durable manifest");
  return rows.sort((a,b)=>a.seq-b.seq||a.id.localeCompare(b.id));
}

/** Prunes terminal history only; accepted pending work is never truncated. */
export async function writeCommandQueueInTransaction(storage,key,input,preserveIds=new Set()){
  validateRows(input,2*COMMAND_QUEUE_LIMITS.legacyCommands);
  const rows=validateRows(JSON.parse(jsonOf(input)),2*COMMAND_QUEUE_LIMITS.legacyCommands).sort((a,b)=>a.seq-b.seq);
  const ledger=await readTerminalLedger(storage,key),known=new Map(ledger.map(record=>[record.id,record])),pruned=[];
  for(const row of rows){
    const proof=known.get(row.id);
    if(proof&&(commandIsPending(row)||proof.status!==row.status))
      throw new Error("A compact terminal receipt prohibits reopening or changing that accepted command");
    if(proof){
      const sha=await retainedCommandResultSha256(row);
      if(sha!==proof.resultSha256)throw new Error("Full command result conflicts with its compact terminal proof");
    }
  }
  let bytes=bytesOf(rows);
  while(rows.length>Math.max(COMMAND_QUEUE_LIMITS.commands,rows.filter(commandIsPending).length+rows.filter(row=>!commandIsPending(row)&&preserveIds.has(row.id)).length)||bytes.byteLength>COMMAND_QUEUE_LIMITS.totalBytes){
    const terminal=rows.findIndex(row=>!commandIsPending(row)&&!preserveIds.has(row.id));
    if(terminal<0)throw new Error("Accepted active commands exceed the bounded queue budget; no work was removed");
    pruned.push(...rows.splice(terminal,1));bytes=bytesOf(rows);
  }
  validateRows(rows);
  await writeTerminalLedger(storage,key,ledger,pruned);
  const prior=await storage.get(manifestKey(key));
  const generation=crypto.randomUUID(),sha256=await digest(bytes),chunks=Math.ceil(bytes.byteLength/COMMAND_QUEUE_LIMITS.chunkBytes);
  for(let index=0;index<chunks;index++)await storage.put(chunkKey(key,generation,index),bytes.slice(index*COMMAND_QUEUE_LIMITS.chunkBytes,(index+1)*COMMAND_QUEUE_LIMITS.chunkBytes));
  await storage.put(manifestKey(key),{version:1,generation,bytes:bytes.byteLength,sha256,chunks,count:rows.length,receiptVersion:1});
  await storage.put(formatKey(key),1);
  await storage.delete(key);
  if(prior){
    for(let index=0;index<prior.chunks;index++)await storage.delete(chunkKey(key,prior.generation,index));
  }
  return rows;
}

export async function readCommandQueue(storage,key){
  return storage.transaction(transaction=>readCommandQueueInTransaction(transaction,key));
}

/** The mutator returns {rows,result}; async sequence updates use this same transaction. */
export async function mutateCommandQueue(storage,key,mutator){
  return storage.transaction(async transaction=>{
    const current=await readCommandQueueInTransaction(transaction,key);
    const prior=new Map(current.map(row=>[row.id,jsonOf(row)]));
    const mutation=await mutator(current,transaction);
    if(!mutation||!Array.isArray(mutation.rows))throw new Error("Command queue mutation is invalid");
    if(mutation.changed!==false){
      validateRows(mutation.rows,2*COMMAND_QUEUE_LIMITS.legacyCommands);
      const preserveIds=new Set(mutation.rows.filter(row=>prior.get(row.id)!==jsonOf(row)).map(row=>row.id));
      await writeCommandQueueInTransaction(transaction,key,mutation.rows,preserveIds);
    }
    return mutation.result;
  });
}

export function requireCommandQueueAdmission(rows,next){
  validateRows(rows);
  if(rows.filter(commandIsPending).length>=COMMAND_QUEUE_LIMITS.commands)throw new Error("Native command queue is full; drain accepted work before adding commands");
  if(next){
    validateRows([next]);
    const active=rows.filter(commandIsPending);
    if(bytesOf([...active,next].map(immutableRequest)).byteLength>COMMAND_QUEUE_LIMITS.totalBytes-COMMAND_QUEUE_LIMITS.resultBytes-COMMAND_QUEUE_LIMITS.metadataBytes*COMMAND_QUEUE_LIMITS.commands)
      throw new Error("Native command queue is full; the result-reserved JSON byte budget would be exceeded");
  }
}
