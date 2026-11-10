/**
 * How the viewer shows colour right now (not saved with the project, never applied to renders): bypass all colour
 * work, a compare wipe, or one node's key shown as the picture on grey.
 */
export interface ColorView {
  bypass: boolean;
  /** 0..1 of the frame width: left of it the picture is ungraded. -1 = off. */
  split: number;
  showKey: { clipId: string; nodeId: string } | null;
}

export const colorView: ColorView = { bypass: false, split: -1, showKey: null };
const listeners = new Set<() => void>();

export function setColorView(patch: Partial<ColorView>): ColorView {
  if (patch.bypass !== undefined) colorView.bypass = !!patch.bypass;
  if (patch.split !== undefined) colorView.split = typeof patch.split === "number" && patch.split >= 0 ? Math.min(1, patch.split) : -1;
  if (patch.showKey !== undefined) colorView.showKey = patch.showKey && patch.showKey.clipId && patch.showKey.nodeId ? { clipId: patch.showKey.clipId, nodeId: patch.showKey.nodeId } : null;
  for (const f of listeners) f();
  return colorView;
}

export function onColorView(f: () => void): () => void {
  listeners.add(f);
  return () => listeners.delete(f);
}
