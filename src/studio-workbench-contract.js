// Inert application-v3 authoring contracts. Native facades own storage/rendering.
import {z} from 'zod';
import {cinematic3dTimelineSchema,CINEMATIC_3D_ACTIONS} from './cinematic3d-contract.js';

const finite=z.number().finite(),integer=(min,max)=>finite.int().min(min).max(max);
const id=z.string().regex(/^[A-Za-z0-9_-]{1,96}$/),sha=z.string().regex(/^[a-f0-9]{64}$/);
const text=(max,nonempty=true)=>z.string().max(max).refine(v=>!v.includes('\0')&&(!nonempty||v.trim().length>0),'Bounded text is required');
const revision=integer(1,Number.MAX_SAFE_INTEGER),updatedAt=integer(0,Number.MAX_SAFE_INTEGER);
const time=integer(0,86400000),frame=integer(0,1199),fps=z.union([z.literal(24),z.literal(30),z.literal(60)]);
const color=z.string().regex(/^#[a-fA-F0-9]{8}$/);
const dimensions={width:integer(256,2048).refine(v=>v%2===0),height:integer(256,2048).refine(v=>v%2===0)};
const sketchTransformShape={x:finite.min(-4).max(4),y:finite.min(-4).max(4),scaleX:finite.min(.01).max(8),scaleY:finite.min(.01).max(8),rotation:finite.min(-3600).max(3600),anchorX:finite.min(0).max(1),anchorY:finite.min(0).max(1),opacity:finite.min(0).max(1)};
const fullTransform=z.object(sketchTransformShape).strict();
const transformPatch=z.object(Object.fromEntries(Object.entries(sketchTransformShape).map(([key,value])=>[key,value.optional()]))).strict();
const drawingInterpolation=z.enum(['linear','hold','smooth','ease_in','ease_out','ease_in_out','bezier']);
const timingCurve=z.tuple([finite.min(0).max(1),finite.min(0).max(1),finite.min(0).max(1),finite.min(0).max(1)]);
const timingValid=value=>(value.interpolation==='bezier')===(value.curve!==undefined);
const sketchKeys=z.array(z.object({frame,transform:fullTransform,interpolation:drawingInterpolation,curve:timingCurve.optional()}).strict().refine(timingValid,'Bezier timing requires explicit controls; other modes omit them')).max(4096)
 .refine(keys=>keys.every((key,i)=>i===0||key.frame>keys[i-1].frame),'Transform key frames must strictly increase');
const points=z.array(z.tuple([finite.min(0).max(1),finite.min(0).max(1),finite.min(0).max(1)])).min(1).max(131072);
const bounds=z.object({x:finite.min(0).max(1),y:finite.min(0).max(1),width:finite.min(.0001).max(1),height:finite.min(.0001).max(1)}).strict()
 .refine(v=>v.x+v.width<=1.000001&&v.y+v.height<=1.000001,'Shape bounds must fit the normalized canvas');
const reference=z.object({file:z.string().regex(/^[a-f0-9]{64}\.png$/),sha256:sha,width:integer(1,2048),height:integer(1,2048),sourceProjectId:id,sourceAssetId:id,
 sourceWidth:integer(1,64000000).optional(),sourceHeight:integer(1,64000000).optional(),sourceOrientation:integer(1,8).optional(),sourceSha256:sha.optional()}).strict()
 .refine(v=>v.file===v.sha256+'.png','Reference must use its owned hash-bound PNG filename')
 .refine(v=>(v.sourceWidth===undefined)===(v.sourceHeight===undefined)&&((v.sourceWidth??1)*(v.sourceHeight??1)<=64000000),'Original source dimensions must be paired and within the decode budget');
const brush=z.object({preset:z.enum(['ink','pencil','marker','airbrush','charcoal','calligraphy']),opacity:finite.min(0).max(1).optional(),hardness:finite.min(0).max(1).optional(),pressure:z.boolean().optional(),angle:finite.min(-180).max(180).optional()}).strict();
const coordinate=finite.min(0).max(1);
const paintBrush=z.object({mode:z.enum(['paint','erase','clone']),color,sizePx:finite.min(.5).max(256),opacity:coordinate.optional(),flow:coordinate.optional(),hardness:coordinate.optional(),spacing:finite.min(.05).max(1).optional(),pressure:z.boolean().optional(),texture:z.enum(['smooth','grain']).optional(),seed:integer(0,2147483647).optional(),angle:finite.min(-180).max(180).optional(),tip:z.enum(['round','flat']).optional()}).strict();
const affineMap=z.tuple([finite.min(-8).max(8),finite.min(-8).max(8),finite.min(-8).max(8),finite.min(-8).max(8),finite.min(-4).max(4),finite.min(-4).max(4)])
 .refine(v=>Math.abs(v[0]*v[3]-v[1]*v[2])>1e-10,'Clone mapping must be invertible');
const cloneSource=z.object({objectId:id,sha256:sha,offsetX:finite.min(-1).max(1).optional(),offsetY:finite.min(-1).max(1).optional(),scale:finite.min(.01).max(8).optional(),rotation:finite.min(-180).max(180).optional(),mapping:affineMap.optional()}).strict()
 .refine(v=>v.mapping===undefined||['offsetX','offsetY','scale','rotation'].every(k=>v[k]===undefined),'Choose affine mapping or offset/scale/rotation');
const commands=z.array(z.union([z.tuple([z.literal('M'),coordinate,coordinate]),z.tuple([z.literal('L'),coordinate,coordinate]),z.tuple([z.literal('Q'),coordinate,coordinate,coordinate,coordinate]),z.tuple([z.literal('C'),coordinate,coordinate,coordinate,coordinate,coordinate,coordinate]),z.tuple([z.literal('Z')])])).min(2).max(4096).refine(v=>v[0][0]==='M','Path must start with M');
const shapeKeys=z.array(z.object({frame,commands,interpolation:drawingInterpolation,curve:timingCurve.optional()}).strict().refine(timingValid,'Invalid shape timing curve')).max(240)
 .refine(keys=>keys.every((key,index)=>index===0||key.frame>keys[index-1].frame),'Shape key frames must strictly increase');
const shapeTopology=object=>!object.shapeKeys||object.shapeKeys.every(key=>key.commands.length===object.commands.length&&key.commands.every((command,index)=>command[0]===object.commands[index][0]&&command.length===object.commands[index].length));
const legacyGradient=z.object({startColor:color,endColor:color,x0:coordinate,y0:coordinate,x1:coordinate,y1:coordinate}).strict().refine(v=>v.x0!==v.x1||v.y0!==v.y1,'Gradient endpoints must differ');
const radialGradient=z.object({type:z.literal('radial'),cx:coordinate,cy:coordinate,radius:finite.min(.0001).max(2),colors:z.array(color).min(2).max(16),stops:z.array(coordinate).min(2).max(16).optional()}).strict()
 .refine(v=>!v.stops||v.stops.length===v.colors.length&&v.stops[0]===0&&v.stops.at(-1)===1&&v.stops.every((stop,i)=>i===0||stop>v.stops[i-1]),'Each color needs a matching strictly increasing stop from 0 to 1');
const gradient=z.union([legacyGradient,radialGradient]);
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
  z.object({...base,kind:z.literal('path'),commands,shapeKeys:shapeKeys.optional(),gradient:gradient.optional()}).strict().refine(shapeTopology,'Shape keys must preserve the base path topology'),
  z.object({...base,kind:z.literal('image'),bounds,reference,referenceCrop:bounds.optional()}).strict(),
  z.object({...base,kind:z.literal('text'),bounds,text:text(4096),fontFamily:z.enum(['sans-serif','sans-serif-condensed','serif','monospace','cursive']).optional(),fontSize:finite.min(.005).max(.3).optional(),fontStyle:z.enum(['normal','bold','italic','bold_italic']).optional(),textAlign:z.enum(['left','center','right']).optional(),letterSpacing:finite.min(-.1).max(.5).optional()}).strict()
 ]).refine(v=>(v.frame??0)+(v.exposure??1)<=1200,'Exposure exceeds the bounded frame domain');
}
const sketchObject=objectUnion(),completeSketchObject=objectUnion(true);
const layer=z.object({id,name:text(160).optional(),visible:z.boolean().optional(),locked:z.boolean().optional(),blendMode:blendMode.optional(),opacity:finite.min(0).max(1).optional(),transform:fullTransform.optional(),keys:sketchKeys.optional(),objects:z.array(completeSketchObject).max(4096).optional()}).strict();
const target={layerId:id.optional(),objectId:id.optional()};
const symmetry=z.object({mode:z.enum(['vertical','horizontal','both','radial']),segments:integer(2,8).optional(),centerX:coordinate.optional(),centerY:coordinate.optional()}).strict()
 .refine(v=>(v.mode==='radial')===(v.segments!==undefined),'Only radial symmetry requires segments');
const symmetryObject=z.object({id:id.max(90),kind:z.enum(['stroke','eraser']),points:points.max(8192),frame:frame.optional(),exposure:integer(1,1200).optional(),color:color.optional(),width:finite.min(.0005).max(.2).optional(),fill:z.boolean().optional(),brush:brush.optional()}).strict()
 .refine(v=>(v.frame??0)+(v.exposure??1)<=1200,'Exposure exceeds the bounded frame domain');
const operation=z.discriminatedUnion('type',[
 z.object({type:z.literal('symmetry_stroke'),layerId:id,object:symmetryObject,symmetry}).strict(),
 z.object({type:z.literal('add_paint_cel'),layerId:id,objectId:id,frame:frame.optional(),exposure:integer(1,1200).optional()}).strict(),
 z.object({type:z.literal('paint_stroke'),objectId:id,strokeId:id,frameIndex:frame.optional(),points:points.max(2048),brush:paintBrush,alphaLock:z.boolean().optional(),clip:bounds.optional(),cloneSource:cloneSource.optional()}).strict(),
 z.object({type:z.literal('add_layer'),layer}).strict(),
 z.object({type:z.literal('update_layer'),layerId:id,patch:z.object({name:text(160).optional(),visible:z.boolean().optional(),locked:z.boolean().optional(),blendMode:blendMode.optional(),opacity:finite.min(0).max(1).optional()}).strict()}).strict(),
 z.object({type:z.literal('delete_layer'),layerId:id}).strict(),
 z.object({type:z.literal('reorder_layer'),layerId:id,index:integer(0,31)}).strict(),
 z.object({type:z.literal('add_object'),layerId:id,object:sketchObject}).strict(),
 z.object({type:z.literal('update_object'),objectId:id,patch:z.object({color:color.optional(),width:finite.min(.0005).max(.2).optional(),fill:z.boolean().optional(),text:text(4096).optional(),fontFamily:z.enum(['sans-serif','sans-serif-condensed','serif','monospace','cursive']).optional(),fontSize:finite.min(.005).max(.3).optional(),fontStyle:z.enum(['normal','bold','italic','bold_italic']).optional(),textAlign:z.enum(['left','center','right']).optional(),letterSpacing:finite.min(-.1).max(.5).optional(),brush:brush.optional(),commands:commands.optional(),shapeKeys:shapeKeys.optional(),gradient:gradient.nullable().optional(),referenceCrop:bounds.optional(),points:points.optional(),bounds:bounds.optional(),frame:frame.optional(),exposure:integer(1,1200).optional()}).strict()}).strict(),
 z.object({type:z.literal('append_points'),objectId:id,points:points.max(2048)}).strict(),
 z.object({type:z.literal('duplicate_cel'),layerId:id,fromFrame:frame,toFrame:frame,idPrefix:id.max(72)}).strict(),
 z.object({type:z.literal('clear_cel'),layerId:id,frame}).strict(),
 z.object({type:z.literal('insert_frame'),frame:integer(0,1200)}).strict(),
 z.object({type:z.literal('delete_frame'),frame}).strict(),
 z.object({type:z.literal('trace_reference'),objectId:id,layerId:id,frame,threshold:finite.min(.01).max(1).optional(),mode:z.enum(['edges','colour_regions']).optional(),maxDimension:integer(32,384).optional(),colourLevels:integer(2,32).optional(),idPrefix:id.max(72),exposure:integer(1,1200).optional(),color:color.optional(),width:finite.min(.0005).max(.2).optional()}).strict(),
 z.object({type:z.literal('set_reference_visibility'),objectId:id,visible:z.boolean()}).strict(),
 z.object({type:z.literal('delete_object'),objectId:id}).strict(),
 z.object({type:z.literal('set_canvas'),width:dimensions.width.optional(),height:dimensions.height.optional(),background:color.optional(),name:text(160).optional()}).strict(),
 z.object({type:z.literal('set_timing'),fps:fps.optional(),frameCount:integer(1,1200).optional()}).strict(),
 z.object({type:z.literal('set_exposure'),objectId:id,frame,exposure:integer(1,1200)}).strict(),
 z.object({type:z.literal('set_transform'),...target,transform:transformPatch}).strict(),
 z.object({type:z.literal('set_keyframe'),...target,frame,transform:transformPatch.optional(),interpolation:drawingInterpolation.optional(),curve:timingCurve.optional(),remove:z.boolean().optional()}).strict().refine(timingValid,'Bezier timing requires explicit controls; other modes omit them'),
 z.object({type:z.literal('set_shape_key'),objectId:id,frame,commands:commands.optional(),interpolation:drawingInterpolation.optional(),curve:timingCurve.optional(),remove:z.boolean().optional()}).strict()
  .refine(v=>v.remove===true||v.commands!==undefined,'A shape key requires curve geometry').refine(timingValid,'Invalid shape timing curve')
]).superRefine((value,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 if(['set_transform','set_keyframe'].includes(value.type)&&Number(value.layerId!==undefined)+Number(value.objectId!==undefined)!==1)fail('Choose exactly one layerId or objectId');
 if(value.type==='set_keyframe'&&!value.remove&&value.transform===undefined)fail('A transform patch is required when setting a key');
 if(value.type==='set_exposure'&&value.frame+value.exposure>1200)fail('Exposure exceeds the bounded frame domain');
 if(value.type==='add_paint_cel'&&(value.frame??0)+(value.exposure??1)>1200)fail('Exposure exceeds the bounded frame domain');
 if(value.type==='paint_stroke'){
  if((value.brush.mode==='clone')!==(value.cloneSource!==undefined))fail('Clone mode requires an owned source; other modes do not accept one');
  if(value.brush.mode==='erase'&&value.alphaLock)fail('Erase cannot use alpha lock');
 }
 if(value.type==='set_canvas'&&value.width!==undefined&&value.height!==undefined&&value.width*value.height>2048*2048)fail('Raster exceeds 2048² pixels');
});

const clipTransformShape={x:finite.min(-2).max(2),y:finite.min(-2).max(2),scale:finite.min(.1).max(8),scaleX:finite.min(.1).max(8),scaleY:finite.min(.1).max(8),rotate:finite.min(-360).max(360),anchorX:finite.min(0).max(1),anchorY:finite.min(0).max(1),opacity:finite.min(0).max(1)};
const optionalClipTransform=Object.fromEntries(Object.entries(clipTransformShape).map(([key,value])=>[key,value.optional()]));
const clipKeys=z.array(z.object({timeMs:time,...optionalClipTransform,easing:z.enum(['linear','ease_in','ease_out','ease_in_out','hold']).optional()}).strict()).max(64)
 .refine(keys=>keys.every((key,i)=>i===0||key.timeMs>keys[i-1].timeMs),'Clip keyframe timeMs must strictly increase');
const incrementalKeys=z.array(z.discriminatedUnion('action',[
 z.object({action:z.literal('upsert'),keyframe:z.object({timeMs:time,...optionalClipTransform,easing:z.enum(['linear','ease_in','ease_out','ease_in_out','hold']).optional()}).strict()}).strict(),
 z.object({action:z.literal('delete'),timeMs:time}).strict()
])).min(1).max(64);
const cropBounds=bounds.refine(v=>v.width>=.01&&v.height>=.01,"Crop must cover at least 1% of each source axis");
const clipSettings=z.object({...optionalClipTransform,crop:cropBounds.optional(),inMs:time.optional(),outMs:integer(1,86400000).optional(),speed:finite.min(.25).max(4).optional(),volume:finite.min(0).max(2).optional(),audioFadeInMs:time.optional(),audioFadeOutMs:time.optional(),audioPan:finite.min(-1).max(1).optional(),audioMono:z.boolean().optional(),brightness:finite.min(-1).max(1).optional(),contrast:finite.min(-1).max(1).optional(),saturationAdjust:finite.min(-100).max(100).optional(),lightnessAdjust:finite.min(-100).max(100).optional(),blur:finite.min(0).max(18).optional(),
 motionPreset:z.enum(['none','push_in','pull_out','pan_left','pan_right','pan_up','pan_down','drift','float','orbit','micro_shake','handheld','impact_shake','snap_zoom','zoom_punch','tilt','roll','hero_reveal','ken_burns']).optional(),
 colorPreset:z.enum(['none','cinematic','teal_orange','warm_film','cool_night','noir','high_contrast','soft_portrait','matte','golden_hour','cyberpunk']).optional(),effectPreset:z.enum(['none','gaussian_blur','dream']).optional(),title:text(160,false).optional(),
 chromaKey:z.boolean().optional(),chromaColor:z.string().regex(/^#[a-fA-F0-9]{6}$/).optional(),chromaTolerance:finite.min(0).max(1).optional(),chromaFeather:finite.min(0).max(1).optional(),
 maskShape:z.enum(['none','rectangle','ellipse']).optional(),maskLeft:finite.min(0).max(1).optional(),maskTop:finite.min(0).max(1).optional(),maskRight:finite.min(0).max(1).optional(),maskBottom:finite.min(0).max(1).optional(),maskFeather:finite.min(0).max(.5).optional(),
 glowStrength:finite.min(0).max(2).optional(),glowRadiusPx:finite.min(0).max(24).optional(),glowThreshold:finite.min(0).max(1).optional()
}).strict().refine(v=>(v.maskLeft??0)<(v.maskRight??1)&&(v.maskTop??0)<(v.maskBottom??1),'Mask rectangle must have positive extent');
const clipOperation=z.object({clipId:text(128),set:clipSettings.optional(),keyframes:clipKeys.optional(),keyframeEdits:incrementalKeys.optional(),clearKeyframes:z.boolean().optional(),resetColor:z.boolean().optional(),resetTransform:z.boolean().optional()}).strict()
 .refine(v=>v.keyframeEdits===undefined||v.keyframes===undefined&&v.clearKeyframes===undefined,'Incremental key edits cannot replace or clear the same track');

const studioLayerBase={id:text(128).optional(),assetId:text(96,false).optional(),text:text(160,false).optional(),startMs:time.optional(),durationMs:integer(100,86400000).optional(),sourceInMs:time.optional(),gain:finite.min(0).max(1).optional(),color:integer(-2147483648,4294967295).optional(),position:integer(0,2).optional()};
const imageGeometry={x:finite.min(0).max(1).optional(),y:finite.min(0).max(1).optional(),width:finite.min(.01).max(2).optional(),height:finite.min(.01).max(2).optional(),rotation:finite.min(-360).max(360).optional(),opacity:finite.min(0).max(1).optional()};
const studioLayer=z.discriminatedUnion('type',[
 z.object({...studioLayerBase,type:z.literal('audio'),assetId:id}).strict(),
 z.object({...studioLayerBase,type:z.literal('title'),text:text(160),fontSize:integer(12,96).optional(),fontFamily:z.enum(['sans-serif','sans-serif-condensed','serif','monospace','cursive']).optional(),fontStyle:z.enum(['normal','bold','italic','bold_italic']).optional(),textAlign:z.enum(['left','center','right']).optional(),backgroundColor:integer(-2147483648,4294967295).optional()}).strict(),
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
const dialogueLine=z.object({lineId:id,characterId:id,voice:z.enum(['af_heart','am_adam','hf_alpha','hm_omega']),text:text(240),startMs:integer(0,599999),slotMs:integer(100,60000),speed:finite.min(.75).max(1.25).optional()}).strict();
const dialogueControls=z.object({language:z.enum(['en-US','hi-IN']),durationMs:integer(100,600000),lines:z.array(dialogueLine).min(1).max(32)}).strict().superRefine((v,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message}),ids=new Set(),cast=new Map();let end=-1,total=0;
 for(const line of v.lines){
  if(ids.has(line.lineId))fail('Duplicate dialogue line');ids.add(line.lineId);
  if(!(v.language==='en-US'?['af_heart','am_adam']:['hf_alpha','hm_omega']).includes(line.voice))fail('DIALOGUE_VOICE_LANGUAGE_MISMATCH');
  if(cast.has(line.characterId)&&cast.get(line.characterId)!==line.voice)fail('CHARACTER_VOICE_CHANGED');cast.set(line.characterId,line.voice);
  if(line.startMs<end||line.startMs+line.slotMs>v.durationMs)fail('Dialogue slots must be ordered, nonoverlapping and inside the scene');end=line.startMs+line.slotMs;
  if(/[\u0000-\u0008\u000b-\u001f]/.test(line.text))fail('Invalid dialogue text');total+=line.text.length;
 }
 if(total>6000)fail('Dialogue text budget exceeded');
});
const backendControls={blender:blenderControls,cogvideox:cogControls,frame_sequence:sequenceControls,interpolation:interpolationControls,kokoro:dialogueControls};
const sourceIds=z.array(id).max(64).refine(v=>new Set(v).size===v.length,'Source asset IDs must be ordered and unique');
const endpoint=text(1024).refine(value=>{
 try{const u=new URL(value),authority=value.slice(value.indexOf('//')+2).split('/')[0];return /^https:\/\/[^\\\s]+$/i.test(value)&&!/[\u0080-\uffff]/.test(authority)&&value===value.trim()&&u.protocol==='https:'&&!!u.hostname&&!u.username&&!u.password&&!u.search&&!u.hash&&u.port!=='0'&&!/[?#]/.test(value);}catch{return false;}
},'Use your own HTTPS runner without embedded credentials, query or fragment');
const sketchIdentity={sketchId:id},mutation={...sketchIdentity,operationId:id,expectedRevision:revision};
const requestIdentity={requestId:id};
const animationInterpolation=drawingInterpolation;
const orderedFrames=keys=>keys.every((key,i)=>i===0||key.frame>keys[i-1].frame);
const animationRange={start:frame,end:frame};
const animationVisibility=z.object({objectId:id,...animationRange}).strict().refine(v=>v.end>=v.start,'Cel end must follow start');
const animationRig=z.object({id,
 joints:z.array(z.object({id,parent:id.optional(),x:coordinate,y:coordinate}).strict()).min(1).max(64),
 bindings:z.array(z.object({objectId:id,jointId:id,weights:z.array(z.record(id,coordinate)).max(131072).optional()}).strict()).max(4096),
 poses:z.array(z.object({frame,interpolation:animationInterpolation.optional(),curve:timingCurve.optional(),rootX:finite.min(-4).max(4).optional(),rootY:finite.min(-4).max(4).optional(),rotations:z.record(id,finite.min(-3600).max(3600)).optional()}).strict().refine(timingValid,'Invalid pose timing curve')).max(1200).refine(orderedFrames,'Pose frames must strictly increase').optional(),
 ik:z.array(z.object({hip:id,knee:id,ankle:id,...animationRange,x:coordinate,y:coordinate,bend:z.union([z.literal(-1),z.literal(1)]).optional(),rotation:finite.min(-3600).max(3600).optional()}).strict().refine(v=>v.end>=v.start,'Contact end must follow start')).max(256).optional()
}).strict();
export const sketchAnimationProgramSchema=z.object({schema:z.literal('videostudio.sketch-animation.v1'),name:text(160).optional(),frameCount:integer(1,1200),fps:fps.optional(),
 rigs:z.array(animationRig).max(16),
 camera:z.array(z.object({frame,x:finite.min(-4).max(4).optional(),y:finite.min(-4).max(4).optional(),zoom:finite.min(.01).max(8).optional(),rotation:finite.min(-3600).max(3600).optional(),interpolation:animationInterpolation.optional(),curve:timingCurve.optional()}).strict().refine(timingValid,'Invalid camera timing curve')).max(1200).refine(orderedFrames,'Camera frames must strictly increase').optional(),
 cameraLayerIds:z.array(id).max(32).optional(),
 dialogues:z.array(z.object({...animationRange,text:text(4096),layerId:id,bounds,color:color.optional(),fontSize:finite.min(.005).max(.3).optional()}).strict().refine(v=>v.end>=v.start,'Dialogue end must follow start')).max(256).optional(),
 mouthCels:z.array(animationVisibility).max(4096).optional(),celRanges:z.array(animationVisibility).max(4096).optional()
}).strict().superRefine((value,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 for(const rig of value.rigs){
  const ids=new Set(rig.joints.map(j=>j.id));if(ids.size!==rig.joints.length)fail('Joint IDs must be unique within a rig');
  for(const joint of rig.joints)if(joint.parent!==undefined&&!ids.has(joint.parent))fail('Parent joint must exist');
  for(const binding of rig.bindings){if(!ids.has(binding.jointId))fail('Binding joint must exist');for(const weights of binding.weights||[]){const entries=Object.entries(weights);if(entries.length<1||entries.length>4||entries.some(([id,w])=>!ids.has(id)||w<=0)||Math.abs(entries.reduce((sum,[,w])=>sum+w,0)-1)>1e-7)fail('Each vertex needs 1..4 positive existing joint weights summing to one');}}
  for(const pose of rig.poses||[])if(pose.frame>=value.frameCount||Object.keys(pose.rotations||{}).some(id=>!ids.has(id)))fail('Pose must fit the timeline and name existing joints');
  for(const contact of rig.ik||[])if(contact.end>=value.frameCount||['hip','knee','ankle'].some(key=>!ids.has(contact[key])))fail('Contact must fit the timeline and name existing joints');
 }
 for(const key of value.camera||[])if(key.frame>=value.frameCount)fail('Camera key must fit the timeline');
 for(const cue of [...value.dialogues||[],...value.mouthCels||[],...value.celRanges||[]])if(cue.end>=value.frameCount)fail('Cue must fit the timeline');
 if(value.camera?.length&&!value.cameraLayerIds?.length)fail('Camera keys require explicit layer IDs');
});
export const studioToolSchemas={
 import_mailbox_media:{projectId:id,attachmentId:z.string().uuid(),manifestSha256:sha},
 precision_edit:{projectId:id,expectedUpdatedAt:updatedAt,operations:z.array(clipOperation).min(1).max(64)},
 set_studio_layers:{projectId:id,expectedUpdatedAt:updatedAt,studioLayers},
 sketch_capabilities:{},
 sketch_animation_save:{animationId:id,sketchId:id,sourceRevision:revision,expectedRevision:integer(0,Number.MAX_SAFE_INTEGER-1),operationId:id,program:sketchAnimationProgramSchema},
 sketch_animation_get:{animationId:id},sketch_animation_list:{},
 sketch_animation_bake:{animationId:id,expectedRevision:revision,operationId:id,outputSketchId:id},
 sketch_paint_journal:{sketchId:id,expectedRevision:revision,objectId:id,offset:integer(0,8192).optional(),limit:integer(1,128).optional()},
 sketch_sample_color:{sketchId:id,expectedRevision:revision,frameIndex:frame.optional(),x:coordinate,y:coordinate,source:z.enum(['paint','reference','composite']),objectId:id.optional(),sha256:sha.optional()},
 sketch_archive:{...mutation},
 sketch_restore_archive:{archiveProjectId:id,archiveName:z.string().max(120).regex(/^[A-Za-z0-9_-][A-Za-z0-9_.-]*\.vssketch$/).refine(v=>!v.includes('..')),expectedSha256:sha,operationId:id,name:text(160).optional()},
 sketch_inspect:{sketchId:id,expectedRevision:revision.optional(),frameOffset:frame.optional(),frameLimit:integer(1,64).optional(),characterLayerIds:z.array(id).max(8).optional()},
 sketch_live_begin:{...mutation,strokeDurationMs:integer(80,5000).optional(),drawingMethod:z.enum(['original_strokes','source_assisted']).optional()},sketch_live_end:{sketchId:id,sessionId:id,expectedRevision:revision},sketch_live_events:{sketchId:id,afterSequence:integer(0,Number.MAX_SAFE_INTEGER),limit:integer(1,8).optional(),operationOffset:integer(0,32768).optional(),operationLimit:integer(1,64).optional(),eventSequence:integer(1,Number.MAX_SAFE_INTEGER).optional()},
 sketch_book_edit:{bookId:id,operationId:id,expectedRevision:revision,name:text(160).optional(),fps:fps.optional(),exposures:z.array(z.object({page:integer(0,999999),frames:integer(1,600)}).strict()).max(128).optional(),cues:z.array(z.union([z.object({id,type:z.enum(['shot','pose','turn','expression','camera','contact','impact','sound']),startFrame:integer(0,999999),endFrame:integer(0,999999),label:text(512),actorId:id.optional(),targetId:id.optional()}).strict().refine(v=>v.endFrame>=v.startFrame),z.object({id,delete:z.literal(true)}).strict()])).max(128).optional()},
 sketch_book_create:{bookId:id.max(72),operationId:id,name:text(160).optional(),pageCount:integer(1,1000000).optional(),width:dimensions.width.optional(),height:dimensions.height.optional(),fps:fps.optional(),background:color.optional()},
 sketch_book_render:{bookId:id,operationId:id,expectedRevision:revision,pageStart:integer(0,999999).optional(),pageCount:integer(1,1000000).optional(),aspect:z.enum(['original','9:16','16:9','1:1']).optional(),quality:z.enum(['720p','1080p']).optional()},
 sketch_component_save:{componentId:id,sketchId:id,expectedRevision:revision,operationId:id,name:text(160),category:z.enum(['character','pose','turn','expression','mouth','hand','prop','background','effect','construction']),frameIndex:frame,layerIds:z.array(id).min(1).max(32).optional()},
 sketch_component_list:{category:z.enum(['character','pose','turn','expression','mouth','hand','prop','background','effect','construction']).optional(),offset:integer(0,1000000).optional(),limit:integer(1,128).optional()},
 sketch_component_apply:{componentId:id,sketchId:id,expectedRevision:revision,operationId:id,frameIndex:frame,exposure:integer(1,1200).optional()},
 sketch_import_animation:{projectId:id,sourceProjectUpdatedAt:updatedAt,assetId:id,expectedSha256:sha,bookId:id.max(72),operationId:id,name:text(160).optional(),fps:fps.optional(),frameStart:integer(0,999999).optional(),frameCount:integer(1,1000000).optional(),width:dimensions.width.optional(),height:dimensions.height.optional()},
 sketch_book_timeline:{bookId:id,expectedRevision:revision.optional(),frameOffset:integer(0,999999).optional(),frameLimit:integer(1,128).optional(),cueOffset:integer(0,65536).optional(),cueLimit:integer(1,128).optional()},
 sketch_book_list:{},sketch_book_get:{bookId:id,pageOffset:integer(0,1000000).optional(),pageLimit:integer(1,128).optional()},sketch_book_open_page:{bookId:id,page:integer(0,999999)},
 sketch_create:{...sketchIdentity,operationId:id,name:text(160).optional(),width:dimensions.width.optional(),height:dimensions.height.optional(),fps:fps.optional(),frameCount:integer(1,1200).optional(),background:color.optional()},
 sketch_list:{},
 sketch_get:{...sketchIdentity,shapeFrame:frame.optional(),expectedRevision:revision.optional(),layerId:id.optional(),objectId:id.optional(),pointOffset:integer(0,131072).optional(),pointLimit:integer(1,512).optional(),commandOffset:integer(0,4096).optional(),commandLimit:integer(1,512).optional(),objectOffset:integer(0,4096).optional(),objectLimit:integer(1,128).optional()},
 sketch_edit:{...mutation,operations:z.array(operation).min(1).max(64)},sketch_undo:mutation,sketch_redo:mutation,
 sketch_import_reference:{...mutation,projectId:id,sourceProjectUpdatedAt:updatedAt,assetId:id,layerId:id,objectId:id,frame:frame.optional(),exposure:integer(1,1200).optional(),bounds:bounds.optional(),guideVisible:z.boolean().optional()},
 sketch_import_frame_manifest:{sketchId:id,manifestName:z.string().min(1).max(120).regex(/^[A-Za-z0-9_.-]+\.jsonl(?:\.gz)?$/).refine(v=>!v.includes('..')),videoName:z.string().min(1).max(120).regex(/^[A-Za-z0-9_.-]+\.mp4$/).refine(v=>!v.includes('..')),expectedSourceSha256:sha,expectedFrameCount:integer(1,1200)},
 sketch_import_drawing_manifest:{sketchId:id,manifestName:z.string().min(1).max(120).regex(/^[A-Za-z0-9_.-]+\.jsonl(?:\.gz)?$/).refine(v=>!v.includes('..')),sourceName:z.string().min(1).max(120).regex(/^[A-Za-z0-9_.-]+\.(mp4|png|jpg|jpeg|webp|txt)$/i).refine(v=>!v.includes('..')),expectedSourceSha256:sha,expectedFrameCount:integer(1,1200)},
 sketch_import_drawing_asset:{projectId:id,sourceProjectUpdatedAt:integer(1,Number.MAX_SAFE_INTEGER),sketchId:id,manifestAssetId:id,expectedManifestSha256:sha,sourceAssetId:id,expectedSourceSha256:sha,expectedFrameCount:integer(1,1200),drawingMethod:z.enum(['original_strokes','source_assisted'])},
 get_native_result_page:{commandId:id,pageIndex:integer(0,2147483647)},
 get_native_delivery_status:{limit:integer(1,4).optional()},
 compact_native_delivery:{commandId:id,expectedResultSha256:sha},
 sketch_preview:{...sketchIdentity,expectedRevision:revision.optional(),frameIndex:frame.optional(),maxDimension:integer(128,720).optional(),crop:bounds.optional()},
 sketch_render:{...mutation,aspect:z.enum(['original','9:16','16:9','1:1']).optional(),quality:z.enum(['720p','1080p']).optional()},
 open_pipeline_configure:{endpoint,bearer:z.string().min(16).max(4096).regex(/^[A-Za-z0-9_.~+/-]+=*$/)},
 open_pipeline_capabilities:{},open_pipeline_list:{},
 open_pipeline_export_request:{projectId:id,sourceProjectUpdatedAt:updatedAt,requestId:id,backend:z.enum(['blender','cogvideox','frame_sequence','interpolation','kokoro']),controls:z.union(Object.values(backendControls)),assetIds:sourceIds},
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
 if(action==='sketch_get'&&parsed.shapeFrame!==undefined&&parsed.objectId===undefined)throw new Error('Choose an object for sampled shape geometry');
 if(['sketch_create','sketch_book_create'].includes(action)&&(parsed.width??720)*(parsed.height??1280)>2048*2048)throw new Error('Sketch raster exceeds 2048² pixels');
 if(action==='sketch_sample_color'){
  if(parsed.source==='composite'&&(parsed.objectId!==undefined||parsed.sha256!==undefined)||parsed.source!=='composite'&&parsed.objectId===undefined||parsed.source==='reference'&&parsed.sha256===undefined||parsed.source==='paint'&&parsed.sha256!==undefined)throw new Error('Colour sample must identify exactly its selected source');
 }
 if(action==='sketch_import_reference'&&(parsed.frame??0)+(parsed.exposure??1)>1200)throw new Error('Reference exposure exceeds the bounded frame domain');
 if(action==='set_studio_layers'){
  const ids=parsed.studioLayers.filter(l=>l.id!==undefined).map(l=>l.id);if(new Set(ids).size!==ids.length)throw new Error('Studio layer IDs must be unique');
 }
 if(action==='open_pipeline_export_request'){
  backendControls[parsed.backend].parse(parsed.controls);
  if(new TextEncoder().encode(JSON.stringify(parsed.controls)).length>256*1024)throw new Error('Generation controls exceed 256 KiB metadata budget');
  const count=parsed.assetIds.length;
  if(parsed.backend==='kokoro'&&count!==0)throw new Error('Speech uses explicitly cast local voices without source assets');
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
 if(action==='open_pipeline_export_request'&&parameters.backend==='kokoro'&&!atLeast(device?.appVersion,'4.7.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: timed Hindi/English character dialogue requires VideoStudio 4.7.0 or newer');
 const longSequence=action==='sketch_book_timeline'||action==='sketch_import_animation'||action.startsWith('sketch_component_')||action==='sketch_book_create'&&parameters.pageCount>10000||action==='sketch_book_render'&&(parameters.pageCount>1200||parameters.pageStart>=10000)||action==='sketch_book_open_page'&&parameters.page>=10000||action==='sketch_book_get'&&parameters.pageOffset>10000||action==='sketch_book_edit'&&(parameters.cues!==undefined||(parameters.exposures||[]).some(e=>e.page>=10000));
 if(longSequence&&!atLeast(device?.appVersion,'4.7.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: feature-length Sketch Studio sequences, animation imports and reusable components require VideoStudio 4.7.0 or newer');
 const shaped=object=>object?.shapeKeys!==undefined;
 const shapes=action==='sketch_get'&&parameters.shapeFrame!==undefined||action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.type==='set_shape_key'||shaped(op.object)||shaped(op.patch)||(op.layer?.objects||[]).some(shaped));
 if(shapes&&!atLeast(device?.appVersion,'4.6.1'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: editable path shape animation requires VideoStudio 4.6.1 or newer');
 const advanced=key=>key?.curve!==undefined||['ease_in','ease_out','ease_in_out','bezier'].includes(key?.interpolation);
 const drawingKey=key=>advanced(key)||key?.interpolation==='smooth';
 const artwork=object=>object?.gradient?.type==='radial'||(object?.keys||[]).some(drawingKey);
 const quality=action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.type==='set_keyframe'&&drawingKey(op)||op.type==='update_object'&&op.patch?.gradient===null||artwork(op.object)||artwork(op.patch)||(op.layer?.keys||[]).some(drawingKey)||(op.layer?.objects||[]).some(artwork))
  ||action==='sketch_animation_save'&&((parameters.program?.camera||[]).some(advanced)||(parameters.program?.rigs||[]).some(rig=>(rig.poses||[]).some(advanced)));
 if(quality&&!atLeast(device?.appVersion,'4.6.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: radial material shading and editable animation timing curves require VideoStudio 4.6.0 or newer');
 if(['apply_edit_plan','autonomous_edit'].includes(action)&&(parameters.expectedUpdatedAt!==undefined||(parameters.clips||[]).some(clip=>clip?.clipId!==undefined||clip?.id!==undefined))&&!atLeast(device?.appVersion,'4.5.12'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: revision-bound timeline plans and stable clip IDs require VideoStudio 4.5.12 or newer');
 if(action.startsWith('sketch_animation_')&&!atLeast(device?.appVersion,'4.4.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: prepared-sketch character animation requires Video studio 4.4.0 or newer');
 if(['sketch_import_drawing_asset','get_native_result_page','get_native_delivery_status','compact_native_delivery'].includes(action)&&!atLeast(device?.appVersion,'4.3.1'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: registered drawing imports and durable native result delivery require Video studio 4.3.1 or newer');
 const needs430=action==='cinematic_3d_edit_program'||action==='precision_edit'&&(parameters.operations||[]).some(op=>op.keyframeEdits!==undefined||op.set?.audioPan!==undefined||op.set?.audioMono!==undefined)
  ||action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.type==='symmetry_stroke')
  ||action==='set_studio_layers'&&(parameters.studioLayers||parameters.layers||[]).some(layer=>layer?.type==='title'&&['fontSize','fontFamily','fontStyle','textAlign','backgroundColor'].some(key=>layer[key]!==undefined));
 if(needs430&&!atLeast(device?.appVersion,'4.3.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: camera paths, incremental keyframes, symmetry, stereo tools and styled captions require Video studio 4.3.0 or newer');
 if((action==='import_mailbox_media'||action==='precision_edit'&&(parameters.operations||[]).some(op=>op.set?.audioFadeInMs!==undefined||op.set?.audioFadeOutMs!==undefined))&&!atLeast(device?.appVersion,'4.2.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: encrypted media imports and clip audio fades require Video studio 4.2.0 or newer');
 if(action==='sketch_live_begin'&&parameters.drawingMethod!==undefined&&!atLeast(device?.appVersion,'4.1.1'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: declared live drawing methods require Video studio 4.1.1 or newer');
 const enlarged=value=>(value?.width??720)>1280||(value?.height??1280)>1280||(value?.width??720)*(value?.height??1280)>720*1280;
 const enlargedPatch=value=>value.width>1280||value.height>1280||value.width!==undefined&&value.height!==undefined&&value.width*value.height>720*1280;
 const needs410=['sketch_paint_journal','sketch_sample_color','sketch_archive','sketch_restore_archive'].includes(action)||['sketch_create','sketch_book_create'].includes(action)&&enlarged(parameters)||action==='sketch_edit'&&(parameters.operations||[]).some(op=>['add_paint_cel','paint_stroke'].includes(op.type)||op.type==='set_canvas'&&enlargedPatch(op));
 if(needs410&&!atLeast(device?.appVersion,'4.1.0'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: native paint, editable archives and larger canvases require Video studio 4.1.0 or newer');
 const enhancedReference=object=>object?.kind==='image'&&(object.reference?.width>1280||object.reference?.height>1280||['sourceWidth','sourceHeight','sourceOrientation','sourceSha256'].some(key=>object.reference?.[key]!==undefined));
 const needs401=action==='sketch_import_reference'&&parameters.guideVisible!==undefined||action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.type==='set_reference_visibility'||enhancedReference(op.object)||(op.layer?.objects||[]).some(enhancedReference));
 if(needs401&&!atLeast(device?.appVersion,'4.0.1'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: photo references and guide visibility require Video studio 4.0.1 or newer');
 const needs396=action==='precision_edit'&&(parameters.operations||[]).some(op=>op.set?.crop!==undefined)||action==='sketch_inspect'||(action==='sketch_import_frame_manifest'||action==='sketch_import_drawing_manifest')&&parameters.manifestName?.endsWith('.gz')||action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.object?.kind==='text'||(op.layer?.objects||[]).some(object=>object.kind==='text')||op.patch?.text!==undefined||op.patch?.fontSize!==undefined||op.patch?.fontFamily!==undefined||op.patch?.fontStyle!==undefined||op.patch?.textAlign!==undefined||op.patch?.letterSpacing!==undefined);
 if(needs396&&!atLeast(device?.appVersion,'3.9.6'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: new native sketch tools and source crop require VideoStudio 3.9.6 or newer');
 const needs395=action==='sketch_live_events'&&(parameters.operationOffset!==undefined||parameters.operationLimit!==undefined||parameters.eventSequence!==undefined)||action==='sketch_get'&&(parameters.commandOffset!==undefined||parameters.commandLimit!==undefined)||action==='sketch_import_drawing_manifest'||action==='sketch_import_frame_manifest'&&alias||action==='sketch_edit'&&(parameters.operations||[]).some(op=>op.type==='trace_reference'&&(op.mode||op.maxDimension||op.colourLevels));
 if(needs395&&!atLeast(device?.appVersion,'3.9.5'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: precision sketch queries, source-linked imports and colour tracing require VideoStudio 3.9.5 or newer');
 if(action==='sketch_import_frame_manifest'&&!atLeast(device?.appVersion,'3.9.4'))throw new Error('NATIVE_APP_UPGRADE_REQUIRED: frame manifest imports require VideoStudio 3.9.4 or newer');
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

function atLeast(value,minimum){const match=String(value||'').match(/^(\d+)\.(\d+)\.(\d+)/);if(!match)return false;const v=match.slice(1).map(Number),m=minimum.split('.').map(Number);for(let n=0;n<3;n++){if(v[n]!==m[n])return v[n]>m[n];}return true;}
