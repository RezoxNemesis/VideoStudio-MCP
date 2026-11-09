/** Small UI kit for the desktop shell (on top of the engine's h()/icon()). */
import { h, icon } from "../../../src/ui/dom";

export { h, icon };

const EXTRA: Record<string, string> = {
  min: "M5 12h14",
  max: "M5 5h14v14H5z",
  restore: "M8 8h11v11H8zM5 16V5h11",
  wave: "M2 12h2l2-6 3 12 3-15 3 18 3-12 2 3h2",
  folderOpen: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v1H3zM3 10h18l-2 9H5z",
  home: "M3 11 12 4l9 7v9h-6v-6H9v6H3z",
  search: "M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14zM20 20l-4-4",
  up: "M12 19V5M5 12l7-7 7 7",
  drive: "M3 14h18v5H3zM5 14l2-8h10l2 8M17 17h.01",
  stepB: "M18 6 10 12l8 6zM6 6v12",
  stepF: "M6 6l8 6-8 6zM18 6v12",
  start: "M19 6 9 12l10 6zM5 6v12",
  end: "M5 6l10 6-10 6zM19 6v12",
  magnet: "M6 4v8a6 6 0 0 0 12 0V4h-4v8a2 2 0 0 1-4 0V4zM6 8h4M14 8h4",
  marker: "M6 3h12v13l-6 5-6-5z",
  fit: "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5",
  claude: "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4",
  curve: "M3 20c6 0 6-16 12-16 3 0 4 4 6 4M3 4v16h18",
  gear: "M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1",
};

export function ic(name: string, size = 16): SVGSVGElement {
  if (!EXTRA[name]) return icon(name, size);
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.classList.add("ic");
  const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
  p.setAttribute("d", EXTRA[name]!);
  svg.append(p);
  return svg;
}

export function b(label: string | null, iconName: string | null, onClick: (e: MouseEvent) => void, cls = "", title?: string): HTMLButtonElement {
  const el = h("button", { class: `b ${cls} ${label ? "" : "ic"}`, type: "button", title: title ?? label ?? "" }) as HTMLButtonElement;
  if (iconName) el.append(ic(iconName, 15));
  if (label) el.append(document.createTextNode(label));
  el.addEventListener("click", (e) => {
    e.stopPropagation();
    onClick(e);
  });
  return el;
}

export function panel(title: string | HTMLElement, cls = "", ...actions: (HTMLElement | null)[]): { el: HTMLElement; body: HTMLElement; head: HTMLElement } {
  const head = h("div", { class: "p-head" }, typeof title === "string" ? h("span", {}, title) : title, h("span", { class: "sp" }), ...actions.filter(Boolean) as HTMLElement[]);
  const body = h("div", { class: "p-body" });
  const el = h("section", { class: `p ${cls}` }, head, body);
  return { el, body, head };
}

/** Tabs in a panel header row. */
export function tabs<T extends string>(items: [T, string][], value: T, onChange: (v: T) => void): { el: HTMLElement; set: (v: T) => void } {
  const el = h("div", { class: "p-tabs" });
  let cur = value;
  const draw = () =>
    el.replaceChildren(
      ...items.map(([id, label]) => {
        const bt = h("button", { class: id === cur ? "on" : "", type: "button" }, label);
        bt.addEventListener("click", () => {
          cur = id;
          draw();
          onChange(id);
        });
        return bt;
      }),
    );
  draw();
  return {
    el,
    set: (v) => {
      cur = v;
      draw();
    },
  };
}

export interface MenuItem {
  label: string;
  key?: string;
  run?: () => void;
  disabled?: boolean;
  sep?: boolean;
}

let openMenu: HTMLElement | null = null;
export function closeMenu(): void {
  openMenu?.remove();
  openMenu = null;
}
export function menu(items: MenuItem[], x: number, y: number): void {
  closeMenu();
  const m = h("div", { class: "menu" });
  for (const it of items) {
    if (it.sep) {
      m.append(h("hr"));
      continue;
    }
    const bt = h("button", { type: "button" }, it.label, it.key ? h("span", { class: "k" }, it.key) : null) as HTMLButtonElement;
    bt.disabled = !!it.disabled;
    bt.addEventListener("click", () => {
      closeMenu();
      it.run?.();
    });
    m.append(bt);
  }
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = `${Math.min(x, innerWidth - r.width - 6)}px`;
  m.style.top = `${Math.min(y, innerHeight - r.height - 6)}px`;
  openMenu = m;
  setTimeout(() => {
    const off = (e: MouseEvent) => {
      if (!m.contains(e.target as Node)) {
        closeMenu();
        removeEventListener("mousedown", off, true);
      }
    };
    addEventListener("mousedown", off, true);
  });
}

export function modal(title: string, body: HTMLElement, actions: HTMLElement[] = []): { close: () => void } {
  const bg = h("div", { class: "modal-bg" });
  const close = () => bg.remove();
  const box = h("div", { class: "modal" }, h("h3", {}, title), body, actions.length ? h("div", { class: "row", style: "justify-content:flex-end;margin-top:14px" }, ...actions) : null);
  bg.append(box);
  bg.addEventListener("mousedown", (e) => e.target === bg && close());
  document.body.append(bg);
  return { close };
}

export function ask(title: string, label: string, value = ""): Promise<string | null> {
  return new Promise((res) => {
    const inp = h("input", { class: "inp", value, style: "width:100%" }) as HTMLInputElement;
    const m = modal(title, h("div", { class: "col" }, h("span", { class: "muted" }, label), inp), [
      b("Cancel", null, () => {
        m.close();
        res(null);
      }),
      b("OK", null, () => {
        m.close();
        res(inp.value);
      }, "pri"),
    ]);
    inp.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        m.close();
        res(inp.value);
      }
      if (e.key === "Escape") {
        m.close();
        res(null);
      }
    });
    setTimeout(() => inp.focus());
  });
}

/** Number slider row with a typed value and an optional keyframe button. */
export function numRow(o: {
  label: string;
  min: number;
  max: number;
  step: number;
  value: number;
  fmt?: (v: number) => string;
  onInput: (v: number) => void;
  onCommit?: () => void;
  key?: { state: "none" | "anim" | "here"; toggle: () => void };
}): HTMLElement {
  const range = h("input", { type: "range", min: String(o.min), max: String(o.max), step: String(o.step) }) as HTMLInputElement;
  const num = h("input", { class: "num", type: "text" }) as HTMLInputElement;
  const fill = (v: number) => range.style.setProperty("--fill", `${((v - o.min) / (o.max - o.min)) * 100}%`);
  const show = (v: number) => {
    range.value = String(v);
    num.value = o.fmt ? o.fmt(v) : String(Math.round(v * 1000) / 1000);
    fill(v);
  };
  show(o.value);
  range.addEventListener("input", () => {
    const v = Number(range.value);
    num.value = o.fmt ? o.fmt(v) : String(Math.round(v * 1000) / 1000);
    fill(v);
    o.onInput(v);
  });
  range.addEventListener("change", () => o.onCommit?.());
  num.addEventListener("keydown", (e) => {
    if (e.key === "Enter") num.blur();
    e.stopPropagation();
  });
  num.addEventListener("change", () => {
    const v = parseFloat(num.value);
    if (Number.isFinite(v)) {
      show(v);
      o.onInput(v);
      o.onCommit?.();
    } else show(Number(range.value));
  });
  const lab = h("label", { title: "Drag to change, double-click to reset" }, o.label);
  // Scrub the label like a pro app: drag left/right.
  lab.addEventListener("mousedown", (e) => {
    const x0 = e.clientX;
    const v0 = Number(range.value);
    const span = o.max - o.min;
    const mv = (ev: MouseEvent) => {
      let v = v0 + ((ev.clientX - x0) / 260) * span * (ev.shiftKey ? 0.2 : 1);
      v = Math.max(o.min, Math.min(o.max, Math.round(v / o.step) * o.step));
      show(v);
      o.onInput(v);
    };
    const up = () => {
      removeEventListener("mousemove", mv);
      removeEventListener("mouseup", up);
      o.onCommit?.();
    };
    addEventListener("mousemove", mv);
    addEventListener("mouseup", up);
  });
  const kf = h("button", { class: `kf ${o.key ? (o.key.state === "none" ? "" : o.key.state) : ""}`, type: "button", title: o.key ? "Add / remove a keyframe here" : "", style: o.key ? "" : "visibility:hidden" });
  if (o.key) kf.addEventListener("click", () => o.key!.toggle());
  return h("div", { class: "prop" }, lab, range, num, kf);
}

export function field(label: string, control: HTMLElement): HTMLElement {
  return h("div", { class: "prop2" }, h("label", {}, label), control);
}

export function selectEl<T extends string>(options: readonly (T | [T, string])[], value: T, onChange: (v: T) => void): HTMLSelectElement {
  const s = h("select", { class: "sel" }) as HTMLSelectElement;
  for (const o of options) {
    const [v, l] = Array.isArray(o) ? o : [o, o];
    const op = h("option", { value: v }, l) as HTMLOptionElement;
    if (v === value) op.selected = true;
    s.append(op);
  }
  s.addEventListener("change", () => onChange(s.value as T));
  return s;
}

export function tc(t: number, fps: number): string {
  const sign = t < 0 ? "-" : "";
  t = Math.abs(t);
  const f = Math.floor((t % 1) * fps + 1e-6);
  const s = Math.floor(t) % 60;
  const m = Math.floor(t / 60) % 60;
  const hh = Math.floor(t / 3600);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${sign}${p(hh)}:${p(m)}:${p(s)}:${p(f)}`;
}
