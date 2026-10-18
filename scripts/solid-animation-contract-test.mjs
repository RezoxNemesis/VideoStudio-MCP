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
