/** Optional server-side Drive plan backups. No ChatGPT OAuth grant is reused. */
import {fail,hash} from './personal-state.js';
const scope='https://www.googleapis.com/auth/drive.file';
const textEncoder=new TextEncoder(), textDecoder=new TextDecoder();
const b64=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes)));
const bytes=value=>Uint8Array.from(atob(value),c=>c.charCodeAt(0));
const random=()=>b64(crypto.getRandomValues(new Uint8Array(32))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const config=env=>({clientId:env.PERSONAL_DRIVE_CLIENT_ID||env.GOOGLE_DRIVE_CLIENT_ID||'',clientSecret:env.PERSONAL_DRIVE_CLIENT_SECRET||'',key:env.PERSONAL_TOKEN_KEY||''});
const configured=env=>{const c=config(env);return !!(c.clientId&&c.clientSecret&&c.key.length>=32);};
function requireConfig(env){if(!configured(env))throw fail(503,'Website Drive OAuth is not configured. ChatGPT Drive access cannot be reused as a website credential.');return config(env);}
async function encryptionKey(env){const c=requireConfig(env);const digest=await crypto.subtle.digest('SHA-256',textEncoder.encode(c.key));return crypto.subtle.importKey('raw',digest,'AES-GCM',false,['encrypt','decrypt']);}
export async function seal(env,owner,value) {const iv=crypto.getRandomValues(new Uint8Array(12)),key=await encryptionKey(env);const cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:textEncoder.encode(owner)},key,textEncoder.encode(JSON.stringify(value)));return {version:1,iv:b64(iv),ciphertext:b64(cipher)};}
export async function unseal(env,owner,value) {if(value?.version!==1)throw fail(409,'Drive credentials need to be reconnected.');const key=await encryptionKey(env);return JSON.parse(textDecoder.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:bytes(value.iv),additionalData:textEncoder.encode(owner)},key,bytes(value.ciphertext))));}
export async function driveStatus(state,auth,env) {const record=await state.personalVault(auth,'get','drive');return {configured:configured(env),connected:configured(env)&&!!record,scope,lastBackupAt:record?.lastBackupAt||null,mediaUploadsAvailable:false,backupType:'project-plan-json'};}
export async function driveConnect(state,auth,env,origin) {
  const c=requireConfig(env),owner=await state.personalAuth(auth),csrf=random(),verifier=random(),challenge=b64(await crypto.subtle.digest('SHA-256',textEncoder.encode(verifier))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
  const redirect=origin+'/api/personal/drive/callback';
  await state.personalVault(auth,'put','oauth:'+await hash(csrf),{expiresAt:Date.now()+10*60000,secret:await seal(env,owner.deviceId,{verifier,redirect})});
  const params=new URLSearchParams({client_id:c.clientId,redirect_uri:redirect,response_type:'code',scope,access_type:'offline',prompt:'consent',state:csrf,code_challenge:challenge,code_challenge_method:'S256'});
  return {url:'https://accounts.google.com/o/oauth2/v2/auth?'+params};
}
async function exchange(env,form,fetcher) {const c=requireConfig(env);const r=await fetcher('https://oauth2.googleapis.com/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({...form,client_id:c.clientId,client_secret:c.clientSecret})});let data;try{data=await r.json();}catch{throw fail(502,'Google returned an unreadable authorisation response.');}if(!r.ok||!data.access_token)throw fail(409,'Google Drive authorisation expired or was rejected. Reconnect Google Drive.');return data;}
export async function driveCallback(state,auth,env,url,fetcher=fetch) {
  requireConfig(env);const owner=await state.personalAuth(auth),csrf=url.searchParams.get('state')||'';
  if(!/^[A-Za-z0-9_-]{32,100}$/.test(csrf))throw fail(400,'Invalid Drive authorisation state.');
  const row=await state.personalVault(auth,'take','oauth:'+await hash(csrf));
  if(!row||row.expiresAt<Date.now())throw fail(400,'Drive authorisation expired or was already used.');
  if(url.searchParams.has('error'))throw fail(400,'Google Drive authorisation was not granted.');
  const proof=await unseal(env,owner.deviceId,row.secret);
  if(proof.redirect!==url.origin+'/api/personal/drive/callback')throw fail(400,'Authorisation callback origin changed.');
  const code=url.searchParams.get('code');if(!code||code.length>5000)throw fail(400,'Google authorisation code is missing.');
  const result=await exchange(env,{grant_type:'authorization_code',code,redirect_uri:proof.redirect,code_verifier:proof.verifier},fetcher);
  if(!result.refresh_token)throw fail(409,'Google did not issue an offline credential. Reconnect and grant consent for background plan backups.');
  if(result.scope&&!result.scope.split(' ').includes(scope))throw fail(403,'The required Drive file permission was not granted.');
  // Do not reuse another Google account's folder IDs after reconnecting.
  await state.personalVault(auth,'put','drive',{connectedAt:new Date().toISOString(),lastBackupAt:null,secret:await seal(env,owner.deviceId,{accessToken:result.access_token,refreshToken:result.refresh_token,expiresAt:Date.now()+Math.max(60,Math.min(3600,Number(result.expires_in)||3600))*1000}),folderId:'',backups:{}});
  return {connected:true};
}
export async function driveBackup(state,auth,env,projectId,fetcher=fetch) {
  requireConfig(env);const owner=await state.personalAuth(auth);
  if(owner.controlPaused||!['everything','all_tools'].includes(owner.permissionMode))throw fail(423,'Drive writes are paused by your native control setting.');
  const archive=await state.personalExport(auth,projectId),record=await state.personalVault(auth,'get','drive');
  if(!record)throw fail(409,'Connect Google Drive in Storage before creating a backup.');
  let credentials=await unseal(env,owner.deviceId,record.secret);
  if(credentials.expiresAt<Date.now()+60000){const result=await exchange(env,{grant_type:'refresh_token',refresh_token:credentials.refreshToken},fetcher);credentials={accessToken:result.access_token,refreshToken:result.refresh_token||credentials.refreshToken,expiresAt:Date.now()+Math.max(60,Math.min(3600,Number(result.expires_in)||3600))*1000};record.secret=await seal(env,owner.deviceId,credentials);await state.personalVault(auth,'put','drive',record);}
  async function google(path,options={}) {const r=await fetcher('https://www.googleapis.com'+path,{...options,headers:{...options.headers,authorization:'Bearer '+credentials.accessToken}});if(!r.ok)throw fail(r.status===401?409:502,r.status===401?'Reconnect Google Drive.':'Google Drive could not complete the backup. No successful backup is reported.');return r;}
  if(!record.folderId){const r=await google('/drive/v3/files?fields=id',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({name:'VideoStudio Personal',mimeType:'application/vnd.google-apps.folder'})});record.folderId=(await r.json()).id;if(!/^[\w-]+$/.test(record.folderId||''))throw fail(502,'Drive did not return a valid folder ID.');await state.personalVault(auth,'put','drive',record);}
  const content=JSON.stringify(archive,null,2),existing=record.backups?.[projectId];let fileId=existing?.fileId;
  if(fileId){await google('/upload/drive/v3/files/'+encodeURIComponent(fileId)+'?uploadType=media',{method:'PATCH',headers:{'content-type':'application/json'},body:content});}
  else{const boundary='vs_'+crypto.randomUUID(),metadata={name:archive.project.name+'.json',parents:[record.folderId],mimeType:'application/json'};const body='--'+boundary+'\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n'+JSON.stringify(metadata)+'\r\n--'+boundary+'\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n'+content+'\r\n--'+boundary+'--';const r=await google('/upload/drive/v3/files?uploadType=multipart&fields=id',{method:'POST',headers:{'content-type':'multipart/related; boundary='+boundary},body});fileId=(await r.json()).id;if(!/^[\w-]+$/.test(fileId||''))throw fail(502,'Drive did not return a valid backup ID.');}
  const readback=await google('/drive/v3/files/'+encodeURIComponent(fileId)+'?alt=media');
  if(await readback.text()!==content)throw fail(502,'Drive readback did not match the saved plan. Backup verification failed.');
  record.lastBackupAt=new Date().toISOString();record.backups={...record.backups,[projectId]:{fileId,revision:archive.project.revision,verifiedAt:record.lastBackupAt}};
  await state.personalVault(auth,'put','drive',record);
  return {ok:true,verified:true,fileId,revision:archive.project.revision,lastBackupAt:record.lastBackupAt,mediaIncluded:false};
}
