/* Additive Render channel for the EXISTING VideoStudio MCP v3 Worker.
 * Authorizes only genuine Render-native typed actions. No Gallery, remote
 * Python source, shell, arbitrary Android intents, cross-project write, or
 * VideoStudio project modifications. Source of truth remains Render Android.
 */
export const RENDER_NATIVE_ACTIONS = Object.freeze({
  "engine.status": [],
  "gpu.info": [],
  "projects.list": ["offset", "limit"],
  "projects.inspect": ["project_id", "expected_sha256"],
  "blender.scene.summary": [],
  "blender.object.add_cube": ["name", "size", "location"],
  "blender.scene.set_frame": ["frame"],
  "blender.object.set_transform": ["name", "location", "rotation_euler", "scale"],
  "blender.object.keyframe": ["name", "frame", "data_path", "value"],
  "blender.project.save_copy": [],
  "blender.object.add_primitive": ["kind", "name", "size", "location"],
  "blender.object.add_camera": ["name", "location", "rotation_euler"],
  "blender.object.add_light": ["name", "light_type", "location", "color", "energy"],
  "blender.object.modifier_add": ["name", "modifier_type", "levels", "width"],
  "blender.material.assign": ["name", "material_name", "color", "metallic", "roughness"],
  "blender.scene.render_settings": ["resolution_x", "resolution_y", "samples", "engine", "denoising"],
  "blender.scene.render_still": [],
});
const o = value => value && typeof value==="object" && !Array.isArray(value) && Object.getPrototypeOf(value)===Object.prototype;
const integer = (v,min,max) => Number.isInteger(v) && v>=min && v<=max;
const scalar = (v,min,max) => typeof v==="number" && Number.isFinite(v) && v>=min && v<=max;
const triple = (v,min,max) => Array.isArray(v) && v.length===3 && v.every(x=>scalar(x,min,max));
const validName = n => typeof n==="string" && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(n);
const hash = n => typeof n==="string" && /^[a-f0-9]{64}$/.test(n);

export function assertRenderAction(action,args) {
  if(typeof action!=="string" || !Object.hasOwn(RENDER_NATIVE_ACTIONS,action) ||
     action.includes("gallery") || action.includes("shell")) throw Error("RENDER_ACTION_UNAVAILABLE");
  if(!o(args)) throw Error("RENDER_ARGUMENTS_INVALID");
  let encoded;try{encoded=JSON.stringify(args)}catch{throw Error("RENDER_ARGUMENTS_INVALID")}
  if(encoded.length>32768) throw Error("RENDER_ARGUMENTS_TOO_LARGE");
  const allowed=RENDER_NATIVE_ACTIONS[action];
  for(const key of Object.keys(args)) {
    if(!allowed.includes(key) || key==="__proto__" || key==="constructor" || key==="prototype")
      throw Error("RENDER_UNKNOWN_ARGUMENT");
  }
  if(action==="projects.list"){
    if(Object.hasOwn(args,"offset")&&!integer(args.offset,0,1e7))throw Error("RENDER_INVALID_OFFSET");
    if(Object.hasOwn(args,"limit")&&!integer(args.limit,1,200))throw Error("RENDER_INVALID_LIMIT");
  }
  if(action==="projects.inspect"){
    if(!hash(args.project_id) || (Object.hasOwn(args,"expected_sha256")&&!hash(args.expected_sha256)))
      throw Error("RENDER_INVALID_PROJECT_ID");
  }
  if(action==="blender.object.add_cube"){
    if(Object.hasOwn(args,"name")&&!validName(args.name))throw Error("RENDER_INVALID_OBJECT_NAME");
    if(Object.hasOwn(args,"size")&&!scalar(args.size,.001,10000))throw Error("RENDER_INVALID_SIZE");
    if(Object.hasOwn(args,"location")&&!triple(args.location,-100000,100000))throw Error("RENDER_INVALID_LOCATION");
  }
  if(action==="blender.scene.set_frame" && !integer(args.frame,1,100000))
    throw Error("RENDER_INVALID_FRAME");
  if(action==="blender.object.set_transform"){
    if(!validName(args.name) || !["location","rotation_euler","scale"].some(k=>Object.hasOwn(args,k)))throw Error("RENDER_INVALID_TRANSFORM");
    for(const [key,min,max] of [["location",-100000,100000],["rotation_euler",-1000,1000],["scale",.001,10000]]){
      if(Object.hasOwn(args,key)&&!triple(args[key],min,max))throw Error("RENDER_INVALID_TRANSFORM");
    }
  }
  if(action==="blender.object.add_primitive"){
    if(Object.hasOwn(args,"kind")&&!["UV_SPHERE","ICO_SPHERE","CYLINDER","CONE","PLANE","TORUS"].includes(args.kind))
      throw Error("RENDER_INVALID_PRIMITIVE");
    if(Object.hasOwn(args,"name")&&!validName(args.name))throw Error("RENDER_INVALID_OBJECT_NAME");
    if(Object.hasOwn(args,"size")&&!scalar(args.size,.001,10000))throw Error("RENDER_INVALID_SIZE");
    if(Object.hasOwn(args,"location")&&!triple(args.location,-100000,100000))throw Error("RENDER_INVALID_LOCATION");
  }
  if(action==="blender.object.add_camera"){
    if(Object.hasOwn(args,"name")&&!validName(args.name))throw Error("RENDER_INVALID_OBJECT_NAME");
    if(Object.hasOwn(args,"location")&&!triple(args.location,-100000,100000))throw Error("RENDER_INVALID_LOCATION");
    if(Object.hasOwn(args,"rotation_euler")&&!triple(args.rotation_euler,-1000,1000))throw Error("RENDER_INVALID_ROTATION");
  }
  if(action==="blender.object.add_light"){
    if(Object.hasOwn(args,"name")&&!validName(args.name))throw Error("RENDER_INVALID_OBJECT_NAME");
    if(Object.hasOwn(args,"light_type")&&!["POINT","SUN","SPOT","AREA"].includes(args.light_type))
      throw Error("RENDER_INVALID_LIGHT_TYPE");
    if(Object.hasOwn(args,"location")&&!triple(args.location,-100000,100000))throw Error("RENDER_INVALID_LOCATION");
    if(Object.hasOwn(args,"color")&&!triple(args.color,0,1))throw Error("RENDER_INVALID_COLOR");
    if(Object.hasOwn(args,"energy")&&!scalar(args.energy,0,10000000))throw Error("RENDER_INVALID_LIGHT_ENERGY");
  }
  if(action==="blender.object.modifier_add"){
    if(!validName(args.name)||!["SUBSURF","BEVEL","SOLIDIFY","MIRROR"].includes(args.modifier_type))
      throw Error("RENDER_INVALID_MODIFIER");
    if(Object.hasOwn(args,"levels")&&!integer(args.levels,0,6))throw Error("RENDER_INVALID_LEVELS");
    if(Object.hasOwn(args,"width")&&!scalar(args.width,0,1000))throw Error("RENDER_INVALID_WIDTH");
  }
  if(action==="blender.material.assign"){
    if(!validName(args.name))throw Error("RENDER_INVALID_OBJECT_NAME");
    if(Object.hasOwn(args,"material_name")&&!validName(args.material_name))throw Error("RENDER_INVALID_MATERIAL_NAME");
    if(Object.hasOwn(args,"color")&&!triple(args.color,0,1))throw Error("RENDER_INVALID_COLOR");
    if(Object.hasOwn(args,"metallic")&&!scalar(args.metallic,0,1))throw Error("RENDER_INVALID_METALLIC");
    if(Object.hasOwn(args,"roughness")&&!scalar(args.roughness,0,1))throw Error("RENDER_INVALID_ROUGHNESS");
  }
  if(action==="blender.scene.render_settings"){
    if(Object.keys(args).length===0)throw Error("RENDER_SETTINGS_REQUIRED");
    for(const key of ["resolution_x","resolution_y"])
      if(Object.hasOwn(args,key)&&!integer(args[key],16,16384))throw Error("RENDER_INVALID_RESOLUTION");
    if(Object.hasOwn(args,"samples")&&!integer(args.samples,1,4096))throw Error("RENDER_INVALID_SAMPLES");
    if(Object.hasOwn(args,"denoising")&&typeof args.denoising!=="boolean")throw Error("RENDER_INVALID_DENOISING");
    if(Object.hasOwn(args,"engine")&&!["CYCLES","BLENDER_EEVEE","BLENDER_EEVEE_NEXT","BLENDER_WORKBENCH"].includes(args.engine))
      throw Error("RENDER_INVALID_ENGINE");
  }
  if(action==="blender.object.keyframe"){
    if(!validName(args.name) || !integer(args.frame,1,100000) || !["location","rotation_euler","scale"].includes(args.data_path))
      throw Error("RENDER_INVALID_KEYFRAME");
    const range=args.data_path==="scale"?[.001,10000]:args.data_path==="rotation_euler"?[-1000,1000]:[-100000,100000];
    if(!triple(args.value,...range))throw Error("RENDER_INVALID_KEYFRAME");
  }
  return {action,args};
}
export function assertRenderRequestId(id) {
  if(typeof id!=="string" || !/^[A-Za-z0-9._-]{1,128}$/.test(id))throw Error("RENDER_INVALID_REQUEST_ID");
  return id;
}
export function renderCommandStatus(state){
  return ["queued","waiting_native","claimed","cancel_requested"].includes(state)?"pending":
    ["completed","succeeded"].includes(state)?"succeeded":
    ["cancelled","failed","recovery_required"].includes(state)?state:"unknown";
}
