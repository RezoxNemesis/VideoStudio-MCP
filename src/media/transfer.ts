/**
 * Encrypted hand-off of files the agent was given in chat. The agent encrypts the file (AES-256-GCM), parks
 * the ciphertext at an https link and sends the key only through the private relay job. The phone downloads,
 * decrypts and imports it into the project. Nothing is read from or written to the gallery.
 */
import { downloadBlob } from "../native/storage";

function fromB64(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Layout: 12-byte IV, then ciphertext with the 16-byte GCM tag appended. */
export async function decryptBlob(data: ArrayBuffer, keyB64: string): Promise<ArrayBuffer> {
  const raw = fromB64(keyB64);
  if (raw.length !== 32) throw new Error("Transfer key must be 32 bytes.");
  const key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);
  const iv = new Uint8Array(data, 0, 12);
  try {
    return await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, new Uint8Array(data, 12));
  } catch {
    throw new Error("Could not decrypt the transfer (wrong key or damaged file).");
  }
}

export async function receiveTransfer(urls: string[], keyB64: string, mime: string, headers: Record<string, string> = {}, onPart?: (i: number, n: number) => void): Promise<Blob> {
  const parts: Blob[] = [];
  for (let i = 0; i < urls.length; i++) {
    onPart?.(i, urls.length);
    parts.push(await downloadBlob(urls[i]!, headers));
  }
  const enc = new Blob(parts);
  const plain = await decryptBlob(await enc.arrayBuffer(), keyB64);
  return new Blob([plain], { type: mime });
}
