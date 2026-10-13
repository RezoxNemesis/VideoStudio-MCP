import test from 'node:test';
import assert from 'node:assert/strict';
const diagnostics=await import('../src/relay-diagnostics.js').catch(()=>({}));

test('a transport completion carrying a queued native job never claims finished work',()=>{
 assert.equal(typeof diagnostics.commandExecution,'function');
 const value=diagnostics.commandExecution({status:'completed',result:{ok:true,queued:true,jobId:'job-owned',background:true}});
 assert.equal(value.transportState,'completed');assert.equal(value.workState,'awaiting_job');assert.equal(value.jobId,'job-owned');assert.equal(value.outputVerified,false);
});
test('successful edits and actual published results preserve their distinct evidence',()=>{
 assert.equal(typeof diagnostics.commandExecution,'function');
 assert.equal(diagnostics.commandExecution({status:'completed',result:{ok:true,revision:7}}).workState,'completed');
 assert.equal(diagnostics.commandExecution({status:'completed',result:{ok:false,error:'failed'}}).workState,'failed');
 const output=diagnostics.commandExecution({status:'completed',result:{ok:true,outputVerified:true,outputName:'owned.mp4'}});assert.equal(output.outputVerified,true);assert.equal(output.creativeAcceptance,false);
 assert.equal(diagnostics.commandExecution({status:'completed',result:{ok:true,outputName:'owned.mp4'}}).outputVerified,false);
});
test('backlog distinguishes running claimed expired leases and terminals without inventing ACK state',()=>{
 assert.equal(typeof diagnostics.commandBacklog,'function');const at=Date.parse('2026-10-13T00:03:00.000Z');
 const status=diagnostics.commandBacklog([{status:'queued',createdAt:'2026-10-13T00:01:00.000Z'},{status:'claimed',leaseUntil:at-1},{status:'running'},{status:'waiting_native'},{status:'completed'},{status:'failed'}],at);
 assert.equal(status.pending,4);assert.equal(status.queued,1);assert.equal(status.claimed,1);assert.equal(status.expiredClaims,1);assert.equal(status.running,1);assert.equal(status.waitingNative,1);assert.equal(status.terminal,2);assert.equal(status.oldestPendingAgeMs,120000);assert.equal(status.awaitingAck,null);
});
test('release identity separates source fingerprint and storage format from phone or deployment acceptance',()=>{
 assert.equal(typeof diagnostics.relayRelease,'function');const value=diagnostics.relayRelease();assert.equal(value.schema,'videostudio.relay-release.v1');assert.equal(value.commandStorage.version,1);assert.equal(value.commandStorage.chunkBytes,65536);assert.match(value.sourceTreeSha256,/^[a-f0-9]{64}$/);assert.equal(value.ownerEndpointChanged,false);assert.equal(value.phoneExecutionVerified,false);assert.equal(value.deploymentId,null);
});
test('native job-status readback reports its actual state and cannot certify an unfinished render',()=>{
 const value=diagnostics.commandExecution({status:'completed',result:{ok:true,status:{found:true,job:{id:'job-owned',state:'running',progress:35}}}});assert.equal(value.workState,'running');assert.equal(value.jobId,'job-owned');assert.equal(value.outputVerified,false);
});

test('actual native job envelopes preserve failures, cancellation and unavailable receipts',()=>{
 for(const state of ['failed','cancelled','completed']){
  const value=diagnostics.commandExecution({status:'completed',result:{ok:true,status:{found:true,job:{id:'job-owned',state,result:{outputVerified:state==='completed'}}}}});
  assert.equal(value.workState,state);assert.equal(value.jobId,'job-owned');assert.equal(value.requiresJobReadback,false);assert.equal(value.outputVerified,state==='completed');
 }
 const missing=diagnostics.commandExecution({status:'completed',result:{ok:true,status:{found:false,jobId:'job-owned'}}});
 assert.equal(missing.workState,'job_unavailable');assert.equal(missing.jobId,'job-owned');assert.equal(missing.outputVerified,false);
});
