// Served as an external script. All executable code is inside this closure.
const client = function () {
  'use strict';
  const $=id=>document.getElementById(id), esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let snapshot=null,selected=null,dirty=false,searchSeq=0,noticeTimer,dbPromise,theme='dark';
  const formatTime=value=>value?new Date(value).toLocaleString(undefined,{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}):'No report yet';
  const bytes=value=>value>=1048576?(value/1048576).toFixed(1)+' MB':Math.ceil(value/1024)+' KB';
  function toast(message) {$('toast').textContent=message;$('toast').hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>$('toast').hidden=true,5500);}
  function notice(message) {$('notice').textContent=message;$('notice').hidden=!message;}
  function database() {return dbPromise ||= new Promise((resolve,reject)=>{const r=indexedDB.open('videostudio-personal-local',1);r.onupgradeneeded=()=>r.result.createObjectStore('items');r.onsuccess=()=>resolve(r.result);r.onerror=()=>{dbPromise=null;reject(new Error('Browser storage is unavailable. Cloud saves remain available.'));};});}
  async function local(method,key,value) {const db=await database();return new Promise((resolve,reject)=>{const tx=db.transaction('items',method==='get'?'readonly':'readwrite'),store=tx.objectStore('items');const r=method==='put'?store.put(value,key):store[method](key);tx.oncomplete=()=>resolve(r.result);tx.onerror=()=>reject(new Error('Local storage failed. Check available device space.'));tx.onabort=()=>reject(new Error('The local save was interrupted.'));});}
  async function api(path,body) {const opts={credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}};if(body!==undefined){opts.method='POST';opts.headers['Content-Type']='application/json';opts.body=JSON.stringify(body);}let r;try{r=await fetch('/api/personal'+path,opts);}catch{throw new Error('Network unavailable. Your local draft has not been uploaded.');}let data;try{data=await r.json();}catch{throw new Error('The server returned an unreadable response.');}if(!r.ok){if(r.status===401)lockView();const e=new Error(data.error||'Request failed.');e.status=r.status;throw e;}return data;}
  async function execute(operation,data) {
    const cacheKey='request:'+operation+':'+(data.id||data.name||'workspace');const payload=JSON.stringify(data);let pending;
    try {pending=await local('get',cacheKey);}catch{}
    if(!pending||pending.payload!==payload) pending={payload,id:crypto.randomUUID()};
    try{await local('put',cacheKey,pending);}catch{}
    const result=await api('/execute',{operation,data,requestId:pending.id});
    try{await local('delete',cacheKey);}catch{}
    return result;
  }
  function applyTheme(value) {theme=value==='light'?'light':'dark';document.documentElement.dataset.theme=theme;try{localStorage.setItem('vs-personal-theme',theme);}catch{}}
  function lockView() {$('workspace').hidden=true;$('login-view').hidden=false;snapshot=null;selected=null;$('editor-frame').removeAttribute('src');$('editor-frame').hidden=true;$('studio-start').hidden=false;}
  function route(view,id) {const hash=id?'#project/'+encodeURIComponent(id):'#'+view;if(location.hash===hash)showRoute();else location.hash=hash;}
  function currentView() {return location.hash.slice(1).split('/')[0]||'overview';}
  async function showRoute() {
    if(!snapshot)return;
    const parts=location.hash.slice(1).split('/');let view=parts[0]||'overview';
    if(view==='project'&&parts[1]) {try{await openProject(decodeURIComponent(parts[1]));}catch(e){toast(e.message);route('projects');}return;}
    if(!['overview','projects','studio','activity','storage'].includes(view))view='overview';
    display(view);if(view==='storage')loadStorage();
  }
  function display(view) {
    for(const section of document.querySelectorAll('.view'))section.hidden=section.id!==view;
    const navView=view==='project-detail'?'projects':view;
    for(const button of document.querySelectorAll('[data-view]'))button.classList.toggle('active',button.dataset.view===navView);
    $('view-label').textContent=navView[0].toUpperCase()+navView.slice(1);
    if(view!=='project-detail')selected=null;
    window.scrollTo({top:0,behavior:'instant'});
  }
  function card(p) {return `<button class="project-card glass" data-project="${esc(p.id)}"><div class="project-art"><span class="project-glyph">◈</span><span class="aspect-tag">${esc(p.aspect)}</span></div><div class="project-body"><h3>${esc(p.name)}</h3><p>${esc(p.brief||'A fresh idea. Give it a direction.')}</p><div class="card-foot"><span>${p.shotCount} shots · ${p.linkCount} links</span><span>${esc(formatTime(p.updatedAt))}</span></div></div></button>`;}
  function renderProjects() {
    const list=snapshot.projects;
    $('recent-projects').innerHTML=list.length?list.slice(0,3).map(card).join(''):'<div class="empty"><h3>Your first frame starts with an idea.</h3><p>Create a project to save notes, references, and a shot list to your private cloud workspace.</p><button class="text-button new-project">＋ Create your first project</button></div>';
    $('all-projects').innerHTML=list.length?list.map(card).join(''):'<div class="empty"><h3>No matching projects.</h3><p>Try another search or create a new project.</p></div>';
  }
  function activity(items,cloud) {
    if(!items.length)return '<div class="empty"><h3>No activity yet.</h3><p>'+ (cloud?'Completed cloud saves will appear here.':'No recent native commands are recorded.')+'</p></div>';
    return items.map(j=>`<div class="activity-item"><span class="activity-icon">${j.status==='completed'?'✓':'◷'}</span><div class="activity-body"><b>${esc(j.title||j.action?.replaceAll('_',' ')||'Operation')}</b><p>${esc(j.action)} · ${esc(j.status)}${j.waitingReason?' · '+esc(j.waitingReason):''}</p><time>${esc(formatTime(j.completedAt||j.createdAt))}</time></div></div>`).join('');
  }
  function renderSnapshot() {
    $('cloud-label').textContent='Available';$('native-label').textContent=snapshot.native.connected?'Online':'Offline';$('native-dot').className=snapshot.native.connected?'live-dot':'wait-dot';
    $('stat-projects').textContent=snapshot.projects.length;$('stat-shots').textContent=snapshot.projects.reduce((n,p)=>n+p.shotCount,0);
    const waiting=snapshot.native.jobs.filter(j=>['waiting_native','queued','claimed'].includes(j.status));$('stat-queue').textContent=waiting.length;
    $('queue-caption').textContent=snapshot.native.connected?'Native executor is online':'Native executor is offline';
    $('cloud-activity').innerHTML=activity(snapshot.jobs,true);$('native-activity').innerHTML=activity([...snapshot.native.jobs].reverse(),false);
    $('last-sync').textContent='Cloud checked '+formatTime(snapshot.serverTime);
    if(snapshot.native.controlPaused||snapshot.cloud.scopeRestricted) notice('Your native control setting restricts this cloud workspace. Change that setting in the native app to restore access.');
    $('save-project').disabled=!!snapshot.native.controlPaused||snapshot.cloud.scopeRestricted;
    renderProjects();
  }
  async function reload(query='') {const seq=++searchSeq;const data=await api('/snapshot'+(query?'?q='+encodeURIComponent(query):''));if(seq!==searchSeq)return;snapshot=data;$('workspace').hidden=false;$('login-view').hidden=true;renderSnapshot();}
  async function start() {try{applyTheme(localStorage.getItem('vs-personal-theme'));}catch{}try{await reload();applyTheme(snapshot.preferences.theme);await showRoute();}catch(e){if(e.status!==401){lockView();$('login-error').textContent=e.message;}}}
  async function openProject(id) {
    const [data,draft]=await Promise.all([api('/projects/'+encodeURIComponent(id)),local('get','draft:'+id).catch(()=>null)]);
    selected=data.project;dirty=false;
    if(draft){selected={...selected,...draft.data,revision:draft.baseRevision};dirty=true;}
    $('project-title').textContent=selected.name;$('project-revision').textContent='Revision '+data.project.revision+' · '+formatTime(data.project.updatedAt);
    $('edit-name').value=selected.name;$('edit-aspect').value=selected.aspect;$('edit-brief').value=selected.brief;$('edit-notes').value=selected.notes;
    renderShots(selected.shots);renderLinks(selected.links);$('save-status').textContent=draft?'Draft saved on this device':'Saved to cloud';
    notice(draft&&draft.baseRevision!==data.project.revision?'A newer cloud version exists. Your older draft is preserved here. Export your draft before reloading the latest cloud version.':draft?'An unsaved local draft was restored. Save it to cloud when ready.':'');
    display('project-detail');await renderAttachments();
  }
  function renderShots(shots) {$('shot-fields').innerHTML=shots.map((s,i)=>`<div class="shot-row"><div class="shot-top"><label>Shot ${i+1}<input class="shot-title" maxlength="120" value="${esc(s.title)}" placeholder="Shot name"></label><label>Seconds<input class="shot-duration" type="number" min="0.01" max="600" step="0.01" value="${esc(s.durationSeconds)}" required></label><button type="button" class="remove-row" data-remove-shot="${i}" aria-label="Remove shot ${i+1}">×</button></div><label>Description<textarea class="shot-description" rows="2" maxlength="800" placeholder="Framing, movement, sound…">${esc(s.description)}</textarea></label></div>`).join('');}
  function renderLinks(links) {$('link-fields').innerHTML=links.map((l,i)=>`<div class="link-row"><div class="link-top"><label>Label<input class="link-title" maxlength="120" value="${esc(l.title)}" placeholder="Reference label"></label><button type="button" class="remove-row" data-remove-link="${i}" aria-label="Remove reference ${i+1}">×</button></div><label>HTTPS URL<input class="link-url" type="url" maxlength="2000" value="${esc(l.url)}" placeholder="https://…" required></label></div>`).join('');}
  function formData() {return {name:$('edit-name').value,brief:$('edit-brief').value,notes:$('edit-notes').value,aspect:$('edit-aspect').value,shots:[...document.querySelectorAll('.shot-row')].map(r=>({title:r.querySelector('.shot-title').value,description:r.querySelector('.shot-description').value,durationSeconds:Number(r.querySelector('.shot-duration').value)})),links:[...document.querySelectorAll('.link-row')].map(r=>({title:r.querySelector('.link-title').value,url:r.querySelector('.link-url').value}))};}
  async function draftSave() {if(!selected)return;dirty=true;try{await local('put','draft:'+selected.id,{data:formData(),baseRevision:selected.revision,savedAt:Date.now()});$('save-status').textContent='Draft saved on this device';}catch(e){$('save-status').textContent='Local draft not saved';notice(e.message);}}
  async function renderAttachments() {if(!selected)return;const items=(await local('get','files:'+selected.id).catch(()=>[]))||[];$('attachment-list').innerHTML=items.map(f=>`<div class="attachment-item"><b>${esc(f.name)}</b><p class="hint">${bytes(f.size)} · Saved on this device</p><div class="attachment-buttons"><button class="text-button" data-download-file="${esc(f.id)}">Download</button><button class="text-button" data-remove-file="${esc(f.id)}">Remove</button></div></div>`).join('');}
  function download(blob,name) {const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);}
  async function loadStorage() {
    try{const e=await navigator.storage?.estimate();$('local-usage').textContent=e?bytes(e.usage||0)+' used · '+bytes(e.quota||0)+' browser quota':'Storage estimate unavailable';const p=await navigator.storage?.persisted();$('persistence-status').textContent=p?'Persistent storage is granted. Backups are still recommended.':'Browser may clear local data under storage pressure.';}catch{$('local-usage').textContent='Storage estimate unavailable';}
    try{const drive=await api('/drive/status');$('drive-badge').textContent=drive.connected?'CONNECTED':drive.configured?'NOT CONNECTED':'SETUP REQUIRED';$('drive-state').textContent=drive.connected?'Private plan backups available':drive.configured?'One Google authorisation is required':'Server OAuth credentials are not configured';$('drive-explanation').textContent=drive.connected?'Your cloud plans can be backed up to your own Drive without keeping the editor open.':'This website needs its own Google OAuth connection. The Drive permission you gave ChatGPT is not automatically available to this website.';$('connect-drive').disabled=!drive.configured;$('connect-drive').textContent=drive.connected?'Reconnect Google Drive':'Connect Google Drive';}catch(e){$('drive-state').textContent=e.message;}
  }
  $('login-form').addEventListener('submit',async e=>{e.preventDefault();const button=e.submitter;let keyAccepted=false;button.disabled=true;$('login-error').textContent='';try{let key=$('owner-key').value.trim();const match=key.match(/\/app-mcp-v3\/([A-Za-z0-9_-]{32,})/);if(match)key=match[1];await api('/session',{ownerKey:key});keyAccepted=true;await reload();$('owner-key').value='';applyTheme(snapshot.preferences.theme);await showRoute();}catch(err){if(err.status===401&&keyAccepted){$('login-error').textContent='The private key was accepted, but the secure session could not be restored. In Brave, allow cookies and on-device site data for this website, then retry. You can also try Chrome.';}else if(err.status===401){$('login-error').textContent='Connection key not recognised. Paste the complete private /app-mcp-v3/ address from VideoStudio Native Agent settings, or just its final key. The public website or Studio Web MCP URL will not sign in.';}else{$('login-error').textContent=err.message;}}finally{button.disabled=false;}});
  document.addEventListener('click',async e=>{const view=e.target.closest('[data-view]');if(view){if(dirty)await draftSave();route(view.dataset.view);return;}const project=e.target.closest('[data-project]');if(project){route('project',project.dataset.project);return;}if(e.target.closest('.new-project')){$('new-name').value='';$('create-error').textContent='';$('create-dialog').showModal();$('new-name').focus();return;}
    const rs=e.target.closest('[data-remove-shot]'),rl=e.target.closest('[data-remove-link]');if(rs||rl){const d=formData();if(rs)d.shots.splice(Number(rs.dataset.removeShot),1);else d.links.splice(Number(rl.dataset.removeLink),1);renderShots(d.shots);renderLinks(d.links);await draftSave();}
    const dl=e.target.closest('[data-download-file]'),rm=e.target.closest('[data-remove-file]');try{if(dl){const f=await local('get','blob:'+dl.dataset.downloadFile);if(!f)throw new Error('This local file is no longer available.');download(f.blob,f.name);}if(rm&&selected){if(!confirm('Remove this local copy? Any separate copies are unchanged.'))return;const list=(await local('get','files:'+selected.id))||[];await local('delete','blob:'+rm.dataset.removeFile);await local('put','files:'+selected.id,list.filter(f=>f.id!==rm.dataset.removeFile));await renderAttachments();}}catch(err){toast(err.message);}
  });
  $('cancel-create').onclick=()=>$('create-dialog').close();
  $('create-form').onsubmit=async e=>{e.preventDefault();const button=e.submitter;button.disabled=true;try{const r=await execute('project.create',{name:$('new-name').value.trim()});$('create-dialog').close();await reload();route('project',r.result.project.id);toast('Project created and saved to cloud.');}catch(err){$('create-error').textContent=err.message;}finally{button.disabled=false;}};
  $('back-projects').onclick=async()=>{if(dirty)await draftSave();route('projects');};
  $('project-form').addEventListener('input',()=>{draftSave();});
  $('project-form').onsubmit=async e=>{e.preventDefault();if(!selected)return;const id=selected.id,revision=selected.revision,patch=formData();e.submitter.disabled=true;$('save-status').textContent='Saving…';try{const result=await execute('project.update',{id,expectedRevision:revision,patch});if(selected?.id===id){const editedDuringSave=JSON.stringify(formData())!==JSON.stringify(patch);selected=result.result.project;dirty=editedDuringSave;if(editedDuringSave)await draftSave();else{await local('delete','draft:'+id).catch(()=>{});$('save-status').textContent='Saved to cloud';}$('project-title').textContent=selected.name;$('project-revision').textContent='Revision '+selected.revision+' · '+formatTime(selected.updatedAt);notice(editedDuringSave?'Your earlier changes were saved to cloud. Newer edits remain a local draft.':'');}else{const draft=await local('get','draft:'+id).catch(()=>null);if(draft&&JSON.stringify(draft.data)===JSON.stringify(patch))await local('delete','draft:'+id).catch(()=>{});}await reload();toast('Cloud save confirmed.');}catch(err){$('save-status').textContent='Cloud save failed · draft preserved';notice(err.message);}finally{$('save-project').disabled=!!snapshot?.native.controlPaused||!!snapshot?.cloud.scopeRestricted;}};
  $('add-shot').onclick=()=>{const d=formData();if(d.shots.length>=48)return toast('Use at most 48 shots.');d.shots.push({title:'',description:'',durationSeconds:3});renderShots(d.shots);draftSave();};
  $('add-link').onclick=()=>{const d=formData();if(d.links.length>=24)return toast('Use at most 24 links.');d.links.push({title:'',url:''});renderLinks(d.links);draftSave();};
  $('export').onclick=async()=>{if(!selected)return;try{const data=dirty?{format:'videostudio-personal',schemaVersion:1,exportedAt:new Date().toISOString(),project:{...selected,...formData()},mediaIncluded:false,localDraft:true}:await api('/projects/'+selected.id+'/export');download(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}),selected.name.replace(/[^a-z0-9 _-]/gi,'_')+'.json');toast(dirty?'Local draft exported. Cloud version is unchanged.':'Cloud project plan exported.');}catch(e){toast(e.message);}};
  $('delete').onclick=async()=>{if(!selected||!confirm('Delete this cloud plan? Native projects and media will not be deleted. Local attachments remain on this device.'))return;try{const id=selected.id;await execute('project.delete',{id,expectedRevision:selected.revision});await local('delete','draft:'+id).catch(()=>{});dirty=false;await reload();route('projects');toast('Cloud plan deleted. Native projects are unchanged.');}catch(e){notice(e.message);}};
  $('import').onclick=()=>$('import-file').click();$('import-file').onchange=async()=>{const file=$('import-file').files[0];if(!file)return;try{if(file.size>90000)throw new Error('Project archives must be smaller than 90 KB.');const archive=JSON.parse(await file.text());const r=await execute('project.import',{archive});await reload();route('project',r.result.project.id);toast('Imported as a new cloud project.');}catch(e){toast(e.message);}finally{$('import-file').value='';}};
  let debounce;$('search').oninput=()=>{clearTimeout(debounce);debounce=setTimeout(()=>reload($('search').value).catch(e=>toast(e.message)),250);};
  $('attachments').onchange=async()=>{if(!selected)return;const pid=selected.id;for(const file of $('attachments').files){try{if(file.size>20*1048576)throw new Error(file.name+' exceeds the 20 MB local attachment limit.');const id=crypto.randomUUID(),meta={id,name:file.name,size:file.size,type:file.type};await local('put','blob:'+id,{...meta,blob:file});const list=(await local('get','files:'+pid))||[];list.push(meta);try{await local('put','files:'+pid,list);}catch(e){await local('delete','blob:'+id);throw e;}}catch(err){toast(err.message);}}$('attachments').value='';await renderAttachments();};
  $('load-editor').onclick=()=>{$('editor-frame').src='/';$('editor-frame').hidden=false;$('studio-start').hidden=true;};
  $('logout').onclick=async()=>{try{if(dirty)await draftSave();await api('/logout',{});lockView();$('login-error').textContent='';}catch(e){toast(e.message);}};
  $('theme').onclick=async()=>{applyTheme(theme==='dark'?'light':'dark');try{await execute('preferences.update',{theme});}catch(e){toast('Theme changed on this device. '+e.message);}};
  $('refresh').onclick=async()=>{try{if(selected&&dirty){if(!confirm('Reload the latest cloud version and discard the local text draft? Export the draft first to keep a copy.'))return;await local('delete','draft:'+selected.id);dirty=false;}const id=selected?.id;await reload(currentView()==='projects'?$('search').value:'');if(id)await openProject(id);else if(currentView()==='storage')await loadStorage();notice('');toast('Workspace refreshed.');}catch(e){toast(e.message);}};
  $('activity-refresh').onclick=()=>reload().then(()=>toast('Activity refreshed.')).catch(e=>toast(e.message));
  $('request-persistence').onclick=async()=>{try{const granted=await navigator.storage?.persist();$('persistence-status').textContent=granted?'Persistent storage granted. Backups are still recommended.':'Not granted. Download important files and export plans before clearing site data.';}catch(e){toast(e.message);}};
  $('connect-drive').onclick=async()=>{try{const r=await api('/drive/connect',{});const u=new URL(r.url);if(u.origin!=='https://accounts.google.com')throw new Error('Unexpected authorisation destination.');location.assign(u.href);}catch(e){toast(e.message);}};
  $('drive-backup').onclick=async()=>{if(!selected)return;const b=$('drive-backup');b.disabled=true;try{if(dirty)throw new Error('Save this plan to cloud before backing it up to Drive.');await api('/drive/backup',{projectId:selected.id});toast('Cloud plan backed up to your Google Drive. Local attachments are not included.');}catch(e){toast(e.message);}finally{b.disabled=false;}};
  window.addEventListener('hashchange',()=>showRoute());
  window.addEventListener('online',()=>{if(snapshot)reload().catch(()=>{});});
  setInterval(()=>{if(snapshot&&!document.hidden&&currentView()!=='projects')reload().catch(e=>{$('cloud-label').textContent='Unreachable';$('last-sync').textContent=e.message;});},60000);
  const params=new URLSearchParams(location.search);if(params.has('drive')){toast(params.get('drive')==='connected'?'Google Drive connected.':'Google Drive authorisation was not completed.');history.replaceState(null,'',location.pathname+location.hash);}
  start();
};
export default '('+client.toString()+')();';
