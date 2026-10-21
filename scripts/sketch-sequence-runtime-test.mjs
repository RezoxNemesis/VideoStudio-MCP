/** Real SDK + local Worker only. Android replies are synthetic, never device evidence. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdtemp,rm,writeFile,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {Client,StreamableHTTPClientTransport} from '@modelcontextprotocol/client';
const origin='http://127.0.0.1:8798',owner='sequence_qa_'+randomUUID().replaceAll('-',''),deviceId=randomUUID(),generation=473;
const persistence=await mkdtemp(tmpdir()+'/sketch-sequence-mcp-');let child,client,output='';
const http=async(path,body)=>{const r=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+owner,...(body===undefined?{}:{'content-type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});assert.equal(r.status,200);return r.json();};
const register=appVersion=>http('/api/v3/app/register',{deviceId,ownerKey:owner,meta:{appVersion,protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:generation,permissionMode:'everything',controlPaused:false,projects:[]}});
const parse=result=>JSON.parse(result.content.find(b=>b.type==='text').text);
const poll=async()=>(await http('/api/v3/app/commands?deviceId='+deviceId+'&appGeneration='+generation+'&wait=0')).commands;
try{
 child=spawn(process.execPath,['node_modules/wrangler/bin/wrangler.js','dev','--local','--ip','127.0.0.1','--port','8798','--persist-to',persistence],{cwd:new URL('..',import.meta.url),detached:true,stdio:['ignore','pipe','pipe']});
 for(const stream of [child.stdout,child.stderr])stream.on('data',data=>{output=(output+data).slice(-8000);});
 let ready=false;const until=Date.now()+60000;
 while(Date.now()<until){if(child.exitCode!==null)throw new Error('Local Worker exited');try{const r=await fetch(origin+'/api/v3/app/status',{signal:AbortSignal.timeout(1000)});if(r.status===409){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}assert(ready,'Local Worker readiness timed out');
 await register('4.6.2');
 client=new Client({name:'sketch-sequence-runtime-qa',version:'1.0.0'},{capabilities:{},supportedProtocolVersions:['2025-11-25','2025-06-18'],versionNegotiation:{mode:'legacy'}});
 await client.connect(new StreamableHTTPClientTransport(new URL(origin+'/app-mcp-v3/'+owner)),{timeout:20000});
 const call=(name,args)=>client.callTool({name,arguments:args},{timeout:20000});
 await call('app_status',{});
 const listed=await client.listTools({}, {timeout:20000});
 for(const name of ['app_sketch_book_timeline','app_sketch_import_animation','app_sketch_component_save','app_sketch_component_apply'])assert(listed.tools.some(t=>t.name===name),name);
 const book={bookId:'film',operationId:'new_hour',pageCount:216000,fps:60};
 const speech={projectId:'owned_project',sourceProjectUpdatedAt:1,requestId:'hindi_dialogue',backend:'kokoro',assetIds:[],controls:{language:'hi-IN',durationMs:12000,lines:[{lineId:'one',characterId:'kael',voice:'hm_omega',text:'मैं लड़ना नहीं चाहता।',startMs:1000,slotMs:5000}]}};
 assert.match(JSON.stringify(await call('app_open_pipeline_export_request',speech)),/4\.7\.0/);assert.equal((await poll()).length,0);
 assert.match(JSON.stringify(await call('app_sketch_book_create',book)),/4\.7\.0/);assert.equal((await poll()).length,0);
 await register('4.7.0');
 const examples=[
  ['app_sketch_book_create',book,'sketch_book_create'],
  ['app_sketch_book_timeline',{bookId:'film',expectedRevision:1,frameOffset:215999,frameLimit:1},'sketch_book_timeline'],
  ['app_sketch_book_edit',{bookId:'film',operationId:'clash',expectedRevision:1,cues:[{id:'contact',type:'contact',startFrame:50000,endFrame:50002,label:'Draw blade contact'}]},'sketch_book_edit'],
  ['app_sketch_import_animation',{projectId:'owned_project',sourceProjectUpdatedAt:1,assetId:'owned_zip',expectedSha256:'a'.repeat(64),bookId:'imported_film',operationId:'import',fps:24,frameCount:86400},'sketch_import_animation'],
  ['app_execute',{action:'studio_sketch_component_apply',parameters:{componentId:'angry_eyes',sketchId:'face_cel',expectedRevision:1,operationId:'place',frameIndex:0}},'studio_sketch_component_apply'],
  ['app_open_pipeline_export_request',speech,'open_pipeline_export_request']
 ];
 for(const [name,args,action]of examples){
  const result=parse(await call(name,args));assert.equal(result.queued,true,JSON.stringify(result));const commands=await poll();assert.equal(commands.length,1);assert.equal(commands[0].id,result.commandId);assert.equal(commands[0].action,action);assert.deepEqual(commands[0].parameters,args.parameters??args);
  await http('/api/v3/app/commands/'+result.commandId+'/complete',{deviceId,appGeneration:generation,protocolVersion:3,status:'completed',result:{ok:true,syntheticNativeReply:true,phoneExecutionVerified:false}});
 }
 await mkdir('.work',{recursive:true});await writeFile('.work/sketch-sequence-mcp-runtime.json',JSON.stringify({observer:'installed MCP SDK + local workerd',discoveryVerified:true,olderAppRefusedBeforeQueue:true,exactRequestsVerified:examples.length,syntheticNativeReplies:true,phoneExecutionVerified:false,liveOwnerEndpointContacted:false},null,2));
 console.log('PASS: real local MCP discovery, native-version guards and six exact queued sequence/dialogue requests; Android replies synthetic');
}finally{if(client)await client.close();if(child?.pid){try{process.kill(-child.pid,'SIGTERM');}catch{}}await rm(persistence,{recursive:true,force:true});}
