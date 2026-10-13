// Inert application-v3 authoring contracts. Native facades own storage/rendering.
import {z} from 'zod';
import {cinematic3dTimelineSchema,CINEMATIC_3D_ACTIONS} from './cinematic3d-contract.js';

const finite=z.number().finite(),integer=(min,max)=>finite.int().min(min).max(max);
const id=z.string().regex(/^[A-Za-z0-9_-]{1,96}$/),sha=z.string().regex(/^[a-f0-9]{64}$/);
const text=(max,nonempty=true)=>z.string().max(max).refine(v=>!v.includes('\0')&&(!nonempty||v.trim().length>0),'Bounded text is required');
const revision=integer(1,Number.MAX_SAFE_INTEGER),updatedAt=integer(0,Number.MAX_SAFE_INTEGER);
const time=integer(0,86400000),frame=integer(0,1199),fps=z.union([z.literal(24),z.literal(30),z.literal(60)]);
const color=z.string().regex(/^#[a-fA-F0-9]{8}$/);
const dimensions={width:integer(256,1280).refine(v=>v%2===0),height:integer(256,1280).refine(v=>v%2===0)};
const sketchTransformShape={x:finite.min(-4).max(4),y:finite.min(-4).max(4),scaleX:finite.min(.01).max(8),scaleY:finite.min(.01).max(8),rotation:finite.min(-3600).max(3600),anchorX:finite.min(0).max(1),anchorY:finite.min(0).max(1),opacity:finite.min(0).max(1)};
const fullTransform=z.object(sketchTransformShape).strict();
const transformPatch=z.object(Object.fromEntries(Object.entries(sketchTransformShape).map(([key,value])=>[key,value.optional()]))).strict();
const sketchKeys=z.array(z.object({frame,transform:fullTransform,interpolation:z.enum(['linear','hold'])}).strict()).max(4096)
 .refine(keys=>keys.every((key,i)=>i===0||key.frame>keys[i-1].frame),'Transform key frames must strictly increase');
const points=z.array(z.tuple([finite.min(0).max(1),finite.min(0).max(1),finite.min(0).max(1)])).min(1).max(131072);
const bounds=z.object({x:finite.min(0).max(1),y:finite.min(0).max(1),width:finite.min(.0001).max(1),height:finite.min(.0001).max(1)}).strict()
 .refine(v=>v.x+v.width<=1.000001&&v.y+v.height<=1.000001,'Shape bounds must fit the normalized canvas');
const reference=z.object({file:z.string().regex(/^[a-f0-9]{64}\.png$/),sha256:sha,width:integer(1,1280),height:integer(1,1280),sourceProjectId:id,sourceAssetId:id}).strict()
 .refine(v=>v.file===v.sha256+'.png','Reference must use its owned hash-bound PNG filename');
const brush=z.object({preset:z.enum(['ink','pencil','marker','airbrush','charcoal','calligraphy']),opacity:finite.min(0).max(1).optional(),hardness:finite.min(0).max(1).optional(),pressure:z.boolean().optional(),angle:finite.min(-180).max(180).optional()}).strict();
const coordinate=finite.min(0).max(1);
const commands=z.array(z.union([z.tuple([z.literal('M'),coordinate,coordinate]),z.tuple([z.literal('L'),coordinate,coordinate]),z.tuple([z.literal('Q'),coordinate,coordinate,coordinate,coordinate]),z.tuple([z.literal('C'),coordinate,coordinate,coordinate,coordinate,coordinate,coordinate]),z.tuple([z.literal('Z')])])).min(2).max(4096).refine(v=>v[0][0]==='M','Path must start with M');
const gradient=z.object({startColor:color,endColor:color,x0:coordinate,y0:coordinate,x1:coordinate,y1:coordinate}).strict().refine(v=>v.x0!==v.x1||v.y0!==v.y1,'Gradient endpoints must differ');
const blendMode=z.enum(['normal','multiply','screen','add']);
const sketchObjectShape={id,frame:frame.optional(),exposure:integer(1,1200).optional(),color:color.optional(),width:finite.min(.0005).max(.2).optional(),fill:z.boolean().optional(),transform:fullTransform.optional(),keys:sketchKeys.optional()};
function objectUnion(complete=false){
 const base=complete?{...sketchObjectShape,frame,exposure:integer(1,1200),color,width:finite.min(.0005).max(.2),fill:z.boolean(),transform:fullTransform,keys:sketchKeys}:sketchObjectShape;
 return z.discriminatedUnion('kind',[
  z.object({...base,kind:z.literal('stroke'),points,brush:brush.optional()}).strict(),
  z.object({...base,kind:z.literal('eraser'),points,brush:brush.optional()}).strict(),
  z.object({...base,kind:z.literal('line'),points:points.length(2)}).strict(),
  z.object({...base,kind:z.literal('polygon'),points:points.min(3),gradient:gradient.optional()}).strict(),
  z.object({...base,kind:z.literal('rect'),bounds,gradient:gradient.optional()}).strict(),
  z.object({...base,kind:z.literal('ellipse'),bounds,gradient:gradient.optional()}).strict(),
  z.object({...base,kind:z.literal('path'),commands,gradient:gradient.optional()}).strict(),
  z.object({...base,kind:z.literal('image'),bounds,reference,referenceCrop:bounds.optional()}).strict()
 ]).refine(v=>(v.frame??0)+(v.exposure??1)<=1200,'Exposure exceeds the bounded frame domain');
}
const sketchObject=objectUnion(),completeSketchObject=objectUnion(true);
const layer=z.object({id,name:text(160).optional(),visible:z.boolean().optional(),locked:z.boolean().optional(),blendMode:blendMode.optional(),opacity:finite.min(0).max(1).optional(),transform:fullTransform.optional(),keys:sketchKeys.optional(),objects:z.array(completeSketchObject).max(4096).optional()}).strict();
const target={layerId:id.optional(),objectId:id.optional()};
const operation=z.discriminatedUnion('type',[
 z.object({type:z.literal('add_layer'),layer}).strict(),
 z.object({type:z.literal('update_layer'),layerId:id,patch:z.object({name:text(160).optional(),visible:z.boolean().optional(),locked:z.boolean().optional(),blendMode:blendMode.optional(),opacity:finite.min(0).max(1).optional()}).strict()}).strict(),
 z.object({type:z.literal('delete_layer'),layerId:id}).strict(),
 z.object({type:z.literal('reorder_layer'),layerId:id,index:integer(0,31)}).strict(),
 z.object({type:z.literal('add_object'),layerId:id,object:sketchObject}).strict(),
 z.object({type:z.literal('update_object'),objectId:id,patch:z.object({color:color.optional(),width:finite.min(.0005).max(.2).optional(),fill:z.boolean().optional(),brush:brush.optional(),commands:commands.optional(),gradient:gradient.optional(),referenceCrop:bounds.optional(),points:points.optional(),bounds:bounds.optional(),frame:frame.optional(),exposure:integer(1,1200).optional()}).strict()}).strict(),
 z.object({type:z.literal('append_points'),objectId:id,points:points.max(2048)}).strict(),
 z.object({type:z.literal('duplicate_cel'),layerId:id,fromFrame:frame,toFrame:frame,idPrefix:id.max(72)}).strict(),
 z.object({type:z.literal('clear_cel'),layerId:id,frame}).strict(),
 z.object({type:z.literal('insert_frame'),frame:integer(0,1200)}).strict(),
 z.object({type:z.literal('delete_frame'),frame}).strict(),
 z.object({type:z.literal('trace_reference'),objectId:id,layerId:id,frame,threshold:finite.min(.01).max(1).optional(),idPrefix:id.max(72),exposure:integer(1,1200).optional(),color:color.optional(),width:finite.min(.0005).max(.2).optional()}).strict(),
 z.object({type:z.literal('delete_object'),objectId:id}).strict(),
 z.object({type:z.literal('set_canvas'),width:dimensions.width.optional(),height:dimensions.height.optional(),background:color.optional(),name:text(160).optional()}).strict(),
 z.object({type:z.literal('set_timing'),fps:fps.optional(),frameCount:integer(1,1200).optional()}).strict(),
 z.object({type:z.literal('set_exposure'),objectId:id,frame,exposure:integer(1,1200)}).strict(),
 z.object({type:z.literal('set_transform'),...target,transform:transformPatch}).strict(),
 z.object({type:z.literal('set_keyframe'),...target,frame,transform:transformPatch.optional(),interpolation:z.enum(['linear','hold']).optional(),remove:z.boolean().optional()}).strict()
]).superRefine((value,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 if(['set_transform','set_keyframe'].includes(value.type)&&Number(value.layerId!==undefined)+Number(value.objectId!==undefined)!==1)fail('Choose exactly one layerId or objectId');
 if(value.type==='set_keyframe'&&!value.remove&&value.transform===undefined)fail('A transform patch is required when setting a key');
 if(value.type==='set_exposure'&&value.frame+value.exposure>1200)fail('Exposure exceeds the bounded frame domain');
 if(value.type==='set_canvas'&&value.width!==undefined&&value.height!==undefined&&value.width*value.height>720*1280)fail('Raster exceeds 720×1280 pixels');
});

const clipTransformShape={x:finite.min(-2).max(2),y:finite.min(-2).max(2),scale:finite.min(.1).max(8),scaleX:finite.min(.1).max(8),scaleY:finite.min(.1).max(8),rotate:finite.min(-360).max(360),anchorX:finite.min(0).max(1),anchorY:finite.min(0).max(1),opacity:finite.min(0).max(1)};
const optionalClipTransform=Object.fromEntries(Object.entries(clipTransformShape).map(([key,value])=>[key,value.optional()]));
const clipKeys=z.array(z.object({timeMs:time,...optionalClipTransform,easing:z.enum(['linear','ease_in','ease_out','ease_in_out','hold']).optional()}).strict()).max(64)
 .refine(keys=>keys.every((key,i)=>i===0||key.timeMs>keys[i-1].timeMs),'Clip keyframe timeMs must strictly increase');
const clipSettings=z.object({...optionalClipTransform,inMs:time.optional(),outMs:integer(1,86400000).optional(),speed:finite.min(.25).max(4).optional(),volume:finite.min(0).max(2).optional(),brightness:finite.min(-1).max(1).optional(),contrast:finite.min(-1).max(1).optional(),saturationAdjust:finite.min(-100).max(100).optional(),lightnessAdjust:finite.min(-100).max(100).optional(),blur:finite.min(0).max(18).optional(),
 motionPreset:z.enum(['none','push_in','pull_out','pan_left','pan_right','pan_up','pan_down','drift','float','orbit','micro_shake','handheld','impact_shake','snap_zoom','zoom_punch','tilt','roll','hero_reveal','ken_burns']).optional(),
 colorPreset:z.enum(['none','cinematic','teal_orange','warm_film','cool_night','noir','high_contrast','soft_portrait','matte','golden_hour','cyberpunk']).optional(),effectPreset:z.enum(['none','gaussian_blur','dream']).optional(),title:text(160,false).optional(),
 chromaKey:z.boolean().optional(),chromaColor:z.string().regex(/^#[a-fA-F0-9]{6}$/).optional(),chromaTolerance:finite.min(0).max(1).optional(),chromaFeather:finite.min(0).max(1).optional(),
 maskShape:z.enum(['none','rectangle','ellipse']).optional(),maskLeft:finite.min(0).max(1).optional(),maskTop:finite.min(0).max(1).optional(),maskRight:finite.min(0).max(1).optional(),maskBottom:finite.min(0).max(1).optional(),maskFeather:finite.min(0).max(.5).optional(),
 glowStrength:finite.min(0).max(2).optional(),glowRadiusPx:finite.min(0).max(24).optional(),glowThreshold:finite.min(0).max(1).optional()
}).strict().refine(v=>(v.maskLeft??0)<(v.maskRight??1)&&(v.maskTop??0)<(v.maskBottom??1),'Mask rectangle must have positive extent');
const clipOperation=z.object({clipId:text(128),set:clipSettings.optional(),keyframes:clipKeys.optional(),clearKeyframes:z.boolean().optional(),resetColor:z.boolean().optional(),resetTransform:z.boolean().optional()}).strict();

const studioLayerBase={id:text(128).optional(),assetId:text(96,false).optional(),text:text(160,false).optional(),startMs:time.optional(),durationMs:integer(100,86400000).optional(),sourceInMs:time.optional(),gain:finite.min(0).max(1).optional(),color:integer(-2147483648,4294967295).optional(),position:integer(0,2).optional()};
const imageGeometry={x:finite.min(0).max(1).optional(),y:finite.min(0).max(1).optional(),width:finite.min(.01).max(2).optional(),height:finite.min(.01).max(2).optional(),rotation:finite.min(-360).max(360).optional(),opacity:finite.min(0).max(1).optional()};
const studioLayer=z.discriminatedUnion('type',[
 z.object({...studioLayerBase,type:z.literal('audio'),assetId:id}).strict(),
 z.object({...studioLayerBase,type:z.literal('title'),text:text(160)}).strict(),
 z.object({...studioLayerBase,...imageGeometry,type:z.literal('image'),assetId:id}).strict()
]);
const studioLayers=z.array(studioLayer).max(32).refine(v=>v.filter(l=>l.type==='image').length<=8,'At most eight image layers');

const cogControls=z.object({prompt:text(10000),negativePrompt:text(10000,false).optional(),mode:z.enum(['text_to_video','video_to_video']).optional(),strength:finite.min(.05).max(1).optional(),width:integer(16,2048).refine(v=>v%16===0),height:integer(16,2048).refine(v=>v%16===0),numFrames:integer(1,241).refine(v=>(v-1)%8===0,'CogVideoX temporal decoder requires 8k+1 frames'),fps:integer(1,60),seed:integer(0,2147483647),numInferenceSteps:integer(1,100),guidanceScale:finite.min(0).max(30)}).strict().superRefine((v,ctx)=>{
 const video=v.mode==='video_to_video';
 if(video&&v.strength===undefined||!video&&v.strength!==undefined)ctx.addIssue({code:z.ZodIssueCode.custom,message:'strength is required only for video_to_video mode'});
 if(video&&v.strength!==undefined&&Math.floor(v.strength*v.numInferenceSteps)<1)ctx.addIssue({code:z.ZodIssueCode.custom,message:'V2V_ZERO_DENOISING_STEPS: strength and inference steps must produce at least one denoising step'});
});
const blenderControls=z.object({timeline:cinematic3dTimelineSchema,renderFrames:integer(0,12000).optional(),conditioningFrames:z.array(integer(0,511)).max(512).optional()}).strict().superRefine((v,ctx)=>{
 const total=v.timeline.shots.reduce((n,s)=>n+s.durationFrames,0),fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 if((v.renderFrames??0)>total)fail('Render frame count exceeds the timeline');
 if(v.conditioningFrames&&(!v.conditioningFrames.every((frame,i)=>frame===i)||v.conditioningFrames.length>total))fail('Conditioning frames must be a contiguous prefix within the timeline');
});
const sequenceControls=z.object({fps:integer(1,60),width:integer(16,4096).refine(v=>v%2===0),height:integer(16,4096).refine(v=>v%2===0)}).strict();
const interpolationControls=z.object({inputFps:integer(1,60),outputFps:integer(1,120),cuts:z.array(integer(1,11999)).max(80).optional(),hitStopRanges:z.array(z.tuple([integer(0,11999),integer(1,11999)])).max(80).optional()}).strict().superRefine((v,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 if(v.outputFps<=v.inputFps)fail('Interpolation output FPS must increase');
 if(v.cuts?.some((cut,i,a)=>i>0&&cut<=a[i-1]))fail('Cuts must be ordered and unique');
 let end=-1;for(const range of v.hitStopRanges||[]){if(range[0]>=range[1]||range[0]<end)fail('Hold ranges must be ordered, nonoverlapping and end-exclusive');end=range[1];}
});
const backendControls={blender:blenderControls,cogvideox:cogControls,frame_sequence:sequenceControls,interpolation:interpolationControls};
const sourceIds=z.array(id).max(64).refine(v=>new Set(v).size===v.length,'Source asset IDs must be ordered and unique');
const endpoint=text(1024).refine(value=>{
 try{const u=new URL(value),authority=value.slice(value.indexOf('//')+2).split('/')[0];return /^https:\/\/[^\\\s]+$/i.test(value)&&!/[\u0080-\uffff]/.test(authority)&&value===value.trim()&&u.protocol==='https:'&&!!u.hostname&&!u.username&&!u.password&&!u.search&&!u.hash&&u.port!=='0'&&!/[?#]/.test(value);}catch{return false;}
},'Use your own HTTPS runner without embedded credentials, query or fragment');
const sketchIdentity={sketchId:id},mutation={...sketchIdentity,operationId:id,expectedRevision:revision};
const requestIdentity={requestId:id};
export const studioToolSchemas={
 precision_edit:{projectId:id,expectedUpdatedAt:updatedAt,operations:z.array(clipOperation).min(1).max(64)},
 set_studio_layers:{projectId:id,expectedUpdatedAt:updatedAt,studioLayers},
 sketch_capabilities:{},
 sketch_live_begin:{...mutation,strokeDurationMs:integer(80,5000).optional()},sketch_live_end:{sketchId:id,sessionId:id,expectedRevision:revision},sketch_live_events:{sketchId:id,afterSequence:integer(0,Number.MAX_SAFE_INTEGER),limit:integer(1,8).optional()},
 sketch_book_edit:{bookId:id,operationId:id,expectedRevision:revision,name:text(160).optional(),fps:fps.optional(),exposures:z.array(z.object({page:integer(0,9999),frames:integer(1,600)}).strict()).max(128).optional()},
 sketch_book_create:{bookId:id.max(72),operationId:id,name:text(160).optional(),pageCount:integer(1,10000).optional(),width:dimensions.width.optional(),height:dimensions.height.optional(),fps:fps.optional(),background:color.optional()},
 sketch_book_render:{bookId:id,operationId:id,expectedRevision:revision,pageStart:integer(0,9999).optional(),pageCount:integer(1,1200).optional(),aspect:z.enum(['original','9:16','16:9','1:1']).optional(),quality:z.enum(['720p','1080p']).optional()},
 sketch_book_list:{},sketch_book_get:{bookId:id,pageOffset:integer(0,10000).optional(),pageLimit:integer(1,128).optional()},sketch_book_open_page:{bookId:id,page:integer(0,9999)},
 sketch_create:{...sketchIdentity,operationId:id,name:text(160).optional(),width:dimensions.width.optional(),height:dimensions.height.optional(),fps:fps.optional(),frameCount:integer(1,1200).optional(),background:color.optional()},
 sketch_list:{},
 sketch_get:{...sketchIdentity,expectedRevision:revision.optional(),layerId:id.optional(),objectId:id.optional(),pointOffset:integer(0,131072).optional(),pointLimit:integer(1,512).optional(),objectOffset:integer(0,4096).optional(),objectLimit:integer(1,128).optional()},
 sketch_edit:{...mutation,operations:z.array(operation).min(1).max(64)},sketch_undo:mutation,sketch_redo:mutation,
 sketch_import_reference:{...mutation,projectId:id,sourceProjectUpdatedAt:updatedAt,assetId:id,layerId:id,objectId:id,frame:frame.optional(),exposure:integer(1,1200).optional(),bounds:bounds.optional()},
 sketch_preview:{...sketchIdentity,expectedRevision:revision.optional(),frameIndex:frame.optional(),maxDimension:integer(128,720).optional(),crop:bounds.optional()},
 sketch_render:{...mutation,aspect:z.enum(['original','9:16','16:9','1:1']).optional(),quality:z.enum(['720p','1080p']).optional()},
 open_pipeline_configure:{endpoint,bearer:z.string().min(16).max(4096).regex(/^[A-Za-z0-9_.~+/-]+=*$/)},
 open_pipeline_capabilities:{},open_pipeline_list:{},
 open_pipeline_export_request:{projectId:id,sourceProjectUpdatedAt:updatedAt,requestId:id,backend:z.enum(['blender','cogvideox','frame_sequence','interpolation']),controls:z.union(Object.values(backendControls)),assetIds:sourceIds},
 open_pipeline_submit:requestIdentity,open_pipeline_status:requestIdentity,open_pipeline_cancel:requestIdentity,
 open_pipeline_download_result:requestIdentity,open_pipeline_import_result:requestIdentity,
 open_pipeline_archive_artifact:{...requestIdentity,kind:z.enum(['request','result'])},
 open_pipeline_restore_artifact:{...requestIdentity,name:z.string().regex(/^[A-Za-z0-9_-]{1,96}\.vsresult$/),sha256:sha}
};
export const STUDIO_NATIVE_ACTIONS=new Set(Object.keys(studioToolSchemas));
const studioAliases=new Map([...new Set([...STUDIO_NATIVE_ACTIONS,...CINEMATIC_3D_ACTIONS,'apply_tool'])].map(action=>['studio_'+action,action]));
export const STUDIO_ALIAS_ACTIONS=new Set(studioAliases.keys());
/** A closed compatibility bridge; unknown names and repeated prefixes stay unchanged. */
export function nativeStudioCanonicalAction(action){return studioAliases.get(action)??action;}

function boundedJson(parameters){
 const active=new Set();let nodes=0;
 const visit=(value,depth)=>{
  if(++nodes>65536||depth>16)throw new Error('Studio metadata exceeds its structural budget');
  if(value===null||typeof value==='boolean')return;
  if(typeof value==='string'){if(value.length>512*1024)throw new Error('Studio request exceeds 512 KiB metadata budget');return;}
  if(typeof value==='number'&&Number.isFinite(value))return;
  if(typeof value!=='object'||active.has(value))throw new Error('Studio parameters must be acyclic finite JSON');
  const array=Array.isArray(value),prototype=Object.getPrototypeOf(value);
  // Plain objects/arrays from another realm are inert; custom prototypes are not.
  const constructor=prototype===null?null:Object.getOwnPropertyDescriptor(prototype,'constructor');
  const nativeConstructor=constructor&&'value'in constructor&&typeof constructor.value==='function'&&constructor.value.prototype===prototype&&/\[native code\]/.test(Function.prototype.toString.call(constructor.value));
  if(array){
   if(!prototype||!Array.isArray(prototype)||!nativeConstructor||constructor.value.name!=='Array'||!Object.getPrototypeOf(prototype)||Object.getPrototypeOf(Object.getPrototypeOf(prototype))!==null)throw new Error('Studio JSON cannot use custom array prototypes');
  }else if(prototype!==null&&(!nativeConstructor||constructor.value.name!=='Object'||Object.getPrototypeOf(prototype)!==null))throw new Error('Studio JSON cannot use custom prototypes');
  active.add(value);
  const keys=Reflect.ownKeys(value);
  if(array&&keys.length!==value.length+1)throw new Error('Studio JSON arrays must be dense with no extra properties');
  for(const key of keys){
   if(array&&key==='length')continue;
   if(typeof key!=='string'||['__proto__','prototype','constructor'].includes(key))throw new Error('Studio JSON contains a forbidden prototype field');
   if(array&&!/^(0|[1-9][0-9]*)$/.test(key))throw new Error('Studio JSON arrays cannot have named properties');
   const descriptor=Object.getOwnPropertyDescriptor(value,key);
   if(!descriptor||!('value' in descriptor)||!descriptor.enumerable)throw new Error('Studio JSON cannot contain accessors or hidden fields');
   visit(descriptor.value,depth+1);
  }
  active.delete(value);
 };
 visit(parameters,0);
 if(new TextEncoder().encode(JSON.stringify(parameters)).length>512*1024)throw new Error('Studio request exceeds 512 KiB metadata budget');
}

export function nativeStudioParameters(action,parameters={}){
 action=nativeStudioCanonicalAction(action);
 if(!STUDIO_NATIVE_ACTIONS.has(action))throw new Error('Unknown studio native action');
 if(!parameters||typeof parameters!=='object'||Array.isArray(parameters))throw new Error('Studio parameters must be an object');
 boundedJson(parameters);
 const parsed=z.object(studioToolSchemas[action]).strict().parse(parameters);
 if(['sketch_create','sketch_book_create'].includes(action)&&(parsed.width??720)*(parsed.height??1280)>720*1280)throw new Error('Sketch raster exceeds 720×1280 pixels');
 if(action==='sketch_import_reference'&&(parsed.frame??0)+(parsed.exposure??1)>1200)throw new Error('Reference exposure exceeds the bounded frame domain');
 if(action==='set_studio_layers'){
  const ids=parsed.studioLayers.filter(l=>l.id!==undefined).map(l=>l.id);if(new Set(ids).size!==ids.length)throw new Error('Studio layer IDs must be unique');
 }
 if(action==='open_pipeline_export_request'){
  backendControls[parsed.backend].parse(parsed.controls);
  if(new TextEncoder().encode(JSON.stringify(parsed.controls)).length>256*1024)throw new Error('Generation controls exceed 256 KiB metadata budget');
  const count=parsed.assetIds.length;
  if(parsed.backend==='cogvideox'){
   if(parsed.controls.mode==='video_to_video'&&count!==1)throw new Error('VIDEO_SOURCE_REQUIRED: video_to_video needs exactly one project-owned MP4 asset');
   if(parsed.controls.mode!=='video_to_video'&&count!==0)throw new Error('REFERENCE_CONDITIONING_UNAVAILABLE: text_to_video has no source assets');
  }
  if(parsed.backend==='frame_sequence'&&count<1||parsed.backend==='interpolation'&&count<2)throw new Error('Backend requires an ordered project-owned PNG source selection');
  if(parsed.backend==='interpolation'){
   if(parsed.controls.cuts?.some(cut=>cut>=count)||parsed.controls.hitStopRanges?.some(range=>range[1]>count))throw new Error('Cuts and hold ranges must fit the ordered source selection');
  }
 }
 if(action==='open_pipeline_restore_artifact'&&parsed.name!==parsed.requestId+'.vsresult')throw new Error('Restore only the pending request immutable result');
 return parsed;
}

const legacy3d=new Set(['STANDOFF','CHARGE','CLASH','BLADE_LOCK','FOLLOW_THROUGH','KNOCKBACK','LANDING','RECOVERY','AERIAL_FLIP','TUMBLE','MACRO_CLASH']);
const pixelFields=new Set(['keyframes','opacity','chromaKey','chromaColor','chromaTolerance','chromaFeather','maskShape','maskLeft','maskTop','maskRight','maskBottom','maskFeather','glowStrength','glowRadiusPx','glowThreshold']);
export function requireStudioVersion(device,action,parameters={}){
 const alias=studioAliases.has(action);action=nativeStudioCanonicalAction(action);
 if(action==='sketch_preview'&&parameters.crop){const match=String(device?.appVersion||'').match(/^(\d+)\.(\d+)\.(\d+)/);const v=match?match.slice(1,4).map(Number):null;if(!v||v[0]<3||v[0]===3&&(v[1]<9||v[1]===9&&v[2]<3))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: cropped sketch previews require VideoStudio 3.9.3 or newer');}
 const advancedSketch=action==='sketch_capabilities'||action.startsWith('sketch_book_')||action.startsWith('sketch_live_')||action==='sketch_edit'&&(parameters.operations||[]).some(op=>['append_points','duplicate_cel','clear_cel','insert_frame','delete_frame','trace_reference'].includes(op.type)||op.object?.kind==='path'||op.object?.brush||op.object?.gradient||op.object?.referenceCrop||op.patch?.brush||op.patch?.commands||op.patch?.gradient||op.patch?.referenceCrop||op.layer?.blendMode||op.patch?.blendMode);
 if(advancedSketch){const match=String(device?.appVersion||'').match(/^(\d+)\.(\d+)\.(\d+)/);const v=match?match.slice(1,4).map(Number):null;if(!v||v[0]<3||v[0]===3&&(v[1]<9||v[1]===9&&v[2]<1))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: advanced and live sketch authoring requires VideoStudio 3.9.1 or newer');}

 let required=STUDIO_NATIVE_ACTIONS.has(action)&&action!=='set_studio_layers';
 if(action==='cinematic_3d_install_builtin'&&parameters.pack==='extended')required=true;
 if(action==='set_studio_layers')required=[...(parameters.studioLayers||[]),...(parameters.layers||[])].some(l=>l?.type==='image');
 if(action==='cinematic_3d_save_program'){
  const timeline=parameters.timeline;
  required=!!timeline&&(Object.hasOwn(timeline,'actors')||(Array.isArray(timeline.shots)&&timeline.shots.some(shot=>shot&&(Object.hasOwn(shot,'motionStyle')||typeof shot.action==='string'&&!legacy3d.has(shot.action)))));
 }
 if(action==='apply_tool')required=['keyframes','mask','green_screen','glow','opacity'].includes(parameters.tool)||['glow','mask','chroma'].includes(parameters.settings?.preset)||Object.keys(parameters.settings||{}).some(key=>pixelFields.has(key));
 if(alias)required=true;
 if(!required)return;
 const match=String(device?.appVersion||'').match(/^(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:[-+].*)?$/);
 const version=match?match.slice(1,4).map(Number):null;
 if(!version||version[0]<3||version[0]===3&&version[1]<9)throw new Error('NATIVE_APP_UPGRADE_REQUIRED: this workbench operation requires VideoStudio 3.9.0 or newer; native application protocol v3 remains unchanged');
}
