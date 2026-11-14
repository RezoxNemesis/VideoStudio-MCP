// The film engine of Code Studio and the studio voices run as plain Node processes beside the app, with their own
// native libraries. electron-builder leaves node_modules out of extra resources, so they are copied here after the
// app is put together.
const fs = require("node:fs");
const path = require("node:path");

/** onnxruntime-node ships every platform and the CUDA / TensorRT providers; this package needs Linux x64 on the CPU. */
const skipVoice = (rel) =>
  /onnxruntime-node[/\\]bin[/\\]napi-v\d+[/\\](darwin|win32)([/\\]|$)/.test(rel) ||
  /onnxruntime-node[/\\]bin[/\\]napi-v\d+[/\\]linux[/\\]arm64([/\\]|$)/.test(rel) ||
  /libonnxruntime_providers_(cuda|tensorrt)\.so$/.test(rel);

exports.default = async function afterPack(ctx) {
  const res = path.join(ctx.appOutDir, "resources");
  const film = path.join(__dirname, "..", "film", "node_modules");
  if (!fs.existsSync(path.join(film, "@napi-rs", "canvas"))) throw new Error("desktop/film/node_modules is missing: run `npm install --prefix film` in desktop/ first.");
  fs.cpSync(film, path.join(res, "film", "node_modules"), { recursive: true });
  console.log("  • film engine: canvas library copied");

  const voice = path.join(__dirname, "..", "voice", "node_modules");
  if (!fs.existsSync(path.join(voice, "onnxruntime-node", "package.json"))) throw new Error("desktop/voice/node_modules is missing: run `npm install --prefix voice` in desktop/ first.");
  fs.cpSync(voice, path.join(res, "voice", "node_modules"), { recursive: true, filter: (src) => !skipVoice(path.relative(voice, src)) });
  console.log("  • studio voices: onnxruntime-node (linux x64, CPU) copied");
};
