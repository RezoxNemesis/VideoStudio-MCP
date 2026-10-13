import build from '../.generated/relay-build.js';
import {COMMAND_QUEUE_LIMITS,commandIsPending} from './native-command-store.js';

/** Source/storage identity is independent of installed APK and creative acceptance. */
export function relayRelease(){return {schema:'videostudio.relay-release.v1',capabilityVersion:'3.9.6',sourceCommit:build.commit,sourceTreeSha256:build.sourceTreeSha256,sourceDirty:build.sourceDirty,deploymentId:null,deploymentEvidence:'Observe this response from the actual deployed endpoint; source identity alone does not establish device execution',commandStorage:{version:1,chunkBytes:COMMAND_QUEUE_LIMITS.chunkBytes,transactional:true,checksummed:true,terminalReceipts:true},resultPaging:true,ownerEndpointChanged:false,phoneExecutionVerified:false};}

/** Command ACK is delivery evidence; a queued background job remains unfinished. */
export function commandExecution(command){
 const transportState=command?.status||'unknown',result=command?.result,hasResult=result&&typeof result==='object';
 const status=result?.status&&typeof result.status==='object'?result.status:null;
 // ControlService returns JobManager.get(): {found,job:{id,state,result}}.
 // Read the older flat form too, without treating found:false as completion.
 const job=status?.job&&typeof status.job==='object'?status.job:status?.id?status:null;
 const jobId=typeof job?.id==='string'?job.id:typeof status?.jobId==='string'?status.jobId:typeof result?.jobId==='string'?result.jobId:null;
 let workState=transportState,output=result;
 if(transportState==='completed'){
  if(!hasResult||command.resultExpired)workState='result_unavailable';
  else if(result.ok===false||result.error)workState='failed';
  else if(status?.found===false)workState='job_unavailable';
  else if(job&&typeof job.state==='string'){workState=job.state;output=job.result;}
  else if(jobId&&(result.queued===true||result.background===true||['queued','running','waiting_native','waiting_thermal','waiting_memory','waiting_network'].includes(result.status)))workState='awaiting_job';
  else workState='completed';
 }
 return {transportState,workState,jobId,requiresJobReadback:!!jobId&&workState!=='completed'&&workState!=='failed'&&workState!=='cancelled',outputVerified:workState==='completed'&&output?.outputVerified===true,creativeAcceptance:workState==='completed'&&output?.cinematicAcceptance===true};
}

/** Awaiting-ACK cannot be inferred from a cloud claim; keep unknown explicit. */
export function commandBacklog(rows,at=Date.now()){
 const result={pending:0,queued:0,claimed:0,running:0,waitingNative:0,expiredClaims:0,terminal:0,failed:0,cancelled:0,awaitingAck:null,oldestPendingAgeMs:null};
 for(const row of rows){if(commandIsPending(row)){result.pending++;if(row.status==='waiting_native')result.waitingNative++;else if(Object.hasOwn(result,row.status))result[row.status]++;if(row.status==='claimed'&&Number(row.leaseUntil||0)<=at)result.expiredClaims++;const created=Date.parse(row.createdAt||'');if(Number.isFinite(created))result.oldestPendingAgeMs=Math.max(result.oldestPendingAgeMs??0,Math.max(0,at-created));}else{result.terminal++;if(row.status==='failed')result.failed++;if(row.status==='cancelled')result.cancelled++;}}
 return result;
}
