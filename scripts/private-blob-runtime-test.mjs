/** Real local SQLite/workerd validation. All media and device replies are synthetic. */
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
const managed=process.env.PRIVATE_BLOB_QA_MANAGED==='1';
const origin=process.env.PRIVATE_BLOB_QA_ORIGIN||(managed?'http://127.0.0.1:8794':'http://127.0.0.1:8793');
assert.match(origin,/^http:\/\/(127\.0\.0\.1|localhost):\d+$/,'Synthetic uploads are loopback-only');
let child=null,persistence=null,output='';
const stop=async()=>{
  if(!child||child.exitCode!==null)return;
  const exited=new Promise(resolve=>child.once('exit',resolve));
  process.kill(-child.pid,'SIGTERM');await exited;child=null;
};
const start=async()=>{
  output='';
  child=spawn(process.execPath,['node_modules/wrangler/bin/wrangler.js','dev','--local','--ip','127.0.0.1','--port',new URL(origin).port,'--persist-to',persistence],{cwd:new URL('..',import.meta.url),detached:true,stdio:['ignore','pipe','pipe']});
  for(const stream of [child.stdout,child.stderr])stream.on('data',data=>{output=(output+data).slice(-8000);});
  const until=Date.now()+60000;
  while(Date.now()<until){
    if(child.exitCode!==null)throw new Error('Local Worker failed: '+output);
    try{const response=await fetch(origin+'/api/v3/app/status',{signal:AbortSignal.timeout(1000)});if(response.status===409)return;}catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('Local Worker readiness timeout: '+output);
};
try{
if(managed){persistence=await mkdtemp(tmpdir()+'/private-blob-runtime-');await start();}
const {relayRelease}=await import('../src/relay-diagnostics.js');
const expectedRelease=relayRelease(),release=await (await fetch(origin+'/api/relay/release')).json();
assert.equal(release.sourceTreeSha256,expectedRelease.sourceTreeSha256);
assert.equal(release.sourceCommit,expectedRelease.sourceCommit);
assert.equal(release.capabilityVersion,expectedRelease.capabilityVersion);
assert.equal(release.commandStorage.chunkBytes,65536);
console.log('PASS: runtime release '+release.capabilityVersion+' matches the baked source fingerprint and64KiB command format');
for(const protocol of [3,2]){
  const deviceId=randomUUID(),owner='blob_qa_'+randomUUID().replaceAll('-',''),generation=392;
  const headers={authorization:'Bearer '+owner};
  const registered=await fetch(origin+(protocol===3?'/api/v3/app/register':'/api/app/register'),{method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({deviceId,ownerKey:owner,meta:{protocolVersion:protocol,protocolMin:protocol,protocolMax:protocol,appGeneration:generation,permissionMode:'everything'}})});
  assert.equal(registered.status,200,await registered.text());
  const bytes=new Uint8Array(protocol===3?14700000:180000);for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
  const sha256=createHash('sha256').update(bytes).digest('hex');
  const response=await fetch(origin+'/api/app/private/upload?deviceId='+deviceId+'&name=synthetic.mp4&mime=video/mp4',{
    method:'POST',headers:{...headers,'content-type':'video/mp4','x-content-sha256':sha256},body:bytes,signal:AbortSignal.timeout(120000)});
  const upload=await response.json();assert.equal(response.status,200,JSON.stringify(upload));assert.equal(upload.size,bytes.length);assert.equal(upload.sha256,sha256);assert.equal(upload.storage,'durable-object');
  const commandStatus=await fetch(origin+'/api/app/private/commands/'+upload.commandId,{headers});
  const status=await commandStatus.json();assert.equal(commandStatus.status,200,JSON.stringify(status));
  assert.equal(status.command.id,upload.commandId);assert.equal(status.command.status,'queued');
  const privateStatus=await fetch(origin+'/api/app/private/status',{headers});
  const pendingStatus=await privateStatus.json();assert.equal(privateStatus.status,200,JSON.stringify(pendingStatus));
  assert.equal(pendingStatus.pendingCommands,1);assert.equal(pendingStatus.lastCommand.id,upload.commandId);
  if(managed&&protocol===3){await stop();await start();console.log('PASS: Worker process restarted using the same SQLite storage before download');}
  const content=origin+(protocol===3?'/api/v3/app':'/api/app')+'/handoffs/'+upload.handoffId+'/content?deviceId='+deviceId+'&appGeneration='+generation;
  const download=await fetch(content,{headers});assert.equal(download.status,200);assert.equal(download.headers.get('x-content-sha256'),sha256);
  assert.equal(createHash('sha256').update(new Uint8Array(await download.arrayBuffer())).digest('hex'),sha256);
  assert.equal((await fetch(content,{headers:{authorization:'Bearer '+randomUUID()}})).status,401);
  if(protocol===3){
    assert.equal((await fetch(content.replace('appGeneration='+generation,'appGeneration=1'),{headers})).status,409);
  }
  const poll=await fetch(origin+(protocol===3?'/api/v3/app':'/api/app')+'/commands?deviceId='+deviceId+'&appGeneration='+generation,{headers});
  const queued=await poll.json();assert.equal(poll.status,200,JSON.stringify(queued));assert.equal(queued.commands.length,1);assert.equal(queued.commands[0].id,upload.commandId);assert.equal(queued.commands[0].parameters.sha256,sha256);
  const acknowledgement=await fetch(origin+(protocol===3?'/api/v3/app':'/api/app')+'/commands/'+upload.commandId+'/complete',{
    method:'POST',headers:{...headers,'content-type':'application/json'},body:JSON.stringify({deviceId,protocolVersion:protocol,appGeneration:generation,status:'completed',result:{ok:true,syntheticNativeReply:true}})});
  assert.equal(acknowledgement.status,200,await acknowledgement.text());
  const completed=await fetch(origin+'/api/app/private/commands/'+upload.commandId,{headers});
  const completedStatus=await completed.json();assert.equal(completed.status,200,JSON.stringify(completedStatus));
  assert.equal(completedStatus.command.status,'completed');assert.equal(completedStatus.command.result.ok,true);
  const completedPrivateStatus=await (await fetch(origin+'/api/app/private/status',{headers})).json();
  assert.equal(completedPrivateStatus.pendingCommands,0);assert.equal(completedPrivateStatus.lastCommand.id,upload.commandId);assert.equal(completedPrivateStatus.lastCommand.status,'completed');
  if(protocol===3){
    for(let index=0;index<15;index++){
      const tiny=await fetch(origin+'/api/app/private/upload?deviceId='+deviceId,{method:'POST',headers,body:new Uint8Array([index])});
      assert.equal(tiny.status,200,await tiny.text());
    }
    const full=await fetch(origin+'/api/app/private/upload?deviceId='+deviceId,{method:'POST',headers,body:new Uint8Array([99])});
    assert.equal(full.status,429,await full.text());
    console.log('PASS: first owner filled16 durable upload slots; next protocol uses an independent owner');
  }
  console.log('PASS: protocol '+protocol+' durable '+bytes.length+'-byte upload, verified download and correct command queue'+(protocol===3?', owner and generation gates':''));
}
}finally{await stop();if(persistence)await rm(persistence,{recursive:true,force:true});}
