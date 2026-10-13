// Inert full-scene data contracts. The phone executes every native action.
import { z } from 'zod';
export const CINEMATIC_3D_ACTIONS = new Set(['cinematic_3d_capabilities','cinematic_3d_install_builtin','cinematic_3d_import_bundle','cinematic_3d_save_program','cinematic_3d_get_program','cinematic_3d_list_programs','cinematic_3d_plan','cinematic_3d_render','cinematic_3d_export_request']);
export const CINEMATIC_3D_BEATS = ['STANDOFF','CHARGE','CLASH','BLADE_LOCK','FOLLOW_THROUGH','KNOCKBACK','LANDING','RECOVERY','AERIAL_FLIP','TUMBLE','MACRO_CLASH','IDLE','WALK','RUN','JUMP','DODGE','DUCK','PUNCH','KICK','DANCE','WAVE'];
const id=z.string().regex(/^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/),hash=z.string().regex(/^[a-f0-9]{64}$/),number=z.number().finite();
const vector=z.tuple([number,number,number]),quaternion=z.tuple([number,number,number,number]).refine(q=>q.some(v=>v!==0),'Quaternion cannot be zero');
const actorMaterial=z.object({color:z.tuple([number.min(0).max(1),number.min(0).max(1),number.min(0).max(1)]),metallic:number.min(0).max(1),roughness:number.min(.1).max(1)}).strict();
const actorModel=z.object({preset:z.enum(['athletic','slender','stocky','chibi','robot']),heightScale:number.min(.8).max(1.2).optional(),buildScale:number.min(.8).max(1.2).optional(),headScale:number.min(.8).max(1.2).optional(),material:actorMaterial.optional()}).strict();
const actorModels=z.object({black:actorModel,white:actorModel}).strict();
const motionStyle=z.object({preset:z.enum(['grounded','anime','martial','cartoony']),intensity:number.min(0).max(1).optional()}).strict();
const actor=z.object({root:vector,rotations:z.array(quaternion).length(16)}).strict();
const pose=z.object({phase:number.min(0).max(1),actors:z.object({black:actor,white:actor}).strict()}).strict();
const contact=z.object({actor:z.enum(['black','white']),foot:z.enum(['leading','trailing']),startPhase:number.min(0).max(1),endPhase:number.min(0).max(1),anchor:vector}).strict().refine(v=>v.startPhase<v.endPhase,'Contact interval must advance');
const cameraVector=z.tuple([number.min(-10000).max(10000),number.min(-10000).max(10000),number.min(-10000).max(10000)]);
const cameraKey=z.object({phase:number.min(0).max(1),position:cameraVector,target:cameraVector,up:cameraVector.optional()}).strict();
const camera=z.object({mode:z.enum(['wide','orbit','macro','top_down']),fovDegrees:number.min(35).max(100),shakeIntensity:number.min(0).max(1),keyframes:z.array(cameraKey).min(1).max(128).optional()}).strict().superRefine((value,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});let phase=-1;
 for(const key of value.keyframes||[]){
  if(key.phase<=phase)fail('Camera phases must increase');phase=key.phase;
  const direction=key.target.map((v,i)=>v-key.position[i]),up=key.up||(value.mode==='top_down'?[0,0,-1]:[0,1,0]);
  const length=Math.hypot(...direction),height=Math.hypot(...up),scale=Math.max(1,...key.position.map(Math.abs),...key.target.map(Math.abs));
  if(length<=1e-6*scale||height<=1e-9){fail('Camera basis is degenerate');continue;}
  const d=direction.map(v=>v/length),u=up.map(v=>v/height);
  if(Math.hypot(d[1]*u[2]-d[2]*u[1],d[2]*u[0]-d[0]*u[2],d[0]*u[1]-d[1]*u[0])<=1e-6)fail('Camera up cannot be parallel to its view direction');
 }
});
const shot=z.object({id,action:z.enum(CINEMATIC_3D_BEATS),durationFrames:z.number().int().min(8).max(600),hitStopFrames:z.union([z.literal(0),z.literal(2),z.literal(3),z.literal(4),z.literal(5)]),
 camera,
 vfx:z.object({sparkBurst:z.boolean(),slashTrail:z.boolean(),rubble:z.boolean()}).strict(),sourceAnchors:z.array(z.object({assetId:id,sha256:hash}).strict()).max(160).optional(),
 poseKeyframes:z.array(pose).min(1).max(128).optional(),footContacts:z.array(contact).max(64).optional(),motionStyle:motionStyle.optional()}).strict().superRefine((value,ctx)=>{
 const fail=message=>ctx.addIssue({code:z.ZodIssueCode.custom,message});
 if(value.hitStopFrames && !['CLASH','BLADE_LOCK','MACRO_CLASH'].includes(value.action))fail('Hit stop requires an impact action');
 if(value.poseKeyframes?.some((v,i,a)=>i>0&&v.phase<=a[i-1].phase))fail('Pose phases must increase');
 const ends=new Map();for(const c of value.footContacts||[]){const key=c.actor+':'+c.foot;if(c.startPhase<(ends.get(key)??-1))fail('Foot contacts must be ordered and nonoverlapping');ends.set(key,c.endPhase);}
});
export const cinematic3dTimelineSchema=z.object({schema:z.literal('videostudio.cinematic3d.timeline.v1'),name:z.string().min(1).max(256).refine(v=>v.trim().length>0),fps:z.union([z.literal(24),z.literal(30),z.literal(60)]),motionProvider:z.enum(['procedural_mannequin','authored_3d']),shots:z.array(shot).min(1).max(80),actors:actorModels.optional()}).strict().superRefine((value,ctx)=>{
 if(new Set(value.shots.map(v=>v.id)).size!==value.shots.length)ctx.addIssue({code:z.ZodIssueCode.custom,message:'Shot IDs must be unique'});
 if(value.shots.reduce((n,s)=>n+s.durationFrames,0)>12000)ctx.addIssue({code:z.ZodIssueCode.custom,message:'Timeline exceeds 12000 frames'});
});
const project={projectId:id},revision={...project,sourceProjectUpdatedAt:z.number().int().nonnegative()},program={...project,programId:id,expectedProgramSha256:hash.optional()};
export const cinematic3dToolSchemas={
 cinematic_3d_capabilities:{},cinematic_3d_install_builtin:{...revision,pack:z.enum(['starter','extended']).optional()},cinematic_3d_import_bundle:{...revision,bundleAssetId:id},
 cinematic_3d_save_program:{...revision,programId:id.optional(),expectedProgramSha256:z.union([hash,z.literal('')]).optional(),bundleId:id,timeline:cinematic3dTimelineSchema},
 cinematic_3d_get_program:{...program,editOnly:z.boolean().optional()},cinematic_3d_list_programs:project,cinematic_3d_plan:program,cinematic_3d_export_request:program,
 cinematic_3d_render:{...program,width:z.number().int().min(256).max(1280).optional(),height:z.number().int().min(256).max(1280).optional(),render:z.boolean().optional(),fileName:z.string().min(1).max(180).optional()}
};
export function nativeCinematic3dParameters(action,parameters){
 if(!CINEMATIC_3D_ACTIONS.has(action))throw new Error('Unknown cinematic 3D action');
 const safe={};for(const [key,value]of Object.entries(parameters))if(!key.startsWith('_')&&value!==undefined)safe[key]=value;
 if(Object.hasOwn(safe,'outputProjectId'))throw new Error('Cinematic outputs are fresh separate projects; outputProjectId is unavailable');
 const active=new Set();let nodes=0;
 const visit=(v,depth)=>{if(++nodes>65536||depth>16)throw new Error('Cinematic JSON exceeds structural budget');if(v===null||typeof v==='string'||typeof v==='boolean')return;if(typeof v==='number'&&Number.isFinite(v))return;if(typeof v!=='object'||active.has(v))throw new Error('Cinematic parameters must be acyclic finite JSON');active.add(v);for(const child of Object.values(v))visit(child,depth+1);active.delete(v);};visit(safe,0);
 if(new TextEncoder().encode(JSON.stringify(safe)).length>512*1024)throw new Error('Cinematic request exceeds 512 KiB metadata budget');
 const known=cinematic3dToolSchemas[action];for(const key of Object.keys(safe))if(!Object.hasOwn(known,key))throw new Error('Unknown cinematic field; arbitrary URI access is unavailable');
 return z.object(known).strict().parse(safe);
}
