import type { Plugin } from "vite";
import { defineConfig } from "vite";
import { copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(__dirname, "..");
const OUT = resolve(__dirname, "dist-web");

/** Same self-hosted ONNX Runtime files as the phone build (no CDN at runtime). */
function selfHostOrt(): Plugin {
  const files = ["ort-wasm-simd-threaded.jsep.mjs", "ort-wasm-simd-threaded.jsep.wasm", "ort-wasm-simd-threaded.mjs", "ort-wasm-simd-threaded.wasm"];
  const src = (f: string) => resolve(ROOT, "node_modules/onnxruntime-web/dist", f);
  return {
    name: "vsd-self-host-ort",
    configureServer(server) {
      server.middlewares.use("/ort/", (req, res, next) => {
        const name = (req.url ?? "").split("?")[0]!.replace(/^\//, "");
        if (!files.includes(name)) return next();
        res.setHeader("Content-Type", name.endsWith(".wasm") ? "application/wasm" : "text/javascript");
        import("node:fs").then((fs) => fs.createReadStream(src(name)).pipe(res));
      });
    },
    closeBundle() {
      const out = resolve(OUT, "ort");
      mkdirSync(out, { recursive: true });
      for (const f of files) if (existsSync(src(f))) copyFileSync(src(f), resolve(out, f));
      const assets = resolve(OUT, "assets");
      if (existsSync(assets)) for (const f of readdirSync(assets)) if (/^ort-wasm.*\.wasm$/.test(f)) rmSync(resolve(assets, f));
      // Icons from the phone app's public folder.
      for (const f of ["favicon.svg", "logo.svg", "icon-512.png"]) if (existsSync(resolve(ROOT, "public", f))) cpSync(resolve(ROOT, "public", f), resolve(OUT, f));
    },
  };
}

const isolation = { "Cross-Origin-Opener-Policy": "same-origin", "Cross-Origin-Embedder-Policy": "credentialless" };

export default defineConfig({
  root: __dirname,
  base: "./",
  publicDir: false,
  server: { headers: isolation, port: 5199, fs: { allow: [ROOT] } },
  plugins: [selfHostOrt()],
  build: { outDir: OUT, emptyOutDir: true, target: "es2022", sourcemap: false, chunkSizeWarningLimit: 4000 },
  worker: { format: "es" },
});
