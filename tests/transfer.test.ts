import { createCipheriv, randomBytes } from "node:crypto";
import { describe, expect, it } from "vitest";
import { decryptBlob } from "../src/media/transfer";

describe("encrypted transfer", () => {
  it("decrypts what the agent encrypted, and rejects a wrong key", async () => {
    const key = randomBytes(32);
    const iv = randomBytes(12);
    const msg = Buffer.from("ID3 fake mp3 bytes ".repeat(500));
    const c = createCipheriv("aes-256-gcm", key, iv);
    const body = Buffer.concat([iv, c.update(msg), c.final(), c.getAuthTag()]);
    const ab = body.buffer.slice(body.byteOffset, body.byteOffset + body.length);
    const out = await decryptBlob(ab, key.toString("base64"));
    expect(Buffer.from(out).equals(msg)).toBe(true);
    await expect(decryptBlob(ab, randomBytes(32).toString("base64"))).rejects.toThrow(/decrypt/);
  });
});
