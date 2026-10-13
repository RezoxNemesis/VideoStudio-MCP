import {createHmac,createCipheriv,createDecipheriv,createHash,randomBytes,randomUUID} from 'node:crypto';
import {readFile,writeFile,mkdir,chmod,rename,unlink} from 'node:fs/promises';
import {execFileSync,spawnSync} from 'node:child_process';
import {isDeepStrictEqual} from 'node:util';
import {dirname,join} from 'node:path';
import {pathToFileURL} from 'node:url';
const P='videostudio.agent.packet.v1', R='videostudio.agent.receipt-envelope.v1', MiB=1024*1024;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const fail=()=>{throw new Error('Invalid mailbox data');};
const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);
const bytes=x=>Buffer.from(JSON.stringify(x));
const hmac=(key,label)=>createHmac('sha256',key).update(label).digest();
export const channelForKey=key=>hmac(key,'VideoStudio agent channel v1').toString('hex').slice(0,32);
export function parseAppKey(input){let value=input.trim(),origin='https://wispy-queen-f9b5.prakasharuntandon634.workers.dev'; if(value.startsWith('{')){const j=JSON.parse(value);value=j.appKey||j.privateEndpoint||j.privateEndpointUrl||j.endpoint||'';}if(value.startsWith('vsapp1.'))value=value.slice(7);else{let u;try{u=new URL(value);}catch{fail();}const m=u.pathname.match(/^\/app-mcp-v3\/([A-Za-z0-9_-]{32,})\/?$/);if(!m)fail();value=m[1];origin=u.origin;}if(!/^[A-Za-z0-9_-]{32,}$/.test(value))fail();return {ownerKey:value,origin};}
function binary(s){if(typeof s!=='string'||!s||!/^[A-Za-z0-9_-]+$/.test(s))fail();const b=Buffer.from(s,'base64url');if(b.toString('base64url')!==s)fail();return b;}
function seal(key,plain,aad,nonce){const raw=bytes(plain);if(raw.length>2*MiB)fail();nonce=nonce||randomBytes(12);if(nonce.length!==12)fail();const c=createCipheriv('aes-256-gcm',hmac(key,'VideoStudio agent encryption v1'),nonce);c.setAAD(Buffer.from(aad));return {nonce:nonce.toString('base64url'),ciphertext:Buffer.concat([c.update(raw),c.final(),c.getAuthTag()]).toString('base64url')};}
function open(key,e,aad){if(bytes(e).length>3*MiB)fail();const n=binary(e.nonce),b=binary(e.ciphertext);if(n.length!==12||b.length<16||b.length>2*MiB+16)fail();const c=createDecipheriv('aes-256-gcm',hmac(key,'VideoStudio agent encryption v1'),n);c.setAAD(Buffer.from(aad));c.setAuthTag(b.subarray(-16));const raw=Buffer.concat([c.update(b.subarray(0,-16)),c.final()]);return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(raw));}
function validate(p,now){if(!object(p)||p.schema!=='videostudio.agent.commands.v1'||!uuid.test(p.packetId)||!Number.isSafeInteger(p.issuedAtMs)||!Number.isSafeInteger(p.expiresAtMs)||p.expiresAtMs<=p.issuedAtMs||p.expiresAtMs-p.issuedAtMs>7*86400000||p.issuedAtMs>now+300000||p.expiresAtMs<=now||!Array.isArray(p.commands)||p.commands.length<1||p.commands.length>128||bytes(p).length>2*MiB)fail();const ids=new Set();for(const c of p.commands){if(!object(c)||!uuid.test(c.id)||ids.has(c.id)||typeof c.action!=='string'||!(/^[a-z][a-z0-9_]{0,95}$/).test(c.action)||Object.keys(c).some(k=>!['id','action','parameters'].includes(k))||!object(c.parameters)||Object.keys(c.parameters).some(k=>k.startsWith('_'))||bytes(c.parameters).length>512*1024)fail();ids.add(c.id);}return p;}
export function encodePacket(key,p,{now=Date.now(),nonce}={}){validate(p,now);const channel=channelForKey(key);return {schema:P,channel,...seal(key,p,P+':'+channel,nonce)};}
export function decodePacket(key,e,{now=Date.now(),seenPacketIds}={}){if(!object(e)||e.schema!==P||e.channel!==channelForKey(key))fail();const p=validate(open(key,e,P+':'+e.channel),now);if(seenPacketIds?.has(p.packetId))fail();return p;}
export function encodeReceipt(key,commandId,p,{nonce}={}){if(!uuid.test(commandId)||!object(p))fail();const channel=channelForKey(key);return {schema:R,channel,commandId,...seal(key,p,'videostudio.agent.receipt.v1:'+channel+':'+commandId,nonce)};}
export function decodeReceipt(key,commandId,e){if(!object(e)||e.schema!==R||e.channel!==channelForKey(key)||e.commandId!==commandId||!uuid.test(commandId))fail();const p=open(key,e,'videostudio.agent.receipt.v1:'+e.channel+':'+commandId);if(!object(p)||(p.commandId!==undefined&&p.commandId!==commandId))fail();return p;}
export async function readReceipt(key,id,origin='https://wispy-queen-f9b5.prakasharuntandon634.workers.dev',fetcher=fetch){if(!uuid.test(id))fail();const signal=AbortSignal.timeout(30000);let total,hash,fragments=[];for(let i=0;i<(total??1);i++){const deviceId=`vsm_${channelForKey(key)}_${id.replaceAll('-','')}_p${i}`;const response=await fetcher(new URL('/api/device/status?deviceId='+deviceId,origin),{signal});if(!response.ok)throw new Error('Receipt service unavailable');const body=await boundedJson(response);const caps=body.device?.capabilities;if(!Array.isArray(caps)||caps[0]!=='videostudio.agent.receipt-carrier.v1')return null;const page=JSON.parse(caps[1]);if(page.commandId!==id||page.index!==i||!Number.isInteger(page.total)||page.total<1||page.total>128||typeof page.fragment!=='string'||page.fragment.length>32768||/[^\x00-\x7f]/.test(page.fragment)||!/^[a-f0-9]{64}$/.test(page.receiptSha256))fail();if(i===0){total=page.total;hash=page.receiptSha256;}if(page.total!==total||page.receiptSha256!==hash)fail();fragments.push(page.fragment);if(fragments.reduce((n,f)=>n+f.length,0)>3*MiB)fail();}const wire=fragments.join('');if(createHash('sha256').update(wire).digest('hex')!==hash)fail();return decodeReceipt(key,id,JSON.parse(wire));}
export async function boundedJson(response){
  if(Number(response.headers?.get('content-length')||0)>3*MiB)fail();
  if(!response.body?.getReader){const body=await response.json();if(bytes(body).length>3*MiB)fail();return body;}
  const reader=response.body.getReader();let size=0;const chunks=[];
  try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>3*MiB)fail();chunks.push(value);}return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks)));}finally{await reader.cancel();}
}
export async function writePrivateReceipt(path,receipt){
  const temporary=join(dirname(path),'.videostudio-receipt-'+randomUUID()+'.tmp');
  try{await writeFile(temporary,JSON.stringify(receipt,null,2)+'\n',{mode:0o600,flag:'wx'});await rename(temporary,path);}finally{await unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
}
const ghDefault=async(args,input)=>JSON.parse(execFileSync('gh',args,{input,encoding:'utf8',timeout:30000,maxBuffer:5*MiB,stdio:['pipe','pipe','pipe']}));
const hasStatus=(e,status)=>e.status===status||new RegExp('(?:HTTP |status |\\()'+status+'(?:\\)|\\s|$)').test(String(e.stderr||e.message||''));
export async function publishPacket(packet,{packetId,gh=ghDefault}={}){
  if(!uuid.test(packetId)||packet.schema!==P||!/^[a-f0-9]{32}$/.test(packet.channel)||bytes(packet).length>3*MiB)fail();
  const repo='repos/RezoxNemesis/VideoStudio-MCP',branch='videostudio-agent-mailbox';
  const base=`${repo}/contents/mailbox/${packet.channel}`,packetPath=`${base}/${packetId}.vscmd`,indexPath=`${base}/index.json`,wire=bytes(packet);
  const get=async path=>{try{return await gh(['api',`${path}?ref=${branch}`]);}catch(e){if(hasStatus(e,404))return null;throw e;}};
  const matches=async item=>{
    if(item?.encoding==='none'){
      if(!/^[a-f0-9]{40}$/.test(item.sha))fail();
      item=await gh(['api',`${repo}/git/blobs/${item.sha}`]);
    }
    if(!item||typeof item.content!=='string'||item.content.length>5*MiB||!/^[A-Za-z0-9+/=\r\n]*$/.test(item.content)||(item.encoding!==undefined&&item.encoding!=='base64'))fail();
    const raw=Buffer.from(item.content,'base64');if(raw.length>3*MiB||!raw.equals(wire))throw new Error('Immutable packet conflict');
  };
  const put=(path,content,sha)=>gh(['api','--method','PUT',path,'--input','-'],JSON.stringify({message:'Encrypted Video studio mailbox',branch,content:content.toString('base64'),...(sha?{sha}:{})}));
  const existing=await get(packetPath);
  if(existing)await matches(existing);else{try{await put(packetPath,wire);}catch(e){if(!hasStatus(e,409)&&!hasStatus(e,422))throw e;await matches(await get(packetPath));}}
  for(let attempt=0;attempt<5;attempt++){
    const current=await get(indexPath);let index={schema:'videostudio.agent.index.v1',packets:[]};
    if(current){if(typeof current.content!=='string')fail();const raw=Buffer.from(current.content,'base64');if(raw.length>256*1024)fail();index=JSON.parse(raw.toString('utf8'));}
    if(!object(index)||index.schema!=='videostudio.agent.index.v1'||!Array.isArray(index.packets)||index.packets.length>4096||index.packets.some(p=>typeof p!=='string'||!uuid.test(p))||new Set(index.packets).size!==index.packets.length)fail();
    if(index.packets.includes(packetId))return {packetId,delivery:'published'};
    if(index.packets.length===4096)throw new Error('Mailbox index full');
    index.packets.push(packetId);
    try{await put(indexPath,bytes(index),current?.sha);return {packetId,delivery:'published'};}catch(e){if(!hasStatus(e,409)&&!hasStatus(e,422))throw e;}
  }
  throw new Error('Mailbox index conflict; retry the persisted packet');
}
async function cli(){
  const [mode,...args]=process.argv.slice(2),opts={};for(let i=0;i<args.length;i+=2)opts[args[i]]=args[i+1];
  const {ownerKey,origin}=parseAppKey(await readFile(opts['--app-key-file'],'utf8'));
  let id=opts['--command-id'];
  if(mode==='status'){
    if(!uuid.test(id)||!opts['--output-file'])fail();
    const receipt=await readReceipt(ownerKey,id,origin);
    if(receipt){await writePrivateReceipt(opts['--output-file'],receipt);}
    console.log(JSON.stringify({commandId:id,receipt:receipt?'authenticated':'pending',status:receipt?.status||null}));return;
  }
  if(mode!=='send')fail();
  const stateDir=join(dirname(opts['--app-key-file']),'.videostudio-mailbox-outbox');await mkdir(stateDir,{recursive:true,mode:0o700});await chmod(stateDir,0o700);
  let state,stateFile;
  if(opts['--retry-file']){stateFile=opts['--retry-file'];state=JSON.parse(await readFile(stateFile,'utf8'));id=state.commandId;}
  else{
    id=id||randomUUID();if(!uuid.test(id))fail();stateFile=join(stateDir,id+'.json');
    try{state=JSON.parse(await readFile(stateFile,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
    if(!state){
      if(!opts['--action'])fail();const parameters=opts['--parameters-file']?JSON.parse(await readFile(opts['--parameters-file'],'utf8')):{};
      const now=Date.now(),packetId=randomUUID();const packet=encodePacket(ownerKey,{schema:'videostudio.agent.commands.v1',packetId,issuedAtMs:now,expiresAtMs:now+86400000,commands:[{id,action:opts['--action'],parameters}]});
      state={schema:'videostudio.agent.outbox.v1',commandId:id,packetId,packet};await writeFile(stateFile,JSON.stringify(state)+'\n',{mode:0o600,flag:'wx'});
    }
  }
  const plain=decodePacket(ownerKey,state.packet);if(state.schema!=='videostudio.agent.outbox.v1'||plain.packetId!==state.packetId||plain.commands.length!==1||plain.commands[0].id!==id)fail();
  if(opts['--command-id']&&opts['--command-id']!==id)fail();
  if(opts['--action']&&opts['--action']!==plain.commands[0].action)throw new Error('Persisted command action differs');
  if(opts['--parameters-file']){const supplied=JSON.parse(await readFile(opts['--parameters-file'],'utf8'));if(!isDeepStrictEqual(supplied,plain.commands[0].parameters))throw new Error('Persisted command parameters differ');}
  console.log(JSON.stringify({commandId:id,packetId:state.packetId,delivery:'prepared',execution:'unconfirmed',retryFile:stateFile}));
  await publishPacket(state.packet,{packetId:state.packetId});
  console.log(JSON.stringify({commandId:id,packetId:state.packetId,delivery:'published',execution:'unconfirmed'}));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const proxyConfigured=Boolean(process.env.HTTPS_PROXY||process.env.HTTP_PROXY||process.env.https_proxy||process.env.http_proxy);
  if(proxyConfigured&&process.env.NODE_USE_ENV_PROXY!=='1'&&!process.execArgv.includes('--use-env-proxy')){
    if(!process.allowedNodeEnvironmentFlags.has('--use-env-proxy')){console.error('Mailbox proxy transport requires a Node version supporting environment proxies.');process.exitCode=1;}
    else{const child=spawnSync(process.execPath,['--use-env-proxy',...process.execArgv,process.argv[1],...process.argv.slice(2)],{env:process.env,stdio:'inherit'});process.exitCode=child.status??1;}
  }else cli().catch(()=>{console.error('Mailbox operation failed; check local inputs, GitHub access, and receipt service.');process.exitCode=1;});
}
