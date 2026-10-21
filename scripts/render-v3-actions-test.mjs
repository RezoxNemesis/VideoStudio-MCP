import test from "node:test";
import assert from "node:assert/strict";
import {assertRenderAction,assertRenderRequestId,RENDER_NATIVE_ACTIONS,renderCommandStatus} from "../src/render-v3-actions.js";
test("all supported Render actions are typed and available as capabilities",()=>{
  assert.equal(Object.keys(RENDER_NATIVE_ACTIONS).length,10);
  for(const action of Object.keys(RENDER_NATIVE_ACTIONS)) {
    const args=action==="projects.inspect"?{project_id:"a".repeat(64)}:
      action==="blender.scene.set_frame"?{frame:1}:
      action==="blender.object.set_transform"?{name:"Cube",location:[0,0,0]}:
      action==="blender.object.keyframe"?{name:"Cube",data_path:"location",frame:3,value:[1,2,3]}:{};
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
