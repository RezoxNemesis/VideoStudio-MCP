import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

test('PNG capability is top-level sketch discovery and does not change reference-part metadata',()=>{
 const source=fs.readFileSync(new URL('../android/app/src/main/java/com/rezoxnemesis/videostudio/SketchCapabilities.java',import.meta.url),'utf8');
 const reference=source.match(/public static JSONObject referenceLayers\(\) throws Exception \{\s*return ([\s\S]*?);\s*\}/);assert.ok(reference);
 assert.doesNotMatch(reference[1],/stillPngExport/,'Existing reference-layer metadata must remain its own unchanged contract');
 const start=source.indexOf('public static JSONObject get() throws Exception {');assert.ok(start>=0);
 assert.match(source.slice(start),/\.put\("stillPngExport",\s*SketchStillExport\.capabilities\(\)\)/,'Ordinary sketch_capabilities must discover PNG export at the top level');
});
