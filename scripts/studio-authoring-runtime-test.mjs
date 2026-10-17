/** Real local MCP SDK/workerd queue checks. Native replies are synthetic, never phone evidence. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {Client,StreamableHTTPClientTransport} from '@modelcontextprotocol/client';
const origin='http://127.0.0.1:8796',owner='authoring_qa_'+randomUUID().replaceAll('-',''),deviceId=randomUUID(),generation=430;
const persistence=await mkdtemp(tmpdir()+'/studio-authoring-runtime-');
let child,client,output='';
const http=async(path,body)=>{
 const response=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+owner,...(body===undefined?{}:{'content-type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});
 assert.equal(response.status,200);return response.json();
};
const register=appVersion=>http('/api/v3/app/register',{deviceId,ownerKey:owner,meta:{appVersion,protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:generation,permissionMode:'everything',controlPaused:false,projects:[]}});
const parse=result=>JSON.parse(result.content.find(block=>block.type==='text').text);
const poll=async()=>(await http('/api/v3/app/commands?deviceId='+deviceId+'&appGeneration='+generation+'&wait=0')).commands;
try{
 child=spawn(process.execPath,['node_modules/wrangler/bin/wrangler.js','dev','--local','--ip','127.0.0.1','--port','8796','--persist-to',persistence],{cwd:new URL('..',import.meta.url),detached:true,stdio:['ignore','pipe','pipe']});
 for(const stream of [child.stdout,child.stderr])stream.on('data',data=>{output=(output+data).slice(-8000);});
 const until=Date.now()+60000;let ready=false;
 while(Date.now()<until){
  if(child.exitCode!==null)throw new Error('Local Worker exited: '+output);
  try{const response=await fetch(origin+'/api/v3/app/status',{signal:AbortSignal.timeout(1000)});if(response.status===409){ready=true;break;}}catch{}
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 assert(ready,'Local authoring Worker readiness timed out: '+output);
 await register('4.2.0');
 client=new Client({name:'studio-authoring-runtime-qa',version:'1.0.0'},{capabilities:{},supportedProtocolVersions:['2025-11-25','2025-06-18'],versionNegotiation:{mode:'legacy'}});
 await client.connect(new StreamableHTTPClientTransport(new URL(origin+'/app-mcp-v3/'+owner)),{timeout:20000});
 const listed=await client.listTools({}, {timeout:20000});
 assert(listed.tools.some(tool=>tool.name==='app_cinematic_3d_edit_program'));
 const camera={projectId:'owned-project',programId:'owned-program',expectedProgramSha256:'a'.repeat(64),sourceProjectUpdatedAt:1,operations:[{type:'upsert_camera_key',shotId:'shot',phase:.5,position:[3,2,5],target:[0,1,0]}]};
 const call=(name,args)=>client.callTool({name,arguments:args},{timeout:20000});
 const refused=await call('app_cinematic_3d_edit_program',camera);
 assert.match(JSON.stringify(refused),/4\.3\.0/);assert.equal((await poll()).length,0);
 console.log('PASS: actual MCP discovery exposes camera editing and an older native version rejects before queue mutation');
 await register('4.3.0');
 const examples=[
  ['app_cinematic_3d_edit_program',camera,'cinematic_3d_edit_program'],
  ['app_execute',{action:'studio_precision_edit',parameters:{projectId:'owned-project',expectedUpdatedAt:1,operations:[{clipId:'clip',keyframeEdits:[{action:'upsert',keyframe:{timeMs:300,x:.2}}],set:{audioPan:-.5,audioMono:true}}]}},'studio_precision_edit'],
  ['app_execute',{action:'studio_sketch_edit',parameters:{sketchId:'owned-sketch',operationId:'draw-petal',expectedRevision:1,operations:[{type:'symmetry_stroke',layerId:'layer_1',object:{id:'petal',kind:'stroke',points:[[.5,.5,1],[.6,.5,.8]]},symmetry:{mode:'radial',segments:6}}]}},'studio_sketch_edit'],
  ['app_execute',{action:'studio_set_studio_layers',parameters:{projectId:'owned-project',expectedUpdatedAt:1,studioLayers:[{id:'caption',type:'title',text:'Native authoring',fontSize:48,fontStyle:'italic',fontFamily:'serif',textAlign:'left',backgroundColor:0x88334455}]}},'studio_set_studio_layers']
 ];
 for(const [tool,args,action]of examples){
  const queued=parse(await call(tool,args));assert.equal(queued.queued,true,JSON.stringify(queued));assert.equal(queued.nativeApp,true);
  const commands=await poll();assert.equal(commands.length,1);assert.equal(commands[0].id,queued.commandId);assert.equal(commands[0].action,action);assert.deepEqual(commands[0].parameters,args.parameters??args);
  await http('/api/v3/app/commands/'+queued.commandId+'/complete',{deviceId,appGeneration:generation,protocolVersion:3,status:'completed',result:{ok:true,syntheticNativeReply:true,phoneExecutionVerified:false}});
  console.log('PASS: '+action+' preserves exact authored fields through actual MCP SDK and durable native queue');
 }
 const plan={projectId:'owned-project',expectedUpdatedAt:123456789,clips:[{clipId:'cut-a',assetId:'owned-source',start:0,end:2,speed:.25,volume:0,effects:{brightness:.2,keyframes:[{timeMs:0,x:0},{timeMs:8000,x:.4}]}}]};
 assert.match(JSON.stringify(await call('app_apply_edit_plan',plan)),/4\.5\.12/);
 assert.equal((await poll()).length,0,'Older installed versions must not silently ignore the revision guard');
 await register('4.5.12');
 for(const [name,action,args]of [['app_apply_edit_plan','apply_edit_plan',plan],['app_autonomous_edit','autonomous_edit',{...plan,render:false}]]){
  const schema=listed.tools.find(tool=>tool.name===name).inputSchema;
  assert(schema.properties.projectId&&schema.properties.expectedUpdatedAt);
  const queued=parse(await call(name,args));assert.equal(queued.queued,true,JSON.stringify(queued));
  const commands=await poll();assert.equal(commands.length,1);assert.equal(commands[0].action,action);assert.deepEqual(commands[0].parameters,args);
  await http('/api/v3/app/commands/'+queued.commandId+'/complete',{deviceId,appGeneration:generation,protocolVersion:3,status:'completed',result:{ok:true,syntheticNativeReply:true,phoneExecutionVerified:false}});
  console.log('PASS: '+name+' discovers and delivers the exact project revision, stable IDs, speed, audio and effect keys through the actual local MCP SDK');
 }
 console.log('PASS: authoring transport checks use synthetic native replies; phone execution and artistic acceptance remain unverified');
}finally{
 if(client)await client.close();
 if(child&&child.exitCode===null){const exited=new Promise(resolve=>child.once('exit',resolve));process.kill(-child.pid,'SIGTERM');await exited;}
 await rm(persistence,{recursive:true,force:true});
}
