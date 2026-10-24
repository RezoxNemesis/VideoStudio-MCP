import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {EDITOR_EDITING_CAPABILITIES} from '../src/studio-workbench-contract.js';

const source=name=>fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/'+name+'.java',import.meta.url),'utf8');
function method(java,name){const start=java.indexOf('private JSONObject '+name+'()');assert.ok(start>=0,name+' exists');const end=java.indexOf('\n    private ',start+1);return java.slice(start,end<0?undefined:end);}

test('native cached/current state and MCP label legacy planning catalogues beside the same executable registry',async()=>{
 const catalogue=source('CreatorCatalog');assert.match(catalogue,/\.put\("kind",\s*"legacy_planning_catalog"\)/);assert.match(catalogue,/\.put\("executable",\s*false\)/);assert.match(catalogue,/\.put\("executableCapabilitiesField",\s*"studioEditing"\)/);
 for(const [java,name] of [[source('ControlService'),'stateJson'],[source('ControlService'),'nativeSelfTest'],[source('MainActivity'),'stateJson']])assert.match(method(java,name),/\.put\("studioEditing",\s*StudioEditingCapabilities\.capabilities\(\)\)/);
 assert.match(method(source('ControlService'),'stateJson'),/\.put\("creatorCatalog",\s*CreatorCatalog\.describe\(\)\)/);assert.match(method(source('MainActivity'),'stateJson'),/\.put\("creatorCatalog",\s*CreatorCatalog\.describe\(\)\)/);
 assert.match(source('NativeWorkbenchBridge'),/\.put\("studioEditing",\s*StudioEditingCapabilities\.capabilities\(\)\)/);
 const worker=fs.readFileSync(new URL('../src/index.js',import.meta.url),'utf8');const start=worker.indexOf('  s.registerTool("app_catalog"'),end=worker.indexOf('  s.registerTool("app_state"',start);assert.ok(start>=0&&end>start);
 let handler;Function('s','out','isV3','EDITOR_EDITING_CAPABILITIES',worker.slice(start,end))({registerTool:(_,options,fn)=>{handler=fn;}},value=>value,true,EDITOR_EDITING_CAPABILITIES);
 const result=await handler();assert.deepEqual(result.studioEditing,EDITOR_EDITING_CAPABILITIES);assert.equal(result.legacyPlanningCatalog.executable,false);assert.equal(result.legacyPlanningCatalog.kind,'legacy_planning_catalog');assert.equal(result.legacyPlanningCatalog.executableCapabilitiesField,'studioEditing');
 // Old planning names are retained, but none is recast as native rendered support.
 assert.ok(result.legacyPlanningCatalog.transitions.includes('camera_shutter'));assert.ok(result.legacyPlanningCatalog.textAnimations.includes('caption_pop'));assert.ok(result.legacyPlanningCatalog.aiTools.includes('beat_sync'));
 assert.deepEqual(result.transitions,EDITOR_EDITING_CAPABILITIES.renderedTransitions);assert.deepEqual(result.effects,EDITOR_EDITING_CAPABILITIES.renderedEffectPresets);
});
