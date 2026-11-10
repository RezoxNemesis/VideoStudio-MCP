/** VideoStudio Desktop renderer: the phone app's engine with a desktop workstation around it. */
import "@fontsource-variable/geist";
import "@fontsource/instrument-serif/latin-400.css";
import { loadFonts } from "../../src/render/fonts";
void loadFonts();
import "../../src/styles/app.css";
import "./styles/desktop.css";
import "./styles/colorpage.css";
import "./styles/insight.css";
import { Studio } from "../../src/app/studio";
import { commandList, runBatch, type Command } from "../../src/agent/api";
import { toast } from "../../src/ui/dom";
import { DesktopLink } from "./link";
import { registerDesktopCommands } from "./commands";
import { Shell } from "./ui/shell";
import { vsd } from "./bridge";
import { assetPath } from "./importer";
import { installNativeMedia } from "./native-media";

async function main() {
  const app = document.getElementById("app")!;
  app.innerHTML = `<div style="height:100%;display:grid;place-items:center;color:#8a94a6;font:14px system-ui">Starting VideoStudio 2…</div>`;
  const studio = new Studio();
  try {
    await studio.boot();
  } catch (e) {
    console.error(e);
    toast("Storage is unavailable; working in memory.", "error", 5000);
  }
  // VideoStudio 2: pictures and sound come from the native media core, never from the window's own video decoder.
  installNativeMedia(studio, (a) => assetPath(studio, a));
  registerDesktopCommands(studio);
  // A fresh install starts on a widescreen desktop project (the phone app's default is vertical).
  const p = studio.project;
  if (!p.assets.length && !p.tracks.some((t) => t.clips.length) && p.width === 1080 && p.height === 1920) studio.mutate("desktop default", (pr) => ((pr.width = 1920), (pr.height = 1080)));
  const link = new DesktopLink(studio);
  const info = await vsd?.info().catch(() => null);
  link.appInfo = () => ({
    app: "VideoStudio 2",
    version: info?.version ?? "dev",
    host: info?.host,
    os: `${info?.platform ?? "web"} ${info?.arch ?? ""}`.trim(),
    cpu: info?.cpuModel,
    memoryGB: info?.memoryGB,
    project: studio.project.name,
    size: `${studio.project.width}x${studio.project.height}`,
    commands: commandList().length,
  });
  const shell = new Shell(studio, link);
  app.replaceChildren(shell.el);
  await link.load();
  // Console helpers for debugging (Ctrl+Shift+I): vsdRun([{op:"state"}]).
  Object.assign(window, { vsdStudio: studio, vsdLink: link, vsdRun: (cmds: Command[]) => runBatch(studio, cmds, true) });
}

void main();
