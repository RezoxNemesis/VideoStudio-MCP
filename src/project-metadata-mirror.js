// An explicit, metadata-only mirror. No media transfer, renderer, model runner,
// or native application reconciliation is implied by a stored project graph.
const MAX_GRAPH_BYTES=64*1024;
const MAX_AUDIT=40;
const MAX_CONFLICTS=8;
const text=(value,limit=180)=>String(value??"").trim().slice(0,limit);
const authoredText=(value,limit,name)=>{
  if(value==null)return"";
  if(typeof value!=="string"||value.length>limit)throw new Error(name+" exceeds its "+limit+" character metadata limit");
  return value;
};
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==="object"
  ?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const sameGraph=(left,right)=>JSON.stringify(canonical(left))===JSON.stringify(canonical(right));
const id=value=>{
  if(typeof value!=="string"||!value||value!==value.trim()||value.length>180)throw new Error("Stable object ID must contain 1..180 characters without surrounding whitespace");
  return value;
};
const integer=(value,name,min=0)=>{
  if(!Number.isSafeInteger(value)||value<min)throw new Error(name+" must be a safe integer >= "+min);
  return value;
};
const number=(value,name,min,max)=>{
  if(!Number.isFinite(value)||value<min||value>max)throw new Error(name+" is outside its supported range");
  return value;
};
const privateKeys=new Set(["uri","url","sourceurl","download_url","downloadurl","base64","ownerkey","authorization","token","secret","localpath","filepath","location","locations","storagepath"]);
const clipSpan=clip=>clip.programDurationMs>0?clip.programDurationMs:Math.round((clip.outMs-clip.inMs)/clip.speed);
const audioTrack=type=>type==="audio"||type.startsWith("audio_")||["music","dialogue","sfx","voiceover","voice_over"].includes(type);
const nativeAudioEvidence=["audioDetached","audioExtractionDetached"];
const metadata=(value,depth=0)=>{
  if(depth>8)throw new Error("Metadata graph nesting exceeds eight levels");
  if(value===null||typeof value==="boolean")return value;
  if(typeof value==="number"){
    if(!Number.isFinite(value)||(Number.isInteger(value)&&!Number.isSafeInteger(value)))throw new Error("Metadata number exceeds safe precision");
    return value;
  }
  if(typeof value==="string")return /^(?:file|content|https?):\/\/|^data:/i.test(value.trim())?"":authoredText(value,2048,"Effect text");
  if(Array.isArray(value)){if(value.length>256)throw new Error("Metadata array is too large");return value.map(v=>metadata(v,depth+1));}
  if(value&&typeof value==="object"){
    const result={};
    if(Object.keys(value).length>128)throw new Error("Metadata object is too large");
    for(const [key,item] of Object.entries(value)){
      if(key.startsWith("_")||privateKeys.has(key.toLowerCase())||/(?:uri|url|path)$/i.test(key)
        ||/(?:credential|password|secret|token|api[_-]?key|authorization)/i.test(key))continue;
      if(key==="__proto__"||key==="prototype"||key==="constructor")continue;
      result[key]=metadata(item,depth+1);
    }
    return result;
  }
  throw new Error("Metadata contains an unsupported value");
};
const unique=(items,name)=>{
  const seen=new Set();for(const item of items){if(seen.has(item.id))throw new Error(name+" IDs must be unique");seen.add(item.id);}
};

export function normalizeMetadataGraph(projectId,raw) {
  if(!raw||typeof raw!=="object"||Array.isArray(raw))throw new Error("projectGraph is required");
  if(raw.id&&raw.id!==projectId)throw new Error("Project graph ID does not match projectId");
  if(!Array.isArray(raw.assets)||raw.assets.length>200||!Array.isArray(raw.tracks)||raw.tracks.length===0||raw.tracks.length>32
    ||!Array.isArray(raw.clips)||raw.clips.length>120)throw new Error("Mirror supports up to 200 assets, 32 tracks and 120 clips");
  for(const clip of raw.clips)if(clip?.effects&&["rig2d","motionPath","celExposure"].some(key=>Object.hasOwn(clip.effects,key)))
    throw new Error("Native rig, motion-path and cel exposure authoring is preserved locally; use the shared native animation tools instead of metadata-mirror effects");
  const assets=raw.assets.map(a=>({id:id(a.id),name:authoredText(a.name,180,"Asset name"),mime:text(a.mime,120),
    durationMs:integer(Number(a.durationMs??0),"Asset duration"),sizeBytes:integer(Math.max(0,Number(a.sizeBytes??0)),"Asset size"),
    width:integer(Number(a.width??0),"Asset width"),height:integer(Number(a.height??0),"Asset height"),
    rotation:number(Number(a.rotation??0),"Asset rotation",-360,360),hasAudio:!!a.hasAudio,
    role:text(a.role||"source",80),mediaAvailability:"metadata_only",explicitlySynced:true}));
  for(const asset of assets)if(!/^(image|video|audio)\/[a-z0-9.+_-]+$/i.test(asset.mime))throw new Error("Mirrored assets must have an explicit media MIME");
  const tracks=raw.tracks.map(t=>({id:id(t.id),name:authoredText(t.name??t.type??"video",120,"Track name"),type:text(t.type||"video",40),
    order:integer(Number(t.order??0),"Track order"),locked:!!t.locked,muted:!!t.muted,solo:!!t.solo,visible:t.visible!==false}));
  const trackTypes=new Set(["video","image","graphics","overlay","adjustment","text","title","subtitle","animation","animation_2d","scene_3d","vfx","effects","control","automation", "audio","audio_music","audio_dialogue","audio_sfx","music","dialogue","sfx","voiceover","voice_over"]);
  for(const track of tracks)if(!trackTypes.has(track.type))throw new Error("Unsupported metadata track type: "+track.type);
  const clips=raw.clips.map(c=>({id:id(c.id),assetId:id(c.assetId),trackId:id(c.trackId),
    linkGroupId:c.linkGroupId==null||c.linkGroupId===""?"":id(c.linkGroupId),
    startMs:integer(Number(c.startMs),"Clip program start"),inMs:integer(Number(c.inMs??0),"Clip source in"),
    programDurationMs:integer(Number(c.programDurationMs??-1),"Exact program duration",-1),
    outMs:integer(Number(c.outMs),"Clip source out"),speed:Math.fround(number(Number(c.speed??1),"Clip speed",.1,16)),
    volume:Math.fround(number(Number(c.volume??1),"Clip volume",0,2)),transition:text(c.transition||"none",80),title:authoredText(c.title,1000,"Clip title"),
    effects:metadata(c.effects||{})}));
  unique(assets,"Asset");unique(tracks,"Track");unique(clips,"Clip");
  const assetById=new Map(assets.map(a=>[a.id,a])),trackById=new Map(tracks.map(t=>[t.id,t]));
  for(const clip of clips){
    const asset=assetById.get(clip.assetId),track=trackById.get(clip.trackId);
    if(!asset||!track)throw new Error("A clip references an asset or track not explicitly synced into this mirror");
    if(clip.linkGroupId.length>128)throw new Error("Native link group ID exceeds 128 characters");
    for(const key of nativeAudioEvidence)if(Object.hasOwn(clip.effects,key)&&typeof clip.effects[key]!=="boolean")
      throw new Error("Native detached-audio state must be boolean metadata: "+key);
    if(clip.effects.audioExtractionDetached===true&&clip.effects.audioDetached!==true)
      throw new Error("Native audio extraction provenance requires detached audio");
    if(clip.outMs<=clip.inMs)throw new Error("Clip source range must have positive duration");
    if(!asset.mime.startsWith("image/")&&(asset.durationMs<=0||clip.outMs>asset.durationMs))throw new Error("Clip exceeds its synced source duration");
    if((asset.mime.startsWith("audio/")&&!audioTrack(track.type))
      ||(audioTrack(track.type)&&(asset.mime.startsWith("image/")||!asset.hasAudio)))
      throw new Error("Audio media and audio-bearing video must use compatible tracks");
    if(clip.programDurationMs===0||clipSpan(clip)<=0)throw new Error("Clip program duration must be positive");
    if(clip.programDurationMs>0&&Math.abs(clip.programDurationMs-Math.round((clip.outMs-clip.inMs)/clip.speed))>Math.max(2,Math.ceil(2/clip.speed)))
      throw new Error("Exact program duration may only preserve native split quantization");
    if(!Number.isSafeInteger(clip.startMs+clipSpan(clip)))throw new Error("Clip program time overflows safe precision");
  }
  for(const track of tracks){
    const ordered=clips.filter(c=>c.trackId===track.id).sort((a,b)=>a.startMs-b.startMs);
    let end=0;for(const clip of ordered){if(clip.startMs<end)throw new Error("Clips on the same track overlap");end=clip.startMs+clipSpan(clip);}
  }
  const linkGroups=new Map();
  for(const clip of clips)if(clip.linkGroupId){
    const group=linkGroups.get(clip.linkGroupId)||[];group.push(clip);linkGroups.set(clip.linkGroupId,group);
  }
  for(const group of linkGroups.values()){
    if(group.length<2||group.some(clip=>clip.startMs!==group[0].startMs||clipSpan(clip)!==clipSpan(group[0])))
      throw new Error("Linked clips require at least two peers with matching program start and end");
  }
  const graph={id:projectId,timelineSchema:2,name:authoredText(raw.name??"Mirrored project",180,"Project name"),sourcePrompt:authoredText(raw.sourcePrompt,2048,"Project prompt"),assets,tracks,clips};
  if(new TextEncoder().encode(JSON.stringify(graph)).byteLength>MAX_GRAPH_BYTES)throw new Error("Project metadata exceeds the 64 KB mirror limit");
  return graph;
}

export class ProjectMetadataMirror {
  constructor(storage,deviceId){this.storage=storage;this.prefix="app-metadata-mirror:"+deviceId+":";}
  key(projectId){return this.prefix+id(projectId);}
  async get(projectId){
    const current=await this.storage.get(this.key(projectId));
    if(!current)return{ok:true,enabled:false,projectId,executorAvailable:false,mediaUploaded:false};
    return{ok:true,...current,executorAvailable:false,mediaUploaded:false,nativeAutoReconciliation:false,
      audit:await this.records(projectId,"audit"),conflicts:await this.records(projectId,"conflict")};
  }
  async records(projectId,kind){
    const keys=await this.storage.get(this.key(projectId)+":"+kind+":index")||[];
    const result=[];for(const key of keys){
      const record=await this.storage.get(key);
      if(!record)continue;
      if(record.currentGraphKey){record.currentGraph=await this.storage.get(record.currentGraphKey);delete record.currentGraphKey;}
      if(record.incomingGraphKey){record.incoming={...record.incoming,graph:await this.storage.get(record.incomingGraphKey)};delete record.incomingGraphKey;}
      result.push(record);
    }
    return result;
  }
  async retain(projectId,kind,record,limit){
    const root=this.key(projectId)+":"+kind, indexKey=root+":index";
    const key=root+":"+crypto.randomUUID();
    const keys=await this.storage.get(indexKey)||[];
    const saved={...record};
    // Each graph is bounded separately. A conflict must retain both candidates
    // without placing two near-limit graphs into one Durable Object value.
    if(kind==="conflict"&&record.currentGraph){
      saved.currentGraphKey=key+":current-graph";
      await this.storage.put(saved.currentGraphKey,record.currentGraph);
      delete saved.currentGraph;
    }
    if(kind==="conflict"&&record.incoming?.graph){
      saved.incomingGraphKey=key+":incoming-graph";
      await this.storage.put(saved.incomingGraphKey,record.incoming.graph);
      saved.incoming={...record.incoming};delete saved.incoming.graph;
    }
    await this.storage.put(key,{id:key.slice(root.length+1),at:new Date().toISOString(),...saved});
    keys.unshift(key);
    for(const expired of keys.slice(limit)){
      await this.storage.delete(expired);
      if(kind==="conflict"){
        await this.storage.delete(expired+":current-graph");
        await this.storage.delete(expired+":incoming-graph");
      }
    }
    await this.storage.put(indexKey,keys.slice(0,limit));
    return key.slice(root.length+1);
  }
  async conflict(projectId,current,reason,incoming={}){
    const conflictId=await this.retain(projectId,"conflict",{reason,sourceRevision:current?.sourceRevision||0,
      mirrorRevision:current?.mirrorRevision||0,currentGraph:current?.graph||null,incoming},MAX_CONFLICTS);
    return{ok:false,conflict:true,conflictId,reason,sourceRevision:current?.sourceRevision||0,mirrorRevision:current?.mirrorRevision||0,
      graph:current?.graph||null,executorAvailable:false,nativeAutoReconciliation:false};
  }
  async sync(input){
    if(input.enabled!==true)throw new Error("Metadata mirroring requires explicit enabled:true opt-in");
    const projectId=id(input.projectId),key=this.key(projectId);
    const graph=normalizeMetadataGraph(projectId,input.projectGraph);
    const sourceRevision=integer(input.sourceRevision,"sourceRevision",1);
    if(input.projectGraph.revision!=null&&input.projectGraph.revision!==sourceRevision)throw new Error("Native graph revision does not match sourceRevision");
    const current=await this.storage.get(key);
    if(current){
      if(input.expectedMirrorRevision!==current.mirrorRevision)return this.conflict(projectId,current,"Mirror revision changed",{sourceRevision,graph});
      if(sourceRevision<current.sourceRevision)return this.conflict(projectId,current,"Native source revision is stale",{sourceRevision,graph});
      if(current.dirty)return this.conflict(projectId,current,"Offline graph edits await explicit native reconciliation",{sourceRevision,graph});
      if(sourceRevision===current.sourceRevision){
        if(!sameGraph(graph,current.graph))return this.conflict(projectId,current,"Native graph changed without a new revision",{sourceRevision,graph});
        if(current.enabled)return this.get(projectId);
      }
    }else if(input.expectedMirrorRevision!=null&&input.expectedMirrorRevision!==0)return this.conflict(projectId,null,"New mirror expects revision zero",{sourceRevision,graph});
    const next={enabled:true,projectId,sourceRevision,mirrorRevision:integer((current?.mirrorRevision||0)+1,"Mirror revision",1),dirty:false,
      graph,updatedAt:new Date().toISOString(),optedInAt:current?.optedInAt||new Date().toISOString()};
    await this.storage.put(key,next);
    await this.retain(projectId,"audit",{operation:"sync",sourceRevision,mirrorRevision:next.mirrorRevision},MAX_AUDIT);
    return this.get(projectId);
  }
  async edit(input){
    const projectId=id(input.projectId),key=this.key(projectId),current=await this.storage.get(key);
    if(!current?.enabled)throw new Error("Project metadata mirror is not opted in");
    if(input.expectedMirrorRevision!==current.mirrorRevision)return this.conflict(projectId,current,"Mirror revision changed",{operation:text(input.operation,80)});
    const graph=JSON.parse(JSON.stringify(current.graph)),p=input.parameters||{},operation=text(input.operation,80);
    const track=trackId=>{const found=graph.tracks.find(t=>t.id===trackId);if(!found)throw new Error("Track is not in this synced graph");if(found.locked)throw new Error("Track is locked");return found;};
    const clip=clipId=>{const found=graph.clips.find(c=>c.id===clipId);if(!found)throw new Error("Clip is not in this synced graph");track(found.trackId);return found;};
    if(operation==="insert_clip"){
      if(!graph.assets.some(a=>a.id===p.assetId))throw new Error("Asset is not explicitly synced into this mirror");
      track(p.trackId);
      if(p.linkGroupId||nativeAudioEvidence.some(key=>Object.hasOwn(p.effects||{},key)))throw new Error("Metadata insertion cannot manufacture native A/V linkage or detached audio provenance");
      graph.clips.push({id:p.clipId||crypto.randomUUID(),assetId:p.assetId,trackId:p.trackId,startMs:p.startMs,
        inMs:p.inMs??0,outMs:p.outMs,speed:p.speed??1,volume:p.volume??1,transition:p.transition||"none",title:p.title||"",effects:p.effects||{}});
    }else if(operation==="delete_clip"){
      const found=clip(p.clipId);
      if(found.linkGroupId)throw new Error("Linked clip deletion requires the shared native timeline operation");
      if(nativeAudioEvidence.some(key=>Object.hasOwn(found.effects,key)))throw new Error("Detached-audio provenance deletion requires the shared native timeline operation");
      graph.clips=graph.clips.filter(c=>c.id!==p.clipId);
    }else if(operation==="patch_clip"){
      const found=clip(p.clipId);
      if(Object.hasOwn(p,"linkGroupId")&&p.linkGroupId!==found.linkGroupId)
        throw new Error("Metadata edits cannot change native A/V linkage");
      if(found.linkGroupId&&["trackId","startMs","inMs","outMs","speed"].some(key=>Object.hasOwn(p,key)&&p[key]!==found[key]))
        throw new Error("Linked clip timing and placement require the shared native timeline operation");
      if(Object.hasOwn(p,"effects")&&nativeAudioEvidence.some(key=>
        Object.hasOwn(p.effects||{},key)!==Object.hasOwn(found.effects,key)||p.effects?.[key]!==found.effects[key]))
        throw new Error("Metadata edits must preserve native detached-audio state and extraction provenance");
      if(p.trackId)track(p.trackId);
      for(const key of ["trackId","startMs","inMs","outMs","speed","volume","transition","title","effects"])if(Object.hasOwn(p,key))found[key]=p[key];
      if(["inMs","outMs","speed"].some(key=>Object.hasOwn(p,key)))found.programDurationMs=-1;
    }else if(operation==="track_flags"){
      const found=graph.tracks.find(t=>t.id===p.trackId);if(!found)throw new Error("Track is not in this synced graph");
      for(const key of ["locked","muted","solo","visible"])if(Object.hasOwn(p,key)){
        if(typeof p[key]!=="boolean")throw new Error("Track flag must be boolean");found[key]=p[key];
      }
    }else throw new Error("Metadata mirror has no executor for operation: "+operation);
    const normalized=normalizeMetadataGraph(projectId,graph);
    if(sameGraph(normalized,current.graph))return this.get(projectId);
    const next={...current,graph:normalized,mirrorRevision:integer(current.mirrorRevision+1,"Mirror revision",1),dirty:true,updatedAt:new Date().toISOString()};
    await this.storage.put(key,next);
    await this.retain(projectId,"audit",{operation,mirrorRevision:next.mirrorRevision,sourceRevision:next.sourceRevision,
      clipId:text(p.clipId),assetId:text(p.assetId),trackId:text(p.trackId),executor:"metadata_graph_only"},MAX_AUDIT);
    return this.get(projectId);
  }
  async reconcile(input){
    const projectId=id(input.projectId),key=this.key(projectId),current=await this.storage.get(key);
    if(!current?.enabled)throw new Error("Project metadata mirror is not opted in");
    const graph=normalizeMetadataGraph(projectId,input.projectGraph);
    const sourceRevision=integer(input.sourceRevision,"sourceRevision",1);
    if(input.projectGraph.revision!=null&&input.projectGraph.revision!==sourceRevision)throw new Error("Native graph revision does not match sourceRevision");
    const noOpAcknowledgement=input.resolution==="acknowledge_mirror"&&sourceRevision===current.sourceRevision&&sameGraph(graph,current.graph);
    const keepUnchangedNative=input.resolution==="keep_native"&&sourceRevision===current.sourceRevision
      &&(current.dirty||sameGraph(graph,current.graph));
    if(input.expectedMirrorRevision!==current.mirrorRevision||input.baseSourceRevision!==current.sourceRevision
      ||sourceRevision<current.sourceRevision||(sourceRevision===current.sourceRevision&&!noOpAcknowledgement&&!keepUnchangedNative))
      return this.conflict(projectId,current,"Native or mirror revision changed during reconciliation",{sourceRevision,graph});
    if(!["keep_native","acknowledge_mirror"].includes(input.resolution))throw new Error("Explicit reconciliation resolution is required");
    if(input.resolution==="acknowledge_mirror"&&!sameGraph(graph,current.graph))
      return this.conflict(projectId,current,"Native readback does not match the edited metadata graph",{sourceRevision,graph});
    const mirrorRevision=integer(current.mirrorRevision+1,"Mirror revision",1);
    if(input.resolution==="keep_native"&&current.dirty)await this.retain(projectId,"conflict",{
      reason:"Owner explicitly kept native graph",sourceRevision:current.sourceRevision,mirrorRevision:current.mirrorRevision,
      currentGraph:current.graph,incoming:{sourceRevision,graph}},MAX_CONFLICTS);
    await this.storage.put(key,{...current,graph,sourceRevision,mirrorRevision,dirty:false,updatedAt:new Date().toISOString()});
    await this.retain(projectId,"audit",{operation:"reconcile",resolution:input.resolution,sourceRevision,
      mirrorRevision,noOpAcknowledgement},MAX_AUDIT);
    return this.get(projectId);
  }
  async revoke(input){
    const projectId=id(input.projectId),key=this.key(projectId),current=await this.storage.get(key);
    if(!current)return this.get(projectId);
    if(input.expectedMirrorRevision!==current.mirrorRevision)return this.conflict(projectId,current,"Mirror revision changed before revocation");
    const mirrorRevision=integer(current.mirrorRevision+1,"Mirror revision",1);
    await this.storage.put(key,{...current,enabled:false,mirrorRevision,updatedAt:new Date().toISOString()});
    await this.retain(projectId,"audit",{operation:"revoke",mirrorRevision},MAX_AUDIT);
    return this.get(projectId);
  }
}
