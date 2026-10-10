import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
if (!globalThis.crypto) globalThis.crypto = webcrypto;
const module = await import('../src/personal-state.js').catch(() => ({}));
const extend = module.withPersonalStudio || (Base => Base);
const keyA = 'test_owner_alpha_'.padEnd(48, 'a');
const keyB = 'test_owner_beta_'.padEnd(48, 'b');
const digest = async value => Buffer.from(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))).toString('hex');
const clone = value => value === undefined ? undefined : structuredClone(value);

import {fixture,LegacyState} from './personal-fixture.mjs';
const requireFeature = state => assert.equal(typeof state.personalExecute, 'function', 'personal cloud execution must exist');
async function project(f, name = 'Offline film') {
  requireFeature(f.state);
  const command = await f.state.personalExecute(f.authA, 'project.create', {name}, crypto.randomUUID());
  assert.equal(command.status, 'completed');
  return command.result.project;
}

test('exports the additive personal state extension', () => assert.equal(typeof module.withPersonalStudio, 'function'));
test('rejects a public device ID as a private credential', async () => {
  const f = await fixture(); requireFeature(f.state);
  await assert.rejects(() => f.state.personalSnapshot({ownerKey: 'native-device-alpha'}), e => e.status === 401);
});
test('creates a durable cloud project with the phone offline', async () => {
  const f = await fixture(); const p = await project(f);
  const restarted = new (extend(LegacyState))({storage: f.storage}, {});
  assert.equal((await restarted.personalReadProject(f.authA, p.id)).name, 'Offline film');
  const view = await restarted.personalSnapshot(f.authA);
  assert.equal(view.native.connected, false);
  assert.equal(view.cloud.available, true);
  assert.equal(view.cloud.videoRenderingAvailable, false);
  assert.equal(view.projects.length, 1);
});
test('another owner cannot read, update or delete a project', async () => {
  const f = await fixture(); const p = await project(f);
  await assert.rejects(() => f.state.personalReadProject(f.authB, p.id), e => e.status === 404);
  await assert.rejects(() => f.state.personalExecute(f.authB, 'project.update', {id: p.id, expectedRevision: 1, patch: {name:'stolen'}}, crypto.randomUUID()), e => e.status === 404);
  await assert.rejects(() => f.state.personalExecute(f.authB, 'project.delete', {id: p.id, expectedRevision: 1}, crypto.randomUUID()), e => e.status === 404);
  assert.equal((await f.state.personalSnapshot(f.authB)).projects.length, 0);
});
test('idempotent create returns the same project and command', async () => {
  const f = await fixture(); requireFeature(f.state);
  const args = [f.authA, 'project.create', {name:'One copy'}, 'reliable-request-1'];
  const a = await f.state.personalExecute(...args); const b = await f.state.personalExecute(...args);
  assert.equal(a.id, b.id); assert.equal(a.result.project.id, b.result.project.id);
  assert.equal((await f.state.personalSnapshot(f.authA)).projects.length, 1);
});
test('idempotency key reuse with different data is rejected', async () => {
  const f = await fixture(); requireFeature(f.state);
  await f.state.personalExecute(f.authA, 'project.create', {name:'First'}, 'same-request');
  await assert.rejects(() => f.state.personalExecute(f.authA, 'project.create', {name:'Second'}, 'same-request'), e => e.status === 409);
});
test('concurrent duplicate submissions create one project', async () => {
  const f = await fixture(); requireFeature(f.state);
  const results = await Promise.all(Array.from({length: 8}, () => f.state.personalExecute(f.authA, 'project.create', {name:'Concurrent'}, 'concurrent-request')));
  assert.equal(new Set(results.map(r => r.result.project.id)).size, 1);
});
test('stale revisions cannot overwrite a newer note', async () => {
  const f = await fixture(); const p = await project(f);
  const a = await f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{notes:'Newer note'}}, 'update-1');
  assert.equal(a.result.project.revision, 2);
  await assert.rejects(() => f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{notes:'Stale note'}}, 'update-2'), e => e.status === 409);
  assert.equal((await f.state.personalReadProject(f.authA, p.id)).notes, 'Newer note');
});
test('only one of two concurrent edits at the same revision succeeds', async () => {
  const f = await fixture(); const p = await project(f);
  const results = await Promise.allSettled(['A', 'B'].map((notes, i) => f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{notes}}, 'race-' + i)));
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.filter(r => r.status === 'rejected' && r.reason.status === 409).length, 1);
});
test('search finds project notes without returning credentials', async () => {
  const f = await fixture(); const p = await project(f);
  await f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{notes:'A lighthouse at sunrise'}}, 'search-note');
  const found = await f.state.personalSnapshot(f.authA, 'lighthouse');
  assert.equal(found.projects.length, 1);
  assert.equal((await f.state.personalSnapshot(f.authA, 'unrelated')).projects.length, 0);
  const text = JSON.stringify(found);
  assert.ok(!text.includes(keyA)); assert.ok(!text.includes('ownerHash'));
});
test('validates HTTPS links and shot durations', async () => {
  const f = await fixture(); const p = await project(f);
  for (const patch of [{links:[{title:'Bad',url:'javascript:alert(1)'}]}, {shots:[{title:'Bad shot',durationSeconds:-2}]}, {notes:'x'.repeat(12001)}]) {
    await assert.rejects(() => f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch}, crypto.randomUUID()), e => e.status === 400);
  }
  const changed = await f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{links:[{title:'Reference',url:'https://example.com/film'}],shots:[{title:'Opening',durationSeconds:3,description:'Wide establishing shot'}]}}, 'good-content');
  assert.equal(changed.result.project.shots[0].durationSeconds, 3);
});
test('invalid operations do not create a cloud job', async () => {
  const f = await fixture(); requireFeature(f.state);
  await assert.rejects(() => f.state.personalExecute(f.authA, 'gallery.list', {}, 'bad-op'), e => e.status === 400 || e.status === 403);
  assert.equal((await f.state.personalSnapshot(f.authA)).jobs.length, 0);
});
test('STOP and One File Lock remain effective for cloud writes', async () => {
  const f = await fixture(); requireFeature(f.state);
  const key = 'app-device:native-device-alpha', d = await f.storage.get(key);
  await f.storage.put(key, {...d, controlPaused:true});
  await assert.rejects(() => project(f), e => e.status === 423);
  assert.equal((await f.state.personalSnapshot(f.authA)).native.controlPaused, true);
  await f.storage.put(key, {...d, permissionMode:'one_file'});
  await assert.rejects(() => project(f), e => e.status === 423);
});
test('sessions are hashed at rest, revocable and owner-scoped', async () => {
  const f = await fixture(); requireFeature(f.state); await project(f);
  const session = await f.state.personalCreateSession(keyA);
  assert.equal((await f.state.personalSnapshot({session:session.session})).projects.length, 1);
  assert.ok(!JSON.stringify([...f.storage.data]).includes(session.session));
  assert.ok(!JSON.stringify([...f.storage.data]).includes(keyA));
  await f.state.personalRevokeSession(session.session);
  await assert.rejects(() => f.state.personalSnapshot({session:session.session}), e => e.status === 401);
});
test('expired sessions are rejected', async () => {
  const f = await fixture(); requireFeature(f.state);
  const session = await f.state.personalCreateSession(keyA);
  const k = 'personal:session:' + await digest(session.session);
  const row = await f.storage.get(k); await f.storage.put(k, {...row, expiresAt:1});
  await assert.rejects(() => f.state.personalSnapshot({session:session.session}), e => e.status === 401);
});
test('export/import round-trip creates a copy without losing content', async () => {
  const f = await fixture(); const p = await project(f);
  await f.state.personalExecute(f.authA, 'project.update', {id:p.id, expectedRevision:1, patch:{notes:'Saved notes',brief:'A short film'}}, 'for-export');
  const archive = await f.state.personalExport(f.authA, p.id);
  assert.equal(archive.format, 'videostudio-personal');
  const imported = await f.state.personalExecute(f.authA, 'project.import', {archive}, 'import-once');
  assert.notEqual(imported.result.project.id, p.id);
  assert.equal(imported.result.project.notes, 'Saved notes');
  assert.equal((await f.state.personalSnapshot(f.authA)).projects.length, 2);
});
test('rejects unsupported import format and forged native linkage', async () => {
  const f = await fixture(); requireFeature(f.state);
  await assert.rejects(() => f.state.personalExecute(f.authA, 'project.import', {archive:{format:'unknown',schemaVersion:99,project:{name:'Bad'}}}, 'bad-import'), e => e.status === 400);
  await assert.rejects(() => f.state.personalExecute(f.authA, 'project.create', {name:'Bad link',nativeProjectId:'someone-elses-project'}, 'bad-link'), e => e.status === 400);
});
test('deleting a cloud project leaves native projects untouched', async () => {
  const f = await fixture(); const p = await project(f);
  const before = await f.storage.get('app-device:native-device-alpha');
  await f.state.personalExecute(f.authA, 'project.delete', {id:p.id, expectedRevision:1}, 'delete-project');
  await assert.rejects(() => f.state.personalReadProject(f.authA, p.id), e => e.status === 404);
  assert.deepEqual(await f.storage.get('app-device:native-device-alpha'), before);
});
test('existing native actions still delegate to the legacy queue', async () => {
  const f = await fixture(); requireFeature(f.state);
  const result = await f.state.appEnqueueV3(keyA, 'get_state', {readOnly:true});
  assert.equal(result.legacy, true); assert.equal(result.status, 'waiting_native');
  assert.equal((await f.state.appCommandV3(keyA, result.id)).id, result.id);
});
test('existing MCP bridge runs explicit personal_cloud operations immediately', async () => {
  const f = await fixture(); requireFeature(f.state);
  const result = await f.state.appEnqueueV3(keyA, 'personal_cloud', {operation:'project.create', data:{name:'MCP without phone'}, requestId:'mcp-create'});
  assert.equal(result.status, 'completed'); assert.equal(result.executionSurface, 'cloud');
  const read = await f.state.appCommandV3(keyA, result.id);
  assert.equal(read.result.project.name, 'MCP without phone');
  const status = await f.state.appStatusV3(keyA);
  assert.equal(status.legacy, true); assert.equal(status.personalCloud.available, true);
});
test('an owner cannot retrieve another owners cloud command', async () => {
  const f = await fixture(); requireFeature(f.state);
  const job = await f.state.personalExecute(f.authA, 'project.create', {name:'Private'}, 'private-command');
  await assert.rejects(() => f.state.appCommandV3(keyB, job.id), e => e.status === 404);
});
test('cloud does not advertise or pretend to execute video rendering', async () => {
  const f = await fixture(); requireFeature(f.state);
  await assert.rejects(() => f.state.personalExecute(f.authA, 'render', {}, 'no-render'), e => e.status === 400);
  const view = await f.state.personalSnapshot(f.authA);
  assert.equal(view.cloud.videoRenderingAvailable, false);
  assert.equal(view.jobs.length, 0);
});
test('notes preserve intentional whitespace exactly',async()=>{const f=await fixture();const p=await project(f);const notes='  opening\n\n';const r=await f.state.personalExecute(f.authA,'project.update',{id:p.id,expectedRevision:1,patch:{notes}},'preserve-whitespace');assert.equal(r.result.project.notes,notes);});
test('a positive duration that rounds to zero is rejected',async()=>{const f=await fixture();const p=await project(f);await assert.rejects(()=>f.state.personalExecute(f.authA,'project.update',{id:p.id,expectedRevision:1,patch:{shots:[{title:'Invalid precision',durationSeconds:.001}]}},'tiny-duration'),e=>e.status===400);});
test('new native project and selected-asset scopes cannot be bypassed through cloud plans',async()=>{const f=await fixture();const p=await project(f);for(const mode of ['project','selected_assets','unknown_future_restricted_mode']){const k='app-device:native-device-alpha';await f.storage.put(k,{...await f.storage.get(k),permissionMode:mode});await assert.rejects(()=>f.state.personalExecute(f.authA,'project.create',{name:'Outside scope'},'scope-'+mode),e=>e.status===423);await assert.rejects(()=>f.state.personalReadProject(f.authA,p.id),e=>e.status===423);const v=await f.state.personalSnapshot(f.authA);assert.equal(v.projects.length,0);assert.equal(v.jobs.length,0);assert.equal(v.cloud.scopeRestricted,true);}});
test('narrowing native scope also restricts previously completed cloud receipts',async()=>{const f=await fixture();const c=await f.state.appEnqueueV3(keyA,'personal_cloud',{operation:'project.create',data:{name:'Previously private'},requestId:'receipt-scope'});await f.storage.put('app-device:native-device-alpha',{...await f.storage.get('app-device:native-device-alpha'),permissionMode:'selected_assets'});await assert.rejects(()=>f.state.appCommandV3(keyA,c.id),e=>e.status===423);});
