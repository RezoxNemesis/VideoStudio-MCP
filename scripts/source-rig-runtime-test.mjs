/**
 * Actual installed MCP SDK + workerd/SQLite source-rig relay verification.
 * Start the ordinary Worker on loopback first, then run this script. Native
 * replies are synthetic owned ACKs; this does not verify Android rendering,
 * ChatGPT presentation or cinematic acceptance. No production seeding route.
 */
import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {Client, StreamableHTTPClientTransport} from '@modelcontextprotocol/client';

const origin = process.env.SOURCE_RIG_QA_ORIGIN || 'http://127.0.0.1:8790';
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin)) {
  throw new Error('Synthetic native registration is restricted to a loopback QA Worker.');
}
const owner = 'source_rig_runtime_' + randomUUID().replaceAll('-', '');
const foreignOwner = 'source_rig_other_' + randomUUID().replaceAll('-', '');
const device = randomUUID(), foreignDevice = randomUUID(), generation = 373;
const projectId = 'source-project-123', assetId = 'source-image-123', rigId = 'actor-rig-123';
const sourceSha256 = 'a'.repeat(64), rigSha256 = 'b'.repeat(64);
// A real one-pixel PNG fixture, used solely to verify the MCP image channel.
const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yN8sAAAAASUVORK5CYII=';
const secretMarkers = ['synthetic-private-access', 'synthetic-private-owner', 'https://private.example/original'];
const outcomes = [], exchanges = [], clients = [];
const sha = value => createHash('sha256').update(value).digest('hex');
const canonical = value => Array.isArray(value) ? value.map(canonical)
  : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const receiptSha = value => sha(JSON.stringify(canonical(value)));
function passed(test) {
  outcomes.push({test, status: 'passed'});
  console.log('PASS:', test);
}
function parseText(result) {
  const text = result.content?.find(item => item.type === 'text');
  assert.ok(text, 'MCP result must contain JSON text.');
  return JSON.parse(text.text);
}
function assertPrivateProjection(result) {
  for (const block of result.content || []) {
    if (block.type !== 'text') continue;
    assert.ok(!block.text.includes(png), 'Image bytes must use the MCP image channel.');
    assert.ok(!block.text.includes(owner) && !block.text.includes(foreignOwner), 'Owner credentials must not enter MCP text.');
    for (const marker of secretMarkers) assert.ok(!block.text.includes(marker), 'Private native fields must be redacted.');
  }
}
function assertImage(result) {
  assertPrivateProjection(result);
  const images = result.content?.filter(item => item.type === 'image') || [];
  assert.equal(images.length, 1, 'Exactly the selected native diagnostic image must be returned.');
  assert.equal(images[0].mimeType, 'image/png');
  assert.equal(images[0].data, png);
}
async function http(path, body, {credential = owner, expectedStatus = 200} = {}) {
  const response = await fetch(origin + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {authorization: 'Bearer ' + credential, ...(body === undefined ? {} : {'content-type': 'application/json'})},
    ...(body === undefined ? {} : {body: JSON.stringify(body)}),
    signal: AbortSignal.timeout(20000)
  });
  assert.equal(response.status, expectedStatus, 'Native REST status must match the expected boundary outcome.');
  return response.json();
}
async function register(version, {credential = owner, id = device} = {}) {
  const result = await http('/api/v3/app/register', {
    deviceId: id, ownerKey: credential,
    meta: {name: 'Synthetic source-rig QA native agent', platform: 'android-native', appVersion: version,
      protocolVersion: 3, protocolMin: 3, protocolMax: 3, appGeneration: generation,
      permissionMode: 'everything', controlPaused: false, projects: []}
  }, {credential});
  assert.equal(result.ok, true);
  assert.equal(result.protocolVersion, 3);
}
async function poll() {
  const result = await http('/api/v3/app/commands?deviceId=' + device + '&appGeneration=' + generation + '&wait=0');
  assert.equal(result.protocolVersion, 3);
  return result.commands;
}
async function acknowledge(commandId, result, options = {}) {
  return http('/api/v3/app/commands/' + commandId + '/complete', {
    deviceId: device, appGeneration: options.appGeneration ?? generation,
    protocolVersion: 3, status: 'completed', result
  }, options);
}
async function connect(path, lane) {
  const client = new Client({name: 'videostudio-source-rig-qa', version: '1.0.0'}, {
    capabilities: {}, supportedProtocolVersions: ['2025-11-25', '2025-06-18', '2025-03-26'],
    versionNegotiation: {mode: 'legacy'}
  });
  const transport = new StreamableHTTPClientTransport(new URL(origin + path), {
    fetch: async (input, init = {}) => {
      const headers = new Headers(init.headers);
      let method;
      if (typeof init.body === 'string') {
        try { method = JSON.parse(init.body).method; } catch {}
      }
      const response = await fetch(input, {...init,
        signal: AbortSignal.any([init.signal, AbortSignal.timeout(20000)].filter(Boolean))});
      // Deliberately record method/revision/status, never private endpoint URLs,
      // credentials, arguments, ACK data or image payloads.
      if (method) exchanges.push({lane, method, protocolVersion: headers.get('mcp-protocol-version'),
        status: response.status, contentType: response.headers.get('content-type')});
      return response;
    }
  });
  clients.push(client);
  await client.connect(transport, {timeout: 20000});
  assert.equal(client.getNegotiatedProtocolVersion(), '2025-11-25');
  return client;
}
async function call(client, name, args = {}) {
  const result = await client.callTool({name, arguments: args}, {timeout: 20000});
  assert.ok(!result.isError, 'A valid source-rig call must not be a tool error.');
  return result;
}
async function rejected(client, name, args) {
  let refused = false;
  try {
    const result = await client.callTool({name, arguments: args}, {timeout: 20000});
    refused = result.isError === true || parseText(result).queued === false;
  } catch { refused = true; }
  assert.equal(refused, true, 'Malformed tool arguments must be rejected.');
}
const inspectArgs = {projectId, assetId, expectedSourceSha256: sourceSha256,
  crop: {left: .1, top: .2, right: .7, bottom: .9}, maxDimension: 512};
const joints = Array.from({length: 16}, (_, index) => [.2 + index * .02, .3 + index * .02]);
const profile = {sourceAssetId: assetId, black: true, sourceSha256, joints,
  outline: [[.1, .1], [.9, .1], [.9, .9]], swordHilt: [.5, .5], swordTip: [.8, .5],
  swordOutline: [[.5, .48], [.8, .48], [.8, .52]]};
const batchArgs = {projectId, sourceProjectUpdatedAt: 1, batchId: 'rig-batch-123', profiles: [profile]};

try {
  await register('3.7.2');
  const app = await connect('/app-mcp-v3/' + owner, 'app-v3');
  const listing = await app.listTools({}, {timeout: 20000});
  const toolByName = new Map(listing.tools.map(tool => [tool.name, tool]));
  for (const name of ['app_inspect_source_artwork', 'app_preview_source_rig', 'app_save_source_rig_profiles', 'app_source_rig_status']) {
    assert.ok(toolByName.has(name), 'Actual SDK discovery must expose ' + name);
    assert.match(toolByName.get(name).description, /3\.7\.3/);
  }
  for (const name of ['app_inspect_source_artwork', 'app_preview_source_rig', 'app_source_rig_status']) {
    assert.equal(toolByName.get(name).annotations?.readOnlyHint, true);
  }
  const batchSchema = toolByName.get('app_save_source_rig_profiles').inputSchema;
  assert.equal(batchSchema.properties.profiles.minItems, 1);
  assert.equal(batchSchema.properties.profiles.maxItems, 8);
  assert.ok(batchSchema.properties.profiles.items.required.includes('sourceSha256'));
  assert.equal(toolByName.get('app_save_source_rig_profiles').annotations?.idempotentHint, true);
  const inspectionSchema = toolByName.get('app_inspect_source_artwork').inputSchema;
  assert.deepEqual(inspectionSchema.required, ['projectId', 'assetId']);
  assert.ok(!('uri' in inspectionSchema.properties) && !('sourceUrl' in inspectionSchema.properties));
  passed('Actual SDK initialization, tool discovery and bounded authoring schemas');

  const oldVersion = parseText(await call(app, 'app_inspect_source_artwork', inspectArgs));
  assert.equal(oldVersion.queued, false);
  assert.match(oldVersion.error, /NATIVE_APP_UPGRADE_REQUIRED.*3\.7\.3/);
  assert.deepEqual(await poll(), []);
  await register('3.7.3');
  await rejected(app, 'app_save_source_rig_profiles', {...batchArgs, profiles: Array(9).fill(profile)});
  const {sourceSha256: omittedHash, ...unboundProfile} = profile;
  await rejected(app, 'app_save_source_rig_profiles', {...batchArgs, profiles: [unboundProfile]});
  for (const parameters of [{...inspectArgs, uri: 'https://unowned.example/art.png'},
    {...inspectArgs, crop: {left: .8, top: 0, right: .2, bottom: 1}}]) {
    const result = parseText(await call(app, 'app_execute', {action: 'inspect_source_artwork', parameters}));
    assert.equal(result.queued, false);
  }
  assert.deepEqual(await poll(), []);
  passed('Native 3.7.3 admission and invalid authoring refusal before queue effects');

  const queued = parseText(await call(app, 'app_inspect_source_artwork', inspectArgs));
  assert.equal(queued.queued, true);
  const claimed = await poll();
  assert.equal(claimed.length, 1);
  assert.equal(claimed[0].id, queued.commandId);
  assert.equal(claimed[0].action, 'inspect_source_artwork');
  assert.deepEqual(claimed[0].parameters, inspectArgs);
  const inspection = {ok: true, projectId, assetId, sourceSha256, sourceProjectUpdatedAt: 1,
    scope: 'source-artwork-inspection', coordinateSpace: 'oriented-source-uv', cinematicAcceptance: false,
    originalBytesModified: false, codedWidth: 864, codedHeight: 1536, exifOrientation: 1, orientedWidth: 864, orientedHeight: 1536,
    previewToOriginalUv: {coordinateSpace: 'oriented-source-uv', offsetX: .1, scaleX: .6, offsetY: .2, scaleY: .7},
    sourceArtworkImage: {base64: png, mimeType: 'image/png', width: 1, height: 1, role: 'original_source_inspection', sha256: sha(Buffer.from(png, 'base64'))},
    accessToken: secretMarkers[0], nested: {privateOwnerKey: secretMarkers[1], sourceUrl: secretMarkers[2]}};
  // Old activated Workers understand contactSheet. The new source result keeps
  // that exact image alias, while the updated Worker deduplicates image blocks.
  inspection.contactSheet = {...inspection.sourceArtworkImage};
  await acknowledge(queued.commandId, inspection, {appGeneration: generation - 1, expectedStatus: 409});
  await acknowledge(queued.commandId, inspection, {credential: foreignOwner, expectedStatus: 401});
  const unchanged = parseText(await call(app, 'app_get_command_result', {commandId: queued.commandId}));
  assert.equal(unchanged.status, 'claimed');
  assert.equal(unchanged.result, null);
  const completed = await acknowledge(queued.commandId, inspection);
  assert.equal(completed.command.resultSha256, receiptSha(inspection));
  const visual = await call(app, 'app_get_command_result', {commandId: queued.commandId});
  assertImage(visual);
  const visible = parseText(visual);
  assert.equal(visible.resultSha256, receiptSha(inspection));
  assert.equal(visible.result.sourceSha256, sourceSha256);
  assert.deepEqual(visible.result.previewToOriginalUv, inspection.previewToOriginalUv);
  assert.ok(!('base64' in visible.result.sourceArtworkImage));
  assert.ok(!('base64' in visible.result.contactSheet));
  assert.equal(visible.result.contactSheet.sha256, visible.result.sourceArtworkImage.sha256);
  passed('Owned native ACK fencing, compatibility image alias deduplication and original byte/mapping proof');

  const compatibility = await connect('/mcp', 'studio-native-compatibility');
  const compatibilityVisual = await call(compatibility, 'get_video_command_result', {deviceId: owner, commandId: queued.commandId});
  assertImage(compatibilityVisual);
  assert.equal(parseText(compatibilityVisual).resultSha256, receiptSha(inspection));
  passed('Active Studio Web compatibility result handler returns the same private native image');

  const previewArgs = {projectId, rigId, expectedRigSha256: rigSha256,
    expectedSourceSha256: sourceSha256, targetJoints16: joints, maxDimension: 512};
  const previewQueued = parseText(await call(app, 'app_preview_source_rig', previewArgs));
  assert.equal(previewQueued.queued, true);
  const previewClaimed = await poll();
  assert.equal(previewClaimed.length, 1);
  assert.equal(previewClaimed[0].action, 'preview_source_rig');
  assert.deepEqual(previewClaimed[0].parameters, previewArgs);
  const stageUv = {coordinateSpace: 'oriented-source-uv', offsetX: 0, scaleX: 1, offsetY: 0, scaleY: 1};
  const preview = {ok: true, diagnosticOnly: true, cinematicAcceptance: false, projectId, rigId,
    scope: 'source-rig-pose-diagnostic', completeAuthoredProfile: true, sourceRestModified: false, originalBytesModified: false,
    rigSha256, sourceSha256, stage: {background: 'checkerboard', originalToStageUv: stageUv, registration: 'identity', independentStretch: false},
    previewToOriginalUv: stageUv,
    rigPreviewImage: {base64: png, mimeType: 'image/png', width: 1, height: 1, role: 'source_rig_pose_diagnostic'},
    notes: Array.from({length: 220}, (_, index) => 'Diagnostic material row ' + index + ': ' + 'x'.repeat(300)),
    nested: {access_token: secretMarkers[0], owner_key: secretMarkers[1], source_url: secretMarkers[2]}};
  preview.contactSheet = {...preview.rigPreviewImage};
  await acknowledge(previewQueued.commandId, preview);
  const pagedVisual = await call(app, 'app_get_command_result', {commandId: previewQueued.commandId});
  assertImage(pagedVisual);
  const firstPage = parseText(pagedVisual);
  assert.equal(firstPage.resultPaged, true);
  assert.equal(firstPage.canonicalResultSha256, receiptSha(preview));
  assert.equal(firstPage.command.resultSha256, receiptSha(preview));
  assert.ok(firstPage.pageCount > 1 && firstPage.pageCount < 32);
  let viewText = firstPage.fragment;
  for (let index = 1; index < firstPage.pageCount; index++) {
    const raw = await call(app, 'app_get_command_result_page', {commandId: previewQueued.commandId, pageIndex: index});
    assertPrivateProjection(raw);
    const page = parseText(raw);
    assert.equal(page.pageIndex, index);
    assert.equal(page.viewSha256, firstPage.viewSha256);
    assert.equal(page.canonicalResultSha256, receiptSha(preview));
    viewText += page.fragment;
  }
  assert.equal(sha(viewText), firstPage.viewSha256);
  assert.equal(Buffer.byteLength(viewText), firstPage.viewBytes);
  const fullView = JSON.parse(viewText);
  assert.equal(fullView.diagnosticOnly, true);
  assert.equal(fullView.cinematicAcceptance, false);
  assert.equal(fullView.rigSha256, rigSha256);
  assert.deepEqual(fullView.stage, preview.stage);
  assert.deepEqual(fullView.previewToOriginalUv, preview.previewToOriginalUv);
  assert.equal(fullView.notes.length, preview.notes.length);
  assert.ok(!('base64' in fullView.rigPreviewImage));
  const largeCompatibilityVisual = await call(compatibility, 'get_video_command_result', {
    deviceId: owner, commandId: previewQueued.commandId
  });
  assertImage(largeCompatibilityVisual);
  const largeCompatibilityText = parseText(largeCompatibilityVisual);
  assert.equal(largeCompatibilityText.resultSha256, receiptSha(preview));
  assert.equal(largeCompatibilityText.result.notes.length, preview.notes.length);
  passed('Large pose diagnostic retains its MCP image and complete redacted paged receipt proof');

  const replay = await acknowledge(queued.commandId, inspection);
  assert.equal(replay.command.resultReplayed, true);
  await acknowledge(queued.commandId, {...inspection, sourceSha256: 'c'.repeat(64)}, {expectedStatus: 400});
  const retained = await call(app, 'app_get_command_result', {commandId: queued.commandId});
  assertImage(retained);
  assert.equal(parseText(retained).resultSha256, receiptSha(inspection));
  passed('A newer image and ACK retries preserve the original immutable result/image receipt');

  const batch = {...batchArgs, profiles: [profile, {...profile, black: false}]};
  const batchQueued = parseText(await call(app, 'app_save_source_rig_profiles', batch));
  const ordered = [assetId, 'source-image-456', assetId];
  const statusQueued = parseText(await call(app, 'app_source_rig_status', {projectId, assetIds: ordered}));
  assert.equal(batchQueued.queued, true);
  assert.equal(statusQueued.queued, true);
  const authoringClaims = await poll();
  assert.equal(authoringClaims.length, 2);
  const batchClaim = authoringClaims.find(command => command.id === batchQueued.commandId);
  const statusClaim = authoringClaims.find(command => command.id === statusQueued.commandId);
  assert.equal(batchClaim.action, 'save_source_rig_profiles');
  assert.deepEqual(batchClaim.parameters, batch);
  assert.equal(statusClaim.action, 'source_rig_status');
  assert.deepEqual(statusClaim.parameters.assetIds, ordered);
  const partial = {ok: false, state: 'PARTIAL_APPLIED', projectId, sourceProjectUpdatedAt: 1,
    batchId: batch.batchId, requestedCount: 2, appliedCount: 1, allOrNothing: false, sourceBytesVerified: false,
    receipts: [{index: 0, rigId, projectId, sourceAssetId: assetId, black: true, sha256: rigSha256, sourceSha256, replayed: false}],
    pendingIndices: [1], error: {code: 'SYNTHETIC_INTERRUPTION', message: 'Synthetic native publication interruption'}};
  await acknowledge(batchQueued.commandId, partial);
  const partialRead = parseText(await call(app, 'app_get_command_result', {commandId: batchQueued.commandId}));
  assert.deepEqual(partialRead.result, partial);
  await acknowledge(statusQueued.commandId, {ok: true, projectId, assetIds: ordered,
    sourceBytesVerified: false, inspectionRequired: true, cinematicApproved: false, preparationComplete: false});
  passed('Batch byte proofs, explicit partial publication receipts and repeated ordered source occurrences survive the relay');

  await register('3.7.3', {credential: foreignOwner, id: foreignDevice});
  const foreign = await connect('/app-mcp-v3/' + foreignOwner, 'foreign-owner');
  const foreignRead = await call(foreign, 'app_get_command_result', {commandId: queued.commandId});
  assert.equal(parseText(foreignRead).error, 'Command not found');
  assert.equal(foreignRead.content.filter(item => item.type === 'image').length, 0);
  assertPrivateProjection(foreignRead);
  const rpcCalls = exchanges.filter(exchange => exchange.method === 'tools/call');
  assert.ok(rpcCalls.length > 10);
  assert.ok(rpcCalls.every(exchange => exchange.protocolVersion === '2025-11-25' && exchange.status === 200));
  passed('Foreign owners cannot retrieve source images; actual SDK calls retain the negotiated HTTP revision');

  const files = ['src/index.js', 'src/storyboard-contract.js', 'src/personal-entry.js'];
  const sourceFileSha256 = Object.fromEntries(await Promise.all(files.map(async path => [path, sha(await readFile(path))])));
  const sdk = JSON.parse(await readFile(new URL('../node_modules/@modelcontextprotocol/client/package.json', import.meta.url), 'utf8'));
  await mkdir('.work', {recursive: true});
  await writeFile('.work/source-rig-runtime-results.json', JSON.stringify({
    verifiedAt: new Date().toISOString(), observer: 'local workerd/SQLite and installed MCP client SDK',
    sdk: {name: sdk.name, version: sdk.version, negotiatedProtocolVersion: '2025-11-25'},
    syntheticOwnedNativeReplies: true, androidNativeRenderingVerified: false,
    chatgptHostAdmissionVerified: false, cinematicAcceptanceVerified: false,
    hybridTransportVerified: false, sourceFileSha256, outcomes, exchanges
  }, null, 2));
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
}
