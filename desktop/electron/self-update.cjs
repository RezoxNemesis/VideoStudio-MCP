// The app updates itself WITHOUT a password.
// A system package needs the person's password every time it is installed. Instead, a release's AppImage is unpacked
// into the person's own folder — ~/.local/share/videostudio2/app/<version> — and the app starts from there from then
// on: its own launcher in ~/.local/share/applications takes the place of the system's (same file name), and an older
// system install that is started by hand hands over to the newer app in the folder. Nothing is written outside the
// person's home, so nothing asks for a password. The version before is kept to go back to. (Rules: tests/selfupdate.test.ts)
const fs = require("node:fs");
const fsp = fs.promises;
const path = require("node:path");

const rootOf = (home) => path.join(home, ".local", "share", "videostudio2", "app");
/** The app unpacked in the person's folder, if there is one that can be started: { version, dir, exe } | null. */
function currentOf(home) {
  try { const j = JSON.parse(fs.readFileSync(path.join(rootOf(home), "current.json"), "utf8")); return j && j.exe && fs.existsSync(j.exe) ? j : null; } catch { return null; }
}
/** Is this the app in the person's own folder (and not a system install)? */
function isUserSpace(execPath, home) { return path.resolve(execPath).startsWith(rootOf(home) + path.sep); }
/** A release's AppImage: { name, url, size } | null. */
function imageOf(rel) { const a = (rel.assets || []).find((x) => /\.AppImage$/.test(x.name)); return a ? { name: a.name, url: a.browser_download_url, size: a.size || 0 } : null; }
/** How the unpacked app is started: as a portable app is (its sandbox helper is not the system's), with what it was started with before. */
function startArgs(dir, argv = []) { return ["--no-sandbox", ...argv.filter((a) => a !== "--no-sandbox")]; }

/** The person's own launcher: the system's entry (if there is one) with its command pointed at the unpacked app. */
async function writeLauncher({ home, exe, dir, systemApps = "/usr/share/applications", exeName = "videostudio" }) {
  let name = `${exeName}.desktop`, text = null;
  try {
    for (const f of await fsp.readdir(systemApps)) {
      if (!f.endsWith(".desktop")) continue;
      const t = await fsp.readFile(path.join(systemApps, f), "utf8");
      if (new RegExp(`^Exec=.*[/"\\s]${exeName}(["\\s]|$)`, "m").test(t) || new RegExp(`^Exec=${exeName}(\\s|$)`, "m").test(t)) { name = f; text = t; break; }
    }
  } catch { /* no system entry: one is written from nothing */ }
  // The launcher starts a small script: the unpacked app — and, should that end at once with an error (it cannot start
  // on this machine), the app that is installed in the system, told not to hand back. The person is never left without an app.
  const sysExe = text ? (/^Exec="?([^"\s]+)"?/m.exec(text) || [])[1] : null, script = path.join(rootOf(home), "run.sh"), q = (v) => `'${String(v).replace(/'/g, "'\\''")}'`;
  await fsp.mkdir(rootOf(home), { recursive: true });
  await fsp.writeFile(script, `#!/bin/sh\n# VideoStudio 2 — started from your own folder (written by the app's updater; safe to delete: the installed app starts instead)\nT0=$(date +%s)\n${q(exe)} ${startArgs(dir).join(" ")} "$@"\nRC=$?\n[ $RC -eq 0 ] && exit 0\n[ $(( $(date +%s) - T0 )) -lt 20 ] || exit $RC\n${sysExe && sysExe.startsWith("/") ? `[ -x ${q(sysExe)} ] && exec ${q(sysExe)} --vsd-no-handover "$@"\n` : ""}exit $RC\n`, { mode: 0o755 });
  const cmd = `Exec="${script}" %U`;
  if (text) text = text.replace(/^Exec=.*$/gm, cmd).replace(/^TryExec=.*\n/gm, "");
  // the window says it is this app (main.cjs gives it the class and desktop name `${exeName}`), so the taskbar and the
  // System Monitor show "VideoStudio 2" with its icon — not "org.chromium.Chromium", which a person may end, not knowing it is the app
  if (text) text = /^StartupWMClass=/m.test(text) ? text.replace(/^StartupWMClass=.*$/gm, `StartupWMClass=${exeName}`) : text.replace(/\n?$/, `\nStartupWMClass=${exeName}\n`);
  else {
    const icon = [path.join(dir, `${exeName}.png`), path.join(dir, ".DirIcon")].find((p) => fs.existsSync(p)) || exeName;
    text = `[Desktop Entry]\nName=VideoStudio 2\n${cmd}\nIcon=${icon}\nType=Application\nTerminal=false\nCategories=AudioVideo;Video;\nStartupWMClass=${exeName}\n`;
  }
  const apps = path.join(home, ".local", "share", "applications");
  await fsp.mkdir(apps, { recursive: true });
  await fsp.writeFile(path.join(apps, name), text, { mode: 0o755 });
  return path.join(apps, name);
}

/**
 * Unpack a release's AppImage into the person's folder and make it the app that starts.
 * o: { appImage (the downloaded file), version, home, run(cmd, args, { cwd, timeout }) → { code, stderr }, systemApps?, exeName? }
 * Returns { version, dir, exe, launcher }. If it cannot be unpacked, the app that was there stays as it was.
 */
async function installUserApp(o) {
  const exeName = o.exeName || "videostudio", root = rootOf(o.home), dir = path.join(root, o.version), tmp = path.join(root, `.unpack-${o.version}-${process.pid}`);
  const before = currentOf(o.home);
  await fsp.rm(tmp, { recursive: true, force: true });
  await fsp.mkdir(tmp, { recursive: true });
  try {
    await fsp.chmod(o.appImage, 0o755);
    const r = await o.run(o.appImage, ["--appimage-extract"], { cwd: tmp, timeout: 15 * 60_000 });
    const got = path.join(tmp, "squashfs-root");
    if (r.code !== 0 || !fs.existsSync(path.join(got, exeName))) throw new Error(`The new version could not be unpacked${r.stderr ? `: ${String(r.stderr).trim().split("\n").slice(-1)[0]}` : "."}`);
    await fsp.rm(dir, { recursive: true, force: true });
    await fsp.rename(got, dir);
  } finally { await fsp.rm(tmp, { recursive: true, force: true }); }
  const exe = path.join(dir, exeName);
  await fsp.chmod(exe, 0o755);
  await fsp.writeFile(path.join(root, "current.json.part"), JSON.stringify({ version: o.version, dir, exe }));
  await fsp.rename(path.join(root, "current.json.part"), path.join(root, "current.json"));
  const launcher = await writeLauncher({ home: o.home, exe, dir, systemApps: o.systemApps, exeName });
  // the version before is kept (to go back to); older ones go
  const keep = new Set([o.version, before && before.version !== o.version ? before.version : null]);
  // (tidying is never a reason for an update to fail: a folder still in use — an old instance that has not quit — gives
  //  ENOTEMPTY; it is left and tried again at the next update. 16 Nov 2026: the new version was in place but the app was
  //  not restarted because of it)
  for (const n of await fsp.readdir(root)) if (/^\d/.test(n) && !keep.has(n)) { try { await (o.rm || fsp.rm)(path.join(root, n), { recursive: true, force: true }); } catch { /* left for next time */ } }
  return { version: o.version, dir, exe, launcher };
}

/** The unpacked app has come up (its window is there): noted, so that a system install may hand over to it. */
function markOk(home, version) {
  try { const f = path.join(rootOf(home), "current.json"), j = JSON.parse(fs.readFileSync(f, "utf8")); if (j.version !== version || j.ok) return; fs.writeFileSync(f, JSON.stringify({ ...j, ok: true })); } catch { /* nothing unpacked */ }
}
/**
 * Started as an OLDER system install while a newer app is unpacked in the person's folder: that one is to run instead —
 * but only one that has shown it can start on this machine (markOk).
 */
function handover(execPath, home, appVersion, newer) {
  if (isUserSpace(execPath, home)) return null;
  const c = currentOf(home);
  return c && c.ok === true && newer(c.version, appVersion) ? c : null;
}

module.exports = { rootOf, currentOf, isUserSpace, imageOf, startArgs, writeLauncher, installUserApp, markOk, handover };
