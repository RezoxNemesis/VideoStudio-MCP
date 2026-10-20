import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters as parse,requireStudioVersion as gate} from '../src/studio-workbench-contract.js';
test('ordinary saved-pose reads and aliases admit strict revision-bound frame sampling',()=>{
 const args={animationId:'actor',expectedRevision:2,sampleFrame:48};for(const action of ['sketch_animation_get','studio_sketch_animation_get'])assert.deepEqual(parse(action,args),args);
 for(const sampleFrame of [-1,1200,1.5,'48',false,null])assert.throws(()=>parse('sketch_animation_get',{...args,sampleFrame}));assert.throws(()=>parse('sketch_animation_get',{animationId:'actor',sampleFrame:48}));for(const expectedRevision of [0,1.5,'2',false,null])assert.throws(()=>parse('sketch_animation_get',{...args,expectedRevision}));assert.throws(()=>parse('sketch_animation_get',{...args,unknown:true}));
});
test('explicit saved-pose options gate new native while old omitted reads retain their request',()=>{
 const old={animationId:'actor'};assert.deepEqual(parse('sketch_animation_get',old),old);for(const args of [{...old,expectedRevision:2},{...old,expectedRevision:2,sampleFrame:48}]){assert.throws(()=>gate({appVersion:'4.9.8'},'sketch_animation_get',args),/4\.9\.9/);assert.doesNotThrow(()=>gate({appVersion:'4.9.9'},'sketch_animation_get',args));}
 assert.doesNotThrow(()=>gate({appVersion:'4.4.0'},'sketch_animation_get',old));
});

test('existing native contact easing is admitted strictly with its historical native floor',()=>{
 const program={schema:'videostudio.sketch-animation.v1',frameCount:96,rigs:[{id:'actor',joints:[{id:'root',x:.4,y:.3},{id:'bend',parent:'root',x:.4,y:.4},{id:'pad',parent:'bend',x:.4,y:.5}],bindings:[],ik:[{hip:'root',knee:'bend',ankle:'pad',start:20,end:60,x:.45,y:.4,blendInFrames:12,blendOutFrames:12}]}]};
 const args={animationId:'contact',sketchId:'drawing',sourceRevision:1,expectedRevision:0,operationId:'save',program};
 for(const action of ['sketch_animation_save','studio_sketch_animation_save'])assert.deepEqual(parse(action,args),args);
 for(const key of ['blendInFrames','blendOutFrames'])for(const bad of [-1,13,1.5,'3',false,null]){const copy=structuredClone(args);copy.program.rigs[0].ik[0][key]=bad;assert.throws(()=>parse('sketch_animation_save',copy));}
 for(const patch of [{start:5},{end:90}]){const copy=structuredClone(args);Object.assign(copy.program.rigs[0].ik[0],patch);assert.throws(()=>parse('sketch_animation_save',copy));}
 assert.throws(()=>gate({appVersion:'4.5.0'},'sketch_animation_save',args),/4\.5\.1/);assert.doesNotThrow(()=>gate({appVersion:'4.5.1'},'sketch_animation_save',args));
 const old=structuredClone(args);delete old.program.rigs[0].ik[0].blendInFrames;delete old.program.rigs[0].ik[0].blendOutFrames;assert.doesNotThrow(()=>gate({appVersion:'4.4.0'},'sketch_animation_save',old));
});
