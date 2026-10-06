import fs from "node:fs";

const app = fs.readFileSync(new URL("../src/app.html", import.meta.url), "utf8");
const worker = fs.readFileSync(new URL("../src/index.js", import.meta.url), "utf8");
const wrangler = fs.readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8");

const scriptMatch = app.match(/<script>([\s\S]*?)<\/script>/);
let appScriptParses = false;
try {
  if (!scriptMatch) throw new Error("Inline script not found");
  new Function(scriptMatch[1]);
  appScriptParses = true;
} catch (error) {
  console.error("APP SCRIPT SYNTAX ERROR:", error.message);
}

const checks = [
  ["inline app JavaScript parses", appScriptParses],
  ["app has Connect to ChatGPT control", app.includes("Connect to ChatGPT")],
  ["app has media import", app.includes('id="fileInput"')],
  ["app has timeline", app.includes('id="timeline"')],
  ["app has local render control", app.includes('id="renderBtn"')],
  ["app can generate local contact sheets", app.includes("analyseLocalMedia")],
  ["app polls remote commands", app.includes("pollCommands")],
  ["worker serves root app", worker.includes('u.pathname==="/"')],
  ["worker serves /mcp", worker.includes('u.pathname==="/mcp"')],
  ["worker exposes queue_video_edit", worker.includes('"queue_video_edit"')],
  ["worker exposes request_media_analysis", worker.includes('"request_media_analysis"')],
  ["worker exposes queue_video_edit_batch", worker.includes('"queue_video_edit_batch"')],
  ["app supports remote clip removal", app.includes('case "remove_clip"')],
  ["app supports remote clip movement", app.includes('case "move_clip"')],
  ["app supports remote timeline reorder", app.includes('case "reorder_timeline"')],
  ["app supports multi-cut timeline replacement", app.includes('case "replace_timeline"')],
  ["app supports per-clip speed", app.includes('case "set_clip_speed"')],
  ["app supports per-clip title", app.includes('case "set_clip_title"')],
  ["autonomous request can carry clips", app.includes("Array.isArray(p.clips)")],
  ["app has scene-change analysis", app.includes("sceneChanges") && app.includes("scanCount")],
  ["app has quiet-section analysis", app.includes("silenceSegments") && app.includes("decodeAudioData")],
  ["app can inspect latest render", app.includes("analyseLatestRender")],
  ["app supports per-clip transforms", app.includes("zoomStart") && app.includes("panXStart")],
  ["app supports per-clip filters", app.includes("brightness") && app.includes("saturation")],
  ["app supports native Android save bridge", app.includes("AndroidNative")],
  ["worker exposes inspect_video_render", worker.includes('"inspect_video_render"')],
  ["worker exposes queue_autonomous_edit", worker.includes('"queue_autonomous_edit"')],
  ["worker exposes fresh /mcp-v06 path", worker.includes('"/mcp-v06"')],
  ["app prefers MP4 when MediaRecorder supports it", app.includes('"video/mp4"')],
  ["worker emits MCP image content", worker.includes('type:"image"')],
  ["worker exposes device_status", worker.includes('"device_status"')],
  ["worker has device registration API", worker.includes('"/api/device/register"')],
  ["worker has Durable Object export", worker.includes("export class VideoStudioState")],
  ["wrangler binds VIDEO_STATE", wrangler.includes('"VIDEO_STATE"')],
  ["wrangler declares sqlite durable object", wrangler.includes('"storage": "sqlite"')],
];

let failed = 0;
for (const [name, ok] of checks) {
  if (ok) console.log("PASS", name);
  else {
    console.error("FAIL", name);
    failed++;
  }
}

if (failed) {
  console.error(`\n${failed} smoke test(s) failed.`);
  process.exit(1);
}

console.log(`\nAll ${checks.length} smoke tests passed.`);
