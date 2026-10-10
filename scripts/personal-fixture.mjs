import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const module=await import('../src/personal-state.js').catch(()=>({}));
const extend=module.withPersonalStudio||(Base=>Base);
const keyA='test_owner_alpha_'.padEnd(48,'a'),keyB='test_owner_beta_'.padEnd(48,'b');
const digest=async value=>Buffer.from(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))).toString('hex');
const clone=value=>value===undefined?undefined:structuredClone(value);
export class MemoryStorage {
  constructor(data = new Map()) { this.data = data; this.chain = Promise.resolve(); }
  async get(key) { return clone(this.data.get(key)); }
  async put(key, value) { this.data.set(key, clone(value)); }
  async delete(key) { return this.data.delete(key); }
  async list({prefix = ''} = {}) { return new Map([...this.data].filter(([k]) => k.startsWith(prefix)).map(([k, v]) => [k, clone(v)])); }
  async transaction(fn) {
    const run = this.chain.then(async () => {
      const copy = new MemoryStorage(clone(this.data));
      const result = await fn(copy);
      this.data = copy.data;
      return result;
    });
    this.chain = run.catch(() => {});
    return run;
  }
}
export class LegacyState {
  constructor(ctx, env = {}) { this.ctx = ctx; this.env = env; }
  async appResolve(ownerKey) {
    if (!ownerKey) return null;
    const id = await this.ctx.storage.get('app-owner:' + await digest(ownerKey));
    return id ? this.ctx.storage.get('app-device:' + id) : null;
  }
  async appStatusV3(ownerKey) { return {connected: !!(await this.appResolve(ownerKey)), nativeConnected: false, legacy: true}; }
  async appEnqueueV3(ownerKey, action, parameters) {
    const owner = await this.appResolve(ownerKey);
    if (!owner) throw new Error('Legacy authorization failed');
    const command = {id: crypto.randomUUID(), action, parameters, status: 'waiting_native', legacy: true};
    await this.ctx.storage.put('test:legacy:' + command.id, command);
    return command;
  }
  async appCommandV3(ownerKey, id) { return (await this.appResolve(ownerKey)) ? this.ctx.storage.get('test:legacy:' + id) : null; }
}
export async function fixture(env = {}) {
  const storage = new MemoryStorage();
  for (const [key, id] of [[keyA, 'native-device-alpha'], [keyB, 'native-device-beta']]) {
    const ownerHash = await digest(key);
    await storage.put('app-owner:' + ownerHash, id);
    await storage.put('app-device:' + id, {deviceId: id, ownerHash, name: 'Test phone', appVersion: '3.4.11', protocolVersion: 3, controlPaused: false, permissionMode: 'everything', galleryAccess: false, lastSeenAt: '2026-01-01T00:00:00Z', projects: []});
  }
  return {storage, state: new (extend(LegacyState))({storage}, env), authA: {ownerKey: keyA}, authB: {ownerKey: keyB}};
}
