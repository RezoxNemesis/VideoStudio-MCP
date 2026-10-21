import test from "node:test";
import assert from "node:assert/strict";
import {assertRenderAction,assertRenderRequestId,RENDER_NATIVE_ACTIONS,renderCommandStatus} from "../src/render-v3-actions.js";
test("all supported Render actions are typed and available as capabilities",()=>{
  assert.equal(Object.keys(RENDER_NATIVE_ACTIONS).length,17);
  for(const action of Object.keys(RENDER_NATIVE_ACTIONS)) {
    const args=action==="projects.inspect"?{project_id:"a".repeat(64)}:
      action==="blender.scene.set_frame"?{frame:1}:
      action==="blender.object.set_transform"?{name:"Cube",location:[0,0,0]}:
      action==="blender.object.keyframe"?{name:"Cube",data_path:"location",frame:3,value:[1,2,3]}:
      action==="blender.object.modifier_add"?{name:"Cube",modifier_type:"BEVEL"}:
      action==="blender.material.assign"?{name:"Cube"}:
      action==="blender.scene.render_settings"?{resolution_x:640}:{};
    assert.deepEqual(assertRenderAction(action,args),{action,args});
  }
});
test("worker refuses shell/gallery/unknown operations and arbitrary arguments",()=>{
  for(const action of ["gallery.list","python.eval","shell.exec","blender.execute_python","render"]){
    assert.throws(()=>assertRenderAction(action,{}),/RENDER_ACTION_UNAVAILABLE/);
  }
  assert.throws(()=>assertRenderAction("blender.scene.summary",{code:"python"}),/RENDER_UNKNOWN_ARGUMENT/);
  assert.throws(()=>assertRenderAction("blender.scene.summary",{__proto__:null}),/RENDER/); // cannot be unknown command object
  assert.throws(()=>assertRenderAction("projects.inspect",{project_id:"../../images"}),/RENDER_INVALID_PROJECT_ID/);
});
test("invalid numeric fields, frame, names and keyframes are rejected",()=>{
  for(const x of [NaN,Infinity,-Infinity,"1",true,[]])
    assert.throws(()=>assertRenderAction("blender.scene.set_frame",{frame:x}),/RENDER_INVALID_FRAME/);
  assert.throws(()=>assertRenderAction("blender.object.add_cube",{location:[0,0,Infinity]}),/RENDER_INVALID_LOCATION/);
  assert.throws(()=>assertRenderAction("blender.object.set_transform",{name:"Cube"}),/RENDER_INVALID_TRANSFORM/);
  assert.throws(()=>assertRenderAction("blender.object.keyframe",{name:"Cube",frame:25,data_path:"__class__",value:[0,0,0]}),/RENDER_INVALID_KEYFRAME/);
  assert.throws(()=>assertRenderAction("projects.list",{limit:1000}),/RENDER_INVALID_LIMIT/);
});
test("request ID controls dedupe and receipt state is distinct from execution",()=>{
  assert.equal(assertRenderRequestId("vs-render-job-1"),"vs-render-job-1");
  for(const id of ["","../../","A".repeat(129)])assert.throws(()=>assertRenderRequestId(id));
  assert.equal(renderCommandStatus("waiting_native"),"pending");
  assert.equal(renderCommandStatus("claimed"),"pending");
  assert.equal(renderCommandStatus("completed"),"succeeded");
});


test("real Blender modeling, light, material, and rendering actions require typed arguments",()=>{
  for(const [action,args] of [
    ["blender.object.add_primitive",{kind:"UV_SPHERE",name:"Sphere",size:1.4}],
    ["blender.object.add_camera",{name:"Camera",location:[0,-6,3]}],
    ["blender.object.add_light",{name:"Light",light_type:"AREA",energy:800,color:[1,.8,.6]}],
    ["blender.object.modifier_add",{name:"Sphere",modifier_type:"SUBSURF",levels:2}],
    ["blender.material.assign",{name:"Sphere",material_name:"Copper",color:[.8,.4,.2],metallic:.6}],
    ["blender.scene.render_settings",{resolution_x:1920,resolution_y:1080,engine:"CYCLES",samples:32}],
    ["blender.scene.render_still",{}],
  ]){
    assert.deepEqual(assertRenderAction(action,args),{action,args});
  }
  assert.throws(()=>assertRenderAction("blender.object.add_light",{light_type:"AREA",color:[0,0,9]}),/RENDER_INVALID_COLOR/);
  assert.throws(()=>assertRenderAction("blender.scene.render_settings",{resolution_x:0}),/RENDER_INVALID_RESOLUTION/);
  assert.throws(()=>assertRenderAction("blender.scene.render_settings",{}),/RENDER_SETTINGS_REQUIRED/);
  assert.throws(()=>assertRenderAction("blender.scene.render_settings",{denoising:"false"}),/RENDER_INVALID_DENOISING/);
  assert.throws(()=>assertRenderAction("blender.object.modifier_add",{name:"Sphere",modifier_type:"RUN_SCRIPT"}),/RENDER_INVALID_MODIFIER/);
  assert.throws(()=>assertRenderAction("blender.scene.render_still",{filepath:"/sdcard/secret"}),/RENDER_UNKNOWN_ARGUMENT/);
});
