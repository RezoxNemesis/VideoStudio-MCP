// Additive native application-v3 contracts. No browser/provider execution fallback.
export const STORYBOARD_NATIVE_ACTIONS = new Set([
  'storyboard_artwork_preflight','storyboard_series_plan','storyboard_series_audit',
  'animate_storyboard_series','animate_storyboard_artwork','save_source_rig_profile',
  'list_source_rig_profiles','save_storyboard_program','get_storyboard_program'
]);
export const CINEMATIC_BEATS = ['STANDOFF','BLACK_STEP','WHITE_ADVANCE','CHARGE','DUAL_CHARGE',
  'PRE_IMPACT','CLASH','BLADE_LOCK','FOLLOW_THROUGH','WHITE_KNOCKBACK','BLACK_LANDING','AFTERMATH_RECOVERY'];
export function requireStoryboardNativeVersion(device, action) {
  if (!STORYBOARD_NATIVE_ACTIONS.has(action)) return;
  const match = String(device?.appVersion || '').match(/^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
  if (!match || Number(match[1])*1000000+Number(match[2])*1000+Number(match[3]) < 3007002)
    throw new Error('NATIVE_APP_UPGRADE_REQUIRED: this source-bound storyboard contract requires VideoStudio 3.7.2 or newer; native application protocol v3 remains unchanged');
}
export function nativeStoryboardParameters(action, parameters={}) {
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) throw new Error('Native parameters must be an object');
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
    if(new TextEncoder().encode(JSON.stringify(safe)).length>64*1024)throw new Error('Storyboard request exceeds its 64 KiB metadata budget');
    if(safe.assetIds&&safe.anchorGroups)throw new Error('Use either literal assetIds or explicit anchorGroups');
    if(safe.motionIntent!==undefined&&!['cinematic_action','source_deformation','environmental'].includes(safe.motionIntent))throw new Error('Unknown motionIntent');
  }
  return safe;
}
