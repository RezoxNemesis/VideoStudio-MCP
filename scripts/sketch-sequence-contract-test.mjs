import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeStudioParameters,requireStudioVersion} from '../src/studio-workbench-contract.js';

test('feature-length books, frame sheets and cues stay bounded and versioned',()=>{
 const create={bookId:'film',operationId:'create',pageCount:216000,fps:60};
 assert.deepEqual(nativeStudioParameters('sketch_book_create',create),create);
 const render={bookId:'film',expectedRevision:1,operationId:'hour',pageCount:216000};
 assert.deepEqual(nativeStudioParameters('sketch_book_render',render),render);
 const sheet={bookId:'film',expectedRevision:1,frameOffset:215999,frameLimit:1};
 assert.deepEqual(nativeStudioParameters('studio_sketch_book_timeline',sheet),sheet);
 const edit={bookId:'film',expectedRevision:1,operationId:'cue',cues:[{id:'clash',type:'contact',startFrame:215000,endFrame:215002,label:'Blade contact'}]};
 assert.deepEqual(nativeStudioParameters('sketch_book_edit',edit),edit);
 for(const [action,args] of [['sketch_book_create',create],['sketch_book_render',render],['sketch_book_timeline',sheet],['sketch_book_edit',edit]]){
  assert.throws(()=>requireStudioVersion({appVersion:'4.6.2'},action,args),/4\.7\.0/);
  assert.doesNotThrow(()=>requireStudioVersion({appVersion:'4.7.0'},action,args));
 }
 assert.throws(()=>nativeStudioParameters('sketch_book_create',{...create,pageCount:1000001}));
 assert.throws(()=>nativeStudioParameters('sketch_book_timeline',{...sheet,frameLimit:129}));
 assert.throws(()=>nativeStudioParameters('sketch_book_edit',{...edit,cues:Array(129).fill(edit.cues[0])}));
 assert.throws(()=>nativeStudioParameters('sketch_book_timeline',{...sheet,unknown:true}));
 assert.doesNotThrow(()=>requireStudioVersion({appVersion:'3.9.1'},'sketch_book_create',{bookId:'small',operationId:'x',pageCount:1000}));
});

test('native animation import and owned components use small source-bound requests',()=>{
 const imp={projectId:'source',sourceProjectUpdatedAt:10,assetId:'video',expectedSha256:'a'.repeat(64),bookId:'new_film',operationId:'import_1',fps:24,frameStart:100,frameCount:86400};
 assert.deepEqual(nativeStudioParameters('studio_sketch_import_animation',imp),imp);
 assert.throws(()=>nativeStudioParameters('sketch_import_animation',{...imp,url:'https://example.test/file.mp4'}));
 assert.throws(()=>nativeStudioParameters('sketch_import_animation',{...imp,frameCount:1000001}));
 assert.throws(()=>nativeStudioParameters('sketch_import_animation',{...imp,expectedSha256:'bad'}));
 const save={componentId:'eyes_angry',sketchId:'face',expectedRevision:3,operationId:'save',name:'Angry eyes',category:'expression',frameIndex:0,layerIds:['eyes']};
 assert.deepEqual(nativeStudioParameters('sketch_component_save',save),save);
 assert.deepEqual(nativeStudioParameters('studio_sketch_component_list',{category:'expression',limit:64}),{category:'expression',limit:64});
 assert.throws(()=>nativeStudioParameters('sketch_component_list',{limit:129}));
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.2'},'studio_sketch_import_animation',imp),/4\.7\.0/);
 assert.throws(()=>requireStudioVersion({appVersion:'4.6.2'},'sketch_component_save',save),/4\.7\.0/);
});
