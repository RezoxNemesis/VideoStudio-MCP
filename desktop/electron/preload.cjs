/** The only bridge between the editor window and the desktop: a small, explicit API (no Node in the page). */
const { contextBridge, ipcRenderer, webUtils } = require("electron");

async function call(name, ...args) {
  const r = await ipcRenderer.invoke(name, ...args);
  if (!r.ok) throw new Error(r.error);
  return r.value;
}

contextBridge.exposeInMainWorld("vsd", {
  desktop: true,
  pathOf: (file) => {
    try {
      return webUtils.getPathForFile(file) || "";
    } catch {
      return "";
    }
  },
  info: () => call("info"),
  list: (dir, o) => call("fs.list", dir, o),
  find: (root, q, o) => call("fs.find", root, q, o),
  stat: (p) => call("fs.stat", p),
  fileUrl: (p) => call("fs.url", p),
  reveal: (p) => call("fs.reveal", p),
  openPath: (p) => call("fs.open", p),
  pick: (o) => call("fs.pick", o),
  pickSave: (o) => call("fs.pickSave", o),
  writeText: (p, t) => call("fs.writeText", p, t),
  readText: (p) => call("fs.readText", p),
  probe: (p) => call("media.probe", p),
  frames: (p, times, max) => call("media.frames", p, times, max),
  audioInspect: (p, o) => call("audio.inspect", p, o),
  audioInspectBytes: (bytes, name, o) => call("audio.inspectBytes", bytes, name, o),
  saveOpen: (collection, name) => call("save.open", collection, name),
  saveWrite: (id, position, data) => call("save.write", id, position, data),
  saveFinish: (id) => call("save.finish", id),
  saveAbort: (id) => call("save.abort", id),
  download: (url, headers) => call("net.download", url, headers),
  capture: (max, quality) => call("win.capture", max, quality),
  enc: {
    caps: () => call("enc.caps"),
    open: (o) => call("enc.open", o),
    frame: (id, data) => call("enc.frame", id, data),
    finish: (id) => call("enc.finish", id),
    abort: (id) => call("enc.abort", id),
  },
  process: (kind, input, o) => call("media.process", kind, input, o),
  needsConversion: (p) => call("media.needsConversion", p),
  workWrite: (name, bytes) => call("work.write", name, bytes),
  workClean: () => call("work.clean"),
  onProgress: (fn) => ipcRenderer.on("ffmpeg:progress", (_e, m) => fn(m)),
  win: {
    minimize: () => call("win.minimize"),
    maximize: () => call("win.maximize"),
    close: () => call("win.close"),
    fullscreen: () => call("win.fullscreen"),
    isMaximized: () => call("win.isMaximized"),
    onState: (fn) => ipcRenderer.on("win:state", (_e, s) => fn(s)),
  },
  openExternal: (url) => call("app.openExternal", url),
});
