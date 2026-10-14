import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash,createHmac,createDecipheriv} from 'node:crypto';
import {mkdtemp,writeFile,readFile,rm,stat,unlink} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {encodeMediaEnvelope,decodeMediaEnvelope,validateMediaManifest,stageMediaUpload,publishMediaUpload,MEDIA_LIMITS} from './native-media-mailbox.mjs';
import {channelForKey,decodePacket} from './native-agent-mailbox.mjs';
const key='synthetic_app_key_abcdefghijklmnopqrstuvwxyz123456789',id='12345678-1234-4234-8234-123456789abc',now=1800000000000;
const hash=data=>createHash('sha256').update(data).digest('hex');
const chunk={schema:'videostudio.agent.media-chunk.v1',attachmentId:id,index:0,base64:Buffer.from('synthetic raw source').toString('base64')};
test('media codec matches the fixed HMAC AES-GCM cross-platform contract',()=>{
  const envelope=encodeMediaEnvelope(key,id,0,chunk,{nonce:Buffer.alloc(12,7),now});
  assert.deepEqual(decodeMediaEnvelope(key,envelope,{now}),chunk);
  assert.equal(envelope.channel,channelForKey(key));
  const cipher=Buffer.from(envelope.ciphertext,'base64url');
  const decipher=createDecipheriv('aes-256-gcm',createHmac('sha256',key).update('VideoStudio media encryption v1').digest(),Buffer.alloc(12,7));
  decipher.setAAD(Buffer.from(`videostudio.agent.media.v1:${envelope.channel}:${id}:0`));decipher.setAuthTag(cipher.subarray(-16));
  assert.deepEqual(JSON.parse(Buffer.concat([decipher.update(cipher.subarray(0,-16)),decipher.final()])),chunk);
  assert.equal(hash(JSON.stringify(envelope)),'3e69c84f5a1577233841401dec7a73ab7e7e304e4051658ac036abb9db81e968');
});
test('tamper, wrong key, cross-attachment, index substitution, malformed base64 and envelope limits reject',()=>{
  const e=encodeMediaEnvelope(key,id,0,chunk,{now});
  for(const bad of [{...e,index:1},{...e,attachmentId:'22345678-1234-4234-8234-123456789abc'},
    {...e,nonce:e.nonce+'='},{...e,ciphertext:'a'.repeat(2*1024*1024)},
    {...e,ciphertext:(e.ciphertext[0]==='A'?'B':'A')+e.ciphertext.slice(1)}])assert.throws(()=>decodeMediaEnvelope(key,bad,{now}));
  assert.throws(()=>decodeMediaEnvelope(key+'wrong',e,{now}));
  assert.throws(()=>encodeMediaEnvelope(key,id,0,{...chunk,base64:'YQ'},{now}));
});
test('manifest enforces total size, contiguous chunk hashes, TTL and maximum chunk count',()=>{
  const manifest={schema:'videostudio.agent.media-manifest.v1',attachmentId:id,name:'synthetic.mp4',mime:'video/mp4',
    size:1,sha256:hash('a'),chunkBytes:524288,chunks:[{index:0,bytes:1,sha256:hash('a')}],createdAtMs:now,expiresAtMs:now+86400000};
  assert.equal(validateMediaManifest(manifest,{now}),manifest);
  for(const change of [{size:MEDIA_LIMITS.maxBytes+1},{expiresAtMs:now+7*86400000+1},{name:'../private.mp4'},
    {chunks:[{index:1,bytes:1,sha256:hash('a')}]},{size:2},{chunkBytes:12},{chunks:Array(513).fill(manifest.chunks[0])}])
    assert.throws(()=>validateMediaManifest({...manifest,...change},{now}));
});
test('filename contract matches native UTF16 and UTF8 limits including astral characters',()=>{
  const manifest={schema:'videostudio.agent.media-manifest.v1',attachmentId:id,name:'synthetic.mp4',mime:'video/mp4',
    size:1,sha256:hash('a'),chunkBytes:524288,chunks:[{index:0,bytes:1,sha256:hash('a')}],createdAtMs:now,expiresAtMs:now+86400000};
  for(const name of ['a'.repeat(180),'é'.repeat(127)+'a','😀'.repeat(63)+'abc'])
    assert.equal(validateMediaManifest({...manifest,name},{now}).name,name);
  for(const name of ['a'.repeat(181),'a'.repeat(255),'é'.repeat(128),'😀'.repeat(63)+'abcd','\ud800','\udc00','a\ud800b'])
    assert.throws(()=>validateMediaManifest({...manifest,name},{now}));
});
test('invalid filenames fail before reading input or creating a private outbox',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-name-'));
  try{
    const inputPath=join(directory,'input.mp4');await writeFile(inputPath,'synthetic');
    for(const [index,name] of ['a'.repeat(181),'a'.repeat(255),'é'.repeat(128),'\ud800'].entries()){
      const outboxDir=join(directory,'outbox-'+index);
      await assert.rejects(()=>stageMediaUpload({ownerKey:key,inputPath,projectId:'project',outboxDir,name,mime:'video/mp4',now}));
      await assert.rejects(()=>stat(outboxDir),{code:'ENOENT'});
    }
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('staging persists private immutable wires and command before publication; retries preserve every byte',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-stage-'));
  try{
    const inputPath=join(directory,'sensitive-name.mp4'),outboxDir=join(directory,'outbox');
    await writeFile(inputPath,Buffer.alloc(524289,37));
    const args={ownerKey:key,inputPath,projectId:'owned-project',outboxDir,now};
    const first=await stageMediaUpload(args),second=await stageMediaUpload({...args,now:now+1000});
    assert.deepEqual(first,second);assert.equal(first.chunks.length,2);
    assert.equal((await stat(join(outboxDir,'upload.json'))).mode&0o777,0o600);
    assert.equal((await stat(outboxDir)).mode&0o777,0o700);
    const manifestWire=await readFile(join(outboxDir,'manifest.vsmedia'));
    assert.equal(first.manifestSha256,hash(manifestWire));
    const manifest=decodeMediaEnvelope(key,JSON.parse(manifestWire),{now});
    assert.equal(manifest.name,'sensitive-name.mp4');assert.equal(manifest.size,524289);
    const command=decodePacket(key,first.packet,{now}).commands[0];
    assert.equal(command.action,'import_mailbox_media');assert.deepEqual(command.parameters,{projectId:'owned-project',attachmentId:first.attachmentId,manifestSha256:first.manifestSha256});
    await writeFile(inputPath,'changed source');await assert.rejects(()=>stageMediaUpload(args));
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('public GitHub writes contain only authenticated ciphertext and publish manifest last; retry checks exact bytes',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-publish-'));
  try{
    const inputPath=join(directory,'private-source.mp4'),outboxDir=join(directory,'outbox');
    await writeFile(inputPath,'RAW_PRIVATE_BINARY_DO_NOT_PUBLISH');
    const state=await stageMediaUpload({ownerKey:key,inputPath,projectId:'private-project',outboxDir,now});
    const objects=new Map(),writes=[];
    const gh=async(args,input)=>{
      const path=args.find(v=>v.startsWith('repos/')).split('?')[0];
      if(!input){if(!objects.has(path))throw Object.assign(new Error(),{status:404});return {encoding:'base64',content:objects.get(path).toString('base64')};}
      const body=JSON.parse(input);assert.equal(body.branch,'videostudio-agent-mailbox');assert.equal(body.sha,undefined);
      const wire=Buffer.from(body.content,'base64');writes.push(path);objects.set(path,wire);
      assert.ok(!wire.includes('RAW_PRIVATE_BINARY_DO_NOT_PUBLISH'));assert.ok(!wire.includes('private-source'));assert.ok(!wire.includes('private-project'));
      assert.ok(!input.includes(key));assert.equal(JSON.parse(wire).schema,'videostudio.agent.media.v1');return {};
    };
    await publishMediaUpload(state,{ownerKey:key,outboxDir,gh,now});
    assert.equal(writes.length,2);assert.ok(writes[0].endsWith('/0.vsmedia'));assert.ok(writes[1].endsWith('/manifest.vsmedia'));
    await publishMediaUpload(state,{ownerKey:key,outboxDir,gh,now});assert.equal(writes.length,2);
    objects.set(writes[0],Buffer.from('{}'));await assert.rejects(()=>publishMediaUpload(state,{ownerKey:key,outboxDir,gh,now}));assert.equal(writes.length,2);
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('changed staged ciphertext fails before any network call',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-corrupt-'));
  try{
    const inputPath=join(directory,'a.mp4'),outboxDir=join(directory,'outbox');await writeFile(inputPath,'synthetic');
    const state=await stageMediaUpload({ownerKey:key,inputPath,projectId:'project',outboxDir,now});
    await writeFile(join(outboxDir,'0.vsmedia'),'{}');let calls=0;
    await assert.rejects(()=>publishMediaUpload(state,{ownerKey:key,outboxDir,now,gh:async()=>{calls++;}}));assert.equal(calls,0);
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('unexpected plaintext on a persisted command envelope is rejected before publishing',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-packet-'));
  try{
    const inputPath=join(directory,'a.mp4'),outboxDir=join(directory,'outbox');await writeFile(inputPath,'synthetic');
    const state=await stageMediaUpload({ownerKey:key,inputPath,projectId:'project',outboxDir,now});
    state.packet.accidentalPlaintext='private filename or key';let calls=0;
    await assert.rejects(()=>publishMediaUpload(state,{ownerKey:key,outboxDir,now,gh:async()=>{calls++;}}));assert.equal(calls,0);
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('interrupted preparation resumes existing chunk ciphertext before creating the durable command',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-resume-'));
  try{
    const inputPath=join(directory,'a.mp4'),outboxDir=join(directory,'outbox');await writeFile(inputPath,'synthetic source');
    const options={ownerKey:key,inputPath,projectId:'project',outboxDir,now};
    const original=await stageMediaUpload(options),chunkWire=await readFile(join(outboxDir,'0.vsmedia'));
    await unlink(join(outboxDir,'upload.json'));await unlink(join(outboxDir,'manifest.vsmedia'));
    const recovered=await stageMediaUpload({...options,now:now+1000});
    assert.equal(recovered.attachmentId,original.attachmentId);
    assert.deepEqual(await readFile(join(outboxDir,'0.vsmedia')),chunkWire);
    assert.equal(recovered.chunks[0].wireSha256,original.chunks[0].wireSha256);
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('CLI failure and retry keep ciphertext stable and never print key, input name, project or private paths',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'vs-media-cli-'));
  try{
    const inputPath=join(directory,'private-original-source.mp4'),outboxDir=join(directory,'private-outbox'),keyFile=join(directory,'owner-private-key');
    await writeFile(inputPath,'synthetic source');await writeFile(keyFile,'vsapp1.'+key,{mode:0o600});
    await writeFile(join(directory,'gh'),`#!/usr/bin/env node\nconst fs=require('node:fs');if(process.argv.includes('PUT')){fs.appendFileSync(process.env.VS_SYNTHETIC_CAPTURE,fs.readFileSync(0,'utf8')+'\\n');process.stderr.write('HTTP 503');process.exit(1);}process.stderr.write('HTTP 404');process.exit(1);\n`,{mode:0o700});
    const args=['scripts/native-media-mailbox.mjs','--app-key-file',keyFile,'--input',inputPath,'--project-id','private-project','--outbox-dir',outboxDir];
    const env={...process.env,PATH:directory+':'+process.env.PATH,VS_SYNTHETIC_CAPTURE:join(directory,'capture')};
    const first=spawnSync(process.execPath,args,{encoding:'utf8',env}),second=spawnSync(process.execPath,args,{encoding:'utf8',env});
    assert.equal(first.status,1);assert.equal(second.status,1);
    assert.deepEqual(JSON.parse(first.stdout),JSON.parse(second.stdout));
    const capture=(await readFile(join(directory,'capture'),'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(capture.length,2);assert.equal(capture[0].content,capture[1].content);
    for(const secret of [key,'private-original-source.mp4','private-project',directory]){
      assert.ok(!first.stdout.includes(secret));assert.ok(!first.stderr.includes(secret));assert.ok(!second.stdout.includes(secret));assert.ok(!second.stderr.includes(secret));
    }
  }finally{await rm(directory,{recursive:true,force:true});}
});
