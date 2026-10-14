import {createHmac,createCipheriv,createDecipheriv,createHash,randomBytes,randomUUID} from 'node:crypto';
import {readFile,mkdir,chmod,link,unlink,open,lstat} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {basename,dirname,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {channelForKey,parseAppKey,encodePacket,decodePacket,publishPacket} from './native-agent-mailbox.mjs';

const SCHEMA='videostudio.agent.media.v1',CHUNK='videostudio.agent.media-chunk.v1',MANIFEST='videostudio.agent.media-manifest.v1';
export const MEDIA_LIMITS=Object.freeze({maxBytes:256*1024*1024,chunkBytes:524288,maxChunks:512,maxEnvelopeBytes:1024*1024,maxManifestBytes:256*1024,maxTtlMs:7*86400000,maxNameCodeUnits:180,maxNameUtf8Bytes:255});
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,hex=/^[a-f0-9]{64}$/;
const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x),wire=x=>Buffer.from(JSON.stringify(x));
const sha=x=>createHash('sha256').update(x).digest('hex');
const invalid=()=>{throw new Error('Invalid encrypted media transfer');};
const integer=(n,min,max)=>Number.isSafeInteger(n)&&n>=min&&n<=max;
const exact=(value,keys)=>object(value)&&Object.keys(value).length===keys.length&&keys.every(k=>Object.hasOwn(value,k));
function b64url(value){if(typeof value!=='string'||!/^[A-Za-z0-9_-]+$/.test(value))invalid();const b=Buffer.from(value,'base64url');if(b.toString('base64url')!==value)invalid();return b;}
function b64(value){if(typeof value!=='string'||value.length>Math.ceil(MEDIA_LIMITS.chunkBytes/3)*4||!/^[A-Za-z0-9+/]+={0,2}$/.test(value))invalid();const b=Buffer.from(value,'base64');if(b.toString('base64')!==value||b.length<1||b.length>MEDIA_LIMITS.chunkBytes)invalid();return b;}
function identity(key,id,index){if(typeof key!=='string'||key.length<32||!uuid.test(id)||!integer(index,-1,511))invalid();}
function metadata(name,mime){
  // Java String.length and JS String.length both count UTF16 code units. Reject malformed
  // pairs because their UTF8 replacement behavior differs across the two runtimes.
  if(typeof name!=='string'||name.length>MEDIA_LIMITS.maxNameCodeUnits||Buffer.byteLength(name)>MEDIA_LIMITS.maxNameUtf8Bytes||!name||name==='.'||name==='..'||/[\x00-\x1f\x7f/\\]/.test(name)||Array.from(name).some(c=>c.length===1&&c.charCodeAt(0)>=0xd800&&c.charCodeAt(0)<=0xdfff)||typeof mime!=='string'||mime.length>127||!/^[a-z0-9][a-z0-9!#$&^_.+-]*\/[a-z0-9][a-z0-9!#$&^_.+-]*$/.test(mime))invalid();
}

export function validateMediaManifest(m,{now=Date.now()}={}){
  if(!exact(m,['schema','attachmentId','name','mime','size','sha256','chunkBytes','chunks','createdAtMs','expiresAtMs'])||m.schema!==MANIFEST||!uuid.test(m.attachmentId)||!integer(m.size,1,MEDIA_LIMITS.maxBytes)||!hex.test(m.sha256)||m.chunkBytes!==MEDIA_LIMITS.chunkBytes||!Array.isArray(m.chunks)||m.chunks.length!==Math.ceil(m.size/m.chunkBytes)||m.chunks.length>512||!integer(m.createdAtMs,0,Number.MAX_SAFE_INTEGER)||!integer(m.expiresAtMs,m.createdAtMs+1,Number.MAX_SAFE_INTEGER)||m.expiresAtMs-m.createdAtMs>MEDIA_LIMITS.maxTtlMs||m.createdAtMs>now+300000||m.expiresAtMs<=now||wire(m).length>MEDIA_LIMITS.maxManifestBytes)invalid();
  metadata(m.name,m.mime);
  for(let i=0;i<m.chunks.length;i++){const c=m.chunks[i],expected=Math.min(m.chunkBytes,m.size-i*m.chunkBytes);if(!exact(c,['index','bytes','sha256'])||c.index!==i||c.bytes!==expected||!hex.test(c.sha256))invalid();}
  return m;
}
function validatePlain(p,id,index,now){
  if(index===-1){validateMediaManifest(p,{now});if(p.attachmentId!==id)invalid();}
  else if(!exact(p,['schema','attachmentId','index','base64'])||p.schema!==CHUNK||p.attachmentId!==id||p.index!==index)invalid();else b64(p.base64);
  return p;
}
export function encodeMediaEnvelope(key,id,index,plain,{nonce=randomBytes(12),now=Date.now()}={}){
  identity(key,id,index);validatePlain(plain,id,index,now);
  if(!Buffer.isBuffer(nonce)||nonce.length!==12)invalid();const channel=channelForKey(key);
  const cipher=createCipheriv('aes-256-gcm',createHmac('sha256',key).update('VideoStudio media encryption v1').digest(),nonce);
  cipher.setAAD(Buffer.from(`${SCHEMA}:${channel}:${id}:${index}`));
  const envelope={schema:SCHEMA,channel,attachmentId:id,index,nonce:nonce.toString('base64url'),ciphertext:Buffer.concat([cipher.update(wire(plain)),cipher.final(),cipher.getAuthTag()]).toString('base64url')};
  if(wire(envelope).length>MEDIA_LIMITS.maxEnvelopeBytes)invalid();return envelope;
}
export function decodeMediaEnvelope(key,envelope,{now=Date.now()}={}){
  if(!exact(envelope,['schema','channel','attachmentId','index','nonce','ciphertext'])||envelope.schema!==SCHEMA||envelope.channel!==channelForKey(key)||wire(envelope).length>MEDIA_LIMITS.maxEnvelopeBytes)invalid();
  const {attachmentId:id,index}=envelope;identity(key,id,index);const nonce=b64url(envelope.nonce),ciphertext=b64url(envelope.ciphertext);
  if(nonce.length!==12||ciphertext.length<17)invalid();
  const decipher=createDecipheriv('aes-256-gcm',createHmac('sha256',key).update('VideoStudio media encryption v1').digest(),nonce);
  decipher.setAAD(Buffer.from(`${SCHEMA}:${envelope.channel}:${id}:${index}`));decipher.setAuthTag(ciphertext.subarray(-16));
  const plain=Buffer.concat([decipher.update(ciphertext.subarray(0,-16)),decipher.final()]);
  if(index===-1&&plain.length>MEDIA_LIMITS.maxManifestBytes)invalid();
  return validatePlain(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(plain)),id,index,now);
}

async function privateWrite(path,data){const file=await open(path,'wx',0o600);try{await file.writeFile(data);await file.sync();}finally{await file.close();}}
async function privateImmutable(path,data){
  const temporary=path+'.'+randomUUID()+'.tmp';
  try{await privateWrite(temporary,data);await link(temporary,path);const directory=await open(dirname(path),'r');try{await directory.sync();}finally{await directory.close();}}
  finally{await unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
}
const mimeFor=name=>({mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm',mkv:'video/x-matroska',jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png',webp:'image/webp',gif:'image/gif',mp3:'audio/mpeg',m4a:'audio/mp4',aac:'audio/aac',wav:'audio/wav',ogg:'audio/ogg',flac:'audio/flac'}[name.split('.').at(-1)?.toLowerCase()]||'application/octet-stream');
async function hashInput(inputPath){
  const input=await open(inputPath,'r');try{const info=await input.stat();if(!info.isFile()||!integer(info.size,1,MEDIA_LIMITS.maxBytes))invalid();const hash=createHash('sha256');for await(const chunk of input.createReadStream({autoClose:false}))hash.update(chunk);return {size:info.size,sha256:hash.digest('hex')};}finally{await input.close();}
}
async function readStaged(outboxDir,name){const path=join(outboxDir,name);const info=await lstat(path);if(!info.isFile()||info.isSymbolicLink()||info.size>MEDIA_LIMITS.maxEnvelopeBytes)invalid();return readFile(path);}

/** One outbox directory is one durable attachment. Reuse it to retry that exact input intent. */
export async function stageMediaUpload({ownerKey,inputPath,projectId,outboxDir,name=basename(inputPath),mime=mimeFor(name),now=Date.now()}){
  if(typeof projectId!=='string'||!projectId||projectId.length>128||/[\x00-\x1f\x7f]/.test(projectId))invalid();metadata(name,mime);
  const source=await hashInput(inputPath),channel=channelForKey(ownerKey);
  const intentSha256=sha(wire({channel,projectId,name,mime,...source}));
  await mkdir(outboxDir,{recursive:true,mode:0o700});const folder=await lstat(outboxDir);if(!folder.isDirectory()||folder.isSymbolicLink())invalid();await chmod(outboxDir,0o700);
  const statePath=join(outboxDir,'upload.json');let state;
  try{const info=await lstat(statePath);if(!info.isFile()||info.isSymbolicLink()||info.size>256*1024)invalid();state=JSON.parse(await readFile(statePath,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
  if(state){if(state.intentSha256!==intentSha256)throw new Error('Persisted media intent differs');await validateStaged(state,{ownerKey,outboxDir,now});return state;}
  const preparationPath=join(outboxDir,'preparation.json');let preparation;
  try{preparation=JSON.parse(await readStaged(outboxDir,'preparation.json'));}
  catch(e){if(e.code!=='ENOENT')throw e;preparation={schema:'videostudio.agent.media-preparation.v1',intentSha256,channel,attachmentId:randomUUID(),createdAtMs:now,expiresAtMs:now+86400000};
    try{await privateImmutable(preparationPath,wire(preparation));}catch(e){if(e.code!=='EEXIST')throw e;preparation=JSON.parse(await readStaged(outboxDir,'preparation.json'));}}
  if(!exact(preparation,['schema','intentSha256','channel','attachmentId','createdAtMs','expiresAtMs'])||preparation.schema!=='videostudio.agent.media-preparation.v1'||preparation.intentSha256!==intentSha256||preparation.channel!==channel||!uuid.test(preparation.attachmentId)||!integer(preparation.createdAtMs,0,now+300000)||!integer(preparation.expiresAtMs,now+1,preparation.createdAtMs+MEDIA_LIMITS.maxTtlMs))invalid();
  const {attachmentId,createdAtMs,expiresAtMs}=preparation,chunks=[];
  const stageWire=async(name,index,plain)=>{
    try{const existing=await readStaged(outboxDir,name);const envelope=JSON.parse(existing);if(envelope.attachmentId!==attachmentId||envelope.index!==index||!wire(decodeMediaEnvelope(ownerKey,envelope,{now})).equals(wire(plain)))invalid();return existing;}
    catch(e){if(e.code!=='ENOENT')throw e;}
    const encrypted=wire(encodeMediaEnvelope(ownerKey,attachmentId,index,plain,{now}));
    try{await privateImmutable(join(outboxDir,name),encrypted);return encrypted;}
    catch(e){if(e.code!=='EEXIST')throw e;const existing=await readStaged(outboxDir,name);const envelope=JSON.parse(existing);if(envelope.attachmentId!==attachmentId||envelope.index!==index||!wire(decodeMediaEnvelope(ownerKey,envelope,{now})).equals(wire(plain)))invalid();return existing;}
  };
  const input=await open(inputPath,'r'),inputHash=createHash('sha256');let actualSize=0;
  try{
    for(let index=0;actualSize<source.size;index++){
      const bytes=Buffer.alloc(Math.min(MEDIA_LIMITS.chunkBytes,source.size-actualSize));let total=0;
      while(total<bytes.length){const result=await input.read(bytes,total,bytes.length-total,actualSize+total);if(!result.bytesRead)invalid();total+=result.bytesRead;}
      actualSize+=bytes.length;inputHash.update(bytes);
      const plain={schema:CHUNK,attachmentId,index,base64:bytes.toString('base64')};
      const encrypted=await stageWire(index+'.vsmedia',index,plain);
      chunks.push({index,bytes:bytes.length,sha256:sha(bytes),wireSha256:sha(encrypted)});
    }
    if((await input.stat()).size!==source.size||inputHash.digest('hex')!==source.sha256)throw new Error('Input changed during encrypted staging');
  }finally{await input.close();}
  const manifest={schema:MANIFEST,attachmentId,name,mime,size:source.size,sha256:source.sha256,chunkBytes:MEDIA_LIMITS.chunkBytes,
    chunks:chunks.map(({index,bytes,sha256})=>({index,bytes,sha256})),createdAtMs,expiresAtMs};
  const encryptedManifest=await stageWire('manifest.vsmedia',-1,manifest);
  const manifestSha256=sha(encryptedManifest),packetId=randomUUID(),commandId=randomUUID();
  const packet=encodePacket(ownerKey,{schema:'videostudio.agent.commands.v1',packetId,issuedAtMs:now,expiresAtMs,
    commands:[{id:commandId,action:'import_mailbox_media',parameters:{projectId,attachmentId,manifestSha256}}]},{now});
  state={schema:'videostudio.agent.media-outbox.v1',channel,attachmentId,projectId,intentSha256,manifestSha256,chunks,packetId,commandId,packet};
  try{await privateImmutable(statePath,wire(state));}
  catch(e){if(e.code!=='EEXIST')throw e;state=JSON.parse(await readFile(statePath,'utf8'));if(state.intentSha256!==intentSha256)invalid();await validateStaged(state,{ownerKey,outboxDir,now});}
  return state;
}

async function validateStaged(state,{ownerKey,outboxDir,now}){
  if(!object(state)||state.schema!=='videostudio.agent.media-outbox.v1'||state.channel!==channelForKey(ownerKey)||!uuid.test(state.attachmentId)||!uuid.test(state.packetId)||!uuid.test(state.commandId)||!hex.test(state.intentSha256)||!hex.test(state.manifestSha256)||!Array.isArray(state.chunks)||state.chunks.length<1||state.chunks.length>512)invalid();
  const manifestWire=await readStaged(outboxDir,'manifest.vsmedia');if(sha(manifestWire)!==state.manifestSha256)invalid();
  const manifest=decodeMediaEnvelope(ownerKey,JSON.parse(manifestWire),{now});
  if(manifest.attachmentId!==state.attachmentId||manifest.chunks.length!==state.chunks.length)invalid();
  const inputHash=createHash('sha256');
  for(let index=0;index<state.chunks.length;index++){
    const entry=state.chunks[index],expected=manifest.chunks[index];
    if(!object(entry)||entry.index!==index||entry.bytes!==expected.bytes||entry.sha256!==expected.sha256||!hex.test(entry.wireSha256))invalid();
    const encrypted=await readStaged(outboxDir,index+'.vsmedia');if(sha(encrypted)!==entry.wireSha256)invalid();
    const envelope=JSON.parse(encrypted);if(envelope.attachmentId!==state.attachmentId||envelope.index!==index)invalid();
    const data=b64(decodeMediaEnvelope(ownerKey,envelope,{now}).base64);
    if(data.length!==expected.bytes||sha(data)!==expected.sha256)invalid();inputHash.update(data);
  }
  if(inputHash.digest('hex')!==manifest.sha256||sha(wire({channel:state.channel,projectId:state.projectId,name:manifest.name,mime:manifest.mime,size:manifest.size,sha256:manifest.sha256}))!==state.intentSha256)invalid();
  if(!exact(state.packet,['schema','channel','nonce','ciphertext']))invalid();
  const packet=decodePacket(ownerKey,state.packet,{now}),command=packet.commands[0];
  if(packet.packetId!==state.packetId||packet.commands.length!==1||command.id!==state.commandId||command.action!=='import_mailbox_media'||!exact(command.parameters,['projectId','attachmentId','manifestSha256'])||command.parameters.projectId!==state.projectId||command.parameters.attachmentId!==state.attachmentId||command.parameters.manifestSha256!==state.manifestSha256)invalid();
}
const ghDefault=async(args,input)=>JSON.parse(execFileSync('gh',args,{input,encoding:'utf8',timeout:30000,maxBuffer:4*1024*1024,stdio:['pipe','pipe','pipe']}));
const status=(e,n)=>e.status===n||new RegExp('(?:HTTP |status |\\()'+n+'(?:\\)|\\s|$)').test(String(e.stderr||e.message||''));

/** No raw bytes, names or project identifiers are written into the public GitHub branch. */
export async function publishMediaUpload(state,{ownerKey,outboxDir,gh=ghDefault,now=Date.now()}={}){
  await validateStaged(state,{ownerKey,outboxDir,now});
  const repo='repos/RezoxNemesis/VideoStudio-MCP',branch='videostudio-agent-mailbox';
  const base=`${repo}/contents/mailbox/${state.channel}/media/${state.attachmentId}`;
  for(const name of [...state.chunks.map(c=>c.index+'.vsmedia'),'manifest.vsmedia']){
    const path=base+'/'+name,encrypted=await readStaged(outboxDir,name);
    const expectedHash=name==='manifest.vsmedia'?state.manifestSha256:state.chunks[Number(name.split('.')[0])].wireSha256;
    if(sha(encrypted)!==expectedHash)invalid();
    const get=async()=>{try{return await gh(['api',path+'?ref='+branch]);}catch(e){if(status(e,404))return null;throw e;}};
    const matches=async item=>{
      if(item?.encoding==='none'){if(!/^[a-f0-9]{40}$/.test(item.sha))invalid();item=await gh(['api',`${repo}/git/blobs/${item.sha}`]);}
      if(!item||typeof item.content!=='string'||item.content.length>2*1024*1024||(item.encoding!==undefined&&item.encoding!=='base64')||!/^[A-Za-z0-9+/=\r\n]*$/.test(item.content)||!Buffer.from(item.content,'base64').equals(encrypted))throw new Error('Immutable media conflict');
    };
    const existing=await get();if(existing){await matches(existing);continue;}
    try{await gh(['api','--method','PUT',path,'--input','-'],JSON.stringify({message:'Encrypted VideoStudio media',branch,content:encrypted.toString('base64')}));}
    catch(e){if(!status(e,409)&&!status(e,422))throw e;await matches(await get());}
  }
  return {attachmentId:state.attachmentId,manifestSha256:state.manifestSha256,delivery:'media_published',execution:'unconfirmed'};
}

async function cli(){
  let args=process.argv.slice(2);if(args[0]==='send')args=args.slice(1);const options={};
  const allowed=new Set(['--app-key-file','--input','--project-id','--outbox-dir','--name','--mime']);
  for(let i=0;i<args.length;i+=2){if(!allowed.has(args[i])||!args[i+1]||options[args[i]]!==undefined)invalid();options[args[i]]=args[i+1];}
  for(const field of ['--app-key-file','--input','--project-id','--outbox-dir'])if(!options[field])invalid();
  const {ownerKey}=parseAppKey(await readFile(options['--app-key-file'],'utf8'));
  const outboxDir=options['--outbox-dir'];
  const state=await stageMediaUpload({ownerKey,inputPath:options['--input'],projectId:options['--project-id'],outboxDir,
    ...(options['--name']?{name:options['--name']}:{}),...(options['--mime']?{mime:options['--mime']}:{})});
  console.log(JSON.stringify({attachmentId:state.attachmentId,commandId:state.commandId,delivery:'prepared',execution:'unconfirmed'}));
  await publishMediaUpload(state,{ownerKey,outboxDir});
  await publishPacket(state.packet,{packetId:state.packetId});
  console.log(JSON.stringify({attachmentId:state.attachmentId,commandId:state.commandId,delivery:'published',execution:'unconfirmed'}));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)cli().catch(()=>{console.error('Encrypted media upload failed; keep the private outbox and retry the same input.');process.exitCode=1;});
