import test from 'node:test';
import assert from 'node:assert/strict';
import {persistNativeCommandQueue, hydrateNativeCommand, hydrateNativeField}
  from '../src/native-payload-storage.js';

function fakeStorage() {
  const rows = new Map();
  return {
    rows,
    async put(key, val) {
      const size = Buffer.byteLength(JSON.stringify(val));
      assert.ok(size < 1_900_000, 'Storage row size cap exceeded for ' + key);
      rows.set(key, structuredClone(val));
    },
    async get(key) { return structuredClone(rows.get(key)); }
  };
}

test('200 old results are compacted to bounded queue; most recent 160 retained', async () => {
  const store = fakeStorage();
  const history=Array.from({length:200}, (_,i) =>
    ({id:'c'+i,seq:i,status:'completed',parameters:{mode:'normal'},
      result:{ok:true,text:'a'.repeat(18000),index:i}}));
  const result=await persistNativeCommandQueue(store,'native1',history);
  assert.equal(result.length,160);
  assert.equal(result[0].id,'c40');
  assert.ok(Buffer.byteLength(JSON.stringify(await store.get('app-v3-cl:native1'))) < 1_900_000);
  assert.equal((await hydrateNativeCommand(store,result[159])).result.index,199);
});
test('large contact sheet is fully preserved, not truncated or lost', async () => {
  const store=fakeStorage();
  const result={ok:true,contactSheet:{base64:'x'.repeat(1_600_000)}};
  const [saved]=await persistNativeCommandQueue(store,'native2',
    [{id:'done',status:'completed',parameters:{},result}]);
  assert.equal((await hydrateNativeCommand(store,saved)).result.contactSheet.base64,result.contactSheet.base64);
});
test('queued UTF-8 / emoji parameters survive archival and device migration', async () => {
  const store=fakeStorage();
  const original={id:'draft',seq:7,status:'queued',
    parameters:{instruction:'🚗'.repeat(120_000)},result:null};
  const [packed]=await persistNativeCommandQueue(store,'native-old',[original]);
  const [remapped]=await persistNativeCommandQueue(store,'native-new',[packed]);
  assert.equal((await hydrateNativeCommand(store,remapped)).parameters.instruction,original.parameters.instruction);
});
test('small fields stay inline, null fields survive, missing chunks fail clearly', async () => {
  const store=fakeStorage();
  const [saved]=await persistNativeCommandQueue(store,'native3',
    [{id:'x',seq:0,status:'queued',parameters:{name:'Car Race'},result:null}]);
  assert.deepEqual(saved.parameters,{name:'Car Race'});
  assert.equal(await hydrateNativeField(store,null),null);
  await assert.rejects(hydrateNativeField(store,{
    __nativePayloadVersion:1,originDeviceId:'native3',commandId:'x',
    field:'result',parts:1,length:5
  }),/Missing archived/);
});