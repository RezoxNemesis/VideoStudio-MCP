import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
import {queueBindings,memoryStorage} from './native-queue-fixture.mjs';
import * as storyboardContract from '../src/storyboard-contract.js';
import {nativeCommandSummary,nativeResultPage} from '../src/native-result-pages.js';
const source=readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
const end=source.indexOf('\n}\n',source.indexOf('export class VideoStudioState'))+3;
const context={...storyboardContract,...queueBindings,nativeCommandSummary,nativeResultPage,crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,setTimeout,Date,
  DurableObject:class{constructor(ctx){this.ctx=ctx;}}};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('const JH'),end).replace('export class VideoStudioState','globalThis.VideoStudioState = class VideoStudioState'),context);
async function fixture(){
  const rows=new Map(),storage=memoryStorage(rows,{maxValueBytes:128*1024});
  const relay=new context.VideoStudioState({storage},{}),owner='synthetic-legacy-owner-'.repeat(2),deviceId='synthetic-legacy-device';
  await relay.appRegister(deviceId,owner,{protocolVersion:2,permissionMode:'everything'});
  return {rows,storage,relay,owner,deviceId};
}
test('legacy queue admits concurrent distinct sequences and round-trips oversized request and receipt',async()=>{
  const f=await fixture(),parameters={program:'x'.repeat(180000)};
  const commands=await Promise.all([f.relay.appEnqueue(f.owner,'ping',parameters),f.relay.appEnqueue(f.owner,'ping',{})]);
  assert.deepEqual(commands.map(row=>row.seq),[1,2]);
  const claimed=await f.relay.appCommands(f.deviceId,f.owner,999,0);
  assert.deepEqual(Array.from(claimed,row=>row.id),commands.map(row=>row.id));
  const result={ok:true,text:'y'.repeat(200000)};
  await f.relay.appComplete(f.deviceId,f.owner,commands[0].id,result);
  const read=await f.relay.appCommand(f.owner,commands[0].id);
  assert.equal(read.parameters.program,parameters.program);assert.equal(read.result.text,result.text);
  assert.equal((await f.relay.appComplete(f.deviceId,f.owner,commands[0].id,result)).resultReplayed,true);
  await assert.rejects(f.relay.appComplete(f.deviceId,f.owner,commands[0].id,{ok:false}),/Conflicting terminal/);
  assert.equal(await f.storage.get('app-cl:'+f.deviceId),undefined);
});
test('legacy queue migrates accepted inline rows atomically without advancing sequence on failed admission',async()=>{
  const f=await fixture(),key='app-cl:'+f.deviceId;
  const original={id:'original-accepted-command',seq:7,deviceId:f.deviceId,action:'ping',parameters:{},status:'queued',createdAt:new Date().toISOString(),completedAt:null,result:null};
  await f.storage.put(key,[original]);
  const next=await f.relay.appEnqueue(f.owner,'ping',{});assert.equal(next.seq,8);
  assert.deepEqual(Array.from(await f.relay.appCommands(f.deviceId,f.owner),row=>row.id),[original.id,next.id]);
  const seq=await f.storage.get('app-seq:'+f.deviceId);
  await assert.rejects(f.relay.appEnqueue(f.owner,'ping',{text:'x'.repeat(2*1024*1024)}),/2 MiB/);
  assert.equal(await f.storage.get('app-seq:'+f.deviceId),seq);
  assert.equal((await f.relay.appStatus(f.owner)).pendingCommands,2);
});
