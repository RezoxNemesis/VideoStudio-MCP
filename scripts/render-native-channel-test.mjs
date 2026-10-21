import test from "node:test";
import assert from "node:assert/strict";
import * as R from "../src/render-native-channel.js";
function fakeState(){
  const entries=new Map();
  const storage={
    async get(k){return entries.get(k)===undefined?undefined:structuredClone(entries.get(k))},
    async put(k,v){entries.set(k,structuredClone(v))},
    async delete(k){entries.delete(k)}
  };
  let parent={deviceId:"vs-original",protocolFamily:"videostudio-native",nativeAgent:"videostudio-v3"};
  entries.set("app-device:vs-original",structuredClone(parent));
  return {
    ctx:{storage},
    async appV3Device(key){if(key!=="private-original-video-owner")throw Error("OWNER_REJECTED");return parent},
    async appRegister(id,ownerKey,meta){
      const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(ownerKey));
      const hash=[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
      await storage.put("app-device:"+id,{...meta,deviceId:id,ownerHash:hash});
      await storage.put("app-owner:"+hash,id);
      return {deviceId:id,protocolVersion:3};
    },
    async appAuth(id,ownerKey){
      const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(ownerKey));
      const hash=[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
      const d=await storage.get("app-device:"+id);
      return d&&d.ownerHash===hash?d:null;
    }
  };
}
const video="private-original-video-owner",device="7a8f43c0-6d2a-4b79-9cb5-96c1ffcd33fa",
 key="private-render-device-key-b6d9QKYcAxG16Sl_-tlto0-Uf4W6g7U";
test("link isolated Render identity without touching old VideoStudio queue",async()=>{
  const st=fakeState();
  await st.ctx.storage.put("app-v3-cl:vs-original",[{id:"video-edit",status:"queued"}]);
  const oneTime=(await R.challenge(st,video)).pairingCode;
  const link=await R.link(st,oneTime,device,key,{});
  assert.equal(link.linked,true);
  assert.equal(link.separateMcpConnectionRequired,false);
  assert.deepEqual((await st.ctx.storage.get("app-v3-cl:vs-original")).map(x=>x.id),["video-edit"]);
  assert.equal((await R.status(st,video)).linked,true);
  await assert.rejects(R.link(st,oneTime,"e4bf43b3-87e2-41a1-80b3-887c14e55d31",key),/RENDER_PAIR_CODE_EXPIRED_OR_USED/);
  const second=(await R.challenge(st,video)).pairingCode;
  await assert.rejects(R.link(st,second,"e4bf43b3-87e2-41a1-80b3-887c14e55d31",key),/RENDER_ALREADY_LINKED/);
});
test("true queued native execution, authenticated completion and idempotency",async()=>{
  const st=fakeState();await R.link(st,(await R.challenge(st,video)).pairingCode,device,key,{});
  // Owner first enables Render on-phone; later it falls offline without
  // disabling the user-authorized session. Queue must remain durable.
  await R.poll(st,device,key,true,null);
  const stale=await st.ctx.storage.get("app-device:"+device);
  stale.lastSeenAt=new Date(Date.now()-120000).toISOString();
  await st.ctx.storage.put("app-device:"+device,stale);
  const offline=await R.enqueue(st,video,"blender.scene.summary",{},"one");
  assert.equal(offline.status,"waiting_native");
  const original=await R.enqueue(st,video,"blender.object.add_cube",{name:"CubeA",size:2},"two");
  const duplicate=await R.enqueue(st,video,"blender.object.add_cube",{name:"CubeA",size:2},"two");
  assert.equal(duplicate.duplicate,true);assert.equal(duplicate.id,original.id);
  await assert.rejects(R.enqueue(st,video,"blender.object.add_cube",{name:"CubeB",size:2},"two"),/CONFLICT/);
  const delivered=await R.poll(st,device,key,true,null);
  assert.equal(delivered.job.job_id,offline.id);
  assert.equal(JSON.parse(delivered.job.payload).action,"blender.scene.summary");
  assert.equal((await R.complete(st,device,key,offline.id,delivered.job.digest,"succeeded",{scene:"Main"})).accepted,true);
  assert.equal((await R.command(st,video,offline.id)).result.native_result.scene,"Main");
  assert.deepEqual((await st.ctx.storage.get("app-v3-cl:vs-original"))||[],[]);
});
test("Render STOP, cancellation and revoke cannot affect VideoStudio",async()=>{
  const st=fakeState();await R.link(st,(await R.challenge(st,video)).pairingCode,device,key,{});
  await R.poll(st,device,key,true,null);
  const command=await R.enqueue(st,video,"engine.status",{},"stop");
  const cancelled=await R.cancel(st,video,command.id);assert.equal(cancelled.status,"cancelled");
  await R.poll(st,device,key,false,null);
  await assert.rejects(R.enqueue(st,video,"engine.status",{},"again"),/STOPPED/);
  const unlinked=await R.unlink(st,device,key);assert.equal(unlinked.unlinked,true);
  assert.equal((await R.status(st,video)).linked,false);
  await assert.rejects(R.poll(st,device,key,true,null),/AUTH_REQUIRED/);
});
test("wrong device key cannot read or report native result",async()=>{
  const st=fakeState();await R.link(st,(await R.challenge(st,video)).pairingCode,device,key,{});
  await assert.rejects(R.poll(st,device,"wrong",true,null),/AUTH_REQUIRED/);
  await assert.rejects(R.complete(st,device,"wrong","some","0".repeat(64),"failed",{}),/AUTH_REQUIRED/);
  await assert.rejects(R.status(st,"wrong-parent-key"),/OWNER_REJECTED/);
});
