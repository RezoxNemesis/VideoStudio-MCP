/**
 * The code assistant of Code Studio: completion that knows the film kit (every function with its parameters and its
 * note) and the catalogues ids come from (people, places, props, sounds, looks, emotions, the 2D library, body parts,
 * the film's own lines and actors); notes on hover; checks while you type (syntax, names vs.js does not have, ids that
 * do not exist, the film's load error at its line, and Sensei's findings on the shots they belong to).
 */
import { type Completion, type CompletionContext, type CompletionResult } from "@codemirror/autocomplete";
import { javascriptLanguage } from "@codemirror/lang-javascript";
import { syntaxTree } from "@codemirror/language";
import { type Diagnostic, linter, lintGutter } from "@codemirror/lint";
import type { Extension } from "@codemirror/state";
import { EditorView, hoverTooltip } from "@codemirror/view";
import type { KitIndex } from "./types";
import type { SenseiIssue } from "../sensei/types";

export interface AssistContext {
  index(): KitIndex | null;
  /** the file open in the editor */
  file(): string | null;
  /** script.json lines: id → who */
  lines(): Record<string, string>;
  /** the film's actor ids (from its sequences) */
  actors(): string[];
  /** music cue names (cues.json) */
  cues(): string[];
  /** the film's load error, if it does not load */
  error(): string | null;
  /** Sensei's latest findings */
  findings(): SenseiIssue[];
  /** the sequence id of the open file (seq/<id>.js) */
  seq(): string | null;
  /** what to do with a finding (open it in Sensei, paint a fix) */
  act(kind: "sensei" | "paint", issue: SenseiIssue): void;
}

const GAITS = ["walk", "stroll", "march", "jog", "run", "sneak"];
const TIMES = ["dawn", "day", "dusk", "night"];
const OPS = ["stage.do", "stage.overlay", "stage.fight"];
const EYES = ["open", "half", "closed", "happy", "angry", "sad", "surprised"];
const MOUTHS = ["rest", "A", "E", "I", "O", "U", "M", "F", "smile", "grin", "frown", "shout"];
const POSES = ["stand", "wave", "point", "cheer", "think", "shrug", "hips", "surprised", "crossed", "sad", "angry", "run"];

/** What kind of id a string at this place wants: from the text just before the string starts. */
function stringKind(before: string): string | null {
  const b = before.slice(-80);
  if (/\b(person|presetOf)\(\s*$/.test(b)) return "people";
  if (/\b(parallax|standOn)\(\s*$/.test(b)) return "bg";
  if (/\bcutoutActor\(\s*$/.test(b)) return "char";
  if (/\b(sticker|pop|asset2d)\(\s*$/.test(b) || /\bdrawAsset\(\s*\w+\s*,\s*$/.test(b)) return "asset";
  if (/\bput\(\s*\w+\s*,\s*$/.test(b)) return "props";
  if (/\btime\s*:\s*$/.test(b)) return "time";
  if (/\blook\s*:\s*$/.test(b) && !/look\s*:\s*\[\s*\[\s*[\d.]+\s*,\s*$/.test(b)) return "look";
  if (/\bgait\s*:\s*$/.test(b)) return "gait";
  if (/\bsfx\s*:\s*$/.test(b)) return "sfx";
  if (/\bamb\s*:\s*$/.test(b)) return "amb";
  if (/\bmusic\s*:\s*$/.test(b)) return "music";
  if (/\[\s*'say'\s*,\s*$/.test(b) || /\bT\.[te]\(\s*$/.test(b)) return "line";
  if (/\bemote\s*:\s*\[.*\[\s*[\d.T()'\w\s+-]*,\s*$/.test(b) || /\blike\s*:\s*$/.test(b)) return "emotion";
  if (/\b(actor|look)\s*:\s*$/.test(b) || /\bat\(\s*ctx\s*,\s*$/.test(b) || /\blook\s*:\s*\[.*\[\s*[\d.T()'\w\s+-]*,\s*$/.test(b) || /\b(hide|only)\s*:\s*\[\s*(?:'[^']*'\s*,\s*)*$/.test(b)) return "actor";
  if (/\bop\s*:\s*$/.test(b)) return "op";
  if (/\bpart\s*:\s*$/.test(b)) return "part";
  if (/\beyes\s*:\s*$/.test(b)) return "eyes";
  if (/\bmouth\s*:\s*$/.test(b)) return "mouth";
  if (/\bCUTOUT_POSES\.\s*$/.test(b)) return "pose";
  return null;
}
function idsOf(kind: string, ix: KitIndex, cx: AssistContext): { label: string; detail?: string }[] {
  const s = ix.sounds as Record<string, string[]>;
  switch (kind) {
    case "people": return [...ix.people, ...ix.cast].map((x) => ({ label: x }));
    case "bg": return ix.library.filter((a) => a.cat === "backgrounds").map((a) => ({ label: a.id, detail: a.name }));
    case "char": return ix.library.filter((a) => a.cat === "characters").map((a) => ({ label: a.id, detail: a.name }));
    case "asset": return ix.library.map((a) => ({ label: a.id, detail: `${a.cat} · ${a.name}` }));
    case "props": return ix.props.map((x) => ({ label: x }));
    case "time": return TIMES.map((x) => ({ label: x }));
    case "look": return ix.looks.map((x) => ({ label: x }));
    case "gait": return GAITS.map((x) => ({ label: x }));
    case "sfx": return (s.sfx ?? []).map((x) => ({ label: x }));
    case "amb": return (s.amb ?? []).map((x) => ({ label: x }));
    case "music": return cx.cues().map((x) => ({ label: x }));
    case "line": return Object.entries(cx.lines()).map(([id, who]) => ({ label: id, detail: who }));
    case "emotion": return ix.emotions.map((x) => ({ label: x }));
    case "actor": return cx.actors().map((x) => ({ label: x }));
    case "op": return OPS.map((x) => ({ label: x }));
    case "part": return ix.parts.map((x) => ({ label: x }));
    case "eyes": return EYES.map((x) => ({ label: x }));
    case "mouth": return MOUTHS.map((x) => ({ label: x }));
  }
  return [];
}

function completion(cx: AssistContext) {
  return (ctx: CompletionContext): CompletionResult | null => {
    const ix = cx.index();
    if (!ix) return null;
    const node = syntaxTree(ctx.state).resolveInner(ctx.pos, -1);
    // inside a string: ids from the catalogue the call or key wants
    if (node.name === "String") {
      const quote = ctx.state.sliceDoc(node.from, node.from + 1), before = ctx.state.sliceDoc(Math.max(0, node.from - 120), node.from), kind = stringKind(before);
      if (!kind) return null;
      const opts = idsOf(kind, ix, cx).map((o): Completion => ({ label: o.label, detail: o.detail, type: "constant", apply: o.label }));
      return opts.length ? { from: node.from + 1, to: ctx.state.sliceDoc(node.to - 1, node.to) === quote && node.to - node.from > 1 ? node.to - 1 : ctx.pos, options: opts, validFor: /^[\w.\- ]*$/ } : null;
    }
    // after CUTOUT_POSES.
    const dot = ctx.matchBefore(/CUTOUT_POSES\.\w*/);
    if (dot) return { from: dot.from + "CUTOUT_POSES.".length, options: POSES.map((p) => ({ label: p, type: "property" })) };
    // a name: everything the kit exports, with its parameters and note
    const w = ctx.matchBefore(/[A-Za-z_$][\w$]*/);
    if (!w || (w.from === w.to && !ctx.explicit)) return null;
    if (node.name === "PropertyName" || node.name === "LineComment" || node.name === "BlockComment") return null;
    const before = ctx.state.sliceDoc(Math.max(0, w.from - 1), w.from);
    if (before === ".") return null;
    return {
      from: w.from,
      options: ix.exports.map((e): Completion => ({ label: e.name, type: e.kind === "fn" ? "function" : e.kind === "class" ? "class" : "variable", detail: e.sig.length > 60 ? e.sig.slice(0, 58) + "…" : e.sig.slice(e.name.length), info: e.doc ? `${e.sig}\n\n${e.doc}` : e.sig, boost: e.doc ? 1 : 0 })),
      validFor: /^[\w$]*$/,
    };
  };
}

function hover(cx: AssistContext) {
  return hoverTooltip((view, pos) => {
    const ix = cx.index();
    if (!ix) return null;
    const { from, to, text } = view.state.doc.lineAt(pos);
    let a = pos, b = pos;
    while (a > from && /[\w$]/.test(text[a - from - 1]!)) a--;
    while (b < to && /[\w$]/.test(text[b - from]!)) b++;
    const word = text.slice(a - from, b - from), e = word && ix.exports.find((x) => x.name === word);
    if (!e) return null;
    return {
      pos: a,
      end: b,
      above: true,
      create: () => {
        const dom = document.createElement("div");
        dom.className = "cs-tip";
        const sig = document.createElement("code");
        sig.textContent = e.sig;
        dom.append(sig);
        if (e.doc) {
          const p = document.createElement("p");
          p.textContent = e.doc;
          dom.append(p);
        }
        if (e.file) {
          const f = document.createElement("small");
          f.textContent = `film kit · ${e.file}`;
          dom.append(f);
        }
        return { dom };
      },
    };
  });
}

/** Checks while you type. */
function checks(cx: AssistContext) {
  return linter((view) => {
    const out: Diagnostic[] = [], doc = view.state.doc, text = doc.toString(), ix = cx.index(), file = cx.file() ?? "";
    if (!/\.m?js$/.test(file)) {
      if (/\.json$/.test(file)) {
        try {
          JSON.parse(text);
        } catch (e) {
          const m = /position (\d+)/.exec(String(e));
          const at = m ? Math.min(text.length, Number(m[1])) : 0;
          out.push({ from: at, to: Math.min(text.length, at + 1), severity: "error", message: `Not valid JSON: ${e instanceof Error ? e.message : e}` });
        }
      }
      return out;
    }
    // syntax: what the parser could not read
    syntaxTree(view.state).iterate({ enter: (n) => { if (n.type.isError && out.length < 20) out.push({ from: n.from, to: Math.max(n.to, Math.min(doc.length, n.from + 1)), severity: "error", message: "The code cannot be read here (a missing bracket, comma or quote?)." }); } });
    if (ix) {
      const names = new Set(ix.exports.map((e) => e.name));
      // names taken from vs.js that the kit does not have
      for (const m of text.matchAll(/import\s*\{([^}]*)\}\s*from\s*['"](\.\.?\/)+vs\.js['"]/g)) {
        const start = m.index! + m[0].indexOf("{") + 1;
        for (const part of m[1]!.split(",")) {
          const nm = part.trim().split(/\s+as\s+/)[0]!.trim();
          if (!nm || names.has(nm)) continue;
          const at = start + m[1]!.indexOf(part) + part.indexOf(nm), near = [...names].filter((x) => x.toLowerCase().startsWith(nm.slice(0, 3).toLowerCase())).slice(0, 4);
          out.push({ from: at, to: at + nm.length, severity: "error", message: `The film kit has no ${nm}.${near.length ? ` Did you mean ${near.join(", ")}?` : ""}` });
        }
      }
      // ids that are not in their catalogue
      const check = (re: RegExp, kind: string, what: string) => {
        const known = new Set(idsOf(kind, ix, cx).map((o) => o.label));
        if (!known.size) return;
        for (const m of text.matchAll(re)) {
          const id = m[2]!;
          if (known.has(id)) continue;
          const at = m.index! + m[0].lastIndexOf(id);
          out.push({ from: at, to: at + id.length, severity: kind === "line" ? "error" : "warning", message: `No ${what} "${id}".` });
        }
      };
      check(/\b(person|presetOf)\(\s*['"]([^'"]+)['"]/g, "people", "library person");
      check(/\b(parallax|standOn)\(\s*['"]([^'"]+)['"]/g, "bg", "layered background");
      check(/\b(cutoutActor)\(\s*['"]([^'"]+)['"]/g, "char", "cut-out character");
      check(/\b(sticker|pop)\(\s*['"]([^'"]+)['"]/g, "asset", "2D library asset");
      check(/(\[\s*'say'\s*,|T\.[te]\()\s*['"]([^'"]+)['"]/g, "line", "line in script.json");
      check(/\b(sfx)\s*:\s*['"]([^'"]+)['"]/g, "sfx", "sound effect");
      check(/\b(amb)\s*:\s*['"]([^'"]+)['"]/g, "amb", "ambience");
    }
    // the film's own load error, at its line
    const err = cx.error();
    if (err && file) {
      const esc = file.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), m = new RegExp(`${esc}:(\\d+)(?::(\\d+))?`).exec(err);
      if (m) {
        const ln = doc.line(Math.min(doc.lines, Math.max(1, Number(m[1])))), col = m[2] ? Math.min(ln.length, Number(m[2]) - 1) : 0;
        out.push({ from: ln.from + col, to: ln.to, severity: "error", message: err.split("\n")[0]!.slice(0, 400), source: "film" });
      }
    }
    // Sensei's findings on the shots this file defines
    const seq = cx.seq();
    if (seq) {
      const fs = cx.findings().filter((i) => i.seq === seq || i.shot?.startsWith(`${seq}_`));
      const shotsArr = text.indexOf("shots:");
      for (const m of text.matchAll(/\bid\s*:\s*['"]([\w-]+)['"]/g)) {
        if (shotsArr < 0 || m.index! < shotsArr) continue;
        const list = fs.filter((i) => i.shot === `${seq}_${m[1]}`);
        if (!list.length) continue;
        const must = list.filter((i) => i.sev === "must"), sev = must.length ? "error" : list.some((i) => i.sev === "should") ? "warning" : "info";
        const worst = [...must, ...list.filter((i) => i.sev !== "must")].slice(0, 4);
        out.push({
          from: m.index!, to: m.index! + m[0].length, severity: sev, source: "Sensei",
          message: `Sensei: ${must.length} MUST, ${list.length - must.length} more in shot ${m[1]}.\n${worst.map((i) => `• ${i.msg}${i.fix ? ` → ${i.fix}` : ""}`).join("\n")}`,
          actions: [
            { name: "Open in Sensei", apply: () => cx.act("sensei", worst[0]!) },
            ...(worst[0]!.actor ? [{ name: "Fix by painting", apply: () => cx.act("paint", worst[0]!) }] : []),
          ],
        });
      }
    }
    return out;
  }, { delay: 500 });
}

/** The assistant as editor extensions. */
export function assistant(cx: AssistContext): Extension[] {
  return [
    javascriptLanguage.data.of({ autocomplete: completion(cx) }),
    hover(cx),
    checks(cx),
    lintGutter(),
    EditorView.theme({ ".cs-tip": { maxWidth: "520px", padding: "8px 10px", fontSize: "12px", lineHeight: "1.45" }, ".cs-tip code": { display: "block", fontFamily: "var(--mono)", color: "#8fe3cf", marginBottom: "4px", whiteSpace: "pre-wrap" }, ".cs-tip p": { margin: "0 0 4px" }, ".cs-tip small": { opacity: "0.55" } }),
  ];
}

/* ---- outline: sequences, shots and functions of a file, with their lines ---- */
export function outline(text: string): { label: string; line: number; kind: "seq" | "shot" | "fn" | "part" }[] {
  const out: { label: string; line: number; kind: "seq" | "shot" | "fn" | "part" }[] = [], lines = text.split("\n");
  let inShots = false;
  lines.forEach((l, i) => {
    let m: RegExpExecArray | null;
    if ((m = /export\s+const\s+(\w+)\s*=\s*makeSeq\(/.exec(l))) out.push({ label: `sequence ${m[1]}`, line: i + 1, kind: "seq" });
    else if ((m = /^\s*(script|set|motion|actors|shots|sound|cues)\s*:/.exec(l))) {
      inShots = m[1] === "shots";
      out.push({ label: `  ${m[1]}`, line: i + 1, kind: "part" });
    } else if (inShots && (m = /\{\s*id\s*:\s*['"]([\w-]+)['"]/.exec(l))) {
      const note = /note\s*:\s*['"]([^'"]+)['"]/.exec(l);
      out.push({ label: `    shot ${m[1]}${note ? ` — ${note[1]!.slice(0, 40)}` : ""}`, line: i + 1, kind: "shot" });
    } else if ((m = /^(?:export\s+)?(?:async\s+)?function\s+(\w+)|^(?:export\s+)?const\s+(\w+)\s*=\s*(?:\([^)]*\)|\w+)\s*=>/.exec(l))) out.push({ label: `ƒ ${m[1] ?? m[2]}`, line: i + 1, kind: "fn" });
  });
  return out;
}

/* ---- a line diff (for the history of a file) ---- */
export function diffLines(a: string, b: string): { op: " " | "-" | "+"; text: string }[] {
  const A = a.split("\n"), B = b.split("\n"), n = A.length, m = B.length;
  if (n * m > 4_000_000) return [{ op: "-", text: `(${n} lines)` }, { op: "+", text: `(${m} lines) — too long to compare line by line` }];
  const L = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i]![j] = A[i] === B[j] ? L[i + 1]![j + 1]! + 1 : Math.max(L[i + 1]![j]!, L[i]![j + 1]!);
  const out: { op: " " | "-" | "+"; text: string }[] = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (A[i] === B[j]) (out.push({ op: " ", text: A[i]! }), i++, j++);
    else if (L[i + 1]![j]! >= L[i]![j + 1]!) out.push({ op: "-", text: A[i++]! });
    else out.push({ op: "+", text: B[j++]! });
  }
  while (i < n) out.push({ op: "-", text: A[i++]! });
  while (j < m) out.push({ op: "+", text: B[j++]! });
  return out;
}
