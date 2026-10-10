/** Additive private workspace. Existing native state and command queues are untouched. */
const enc = new TextEncoder();
export const PERSONAL_VERSION = '1.0.0';
export const fail = (status, message) => Object.assign(new Error(message), {status});
export const hash = async value => [...new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(String(value))))].map(n=>n.toString(16).padStart(2,'0')).join('');
const iso = () => new Date().toISOString();
const token = () => [...crypto.getRandomValues(new Uint8Array(32))].map(n=>n.toString(16).padStart(2,'0')).join('');
const ownKey = (id, suffix) => `personal:owner:${id}:${suffix}`;
const restricted = device => !['everything','all_tools'].includes(device.permissionMode);
const object = value => !!value && typeof value === 'object' && !Array.isArray(value);
const canonical = value => JSON.stringify(value, function(key, v) { return object(v) ? Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])) : v; });
function text(value, limit, label, fallback='') {
  if(value === undefined) return fallback;
  if(typeof value !== 'string' || value.length > limit || /\u0000/.test(value)) throw fail(400, `${label} must be text of at most ${limit} characters.`);
  return value;
}
const summary = p => ({id:p.id,name:p.name,brief:p.brief.slice(0,200),revision:p.revision,aspect:p.aspect,shotCount:p.shots.length,linkCount:p.links.length,updatedAt:p.updatedAt,createdAt:p.createdAt,nativeProjectId:p.nativeProjectId});
function validate(input, device, previous) {
  if(!object(input)) throw fail(400,'Project data must be an object.');
  const p = previous ? structuredClone(previous) : {name:'Untitled film',brief:'',notes:'',links:[],shots:[],aspect:'9:16',nativeProjectId:''};
  for(const [key,limit] of [['name',120],['brief',5000],['notes',12000],['nativeProjectId',160]]) if(input[key] !== undefined) p[key]=['name','nativeProjectId'].includes(key)?text(input[key],limit,key).trim():text(input[key],limit,key);
  if(!p.name) throw fail(400,'Give the project a name.');
  if(input.aspect !== undefined) p.aspect=input.aspect;
  if(!['9:16','16:9','1:1','4:5'].includes(p.aspect)) throw fail(400,'Unsupported aspect ratio.');
  if(input.links !== undefined) {
    if(!Array.isArray(input.links)||input.links.length>24) throw fail(400,'Use at most 24 reference links.');
    p.links=input.links.map(link=>{
      if(!object(link)) throw fail(400,'Invalid reference link.');
      const url=text(link.url,2000,'Link URL'); let parsed;
      try{parsed=new URL(url);}catch{throw fail(400,'Reference links must be valid HTTPS URLs.');}
      if(parsed.protocol!=='https:'||parsed.username||parsed.password) throw fail(400,'Reference links must use HTTPS without embedded credentials.');
      return {title:text(link.title,120,'Link title',parsed.hostname).trim(),url:parsed.href};
    });
  }
  if(input.shots !== undefined) {
    if(!Array.isArray(input.shots)||input.shots.length>48) throw fail(400,'Use at most 48 shots.');
    p.shots=input.shots.map(shot=>{
      if(!object(shot)||typeof shot.durationSeconds!=='number'||!Number.isFinite(shot.durationSeconds)||shot.durationSeconds<0.01||shot.durationSeconds>600) throw fail(400,'Shot durations must be at least 0.01 and at most 600 seconds.');
      return {title:text(shot.title,120,'Shot title','Untitled shot').trim(),description:text(shot.description,800,'Shot description'),durationSeconds:Math.round(shot.durationSeconds*100)/100};
    });
  }
  if(p.nativeProjectId && !(device.projects||[]).some(item=>item.id===p.nativeProjectId)) throw fail(400,'Native project linkage must belong to this owner.');
  if(enc.encode(JSON.stringify(p)).byteLength>80000) throw fail(413,'This project is too large. Export or split its content.');
  return p;
}
export function withPersonalStudio(Base) {
  return class PersonalStudioState extends Base {
    async personalAuth(auth, storage=this.ctx.storage) {
      let device;
      if(auth?.ownerKey) device=await this.appResolve(auth.ownerKey);
      else if(auth?.session && /^[a-f0-9]{64}$/.test(auth.session)) {
        const row=await storage.get('personal:session:'+await hash(auth.session));
        if(row && row.expiresAt>Date.now()) {
          const current=await storage.get('app-device:'+row.ownerId);
          if(current && current.ownerHash===row.ownerHash && !current.supersededByDeviceId) device=current;
        }
      }
      if(!device) throw fail(401,'Sign in to your personal VideoStudio workspace.');
      const fresh=await storage.get('app-device:'+device.deviceId);
      if(!fresh || fresh.supersededByDeviceId) throw fail(401,'This session is no longer active. Sign in again.');
      return fresh;
    }
    async personalCreateSession(ownerKey) {
      if(typeof ownerKey!=='string'||ownerKey.length<32||ownerKey.length>512) throw fail(401,'The private connection key is not valid.');
      const d=await this.personalAuth({ownerKey}), raw=token(), digest=await hash(raw), expiresAt=Date.now()+7*86400000;
      await this.ctx.storage.transaction(async tx=>{
        const k=ownKey(d.deviceId,'sessions'), list=(await tx.get(k))||[];
        list.push(digest);
        while(list.length>8) await tx.delete('personal:session:'+list.shift());
        await tx.put('personal:session:'+digest,{ownerId:d.deviceId,ownerHash:d.ownerHash,expiresAt});
        await tx.put(k,list);
      });
      return {session:raw,expiresAt};
    }
    async personalRevokeSession(session) {
      if(typeof session!=='string'||!/^[a-f0-9]{64}$/.test(session)) return {ok:true};
      const digest=await hash(session), sk='personal:session:'+digest;
      await this.ctx.storage.transaction(async tx=>{
        const row=await tx.get(sk); await tx.delete(sk);
        if(row) {const k=ownKey(row.ownerId,'sessions'); await tx.put(k,((await tx.get(k))||[]).filter(x=>x!==digest));}
      });
      return {ok:true};
    }
    async personalVault(auth,operation,key,value) {
      if(key!=='drive'&&!/^oauth:[a-f0-9]{64}$/.test(key)) throw fail(400,'Invalid private storage key.');
      if(!['get','put','take'].includes(operation)) throw fail(400,'Invalid private storage operation.');
      const d=await this.personalAuth(auth), k=ownKey(d.deviceId,'vault:'+key);
      if(operation==='get') return (await this.ctx.storage.get(k))||null;
      if(operation==='put'&&(!object(value)||enc.encode(JSON.stringify(value)).byteLength>25000)) throw fail(400,'Invalid private storage record.');
      return this.ctx.storage.transaction(async tx=>{
        const current=await this.personalAuth(auth,tx);
        if(current.controlPaused||restricted(current)) throw fail(423,'Cloud writes are paused by the native control setting.');
        if(operation==='take') {const result=await tx.get(k);await tx.delete(k);return result||null;}
        if(key.startsWith('oauth:')) {
          const index=ownKey(d.deviceId,'oauth-index'), keys=(await tx.get(index))||[];
          keys.push(k);while(keys.length>4) await tx.delete(keys.shift());await tx.put(index,keys);
        }
        await tx.put(k,value);return {ok:true};
      });
    }
    async personalReadProject(auth,id) {
      const d=await this.personalAuth(auth);
      if(restricted(d)) throw fail(423,'Cloud project access is restricted by the native permission scope.');
      const p=await this.ctx.storage.get(ownKey(d.deviceId,'project:'+String(id)));
      if(!p) throw fail(404,'Project not found.');
      return p;
    }
    async personalSnapshot(auth,query='') {
      const d=await this.personalAuth(auth), storage=this.ctx.storage;
      const scopeRestricted=restricted(d);
      const ids=scopeRestricted?[]:(await storage.get(ownKey(d.deviceId,'projects')))||[], projects=[];
      const q=text(query,200,'Search').toLowerCase().trim();
      for(const id of ids) {
        const p=await storage.get(ownKey(d.deviceId,'project:'+id));
        if(p && (!q || [p.name,p.brief,p.notes,...p.links.map(x=>x.title),...p.shots.map(x=>x.title+' '+x.description)].join(' ').toLowerCase().includes(q))) projects.push(summary(p));
      }
      projects.sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt));
      const jobs=scopeRestricted?[]:(await storage.get(ownKey(d.deviceId,'jobs')))||[];
      const nativeCommands=scopeRestricted?[]:(await storage.get('app-v3-cl:'+d.deviceId))||[];
      const age=Date.now()-(Date.parse(d.lastSeenAt)||0);
      const drive=await storage.get(ownKey(d.deviceId,'vault:drive')); 
      const prefs=(await storage.get(ownKey(d.deviceId,'preferences')))||{theme:'dark'};
      return {version:PERSONAL_VERSION,serverTime:iso(),cloud:{available:true,scopeRestricted,writable:!scopeRestricted&&!d.controlPaused,executionSurface:'cloud',storage:'Cloudflare Durable Object SQLite',videoRenderingAvailable:false},native:{connected:age>=0&&age<=45000,controlPaused:!!d.controlPaused,permissionMode:d.permissionMode,appVersion:d.appVersion,lastSeenAt:d.lastSeenAt||null,projects:(scopeRestricted?[]:d.projects||[]).map(p=>({id:p.id,name:p.name,revision:p.revision,clipCount:p.clipCount,durationMs:p.durationMs})),jobs:nativeCommands.slice(-20).map(c=>({id:c.id,action:c.action,status:c.status,createdAt:c.createdAt,completedAt:c.completedAt,waitingReason:c.waitingReason||''}))},projects,jobs:jobs.slice(-40).reverse(),preferences:prefs,drive:{connected:!!drive,connectedAt:drive?.connectedAt||null,lastBackupAt:drive?.lastBackupAt||null},galleryAccess:false};
    }
    async personalExport(auth,id) {
      const p=await this.personalReadProject(auth,id);
      return {format:'videostudio-personal',schemaVersion:1,exportedAt:iso(),project:p,mediaIncluded:false,note:'Cloud project plan only. Browser/native media must be exported separately.'};
    }
    async personalExecute(auth,operation,data={},requestId) {
      if(operation==='snapshot') return {id:'pc_read_'+crypto.randomUUID(),status:'completed',executionSurface:'cloud',result:await this.personalSnapshot(auth,data.query||'')};
      if(operation==='project.read') return {id:'pc_read_'+crypto.randomUUID(),status:'completed',executionSurface:'cloud',result:{project:await this.personalReadProject(auth,data.id)}};
      if(operation==='project.export') return {id:'pc_read_'+crypto.randomUUID(),status:'completed',executionSurface:'cloud',result:await this.personalExport(auth,data.id)};
      if(!['project.create','project.update','project.delete','project.import','preferences.update'].includes(operation)) throw fail(400,'Unsupported cloud operation. Video rendering requires an available native or browser executor.');
      if(!object(data)) throw fail(400,'Operation data must be an object.');
      if(typeof requestId!=='string'||!/^[\w-]{4,100}$/.test(requestId)) throw fail(400,'Supply a stable requestId of 4–100 letters, numbers, underscores or hyphens.');
      const d=await this.personalAuth(auth), fingerprint=await hash(canonical({operation,data})), requestHash=await hash(requestId);
      if(enc.encode(JSON.stringify(data)).byteLength>90000) throw fail(413,'Request is too large.');
      return this.ctx.storage.transaction(async tx=>{
        const current=await this.personalAuth(auth,tx);
        if(current.controlPaused||restricted(current)) throw fail(423,'Cloud writes are paused by STOP CHATGPT CONTROL or One File Lock.');
        const requestKey=ownKey(d.deviceId,'request:'+requestHash), existing=await tx.get(requestKey);
        if(existing) {
          if(existing.fingerprint!==fingerprint) throw fail(409,'This requestId was already used for different content.');
          return existing.command;
        }
        const rateKey=ownKey(d.deviceId,'rate'), rate=(await tx.get(rateKey))||{minute:0,count:0}, minute=Math.floor(Date.now()/60000);
        if(rate.minute===minute&&rate.count>=90) throw fail(429,'Too many saves. Try again in a minute.');
        const listKey=ownKey(d.deviceId,'projects'), ids=(await tx.get(listKey))||[];
        let result, projectId='', title='';
        if(operation==='project.create'||operation==='project.import') {
          if(ids.length>=50) throw fail(409,'The personal workspace holds 50 projects. Export and remove an old project first.');
          let input=data;
          if(operation==='project.import') {
            if(data.archive?.format!=='videostudio-personal'||data.archive.schemaVersion!==1||!object(data.archive.project)) throw fail(400,'Unsupported project archive.');
            input={...data.archive.project,nativeProjectId:''};
          }
          const p=validate(input,current);
          p.id='pcp_'+crypto.randomUUID(); p.revision=1; p.createdAt=iso(); p.updatedAt=p.createdAt;
          await tx.put(ownKey(d.deviceId,'project:'+p.id),p); ids.unshift(p.id); await tx.put(listKey,ids);
          result={project:p}; projectId=p.id; title=p.name;
        } else if(operation==='preferences.update') {
          if(!['dark','light'].includes(data.theme)) throw fail(400,'Theme must be dark or light.');
          result={preferences:{theme:data.theme}}; await tx.put(ownKey(d.deviceId,'preferences'),result.preferences);
        } else {
          const k=ownKey(d.deviceId,'project:'+String(data.id)), original=await tx.get(k);
          if(!original) throw fail(404,'Project not found.');
          if(data.expectedRevision!==original.revision) throw fail(409,'This project changed elsewhere. Reload its latest version before saving. Your local draft is preserved.');
          projectId=original.id; title=original.name;
          if(operation==='project.delete') {
            await tx.delete(k); await tx.put(listKey,ids.filter(id=>id!==original.id)); result={deleted:true,id:original.id};
          } else {
            const p=validate(data.patch,current,original); p.revision++; p.updatedAt=iso(); await tx.put(k,p); result={project:p}; title=p.name;
          }
        }
        const command={id:'pc_'+crypto.randomUUID(),action:'personal_cloud',operation,projectId,status:'completed',executionSurface:'cloud',createdAt:iso(),completedAt:iso(),result};
        const jobsKey=ownKey(d.deviceId,'jobs'), jobs=(await tx.get(jobsKey))||[];
        jobs.push({id:command.id,action:operation,projectId,title,status:'completed',executionSurface:'cloud',createdAt:command.createdAt,completedAt:command.completedAt});
        await tx.put(jobsKey,jobs.slice(-100));
        await tx.put(ownKey(d.deviceId,'command:'+command.id),command);
        await tx.put(requestKey,{fingerprint,command});
        const requestsKey=ownKey(d.deviceId,'requests'), requests=(await tx.get(requestsKey))||[];
        requests.push({key:requestKey,commandId:command.id});
        while(requests.length>100) {const old=requests.shift(); await tx.delete(old.key); await tx.delete(ownKey(d.deviceId,'command:'+old.commandId));}
        await tx.put(requestsKey,requests); await tx.put(rateKey,{minute,count:rate.minute===minute?rate.count+1:1});
        return command;
      });
    }
    async appEnqueueV3(ownerKey,action,parameters={}) {
      if(action!=='personal_cloud') return super.appEnqueueV3(ownerKey,action,parameters);
      const p=parameters||{};
      // Read results also need stable command retrieval through the existing MCP schema.
      const command=await this.personalExecute({ownerKey},p.operation,p.data||{},p.requestId);
      if(command.id.startsWith('pc_read_')) {
        const d=await this.personalAuth({ownerKey});
        const key=ownKey(d.deviceId,'reads');
        await this.ctx.storage.transaction(async tx=>{
          const ids=(await tx.get(key))||[]; ids.push(command.id);
          while(ids.length>8) await tx.delete(ownKey(d.deviceId,'command:'+ids.shift()));
          await tx.put(ownKey(d.deviceId,'command:'+command.id),command); await tx.put(key,ids);
        });
      }
      return command;
    }
    async appCommandV3(ownerKey,id) {
      if(!String(id).startsWith('pc_')) return super.appCommandV3(ownerKey,id);
      const d=await this.personalAuth({ownerKey});
      if(restricted(d)) throw fail(423,'Cloud command access is restricted by the native permission scope.');
      const c=await this.ctx.storage.get(ownKey(d.deviceId,'command:'+id));
      if(!c) throw fail(404,'Cloud command not found or outside its 100-operation history window.');
      return c;
    }
    async appStatusV3(ownerKey) {
      const legacy=await super.appStatusV3(ownerKey);
      if(!legacy.connected) return legacy;
      return {...legacy,personalCloud:{available:true,version:PERSONAL_VERSION,website:'/personal/',videoRenderingAvailable:false,operations:['snapshot','project.read','project.create','project.update','project.delete','project.import','project.export','preferences.update'],bridge:{action:'personal_cloud',parameters:['operation','data','requestId']},note:'Cloud project plans and activity work without the phone or browser. Native media/rendering still require an executor.'}};
    }
  };
}
