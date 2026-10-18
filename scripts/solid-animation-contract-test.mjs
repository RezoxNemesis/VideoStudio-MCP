import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';
const program={size:[140,140,140],camera:{focal:900,distance:1000,height:300,horizon:.2},keys:[{frame:0,x:0,elevation:0,depth:0,yaw:0},{frame:60,x:80,elevation:0,depth:150,yaw:360}]};
const request={sketchId:'original',operationId:'solid_1',expectedRevision:1,operations:[{type:'add_solid',layerId:'layer_1',solidId:'crate',program}]};
test('MCP accepts native solid construction with bounded authored poses',()=>{
 assert.deepEqual(nativeStudioParameters('studio_sketch_edit',request),request);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:{...program,url:'https://example.test/reference.mp4'}}]}));
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:{...program,keys:[program.keys[1],program.keys[0]]}}]}));
});
test('old APKs are rejected before receiving the new native construction operation',()=>{
 assert.throws(()=>requireStudioVersion({appVersion:'4.7.0'},'sketch_edit',request),/4\.7\.1/);
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',request));
});
test('depth timing can be authored independently of rotation timing',()=>{
 const p={...program,keys:[{...program.keys[0],depthInterpolation:'smooth'},program.keys[1]]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',{...request,operations:[{...request.operations[0],program:p}]}).operations[0].program,p);
});

test('native flight and impact controls are accepted only by compatible APKs',()=>{
 const p={...program,preserveVolume:true,keys:[{...program.keys[0],flightHeight:200,scaleInterpolation:'smooth'},program.keys[1]]};const r={...request,operations:[{...request.operations[0],program:p}]};
 assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r));
});
test('native sphere and distance-driven rolling are strictly validated and version gated',()=>{
 const p={...program,shape:'sphere',rollingRadius:80};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,shape:'external_mesh'}}]}));assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,rollingRadius:0}}]}));
});

test('ground contact accepts a native cylinder and rejects conflicting flight',()=>{
 const p={...program,shape:'cylinder',groundLock:true,rollingRadius:70};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);
 assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,keys:[{...p.keys[0],flightHeight:10},p.keys[1]]}}]}));
});

test('cone and native world lighting are exposed with the same bounds',()=>{
 const p={...program,shape:'cone',lighting:true};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.1'},'sketch_edit',r),/4\.7\.2/);
});

test('rocking constraints and held-cel optimization match native policy',()=>{
 const p={...program,shape:'pyramid',groundLock:true,rockingPivot:true,holdLast:true,mergeHolds:true};const r={...request,operations:[{...request.operations[0],program:p}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);for(const patch of [{groundLock:false},{rollingRadius:70},{shape:'sphere'}])assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...p,...patch}}]}));
});

test('two-body native scenes use strict app settings and a 4.7.3 gate',()=>{
 const r={...request,operations:[{type:'add_solid_collision',layerId:'layer_1',sceneId:'impact',settings:{ballMass:1,boxMass:3,restitution:.7,friction:100}}]};
 assert.deepEqual(nativeStudioParameters('studio_sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r),/4\.7\.3/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.3'},'sketch_edit',r));
 for(const settings of [{url:'https://example.test/reference.mp4'},{boxMass:0},{restitution:1.1},{boxSize:[10,20,30]},{speed:0}])assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],settings}]}));
});

test('fine mesh quality is bounded and rejects APKs without its native implementation',()=>{
 const r={...request,operations:[{...request.operations[0],program:{...program,shape:'sphere',meshDetail:'fine'}}]};assert.deepEqual(nativeStudioParameters('sketch_edit',r),r);assert.throws(()=>requireStudioVersion({appVersion:'4.7.2'},'sketch_edit',r),/4\.7\.3/);assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.3'},'sketch_edit',r));assert.throws(()=>nativeStudioParameters('sketch_edit',{...r,operations:[{...r.operations[0],program:{...r.operations[0].program,meshDetail:'unbounded'}}]}));
});
