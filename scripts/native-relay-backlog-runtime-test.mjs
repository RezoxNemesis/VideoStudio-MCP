/** Backlog/receipt integration against a loopback Worker using the installed MCP SDK.
 * Replies are synthetic, never phone drawing, rendering or live ChatGPT-host evidence.
 * Start `npm run dev -- --local --ip 127.0.0.1 --port 8790` first. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {Client,StreamableHTTPClientTransport} from '@modelcontextprotocol/client';
import {mkdir,writeFile} from 'node:fs/promises';
const origin=process.env.NATIVE_BACKLOG_QA_ORIGIN||'http://127.0.0.1:8790';
assert.match(origin,/^http:\/\/(127\.0\.0\.1|localhost):\d+$/,'Synthetic native registration is loopback only');
const deviceId=randomUUID(),owner='backlog_qa_'+randomUUID().replaceAll('-',''),generation=392,checks=[];
const passed=name=>{checks.push(name);console.log('PASS:',name);};
const http=async(path,body)=>{
 const r=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+owner,...(body===undefined?{}:{'content-type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
 const text=await r.text();assert.equal(r.status,200,text.slice(0,1000));return JSON.parse(text);
};
await http('/api/v3/app/register',{deviceId,ownerKey:owner,meta:{name:'Synthetic native backlog QA',platform:'android-native',appVersion:'3.9.2',protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:generation,permissionMode:'everything',controlPaused:false,projects:[]}});
const client=new Client({name:'native-backlog-qa',version:'1.0.0'},{capabilities:{},supportedProtocolVersions:['2025-11-25','2025-06-18','2025-03-26'],versionNegotiation:{mode:'legacy'}});
const parse=r=>JSON.parse(r.content.find(x=>x.type==='text').text);
try{
 await client.connect(new StreamableHTTPClientTransport(new URL(origin+'/app-mcp-v3/'+owner)),{timeout:30000});
 const call=async(name,args)=>parse(await client.callTool({name,arguments:args},{timeout:30000}));
 const queue=async(action,parameters={})=>{const q=await call('app_execute',{action,parameters});assert.equal(q.queued,true,JSON.stringify(q));return q;};
 const poll=async()=> (await http('/api/v3/app/commands?deviceId='+deviceId+'&appGeneration='+generation+'&wait=0')).commands;
 const ack=(id,result)=>http('/api/v3/app/commands/'+id+'/complete',{deviceId,appGeneration:generation,protocolVersion:3,status:'completed',result});
 const terminal=[];
 for(let i=0;i<12;i++){
  const q=await queue('studio_sketch_preview',{sketchId:'owned',expectedRevision:1,frameIndex:0});terminal.push(q.commandId);
  await ack(q.commandId,{ok:true,syntheticNativeReply:true,retained:'x'.repeat(1536*1024)});
 }
 const proof=await call('app_get_command_result',{commandId:terminal[0]});assert.equal(proof.resultExpired,true);assert.equal(proof.command.status,'completed');
 passed('18MiB of terminal payloads compact into explicit expired proof instead of one oversized queue value');
 const pending=[];
 for(let i=0;i<36;i++)pending.push(await queue('studio_sketch_edit',{sketchId:'owned',operationId:'step_'+i,expectedRevision:i+1,operations:[{type:'add_object',layerId:'layer_1',object:{id:'eye_'+i,kind:'path',commands:[['M',.45,.4],['Q',.5,.6,.55,.4]],width:.003,color:'#FF443322'}}]}));
 const claimed=[];for(let i=0;i<9;i++)claimed.push(...await poll());
 assert.deepEqual(claimed.map(x=>x.id),pending.map(x=>x.commandId));assert(claimed.every(x=>x.status==='claimed'));
 passed('All36 accepted drawing commands are claimable in sequence without dropping older pending work');
 const q=await queue('studio_sketch_edit',{sketchId:'owned',operationId:'final_step',expectedRevision:37,operations:[{type:'add_object',layerId:'layer_1',object:{id:'panel_outline',kind:'rect',fill:false,bounds:{x:.1,y:.1,width:.3,height:.3}}}]});
 assert.equal((await poll())[0].id,q.commandId);await ack(q.commandId,{ok:true,revision:38,syntheticNativeReply:true});
 const receipt=await call('app_get_command_result',{commandId:q.commandId});assert.equal(receipt.status,'completed');assert.equal(receipt.result.revision,38);
 passed('A small sketch edit and terminal ACK succeed after36 claims and large retained results');
 await ack(q.commandId,{syntheticNativeReply:true,revision:38,ok:true});
 passed('Exact terminal ACK replay is idempotent across JSON key ordering');
 for(const command of claimed)await ack(command.id,{ok:true,syntheticNativeReply:true});
 assert.equal((await http('/api/v3/app/status')).pendingCommands,0);
 passed('Acknowledging a newer command first still permits all36 older ACKs to drain');
 const denied=await call('app_execute',{action:'studio_list_gallery',parameters:{}});assert.equal(denied.queued,false);assert.equal((await poll()).length,0);
 passed('Backlog recovery preserves the Gallery privacy boundary');
 await mkdir('.work',{recursive:true});await writeFile('.work/native-relay-backlog-runtime-results.json',JSON.stringify({observer:'installed MCP SDK and local SQLite-backed workerd',syntheticNativeReplies:true,phoneExecutionVerified:false,productionRelayVerified:false,liveChatgptHostVerified:false,checks},null,2));
}finally{await client.close();}
