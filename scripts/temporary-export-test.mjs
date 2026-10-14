import {nativeCommandSummary,nativeResultPage} from "../src/native-result-pages.js";
import {queueBindings,memoryStorage} from "./native-queue-fixture.mjs";
import * as storyboardContract from "../src/storyboard-contract.js";
import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {webcrypto} from "node:crypto";
import {cleanupPrivateBlobs} from '../src/private-blob-store.js';

const worker=readFileSync(new URL("../src/index.js",import.meta.url),"utf8");
const runtime=readFileSync(new URL("../src/studio-runtime.js",import.meta.url),"utf8");
const end=worker.indexOf("\n}\n",worker.indexOf("export class VideoStudioState"))+3;
const ctx={...storyboardContract,...queueBindings,nativeCommandSummary,nativeResultPage,cleanupPrivateBlobs,
  crypto:webcrypto,TextEncoder,TextDecoder,Response,Request,Headers,URL,
  Date,Blob,Uint8Array,setTimeout,DurableObject:class{constructor(context){this.ctx=context;}}
};
vm.createContext(ctx);
vm.runInContext(
  worker.slice(worker.indexOf("const JH"),end)
    .replace("export class VideoStudioState","globalThis.VideoStudioState = class VideoStudioState"),
  ctx
);
function fixture(options={}){
  const rows=new Map();let alarmAt=null;
  const storage=memoryStorage(rows,{maxValueBytes:128*1024,...options});
  storage.getAlarm=async()=>alarmAt;storage.setAlarm=async time=>{alarmAt=time;};
  const relay=new ctx.VideoStudioState({storage},{});
  return {relay,storage,rows};
}
const deviceId="verified-web-device-0001";
async function setup(options={}){
  const f=fixture(options);
  const project=await f.relay.createProject(deviceId,"User animation");
  const asset={id:"exact-video-asset-0001",kind:"video",type:"video/mp4",size:500000,name:"Goku vs Saitama V2.mp4"};
  await f.relay.update(deviceId,project.id,{assets:[asset]});
  return {...f,project,asset};
}

test("explicit video export never accepts another project, asset or image",async()=>{
  const f=await setup();
  await assert.rejects(f.relay.createTemporaryVideoExport(deviceId,f.project.id,"wrong-asset"),/video not registered/);
  await assert.rejects(f.relay.createTemporaryVideoExport(deviceId,"wrong-project",f.asset.id),/Project not found/);
  const t=await f.relay.createTemporaryVideoExport(deviceId,f.project.id,f.asset.id);
  assert.match(t.uploadToken,/^[0-9a-f]{64}$/);
  assert.match(t.downloadToken,/^[0-9a-f]{64}$/);
  assert.notEqual(t.uploadToken,t.downloadToken);
  assert.equal(t.safeName,"Goku_vs_Saitama_V2.mp4");
  assert.equal(await f.relay.getTemporaryVideoExportInfo(t.uploadToken),null);
});

test("binary transfer stores exact MP4 bytes, SHA-256 and bounded64KiB chunks",async()=>{
  const f=await setup();
  const t=await f.relay.createTemporaryVideoExport(deviceId,f.project.id,f.asset.id);
  const bytes=new Uint8Array(455000);for(let i=0;i<bytes.length;i++)bytes[i]=i%239;
  await assert.rejects(f.relay.storeTemporaryVideoExport("0".repeat(64),bytes,"video/mp4"),/expired/);
  await assert.rejects(f.relay.storeTemporaryVideoExport(t.uploadToken,bytes,"image/png"),/Unsupported video/);
  const uploaded=await f.relay.storeTemporaryVideoExport(t.uploadToken,bytes,"video/mp4");
  assert.equal(uploaded.ok,true);
  assert.equal(uploaded.size,bytes.length);
  const data=await f.relay.getTemporaryVideoExportInfo(t.downloadToken);
  assert.equal(data.name,"Goku_vs_Saitama_V2.mp4");
  assert.equal(data.mime,"video/mp4");
  assert.equal(data.size,bytes.length);
  assert.equal(data.parts,7);
  const joined=new Uint8Array(bytes.length);let offset=0;
  for(let i=0;i<data.parts;i++){
    const part=await f.relay.getTemporaryVideoExportPart(data.uploadToken,i);
    assert.ok(part.byteLength<=64*1024);
    joined.set(part,offset);offset+=part.length;
  }
  assert.deepEqual(Buffer.from(joined),Buffer.from(bytes));
  assert.equal((await f.relay.storeTemporaryVideoExport(t.uploadToken,bytes,"video/mp4")).reused,true);
  const digest=Buffer.from(await webcrypto.subtle.digest("SHA-256",bytes)).toString("hex");
  assert.equal(uploaded.digest,digest);
});

test('temporary transfer chunk failure rolls back every byte and publication together',async()=>{
  const f=await setup({beforePut(key){if(key.startsWith('te:part:')&&key.endsWith(':1'))throw new Error('injected export chunk failure');}});
  const transfer=await f.relay.createTemporaryVideoExport(deviceId,f.project.id,f.asset.id);
  await assert.rejects(f.relay.storeTemporaryVideoExport(transfer.uploadToken,new Uint8Array(180000),'video/mp4'),/injected export chunk/);
  assert.equal(await f.relay.getTemporaryVideoExportInfo(transfer.downloadToken),null);
  assert.equal([...f.rows.keys()].some(key=>key.startsWith('te:part:')),false);
});

test("temporary transfer expires and cleans itself on Durable Object alarm",async()=>{
  const f=await setup();
  const t=await f.relay.createTemporaryVideoExport(deviceId,f.project.id,f.asset.id);
  const uploadKey="te:up:"+t.uploadToken;
  await f.relay.storeTemporaryVideoExport(t.uploadToken,new Uint8Array([1,2,3,4]),"video/mp4");
  const rec=await f.storage.get(uploadKey);rec.expiresAt=Date.now()-1;
  await f.storage.put(uploadKey,rec);
  assert.equal(await f.relay.getTemporaryVideoExportInfo(t.downloadToken),null);
  await f.relay.alarm();
  assert.equal(await f.storage.get(uploadKey),undefined);
  assert.equal(await f.storage.get("te:down:"+t.downloadToken),undefined);
  assert.equal(await f.storage.get("te:part:"+t.uploadToken+":0"),undefined);
});

test("transfers are separately authorized, bounded and not an implicit Drive/gallery sync",()=>{
  assert.ok(worker.includes('registerTool("deliver_studio_video_to_chat"'));
  assert.ok(worker.includes("temporary_video_export"));
  assert.ok(worker.includes("/api/studio-transfer/download/"));
  assert.ok(runtime.includes("async function exportOneLocalVideo"));
  assert.ok(runtime.includes('a.id===p.assetId&&a.kind==="video"'));
  assert.ok(runtime.includes('idbGet("assets",assetKey(project.id,asset.id))'));
  assert.ok(runtime.includes('fetch("/api/studio-transfer/upload/"+p.uploadToken'));
  assert.ok(!runtime.includes('window.open("/api/studio-transfer'));
});
