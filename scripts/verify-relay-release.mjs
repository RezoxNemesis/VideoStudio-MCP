import assert from 'node:assert/strict';
import build from '../.generated/relay-build.js';
const origin=new URL(process.argv[2]||'https://wispy-queen-f9b5.prakasharuntandon634.workers.dev');
if(origin.protocol!=='https:'&&!['127.0.0.1','localhost','[::1]'].includes(origin.hostname))throw new Error('Release verification requires HTTPS or a local test Worker');
const response=await fetch(new URL('/api/relay/release',origin),{cache:'no-store',signal:AbortSignal.timeout(30000)});assert.equal(response.status,200,'Published relay release endpoint is missing');
const result=await response.json();assert.equal(result.schema,'videostudio.relay-release.v1');assert.equal(result.sourceTreeSha256,build.sourceTreeSha256,'Published relay differs from the source build');assert.equal(result.sourceCommit,build.commit,'Published relay commit differs from the intended build');assert.equal(result.sourceDirty,false,'A release cannot publish dirty source');assert.equal(result.commandStorage?.version,1);assert.equal(result.commandStorage?.chunkBytes,65536);assert.equal(result.ownerEndpointChanged,false);
console.log(JSON.stringify({remoteSourceCommit:result.sourceCommit,remoteSourceTreeSha256:result.sourceTreeSha256,storageVersion:1,readbackVerified:true,phoneExecutionVerified:false}));
