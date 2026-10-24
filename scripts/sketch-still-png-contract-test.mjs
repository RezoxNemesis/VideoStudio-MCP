import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';
import {nativeStoryboardParameters,requireStoryboardNativeVersion} from '../src/storyboard-contract.js';

const png=frame=>({sketchId:'drawing',operationId:'still',expectedRevision:3,format:'png',frame});
const video=()=>({sketchId:'drawing',operationId:'video',expectedRevision:3,aspect:'original',quality:'720p'});

test('existing sketch render and alias accept explicit native PNG frame without injected video defaults',()=>{
 for(const action of ['sketch_render','studio_sketch_render'])for(const frame of [0,1,1199]){
  const request=png(frame);assert.deepEqual(contract.nativeStudioParameters(action,request),request);assert.deepEqual(nativeStoryboardParameters(action,request),request);
 }
});

test('PNG format and required source-frame controls are strict and reject mixed video/book options',()=>{
 assert.doesNotThrow(()=>contract.nativeStudioParameters('sketch_render',png(0)));
 for(const patch of [{format:'PNG'},{format:'mp4'},{format:null},{format:true},{format:1},{frame:-1},{frame:1200},{frame:.1},{frame:'1'},{frame:null},{frame:true},{frame:NaN},{frame:Infinity},{aspect:'original'},{quality:'720p'},{bookId:'book'},{pageStart:0},{pageCount:1},{crop:{x:0,y:0,width:1,height:1}},{projectId:'active'},{unknown:true}])assert.throws(()=>contract.nativeStudioParameters('sketch_render',{...png(0),...patch}));
 const noFrame=png(0);delete noFrame.frame;assert.throws(()=>contract.nativeStudioParameters('sketch_render',noFrame));
 const noFormat=png(0);delete noFormat.format;assert.throws(()=>contract.nativeStudioParameters('sketch_render',noFormat));
 assert.throws(()=>contract.nativeStudioParameters('sketch_book_render',{bookId:'book',operationId:'still',expectedRevision:1,format:'png',frame:0}));
});

test('every explicit still-export field requires4.10.22 through both supported Node routes',()=>{
 for(const action of ['sketch_render','studio_sketch_render'])for(const parameters of [png(0),{...video(),format:'png'},{...video(),frame:0},{...video(),format:null}]){
  for(const appVersion of [undefined,'garbage','3.9.0','4.10.20','4.10.21']){
   assert.throws(()=>contract.requireStudioVersion({appVersion},action,parameters),/4\.10\.22/);
   assert.throws(()=>requireStoryboardNativeVersion({appVersion},action,parameters),/4\.10\.22/);
  }
  for(const appVersion of ['4.10.22','4.10.23','5.0.0']){
   assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,parameters));assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion},action,parameters));
  }
 }
});

test('omitted PNG fields preserve original MP4 normalized bytes and old feature/archive floors',()=>{
 for(const action of ['sketch_render','studio_sketch_render']){
  const request=video(),normalized=contract.nativeStudioParameters(action,request);assert.deepEqual(normalized,request);
  assert.equal(createHash('sha256').update(JSON.stringify(normalized)).digest('hex'),'9b125a716d50ef11cd8f590ca29b744899c114134f1cd9865e1ec217141ab28e');
  assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},action,request));assert.doesNotThrow(()=>requireStoryboardNativeVersion({appVersion:'3.9.0'},action,request));
 }
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{}));
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,76);assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);
 for(const action of ['sketch_render_png','sketch_export_frame','sketch_still_export'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
 // Do not reinterpret a saved opaque archive or unrelated metadata as still export.
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'3.9.0'},'sketch_render',{...video(),unrelated:{format:'png',frame:0}}));
});

test('still PNG has exact native and Node capability metadata with honest source/asset scope',()=>{
 const cap=contract.SKETCH_STILL_EXPORT_CAPABILITIES;assert.ok(cap,'PNG export has a matching native/Node capability');assert.equal(cap.minimumNativeVersion,'4.10.22');assert.equal(cap.action,'sketch_render');assert.equal(cap.format,'png');assert.equal(cap.frameField,'frame');assert.equal(cap.preservesAlpha,true);assert.equal(cap.outputAssetCount,1);assert.equal(cap.outputClipCount,0);assert.equal(cap.newCanonicalAction,false);
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchStillExport.java',import.meta.url),'utf8');const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal);assert.deepEqual(JSON.parse(JSON.parse(literal[1])),cap);
 const nativeCaps=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');assert.match(nativeCaps,/\.put\("stillPngExport",\s*SketchStillExport\.capabilities\(\)\)/);
});

test('ordinary ChatGPT sees native still export, source revision and normal independent project import guidance',()=>{
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');assert.match(worker,/sketchStillExport\s*:\s*isV3[^\n]*SKETCH_STILL_EXPORT_CAPABILITIES/);assert.match(worker,/action==='sketch_render'/);assert.match(worker,/native4\.10\.22/i);assert.match(worker,/format.*png/);assert.match(worker,/project_asset_info/);assert.match(worker,/project_import_asset/);assert.match(worker,/no clips/i);assert.match(worker,/full.*(?:native|document).*PNG/i);
});
