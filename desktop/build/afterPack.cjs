// The film engine of Code Studio runs as plain Node processes beside the app and needs its own canvas library.
// electron-builder leaves node_modules out of extra resources, so it is copied here after the app is put together.
const fs = require("node:fs");
const path = require("node:path");
exports.default = async function afterPack(ctx) {
  const src = path.join(__dirname, "..", "film", "node_modules"), dst = path.join(ctx.appOutDir, "resources", "film", "node_modules");
  if (!fs.existsSync(path.join(src, "@napi-rs", "canvas"))) throw new Error("desktop/film/node_modules is missing: run `npm install --prefix film` in desktop/ first.");
  fs.cpSync(src, dst, { recursive: true });
  console.log("  • film engine: canvas library copied");
};
