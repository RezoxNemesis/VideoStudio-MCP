import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';

// These tests run the implementation's boundary helpers with explicit fake
// network/cache observers. They do not claim that ChatGPT injects file params.
const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
function helpers(overrides={}) {
  const context={crypto:webcrypto,TextEncoder,Response,Request,Headers,URL,Date,
    ReadableStream,TransformStream,Uint8Array,atob,...overrides};
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('const JH'),source.indexOf('export class VideoStudioState'))
    +'\nglobalThis.api={attachmentFile,publicAttachmentUrl,queueAttachment,privateHandoffContent,queueInlineAttachment};',context);
  return context.api;
}

test('additive host file metadata and missing file_id still yield a validated reference',()=>{
  const api=helpers();
  const actual=api.attachmentFile({download_url:'https://cdn.example.com/signed/movie',file_name:'movie.mp4',
    mime_type:'video/mp4',file_size:8192,sha256:'A'.repeat(64),expires_at:1234,unrelated_metadata:{host:true}});
  assert.equal(actual.size,8192);
  assert.equal(actual.sha256,'a'.repeat(64));
  assert.equal(actual.file_id,'');
  assert.equal(actual.download_url,'https://cdn.example.com/signed/movie');
});

test('file_id and sandbox-only references fail before any downstream effects',async()=>{
  let effects=0;
  const st={appCreateHandoff(){effects++;},appEnqueueV3(){effects++;}};
  await assert.rejects(helpers().queueAttachment(st,'owner',{file_id:'file_123'}),/did not supply.*download_url/);
  await assert.rejects(helpers().queueAttachment(st,'owner',{download_url:'sandbox:/mnt/data/video.mp4'}),/HTTPS/);
  assert.equal(effects,0);
});

test('attachment queue uses pre-existing RPC methods and keeps signed URL out of native parameters',async()=>{
  const calls=[];
  const st={
    async appCreateHandoff(owner,url,meta){calls.push({owner,url,meta});return{id:'handoff',name:meta.name,mime:meta.mime,expiresAt:99};},
    async appEnqueueV3(owner,action,parameters){calls.push({owner,action,parameters});return{id:'command',action,parameters};}
  };
  const result=await helpers().queueAttachment(st,'owner',{download_url:'https://cdn.example.com/file?secret=signed',
    file_name:'input.mp4',file_id:'file_123',size:33,sha256:'b'.repeat(64)},'project');
  assert.equal(result.action,'import_chat_file');
  assert.equal(calls[1].parameters.handoffId,'handoff');
  assert.equal(calls[1].parameters.size,33);
  assert.equal(calls[1].parameters.fileId,'file_123');
  assert.equal(calls[1].parameters.sha256,'b'.repeat(64));
  assert.equal(JSON.stringify(calls[1]).includes('secret=signed'),false);
});

test('private relay preserves Range, If-Range, validators and partial status without forwarding owner bearer',async()=>{
  let observed;
  const api=helpers({fetch:async(url,options)=>{observed={url,options};return new Response('abc',{
    status:206,headers:{'content-range':'bytes 3-5/6','content-length':'3','etag':'"version"','accept-ranges':'bytes'}});}});
  const response=await api.privateHandoffContent(new Request('https://worker.example.com/private',{
    headers:{range:'bytes=3-','if-range':'"version"',authorization:'Bearer owner-secret'}}),{
    sourceUrl:'https://cdn.example.com/source',name:'source.mp4',mime:'video/mp4'});
  assert.equal(response.status,206);
  assert.equal(response.headers.get('content-range'),'bytes 3-5/6');
  assert.equal(response.headers.get('etag'),'"version"');
  assert.equal(observed.options.headers.get('range'),'bytes=3-');
  assert.equal(observed.options.headers.get('if-range'),'"version"');
  assert.equal(observed.options.headers.has('authorization'),false);
  assert.equal(observed.options.redirect,'manual');
});

test('every redirect destination is validated before a fetch',async()=>{
  const destinations=[];
  const api=helpers({fetch:async url=>{destinations.push(url);return new Response(null,{status:302,
    headers:{location:'https://169.254.169.254/latest/meta-data'}});}});
  await assert.rejects(api.privateHandoffContent(new Request('https://worker.example.com/private'),{
    sourceUrl:'https://cdn.example.com/redirect',name:'image.png',mime:''}),/Private-network/);
  assert.deepEqual(destinations,['https://cdn.example.com/redirect']);
});

test('range requests to cached uploads use the same private cache key and preserve partial response',async()=>{
  let cacheRequest;
  const api=helpers({caches:{default:{async match(request){cacheRequest=request;return new Response('tail',{
    status:206,headers:{'content-range':'bytes 10-13/14','content-length':'4'}});}}}});
  const response=await api.privateHandoffContent(new Request('https://worker.example.com/private',{
    headers:{range:'bytes=10-'}}),{cacheUrl:'https://worker.example.com/__videostudio_private_upload/private-id',name:'video.mp4'});
  assert.equal(cacheRequest.headers.get('range'),'bytes=10-');
  assert.equal(response.status,206);
  assert.equal(response.headers.get('content-range'),'bytes 10-13/14');
});

test('bounded inline bytes are staged, never stored inside the durable command',async()=>{
  let cached,queued;
  const api=helpers({caches:{default:{async put(request,response){cached={request,bytes:new Uint8Array(await response.arrayBuffer())};},async delete(){}}}});
  const st={async appStatusV3(){return{registered:true,device:{permissionMode:'everything',controlPaused:false}};},
    async appCreateCachedHandoff(owner,url,meta){return{id:'handoff',...meta};},
    async appEnqueueV3(owner,action,parameters){queued={action,parameters};return{id:'command',...queued};}};
  const result=await api.queueInlineAttachment(st,'owner',{mime:'image/png',name:'image.png',base64:'YWJj',sha256:'c'.repeat(64)},'https://worker.example.com');
  assert.deepEqual(Array.from(cached.bytes),[97,98,99]);
  assert.equal(result.action,'import_chat_file');
  assert.equal(queued.parameters.size,3);
  assert.equal(Object.hasOwn(queued.parameters,'base64'),false);
  assert.equal(cached.request.url.includes('__videostudio_private_upload'),true);
});
