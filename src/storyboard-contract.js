import { CINEMATIC_3D_ACTIONS, nativeCinematic3dParameters } from './cinematic3d-contract.js';
import { STUDIO_NATIVE_ACTIONS, STUDIO_ALIAS_ACTIONS, nativeStudioCanonicalAction, nativeStudioParameters, requireStudioVersion } from './studio-workbench-contract.js';
export { cinematic3dToolSchemas } from './cinematic3d-contract.js';
export { nativeStudioCanonicalAction };
// Additive native application-v3 contracts. No browser/provider execution fallback.
export const STORYBOARD_NATIVE_ACTIONS = new Set([
  ...CINEMATIC_3D_ACTIONS,...STUDIO_NATIVE_ACTIONS,...STUDIO_ALIAS_ACTIONS,'apply_tool','storyboard_artwork_preflight','storyboard_series_plan','storyboard_series_audit',
  'animate_storyboard_series','animate_storyboard_artwork','save_source_rig_profile',
  'list_source_rig_profiles','save_storyboard_program','get_storyboard_program',
  'inspect_source_artwork','preview_source_rig','save_source_rig_profiles','source_rig_status'
]);
const RIG_WORKBENCH_ACTIONS=new Set(['inspect_source_artwork','preview_source_rig','save_source_rig_profiles','source_rig_status']);
const WORKBENCH_FIELDS={
  inspect_source_artwork:['projectId','assetId','expectedSourceSha256','crop','maxDimension','rigIds'],
  preview_source_rig:['projectId','rigId','expectedRigSha256','expectedSourceSha256','targetJoints16','targetJoints','swordTip','maxDimension'],
  save_source_rig_profiles:['projectId','sourceProjectUpdatedAt','batchId','profiles'],
  source_rig_status:['projectId','assetIds','programId','expectedProgramSha256']
};
export const CINEMATIC_BEATS = ['STANDOFF','BLACK_STEP','WHITE_ADVANCE','CHARGE','DUAL_CHARGE',
  'PRE_IMPACT','CLASH','BLADE_LOCK','FOLLOW_THROUGH','WHITE_KNOCKBACK','BLACK_LANDING','AFTERMATH_RECOVERY'];
export function requireStoryboardNativeVersion(device, action, parameters={}) {
  requireStudioVersion(device,action,parameters);
  action=nativeStudioCanonicalAction(action);
  if (!STORYBOARD_NATIVE_ACTIONS.has(action)||action==='apply_tool') return;
  const minimum=CINEMATIC_3D_ACTIONS.has(action)?3008000:RIG_WORKBENCH_ACTIONS.has(action)?3007003:3007002;
  const name=minimum===3008000?'3.8.0':minimum===3007003?'3.7.3':'3.7.2';
  const match = String(device?.appVersion || '').match(/^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
  if (!match || Number(match[1])*1000000+Number(match[2])*1000+Number(match[3]) < minimum)
    throw new Error('NATIVE_APP_UPGRADE_REQUIRED: this source artwork contract requires VideoStudio '+name+' or newer; native application protocol v3 remains unchanged');
}
export function nativeStoryboardParameters(action, parameters={}) {
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) throw new Error('Native parameters must be an object');
  action=nativeStudioCanonicalAction(action);
  if(STUDIO_NATIVE_ACTIONS.has(action))return nativeStudioParameters(action,parameters);
  if(CINEMATIC_3D_ACTIONS.has(action))return nativeCinematic3dParameters(action,parameters);
  const stack=[{value:parameters,depth:0}], seen=new Set();let nodes=0;
  while(stack.length) {
    const {value,depth}=stack.pop();
    if(++nodes>32768||depth>64)throw new Error('Native metadata exceeds its structural budget');
    if(value===null||typeof value==='string'||typeof value==='boolean'||value===undefined)continue;
    if(typeof value==='number'){if(!Number.isFinite(value))throw new Error('Native metadata numbers must be finite');continue;}
    if(typeof value!=='object'||seen.has(value))throw new Error('Native metadata must be acyclic JSON');
    seen.add(value);
    for(const item of Object.values(value))stack.push({value:item,depth:depth+1});
  }
  const safe={};for(const [key,value] of Object.entries(parameters))if(!key.startsWith('_')&&value!==undefined)safe[key]=value;
  if(STORYBOARD_NATIVE_ACTIONS.has(action)) {
    if(Object.hasOwn(safe,'outputProjectId'))throw new Error('Storyboard outputs are fresh separate projects; caller outputProjectId is not accepted');
    const budget=action==='save_source_rig_profiles'?192:64;
    if(new TextEncoder().encode(JSON.stringify(safe)).length>budget*1024)throw new Error('Storyboard request exceeds its '+budget+' KiB metadata budget');
    if(safe.assetIds&&safe.anchorGroups)throw new Error('Use either literal assetIds or explicit anchorGroups');
    if(safe.motionIntent!==undefined&&!['cinematic_action','source_deformation','environmental'].includes(safe.motionIntent))throw new Error('Unknown motionIntent');
  }
  if(RIG_WORKBENCH_ACTIONS.has(action)){
    for(const key of Object.keys(safe))if(!WORKBENCH_FIELDS[action].includes(key))throw new Error('Unknown source artwork field; arbitrary URI access is unavailable');
    if(action==='inspect_source_artwork'&&safe.crop!==undefined){
      const crop=safe.crop;
      if(!crop||typeof crop!=='object'||Array.isArray(crop)||Object.keys(crop).some(k=>!['left','top','right','bottom'].includes(k))
          ||!['left','top','right','bottom'].every(k=>typeof crop[k]==='number'&&Number.isFinite(crop[k])&&crop[k]>=0&&crop[k]<=1)
          ||crop.left>=crop.right||crop.top>=crop.bottom)throw new Error('Invalid original-oriented crop');
    }
    if(action==='save_source_rig_profiles'&&(!Array.isArray(safe.profiles)||safe.profiles.length<1||safe.profiles.length>8))throw new Error('Source rig batch requires 1..8 profiles');
    if(action==='preview_source_rig'&&safe.targetJoints16&&safe.targetJoints)throw new Error('Use targetJoints16 or its targetJoints compatibility alias');
    if(action==='source_rig_status'&&safe.assetIds&&safe.programId)throw new Error('Use exact source occurrences or a saved program identity');
  }
  return safe;
}
