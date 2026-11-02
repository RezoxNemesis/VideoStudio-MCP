/**
 * Bundled font library (self-hosted from @fontsource; works offline on the phone). Names are the CSS family
 * names; fontStack() turns a name (case-insensitive, dashes or spaces) into a CSS font stack.
 */
import "@fontsource/anton/latin-400.css";
import "@fontsource/bebas-neue/latin-400.css";
import "@fontsource/bangers/latin-400.css";
import "@fontsource/archivo-black/latin-400.css";
import "@fontsource/oswald/latin-400.css";
import "@fontsource/oswald/latin-700.css";
import "@fontsource/russo-one/latin-400.css";
import "@fontsource/black-ops-one/latin-400.css";
import "@fontsource/bungee/latin-400.css";
import "@fontsource/righteous/latin-400.css";
import "@fontsource/teko/latin-400.css";
import "@fontsource/teko/devanagari-400.css";
import "@fontsource/teko/latin-700.css";
import "@fontsource/teko/devanagari-700.css";
import "@fontsource/monoton/latin-400.css";
import "@fontsource/permanent-marker/latin-400.css";
import "@fontsource/rubik-mono-one/latin-400.css";
import "@fontsource/staatliches/latin-400.css";
import "@fontsource/bowlby-one-sc/latin-400.css";
import "@fontsource/dela-gothic-one/latin-400.css";
import "@fontsource/mochiy-pop-one/latin-400.css";
import "@fontsource/playfair-display/latin-400.css";
import "@fontsource/playfair-display/latin-900.css";
import "@fontsource/cinzel/latin-400.css";
import "@fontsource/cinzel/latin-900.css";
import "@fontsource/cormorant-garamond/latin-400.css";
import "@fontsource/cormorant-garamond/latin-700.css";
import "@fontsource/abril-fatface/latin-400.css";
import "@fontsource/dm-serif-display/latin-400.css";
import "@fontsource/lora/latin-400.css";
import "@fontsource/lora/latin-700.css";
import "@fontsource/fraunces/latin-400.css";
import "@fontsource/fraunces/latin-900.css";
import "@fontsource/pacifico/latin-400.css";
import "@fontsource/great-vibes/latin-400.css";
import "@fontsource/dancing-script/latin-400.css";
import "@fontsource/dancing-script/latin-700.css";
import "@fontsource/caveat/latin-400.css";
import "@fontsource/caveat/latin-700.css";
import "@fontsource/satisfy/latin-400.css";
import "@fontsource/lobster/latin-400.css";
import "@fontsource/sacramento/latin-400.css";
import "@fontsource/inter/latin-400.css";
import "@fontsource/inter/latin-900.css";
import "@fontsource/montserrat/latin-400.css";
import "@fontsource/montserrat/latin-900.css";
import "@fontsource/poppins/latin-400.css";
import "@fontsource/poppins/devanagari-400.css";
import "@fontsource/poppins/latin-900.css";
import "@fontsource/poppins/devanagari-900.css";
import "@fontsource/space-grotesk/latin-400.css";
import "@fontsource/space-grotesk/latin-700.css";
import "@fontsource/syne/latin-400.css";
import "@fontsource/syne/latin-800.css";
import "@fontsource/unbounded/latin-400.css";
import "@fontsource/unbounded/latin-900.css";
import "@fontsource/sora/latin-400.css";
import "@fontsource/sora/latin-800.css";
import "@fontsource/bricolage-grotesque/latin-400.css";
import "@fontsource/bricolage-grotesque/latin-800.css";
import "@fontsource/rubik-glitch/latin-400.css";
import "@fontsource/rubik-wet-paint/latin-400.css";
import "@fontsource/creepster/latin-400.css";
import "@fontsource/nosifer/latin-400.css";
import "@fontsource/press-start-2p/latin-400.css";
import "@fontsource/vt323/latin-400.css";
import "@fontsource/orbitron/latin-400.css";
import "@fontsource/orbitron/latin-900.css";
import "@fontsource/audiowide/latin-400.css";
import "@fontsource/major-mono-display/latin-400.css";
import "@fontsource/hind/latin-400.css";
import "@fontsource/hind/devanagari-400.css";
import "@fontsource/hind/latin-700.css";
import "@fontsource/hind/devanagari-700.css";
import "@fontsource/mukta/latin-400.css";
import "@fontsource/mukta/devanagari-400.css";
import "@fontsource/mukta/latin-800.css";
import "@fontsource/mukta/devanagari-800.css";
import "@fontsource/rozha-one/latin-400.css";
import "@fontsource/rozha-one/devanagari-400.css";
import "@fontsource/kalam/latin-400.css";
import "@fontsource/kalam/devanagari-400.css";
import "@fontsource/kalam/latin-700.css";
import "@fontsource/kalam/devanagari-700.css";
import "@fontsource/yatra-one/latin-400.css";
import "@fontsource/yatra-one/devanagari-400.css";
import "@fontsource/tiro-devanagari-hindi/latin-400.css";
import "@fontsource/tiro-devanagari-hindi/devanagari-400.css";

export interface FontInfo {
  name: string;
  category: "display" | "serif" | "script" | "sans" | "street" | "hindi" | "core";
  weights: number[];
}

export const FONTS: FontInfo[] = [
  { name: "Geist Variable", category: "core", weights: [100, 900] },
  { name: "Instrument Serif", category: "core", weights: [400] },
  { name: "Anton", category: "display", weights: [400] },
  { name: "Bebas Neue", category: "display", weights: [400] },
  { name: "Bangers", category: "display", weights: [400] },
  { name: "Archivo Black", category: "display", weights: [400] },
  { name: "Oswald", category: "display", weights: [400, 700] },
  { name: "Russo One", category: "display", weights: [400] },
  { name: "Black Ops One", category: "display", weights: [400] },
  { name: "Bungee", category: "display", weights: [400] },
  { name: "Righteous", category: "display", weights: [400] },
  { name: "Teko", category: "display", weights: [400, 700] },
  { name: "Monoton", category: "display", weights: [400] },
  { name: "Permanent Marker", category: "display", weights: [400] },
  { name: "Rubik Mono One", category: "display", weights: [400] },
  { name: "Staatliches", category: "display", weights: [400] },
  { name: "Bowlby One SC", category: "display", weights: [400] },
  { name: "Dela Gothic One", category: "display", weights: [400] },
  { name: "Mochiy Pop One", category: "display", weights: [400] },
  { name: "Playfair Display", category: "serif", weights: [400, 900] },
  { name: "Cinzel", category: "serif", weights: [400, 900] },
  { name: "Cormorant Garamond", category: "serif", weights: [400, 700] },
  { name: "Abril Fatface", category: "serif", weights: [400] },
  { name: "DM Serif Display", category: "serif", weights: [400] },
  { name: "Lora", category: "serif", weights: [400, 700] },
  { name: "Fraunces", category: "serif", weights: [400, 900] },
  { name: "Pacifico", category: "script", weights: [400] },
  { name: "Great Vibes", category: "script", weights: [400] },
  { name: "Dancing Script", category: "script", weights: [400, 700] },
  { name: "Caveat", category: "script", weights: [400, 700] },
  { name: "Satisfy", category: "script", weights: [400] },
  { name: "Lobster", category: "script", weights: [400] },
  { name: "Sacramento", category: "script", weights: [400] },
  { name: "Inter", category: "sans", weights: [400, 900] },
  { name: "Montserrat", category: "sans", weights: [400, 900] },
  { name: "Poppins", category: "sans", weights: [400, 900] },
  { name: "Space Grotesk", category: "sans", weights: [400, 700] },
  { name: "Syne", category: "sans", weights: [400, 800] },
  { name: "Unbounded", category: "sans", weights: [400, 900] },
  { name: "Sora", category: "sans", weights: [400, 800] },
  { name: "Bricolage Grotesque", category: "sans", weights: [400, 800] },
  { name: "Rubik Glitch", category: "street", weights: [400] },
  { name: "Rubik Wet Paint", category: "street", weights: [400] },
  { name: "Creepster", category: "street", weights: [400] },
  { name: "Nosifer", category: "street", weights: [400] },
  { name: "Press Start 2P", category: "street", weights: [400] },
  { name: "VT323", category: "street", weights: [400] },
  { name: "Orbitron", category: "street", weights: [400, 900] },
  { name: "Audiowide", category: "street", weights: [400] },
  { name: "Major Mono Display", category: "street", weights: [400] },
  { name: "Hind", category: "hindi", weights: [400, 700] },
  { name: "Mukta", category: "hindi", weights: [400, 800] },
  { name: "Rozha One", category: "hindi", weights: [400] },
  { name: "Kalam", category: "hindi", weights: [400, 700] },
  { name: "Yatra One", category: "hindi", weights: [400] },
  { name: "Tiro Devanagari Hindi", category: "hindi", weights: [400] },
];

const norm = (s: string) => s.toLowerCase().replace(/[-_\s]+/g, "");

/** CSS font stack for a library font name (falls back to the name itself, then system fonts). */
export function fontStack(name: string): string {
  const hit = FONTS.find((f) => norm(f.name) === norm(name) || norm(f.name.replace(/ Variable$/, "")) === norm(name));
  const fam = hit?.name ?? name;
  const fallback = hit?.category === "serif" ? "Georgia, serif" : hit?.category === "script" ? "cursive" : hit?.category === "hindi" ? "'Noto Sans Devanagari', sans-serif" : "system-ui, sans-serif";
  return `"${fam}", ${fallback}`;
}

export function findFont(name: string): FontInfo | undefined {
  return FONTS.find((f) => norm(f.name) === norm(name));
}

/** Make sure every library font is ready for canvas drawing (call before rendering/exporting). */
let ready: Promise<void> | null = null;
export function loadFonts(): Promise<void> {
  if (typeof document === "undefined" || !document.fonts) return Promise.resolve();
  ready ??= Promise.all(
    FONTS.flatMap((f) => f.weights.map((w) => document.fonts.load(`${w} 32px "${f.name}"`, f.category === "hindi" ? "अआ Aa" : "Aa").catch(() => []))),
  ).then(() => undefined);
  return ready;
}
