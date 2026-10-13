import {webcrypto} from "node:crypto";
if(!globalThis.crypto)globalThis.crypto=webcrypto;
import * as queue from '../src/native-command-store.js';
const json=value=>value===undefined?undefined:JSON.parse(JSON.stringify(value));
// These VM fixtures cross a JS realm boundary. Normalize their JSON at the
// same boundary as HTTP; production keeps the real helper's strict type guard.
export const queueBindings={...queue,
  mutateCommandQueue:(storage,key,mutator)=>queue.mutateCommandQueue(storage,key,async(rows,transaction)=>{
    const mutation=await mutator(rows,transaction);return {...mutation,rows:json(mutation.rows)};
  }),
  requireCommandQueueAdmission:(rows,next)=>queue.requireCommandQueueAdmission(json(rows),json(next)),
  terminalCommandUpdate:(row,result,status,parameters)=>queue.terminalCommandUpdate(json(row),json(result),status,json(parameters)),
  canonicalCommandResultSha256:result=>queue.canonicalCommandResultSha256(json(result)),
  writeCommandQueueInTransaction:(storage,key,rows,preserve)=>queue.writeCommandQueueInTransaction(storage,key,json(rows),preserve),
  writeNativeDevice:(storage,key,record)=>queue.writeNativeDevice(storage,key,json(record)),
  writeNativeDeviceInTransaction:(storage,key,record)=>queue.writeNativeDeviceInTransaction(storage,key,json(record)),
  acknowledgePrunedCommand:(storage,key,id,result,status)=>queue.acknowledgePrunedCommand(storage,key,id,json(result),status)
};
export function memoryStorage(rows=new Map(),options={}) {
  let chain=Promise.resolve(),committedWrites=0;
  const copy=value=>structuredClone(value),size=value=>value instanceof Uint8Array?value.byteLength:new TextEncoder().encode(JSON.stringify(value)).byteLength;
  const api=(map,write)=>({
    get:async key=>copy(map.get(key)),
    put:async(key,value)=>{if(size(value)>(options.maxValueBytes??Infinity))throw new Error('DurableObject value exceeds 128 KiB');options.beforePut?.(key,value);write();map.set(key,copy(value));},
    delete:async key=>{write();return map.delete(key);},
    list:async({prefix='',limit=Infinity}={})=>new Map([...map].filter(([key])=>key.startsWith(prefix)).slice(0,limit).map(([key,value])=>[key,copy(value)]))
  });
  const storage=api(rows,()=>committedWrites++);
  storage.transaction=callback=>{
    const operation=chain.then(async()=>{const draft=new Map([...rows].map(([key,value])=>[key,copy(value)]));let writes=0;
      const result=await callback(api(draft,()=>writes++));
      rows.clear();for(const [key,value] of draft)rows.set(key,value);committedWrites+=writes;return result;
    });chain=operation.catch(()=>{});return operation;
  };
  storage.writes=()=>committedWrites;
  return storage;
}
