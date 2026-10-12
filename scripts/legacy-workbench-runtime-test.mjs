/** Verify the existing generic MCP relay with synthetic native3.9 replies.
 * Start the unmodified3.8.1 Worker on loopback8791; this is transport evidence,
 * not a phone render, installed update, or live ChatGPT host assertion. */
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import {Client,StreamableHTTPClientTransport} from '@modelcontextprotocol/client';

const origin=process.env.LEGACY_WORKBENCH_QA_ORIGIN||'http://127.0.0.1:8791';
if(!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin))throw new Error('Synthetic native registration requires loopback');
const owner='legacy_workbench_'+randomUUID().replaceAll('-',''),deviceId=randomUUID(),generation=390;
const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yN8sAAAAASUVORK5CYII=';
const outcomes=[];
const passed=name=>{outcomes.push(name);console.log('PASS:',name);};
const parse=r=>JSON.parse(r.content.find(b=>b.type==='text').text);
async function http(path,body){
 const r=await fetch(origin+path,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+owner,...(body===undefined?{}:{'content-type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});
 assert.equal(r.status,200);return r.json();
}
await http('/api/v3/app/register',{deviceId,ownerKey:owner,meta:{name:'Synthetic native workbench',platform:'android-native',appVersion:'3.9.0',protocolVersion:3,protocolMin:3,protocolMax:3,appGeneration:generation,permissionMode:'everything',controlPaused:false,projects:[]}});
const client=new Client({name:'legacy-workbench-qa',version:'1.0.0'},{capabilities:{},supportedProtocolVersions:['2025-11-25','2025-06-18','2025-03-26'],versionNegotiation:{mode:'legacy'}});
try{
 await client.connect(new StreamableHTTPClientTransport(new URL(origin+'/app-mcp-v3/'+owner)),{timeout:20000});
 const listed=await client.listTools({}, {timeout:20000});assert(listed.tools.some(t=>t.name==='app_execute'));assert(!listed.tools.some(t=>t.name==='app_sketch_edit'));
 passed('Unmodified older Worker discovers its existing generic execute tool');
 const call=(name,args)=>client.callTool({name,arguments:args},{timeout:20000});
 const poll=async()=> (await http('/api/v3/app/commands?deviceId='+deviceId+'&appGeneration='+generation+'&wait=0')).commands;
 const ack=(id,result)=>http('/api/v3/app/commands/'+id+'/complete',{deviceId,appGeneration:generation,protocolVersion:3,status:'completed',result});
 const timeline={schema:'videostudio.cinematic3d.timeline.v1',name:'Alias authoring',fps:30,motionProvider:'procedural_mannequin',actors:{black:{preset:'chibi'},white:{preset:'robot'}},shots:[{id:'wave',action:'WAVE',durationFrames:30,hitStopFrames:0,motionStyle:{preset:'anime',intensity:.8},camera:{mode:'wide',fovDegrees:57,shakeIntensity:0},vfx:{sparkBurst:false,slashTrail:false,rubble:false}}]};
 const examples=[
  ['studio_cinematic_3d_install_builtin',{projectId:'owned-project',sourceProjectUpdatedAt:1,pack:'extended'}],
  ['studio_cinematic_3d_save_program',{projectId:'owned-project',sourceProjectUpdatedAt:1,bundleId:'owned-bundle',timeline}],
  ['studio_precision_edit',{projectId:'owned-project',expectedUpdatedAt:1,operations:[{clipId:'stable-clip',set:{opacity:.5,maskShape:'ellipse'},keyframes:[{timeMs:0,x:0},{timeMs:1000,x:.2}]}]}],
  ['studio_sketch_create',{sketchId:'owned-sketch',operationId:'create-op',width:256,height:256,fps:24,frameCount:12}],
  ['studio_open_pipeline_export_request',{projectId:'owned-project',sourceProjectUpdatedAt:1,requestId:'owned-request',backend:'cogvideox',controls:{mode:'video_to_video',strength:.5,prompt:'Preserve the owned reference',width:64,height:64,numFrames:9,fps:8,seed:17,numInferenceSteps:2,guidanceScale:6},assetIds:['owned-video']}]
 ];
 for(const [action,parameters] of examples){
  const queued=parse(await call('app_execute',{action,parameters}));assert.equal(queued.queued,true);
  const claims=await poll();assert.equal(claims.length,1);assert.equal(claims[0].action,action);assert.deepEqual(claims[0].parameters,parameters);
  await ack(queued.commandId,{ok:true,syntheticNativeReply:true,cinematicAcceptance:false});
  passed(action+' retains exact parameters through the older native queue');
 }
 const queued=parse(await call('app_execute',{action:'studio_sketch_preview',parameters:{sketchId:'owned-sketch',expectedRevision:1,frameIndex:0}}));
 assert.equal((await poll())[0].action,'studio_sketch_preview');
 await ack(queued.commandId,{ok:true,sketchId:'owned-sketch',revision:1,diagnosticOnly:true,contactSheet:{base64:png,mimeType:'image/png',width:1,height:1,role:'sketch_canvas_preview'}});
 const image=await call('app_get_command_result',{commandId:queued.commandId});assert.equal(image.content.filter(b=>b.type==='image').length,1);assert.equal(image.content.find(b=>b.type==='image').data,png);
 assert(!image.content.find(b=>b.type==='text').text.includes(png));passed('The older MCP image channel delivers the compatible sketch preview without duplicating private bytes');
 const denied=parse(await call('app_execute',{action:'studio_list_gallery',parameters:{}}));assert.equal(denied.queued,false);assert.equal((await poll()).length,0);
 passed('Compatibility does not remove the existing Gallery boundary');
 await mkdir('.work',{recursive:true});await writeFile('.work/legacy-workbench-runtime-results.json',JSON.stringify({observer:'installed MCP SDK and unmodified3.8.1 local workerd',legacySourceCommit:'68e3b50edbfc5e99f9beb9c32ae9ffcc28e5489d',syntheticNativeReplies:true,phoneExecutionVerified:false,liveChatgptHostVerified:false,outcomes},null,2));
}finally{await client.close();}
