/** Small DOM toolkit: element builder, icons, toasts, sheets and form rows. */

type Attrs = Record<string, string | number | boolean | undefined | null | ((e: Event) => void)>;

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...kids: Array<Node | string | null | undefined | false>): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v as EventListener);
    else if (k === "class") el.className = String(v);
    else if (k === "html") el.innerHTML = String(v);
    else if (v === true) el.setAttribute(k, "");
    else el.setAttribute(k, String(v));
  }
  for (const kid of kids) if (kid !== null && kid !== undefined && kid !== false) el.append(kid);
  return el;
}

const P: Record<string, string> = {
  play: "M8 5v14l11-7z",
  pause: "M7 5h4v14H7zM13 5h4v14h-4z",
  undo: "M9 14L4 9l5-5M4 9h11a5 5 0 010 10h-3",
  redo: "M15 14l5-5-5-5M20 9H9a5 5 0 000 10h3",
  split: "M12 3v18M7 8l-4 4 4 4M17 8l4 4-4 4",
  trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
  copy: "M9 9h11v11H9zM5 15H4V4h11v1",
  speed: "M12 13l4-4M4.9 19a9 9 0 1114.2 0",
  volume: "M4 9v6h4l5 4V5L8 9zM16 9a4 4 0 010 6M18.5 6.5a8 8 0 010 11",
  text: "M5 6V4h14v2M12 4v16M9 20h6",
  plus: "M12 5v14M5 12h14",
  film: "M4 4h16v16H4zM8 4v16M16 4v16M4 8h4M4 12h4M4 16h4M16 8h4M16 12h4M16 16h4",
  brush: "M14 4l6 6-9 9H5v-6zM12 6l6 6",
  mic: "M12 3a3 3 0 00-3 3v6a3 3 0 006 0V6a3 3 0 00-3-3zM5 11a7 7 0 0014 0M12 18v3",
  export: "M12 3v12M7 8l5-5 5 5M5 15v5h14v-5",
  bot: "M12 3v3M7 7h10a3 3 0 013 3v6a3 3 0 01-3 3H7a3 3 0 01-3-3v-6a3 3 0 013-3zM9 12h.01M15 12h.01M9 16h6",
  color: "M12 3a9 9 0 100 18c1 0 1.5-.8 1.5-1.5 0-1-1-1.3-1-2.3 0-.9.8-1.7 1.7-1.7H17a4 4 0 004-4c0-4.4-4-8.5-9-8.5zM7.5 11h.01M9.5 7h.01M14.5 7h.01",
  sliders: "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0M14 4v4M8 10v4M16 16v4",
  layers: "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5",
  key: "M12 4l8 8-8 8-8-8z",
  transition: "M4 5h7v14H4zM13 5h7v14h-7zM9 12h6",
  image: "M4 5h16v14H4zM8 10a1.5 1.5 0 100-.01M20 16l-5-5-8 8",
  folder: "M3 7h6l2 2h10v10H3z",
  menu: "M4 7h16M4 12h16M4 17h16",
  close: "M6 6l12 12M18 6L6 18",
  check: "M5 12l5 5L20 7",
  eye: "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 100 6 3 3 0 000-6",
  eyeOff: "M3 3l18 18M10.6 6.2A9.8 9.8 0 0112 6c6 0 10 6 10 6a17 17 0 01-3.2 3.8M6.5 6.6C3.9 8.3 2 12 2 12s4 6 10 6a9.6 9.6 0 004-.9",
  lock: "M6 11h12v9H6zM8 11V8a4 4 0 018 0v3",
  unlock: "M6 11h12v9H6zM8 11V8a4 4 0 017.5-2",
  mute: "M4 9v6h4l5 4V5L8 9zM17 9l4 6M21 9l-4 6",
  pencil: "M4 20l4-1 11-11-3-3L5 16zM14 6l3 3",
  eraser: "M8 20h12M5 15l9-9 5 5-8 8H8z",
  bucket: "M5 11l7-7 7 7-7 7zM19 15s2 2.5 2 4a2 2 0 01-4 0c0-1.5 2-4 2-4",
  lasso: "M12 4c5 0 9 2.5 9 5.5S17 15 12 15s-9-2.5-9-5.5S7 4 12 4zM6 14c-1 2 0 4 2 5",
  move: "M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3",
  dropper: "M14 6l4 4M5 19l1-4 9-9 3 3-9 9z",
  shape: "M4 4h7v7H4zM17.5 13a3.5 3.5 0 110 7 3.5 3.5 0 010-7zM14 4l6 7h-12z",
  onion: "M8 12a6 6 0 1012 0 6 6 0 00-12 0M4 12a6 6 0 006 6",
  frames: "M4 6h16v12H4zM8 6v12M12 6v12M16 6v12",
  wand: "M4 20L16 8M14 4v2M18 6h2M18 10v2M20 4l-2 2",
  symmetry: "M12 3v18M8 7L4 12l4 5M16 7l4 5-4 5",
  hand: "M8 13V5a1.5 1.5 0 013 0v6M11 11V4a1.5 1.5 0 013 0v7M14 11V5.5a1.5 1.5 0 013 0V14a7 7 0 01-7 7c-3 0-4.5-1.5-6-4l-2-3.5a1.5 1.5 0 012.6-1.5L8 15",
  loop: "M17 2l4 4-4 4M3 11V9a3 3 0 013-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 01-3 3H3",
  zoomIn: "M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5M11 8v6M8 11h6",
  download: "M12 3v12M7 10l5 5 5-5M5 20h14",
  share: "M18 8a3 3 0 100-6 3 3 0 000 6zM6 15a3 3 0 100-6 3 3 0 000 6zM18 22a3 3 0 100-6 3 3 0 000 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4",
  link: "M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1M14 10a4 4 0 00-5.7 0l-3 3a4 4 0 005.7 5.7l1-1",
  grid: "M4 4h16v16H4zM4 12h16M12 4v16",
  adjust: "M12 3a9 9 0 100 18V3z",
  rect: "M4 6h16v12H4z",
};

export function icon(name: keyof typeof P | string, size = 22): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const s = document.createElementNS(ns, "svg");
  s.setAttribute("viewBox", "0 0 24 24");
  s.setAttribute("width", String(size));
  s.setAttribute("height", String(size));
  s.setAttribute("aria-hidden", "true");
  s.classList.add("ic");
  const path = document.createElementNS(ns, "path");
  path.setAttribute("d", P[name] ?? P.plus!);
  if (name === "play" || name === "pause") path.setAttribute("fill", "currentColor");
  else {
    path.setAttribute("fill", "none");
    path.setAttribute("stroke", "currentColor");
    path.setAttribute("stroke-width", "1.8");
    path.setAttribute("stroke-linecap", "round");
    path.setAttribute("stroke-linejoin", "round");
  }
  s.append(path);
  return s;
}

export function btn(label: string, ic: string | null, onClick: (e: MouseEvent) => void, cls = ""): HTMLButtonElement {
  const b = h("button", { class: `btn ${cls}`.trim(), type: "button", title: label, "aria-label": label }, ic ? icon(ic) : null, ic && cls.includes("icon-only") ? null : h("span", {}, label));
  b.addEventListener("click", (e) => onClick(e as MouseEvent));
  return b;
}

export function toolBtn(label: string, ic: string, onClick: () => void, cls = ""): HTMLButtonElement {
  const b = h("button", { class: `tool ${cls}`.trim(), type: "button", title: label, "aria-label": label }, icon(ic, 22), h("span", {}, label));
  b.addEventListener("click", onClick);
  return b;
}

let toastBox: HTMLElement | null = null;
export function toast(msg: string, tone: "info" | "error" | "ok" = "info", ms = 2800): void {
  if (!toastBox) {
    toastBox = h("div", { class: "toasts", role: "status", "aria-live": "polite" });
    document.body.append(toastBox);
  }
  const t = h("div", { class: `toast ${tone}` }, msg);
  toastBox.append(t);
  setTimeout(() => {
    t.classList.add("out");
    setTimeout(() => t.remove(), 300);
  }, ms);
}

/** Bottom sheet that slides up over the current view. */
export function sheet(title: string, body: HTMLElement, opts: { onClose?: () => void; tall?: boolean } = {}): { el: HTMLElement; close: () => void } {
  const scrim = h("div", { class: "scrim" });
  const panel = h("section", { class: `sheet ${opts.tall ? "tall" : ""}`, role: "dialog", "aria-label": title }, h("header", { class: "sheet-head" }, h("h2", {}, title), btn("Close", "close", () => close(), "icon-only ghost")), h("div", { class: "sheet-body" }, body));
  const close = () => {
    panel.classList.add("out");
    scrim.classList.add("out");
    setTimeout(() => {
      panel.remove();
      scrim.remove();
    }, 200);
    opts.onClose?.();
  };
  scrim.addEventListener("click", close);
  document.body.append(scrim, panel);
  return { el: panel, close };
}

export function confirmBox(message: string, ok = "OK"): Promise<boolean> {
  return new Promise((resolve) => {
    const body = h("div", { class: "confirm" }, h("p", {}, message));
    const s = sheet("Confirm", body, { onClose: () => resolve(false) });
    body.append(
      h(
        "div",
        { class: "row end" },
        btn("Cancel", null, () => s.close(), "ghost"),
        btn(ok, null, () => {
          resolve(true);
          s.close();
        }, "primary"),
      ),
    );
  });
}

export interface SliderRow {
  el: HTMLElement;
  input: HTMLInputElement;
  set(v: number): void;
  setKeyState(state: "none" | "animated" | "onKey"): void;
}

export function slider(label: string, min: number, max: number, step: number, value: number, onInput: (v: number) => void, opts: { fmt?: (v: number) => string; onKey?: () => void; onReset?: () => void } = {}): SliderRow {
  const fmt = opts.fmt ?? ((v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(2)));
  const out = h("output", {}, fmt(value));
  const input = h("input", { type: "range", min, max, step, value, "aria-label": label }) as HTMLInputElement;
  input.addEventListener("input", () => {
    const v = Number(input.value);
    out.textContent = fmt(v);
    onInput(v);
  });
  const keyBtn = opts.onKey ? h("button", { class: "kf", type: "button", title: `Keyframe ${label}`, "aria-label": `Keyframe ${label}` }, icon("key", 16)) : null;
  keyBtn?.addEventListener("click", () => opts.onKey?.());
  const name = h("span", { class: "lbl" }, label);
  if (opts.onReset) name.addEventListener("dblclick", opts.onReset);
  const el = h("label", { class: "srow" }, name, input, out, keyBtn);
  return {
    el,
    input,
    set(v: number) {
      input.value = String(v);
      out.textContent = fmt(v);
    },
    setKeyState(state) {
      if (!keyBtn) return;
      keyBtn.classList.toggle("animated", state !== "none");
      keyBtn.classList.toggle("on", state === "onKey");
    },
  };
}

export function select<T extends string>(label: string, options: readonly (T | [T, string])[], value: T, onChange: (v: T) => void): HTMLLabelElement {
  const s = h("select", { "aria-label": label }) as HTMLSelectElement;
  for (const o of options) {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    s.append(h("option", { value: v, selected: v === value }, l));
  }
  s.addEventListener("change", () => onChange(s.value as T));
  return h("label", { class: "frow" }, h("span", { class: "lbl" }, label), s);
}

export function toggle(label: string, value: boolean, onChange: (v: boolean) => void): HTMLLabelElement {
  const i = h("input", { type: "checkbox", checked: value }) as HTMLInputElement;
  i.addEventListener("change", () => onChange(i.checked));
  return h("label", { class: "frow toggle" }, h("span", { class: "lbl" }, label), i);
}

export function colorInput(label: string, value: string, onChange: (v: string) => void): HTMLLabelElement {
  const i = h("input", { type: "color", value: /^#[0-9a-f]{6}$/i.test(value) ? value : "#ffffff" }) as HTMLInputElement;
  i.addEventListener("input", () => onChange(i.value));
  return h("label", { class: "frow" }, h("span", { class: "lbl" }, label), i);
}

export function fmtTime(t: number, fps = 30): string {
  const s = Math.max(0, t);
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  const f = Math.floor((s % 1) * fps);
  return `${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}:${String(f).padStart(2, "0")}`;
}

export function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

export function pickFiles(accept: string, multiple = true): Promise<File[]> {
  return new Promise((resolve) => {
    const i = h("input", { type: "file", accept, multiple }) as HTMLInputElement;
    i.addEventListener("change", () => resolve([...(i.files ?? [])]));
    i.click();
  });
}
