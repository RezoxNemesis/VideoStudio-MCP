/**
 * Native MCP v3 bounded SQLite Durable Object command-queue persistence.
 * Large results and parameters are written separately in bounded chunks so
 * command history never balloons into one oversized SQLite value.
 */
const MAX_INLINE_BYTES = 1024;
const PART_CHARS = 64000; // <256 KiB even for four-byte UTF-8 text
const MAX_PARTS = 512;
const codec = new TextEncoder();
const refTag = '__nativePayloadVersion';

function validRef(value) {
  return value !== null && typeof value === 'object' && value[refTag] === 1
      && Number.isInteger(value.parts) && value.parts >= 1 && value.parts <= MAX_PARTS
      && typeof value.originDeviceId === 'string' && typeof value.commandId === 'string'
      && ['result', 'parameters'].includes(value.field);
}
function key(deviceId, commandId, field, part) {
  return `app-v3-blob:${deviceId}:${commandId}:${field}:${part}`;
}
export async function archiveNativeField(storage, deviceId, commandId, field, value) {
  if (value === null || value === undefined || validRef(value)) return value;
  const encoded = JSON.stringify(value);
  if (encoded === undefined || codec.encode(encoded).byteLength <= MAX_INLINE_BYTES) return value;
  const count = Math.ceil(encoded.length / PART_CHARS);
  if (count > MAX_PARTS) throw new Error('Native command payload too large; import the asset by attachment transfer instead.');
  for (let part = 0; part < count; part++) {
    await storage.put(key(deviceId, commandId, field, part),
      encoded.slice(part * PART_CHARS, (part + 1) * PART_CHARS));
  }
  return {[refTag]: 1, originDeviceId: deviceId, commandId, field, parts: count, length: encoded.length};
}
export async function hydrateNativeField(storage, field) {
  if (!validRef(field)) return field;
  const pieces = [];
  for (let part = 0; part < field.parts; part++) {
    const payload = await storage.get(key(field.originDeviceId, field.commandId, field.field, part));
    if (typeof payload !== 'string') throw new Error(`Missing archived native MCP payload for ${field.commandId}`);
    pieces.push(payload);
  }
  const restored = pieces.join('');
  if (restored.length !== field.length) throw new Error('Archived native MCP payload failed integrity length check');
  return JSON.parse(restored);
}
export async function hydrateNativeCommand(storage, command) {
  if (!command) return command;
  return {...command,
    parameters: await hydrateNativeField(storage, command.parameters),
    result: await hydrateNativeField(storage, command.result)};
}
export async function persistNativeCommandQueue(storage, deviceId, entries) {
  const packed = [];
  for (const original of entries.slice(-160)) {
    if (!original || !original.id) continue;
    const row = {...original,
      parameters: await archiveNativeField(storage, deviceId, original.id, 'parameters', original.parameters),
      result: await archiveNativeField(storage, deviceId, original.id, 'result', original.result)};
    packed.push(row);
  }
  await storage.put('app-v3-cl:' + deviceId, packed);
  return packed;
}
