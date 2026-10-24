import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as contract from '../src/studio-workbench-contract.js';

const video=(motionKeyframes=undefined)=>({id:'insert',type:'video',assetId:'owned-video',audioMode:'silent',startMs:1750,durationMs:2000,sourceInMs:1500,x:.25,y:.75,width:.5,height:.25,rotation:12,opacity:.75,...(motionKeyframes===undefined?{}:{motionKeyframes})});
const request=layer=>({projectId:'edit',expectedUpdatedAt:7,studioLayers:[layer]});
const parse=layer=>contract.nativeStudioParameters('set_studio_layers',request(layer));
const image=(id='panel')=>({id,type:'image',assetId:'owned-image',durationMs:2000,motionKeyframes:[]});
const title=(id='subtitle')=>({id,type:'title',text:'Why not you?',durationMs:2000});

test('silent VIDEO uses existing layer action and alias with exact source/timeline placement and no defaults',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers']){
  const p=request(video());assert.deepEqual(contract.nativeStudioParameters(action,p),p);
  for(const motionKeyframes of [[],[{timeMs:0},{timeMs:2000,x:2,y:-2,scaleX:.1,scaleY:8,rotate:360,opacity:0,easing:'ease_in_out'}]]){
   const p=request(video(motionKeyframes));assert.deepEqual(contract.nativeStudioParameters(action,p),p);
  }
 }
 const minimal={type:'video',assetId:'owned-video',audioMode:'silent'};assert.deepEqual(parse(minimal),request(minimal));
 assert.equal(Object.hasOwn(parse(minimal).studioLayers[0],'startMs'),false);assert.equal(Object.hasOwn(parse(minimal).studioLayers[0],'motionKeyframes'),false);
});

test('VIDEO type itself requires4.10.24 with and without motion across both action and array aliases',()=>{
 for(const action of ['set_studio_layers','studio_set_studio_layers'])for(const name of ['studioLayers','layers'])for(const lane of [video(),video([]),video([{timeMs:0}])]){
  const p={projectId:'edit',expectedUpdatedAt:7,[name]:[lane]};
  for(const appVersion of [undefined,'garbage','3.9.0','4.10.20','4.10.23'])assert.throws(()=>contract.requireStudioVersion({appVersion},action,p),/4\.10\.24/);
  for(const appVersion of ['4.10.24','4.10.25','5.0.0'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion},action,p));
 }
});

test('VIDEO requires literal silent mode and rejects audio caption crop speed URI and unknown controls',()=>{
 assert.doesNotThrow(()=>parse(video()));
 const absent=video();delete absent.audioMode;assert.throws(()=>parse(absent));
 for(const audioMode of [null,false,true,0,1,'','mute','source','SILENT'])assert.throws(()=>parse({...video(),audioMode}));
 for(const field of ['uri','sourceUri','videoUri','gain','text','position','color','fontSize','fontFamily','fontStyle','textAlign','backgroundColor','crop','fit','speed','unexpected'])
  assert.throws(()=>parse({...video(),[field]:field==='uri'?'file:///foreign.mp4':1}));
 for(const lane of [image(),title(),{type:'audio',assetId:'sound'}])assert.throws(()=>parse({...lane,audioMode:'silent'}));
 for(const assetId of ['',null,false,0])assert.throws(()=>parse({...video(),assetId}));
});

test('VIDEO timing and normalized placement reject coercion nonfinite values and out-of-range bounds',()=>{
 assert.doesNotThrow(()=>parse(video()));
 for(const field of ['startMs','durationMs','sourceInMs','x','y','width','height','rotation','opacity'])
  for(const value of [null,'1',true,NaN,Infinity])assert.throws(()=>parse({...video(),[field]:value}));
 for(const field of ['startMs','durationMs','sourceInMs'])assert.throws(()=>parse({...video(),[field]:1.5}));
 for(const patch of [{startMs:-1},{durationMs:99},{durationMs:86400001},{sourceInMs:-1},{x:1.01},{y:-.01},{width:.009},{height:2.01},{rotation:361},{opacity:1.01}])assert.throws(()=>parse({...video(),...patch}));
 const boundary={...video(),startMs:0,durationMs:100,sourceInMs:0,x:0,y:1,width:.01,height:2,rotation:-360,opacity:0};assert.deepEqual(parse(boundary),request(boundary));
});

test('VIDEO uses strict shared64-key local motion with detached recipes and default2500 duration',()=>{
 const keys=Array.from({length:64},(_,timeMs)=>({timeMs}));assert.deepEqual(parse(video(keys)),request(video(keys)));
 assert.throws(()=>parse(video([...keys,{timeMs:64}])));
 for(const motionKeyframes of [null,false,'[]',{},[null],[{}],[{timeMs:0},{timeMs:0}],[{timeMs:100},{timeMs:0}],[{timeMs:2001}],[{timeMs:0,anchorX:.5}],[{timeMs:0,opacity:1.01}]])assert.throws(()=>parse(video(motionKeyframes)));
 const implicit=video([{timeMs:2500}]);delete implicit.durationMs;assert.deepEqual(parse(implicit),request(implicit));
 const beyond=video([{timeMs:2501}]);delete beyond.durationMs;assert.throws(()=>parse(beyond));
 const raw=request(video([{timeMs:0},{timeMs:2000,x:.2}])),parsed=contract.nativeStudioParameters('set_studio_layers',raw);
 raw.studioLayers[0].motionKeyframes[1].x=1;assert.equal(parsed.studioLayers[0].motionKeyframes[1].x,.2);
});

test('VIDEO maximum2 coexists with IMAGE maximum8 total32 and unique IDs',()=>{
 const two=[video(),{...video(),id:'second'}];
 assert.equal(contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:two}).studioLayers.length,2);
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:[...two,{...video(),id:'third'}]}));
 const bounded=[...two,...Array.from({length:8},(_,i)=>image('image'+i)),...Array.from({length:22},(_,i)=>title('title'+i))];
 assert.equal(contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:bounded}).studioLayers.length,32);
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:[...bounded,title('overflow')]}));
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:[...two,...Array.from({length:9},(_,i)=>image('image'+i))]}));
 assert.throws(()=>contract.nativeStudioParameters('set_studio_layers',{...request(video()),studioLayers:[video(),video()]}));
});

test('old IMAGE/TITLE floors normalized bytes action inventory and opaque archives remain unchanged',()=>{
 assert.throws(()=>contract.requireStudioVersion({appVersion:'4.10.19'},'set_studio_layers',request(image())),/4\.10\.20/);
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.10.20'},'set_studio_layers',request(image())));
 const styled={id:'subtitle',type:'title',text:'Why not you?',startMs:1250,durationMs:1000,position:2,fontSize:42,fontFamily:'monospace',fontStyle:'bold_italic',textAlign:'center',color:0xffeeddcc,backgroundColor:0xff112233};
 assert.equal(createHash('sha256').update(JSON.stringify(parse(styled))).digest('hex'),'31b56428746f5448788c1c2df30c54ce3d031de8890d92b627359df97f499d18');
 assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.3.0'},'set_studio_layers',request(styled)));
 for(const action of ['set_studio_layers','studio_set_studio_layers'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.3.0'},action,{...request(styled),unrelated:{type:'video',audioMode:'silent'}}));
 for(const action of ['sketch_restore_archive','sketch_component_apply'])assert.doesNotThrow(()=>contract.requireStudioVersion({appVersion:'4.8.4'},action,{opaque:{type:'video',audioMode:'silent'}}));
 assert.equal(createHash('sha256').update([...contract.STUDIO_NATIVE_ACTIONS].sort().join('\n')).digest('hex'),'42e3cf3dac926f3c60760d13a15094297c0689b6073c6c837d76dff68b2f9f7f');
 assert.equal(contract.STUDIO_NATIVE_ACTIONS.size,Object.keys(contract.studioToolSchemas).length);
 for(const action of ['studio_video_overlay','add_video_overlay','set_video_layers'])assert.equal(contract.STUDIO_NATIVE_ACTIONS.has(action),false);
});

test('VIDEO native and Node capabilities match silent bounded native-source composition without unsupported claims',()=>{
 const cap=contract.EDITOR_EDITING_CAPABILITIES,video=cap.videoOverlay;
 assert.equal(cap.registryMinimumNativeVersion,'4.10.24');assert.equal(video.minimumNativeVersion,'4.10.24');
 assert.equal(cap.titleOverlayMotion.minimumNativeVersion,'4.10.23');assert.equal(cap.imageOverlayMotion.minimumNativeVersion,'4.10.20');
 assert.equal(video.action,'set_studio_layers');assert.equal(video.type,'video');assert.equal(video.maximumVideos,2);assert.equal(cap.layerTracks.maximumVideos,2);assert.equal(cap.layerTracks.maximumLayers,32);assert.equal(cap.layerTracks.maximumImages,8);
 assert.equal(video.audioMode,'silent');assert.equal(video.audioModeRequired,true);assert.equal(video.knownPositiveDurationRequired,true);assert.equal(video.seekableSourceRequired,true);
 assert.equal(video.maximumKeys,64);assert.equal(video.sourceSpeed,1);assert.equal(video.cropSupported,false);assert.equal(video.aspectFitSupported,false);
 assert.equal(video.layeredImageAnimationSupported,false);assert.equal(video.sourceFrameRatePreserved,true);
 assert.match(video.limitations,/no.*(?:audio|speed|crop)/i);assert.equal(cap.unavailable.includes('video_overlay_lanes'),false);
 const java=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/StudioEditingCapabilities.java',import.meta.url),'utf8');
 const literal=java.match(/CAPABILITY_JSON\s*=\s*("(?:\\.|[^"\\])*")/);assert.ok(literal);assert.deepEqual(JSON.parse(JSON.parse(literal[1])),cap);
});

test('ordinary ChatGPT existing tool describes VIDEO silence native trim and explicit version floor',()=>{
 const source=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');
 assert.match(source,/native4\.10\.24/i);assert.match(source,/audioMode.{0,20}silent/);assert.match(source,/maximum.{0,15}two VIDEO/i);
 assert.match(source,/sourceInMs/);assert.match(source,/seekab/i);assert.match(source,/layered.{0,40}articulated/i);
});
