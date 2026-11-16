// The app updates itself WITHOUT a password: a release's AppImage is unpacked into the person's own folder
// (~/.local/share/videostudio2/app/<version>) and the app starts from there from then on — its own launcher in the
// person's applications takes the place of the system's. Nothing is installed into the system, so nothing asks for a
// password. (The owner: "remove this app updating requires the password … so you could do everything automatically
// by yourself", 16 Nov 2026.)
import { describe, it, expect } from "vitest";
import { createRequire } from "node:module";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFile } from "node:child_process";

const S = createRequire(import.meta.url)("../desktop/electron/self-update.cjs");
const home = () => fs.mkdtempSync(path.join(os.tmpdir(), "vs-home-"));
const run = (cmd: string, args: string[], o: { cwd?: string } = {}) => new Promise<{ code: number; stderr: string }>((res) => execFile(cmd, args, { cwd: o.cwd }, (e, _so, se) => res({ code: e ? ((e as { code?: number }).code ?? 1) : 0, stderr: String(se) })));
/** Something that behaves as a release's AppImage does when asked to unpack itself. */
function appImage(mark: string, o: { broken?: boolean } = {}) {
  const f = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "vs-img-")), `VideoStudio-${mark}-x86_64.AppImage`);
  fs.writeFileSync(f, o.broken ? "#!/bin/sh\nexit 3\n" : `#!/bin/sh\n[ "$1" = "--appimage-extract" ] || exit 2\nmkdir -p squashfs-root/resources\nprintf '#!/bin/sh\\necho ${mark}\\n' > squashfs-root/videostudio\nchmod +x squashfs-root/videostudio\necho sandbox > squashfs-root/chrome-sandbox\necho png > squashfs-root/videostudio.png\n`);
  return f;
}
const newer = (a: string, b: string) => { const x = a.split(".").map(Number), y = b.split(".").map(Number); for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0); return false; };

describe("the app in the person's own folder", () => {
  it("unpacks a release there and starts from it: no system install, no password", async () => {
    const h = home(), r = await S.installUserApp({ appImage: appImage("2.5.90"), version: "2.5.90", home: h, run });
    expect(r.exe).toBe(path.join(h, ".local/share/videostudio2/app/2.5.90/videostudio"));
    expect(fs.statSync(r.exe).mode & 0o111).not.toBe(0);
    expect(S.currentOf(h)).toMatchObject({ version: "2.5.90", exe: r.exe });
    expect(S.isUserSpace(r.exe, h)).toBe(true);
    expect(S.isUserSpace("/opt/VideoStudio/videostudio", h)).toBe(false);
  });
  it("writes the person's own launcher, which takes the place of the system's in the menu", async () => {
    const h = home(), sys = path.join(h, "sys-apps"); fs.mkdirSync(sys);
    fs.writeFileSync(path.join(sys, "videostudio.desktop"), "[Desktop Entry]\nName=VideoStudio 2\nExec=/opt/VideoStudio/videostudio %U\nIcon=videostudio\nType=Application\nCategories=AudioVideo;\n");
    const r = await S.installUserApp({ appImage: appImage("2.5.90"), version: "2.5.90", home: h, run, systemApps: sys });
    const f = path.join(h, ".local/share/applications/videostudio.desktop"), text = fs.readFileSync(f, "utf8");
    expect(text).toContain("Name=VideoStudio 2");
    expect(text).toMatch(/^Exec="?[^"\n]*videostudio2\/app\/run\.sh"? %U$/m);
    expect(text).not.toContain("Exec=/opt/VideoStudio");
    const sh = fs.readFileSync(path.join(h, ".local/share/videostudio2/app/run.sh"), "utf8");
    expect(sh).toContain(r.exe);
    expect(sh).toContain("--no-sandbox"); // (unpacked, the sandbox helper is not the system's: the app is started as a portable one is)
  });
  it("starts through a small script that falls back to the installed app if the unpacked one cannot start", async () => {
    const h = home(), sys = path.join(h, "sys-apps"), opt = path.join(h, "opt"); fs.mkdirSync(sys); fs.mkdirSync(opt);
    fs.writeFileSync(path.join(opt, "videostudio"), '#!/bin/sh\necho system "$@" > "$(dirname "$0")/ran"\n', { mode: 0o755 });
    fs.writeFileSync(path.join(sys, "videostudio.desktop"), `[Desktop Entry]\nName=VideoStudio 2\nExec=${path.join(opt, "videostudio")} %U\nType=Application\n`);
    const r = await S.installUserApp({ appImage: appImage("2.5.90"), version: "2.5.90", home: h, run, systemApps: sys });
    const text = fs.readFileSync(path.join(h, ".local/share/applications/videostudio.desktop"), "utf8"), script = /^Exec="?([^" ]+)"?/m.exec(text)?.[1] ?? "";
    expect(script).toBe(path.join(h, ".local/share/videostudio2/app/run.sh"));
    // the unpacked app starts and ends well: the installed one is not started
    expect((await run(script, [])).code).toBe(0);
    expect(fs.existsSync(path.join(opt, "ran"))).toBe(false);
    // it cannot start (it ends at once with an error): the installed app is started instead, told not to hand back
    fs.writeFileSync(r.exe, "#!/bin/sh\nexit 1\n", { mode: 0o755 });
    await run(script, ["file.vsd"]);
    expect(fs.readFileSync(path.join(opt, "ran"), "utf8")).toMatch(/system --vsd-no-handover file\.vsd/);
  });
  it("keeps the version before (to go back to) and removes older ones", async () => {
    const h = home();
    for (const v of ["2.5.88", "2.5.89", "2.5.90"]) await S.installUserApp({ appImage: appImage(v), version: v, home: h, run });
    expect(fs.readdirSync(path.join(h, ".local/share/videostudio2/app")).filter((n) => /^\d/.test(n)).sort()).toEqual(["2.5.89", "2.5.90"]);
    expect(S.currentOf(h).version).toBe("2.5.90");
  });
  it("is installed even when an old version's folder cannot be removed (still in use): tidying never fails an update", async () => {
    const h = home();
    for (const v of ["2.5.88", "2.5.89"]) await S.installUserApp({ appImage: appImage(v), version: v, home: h, run });
    // (removing it fails as a folder still in use does: ENOTEMPTY)
    const rm = async (f: string) => { if (f.endsWith("2.5.88")) { const e = new Error(`ENOTEMPTY: directory not empty, rmdir '${f}/resources'`) as Error & { code?: string }; e.code = "ENOTEMPTY"; throw e; } };
    const r = await S.installUserApp({ appImage: appImage("2.5.90"), version: "2.5.90", home: h, run, rm });
    expect(r.version).toBe("2.5.90"); expect(S.currentOf(h).version).toBe("2.5.90");
  });
  it("leaves the app as it was when a download cannot be unpacked", async () => {
    const h = home(); await S.installUserApp({ appImage: appImage("2.5.89"), version: "2.5.89", home: h, run });
    await expect(S.installUserApp({ appImage: appImage("2.5.90", { broken: true }), version: "2.5.90", home: h, run })).rejects.toThrow(/could not be unpacked/);
    expect(S.currentOf(h).version).toBe("2.5.89");
    expect(fs.existsSync(path.join(h, ".local/share/videostudio2/app/2.5.90"))).toBe(false);
  });
  it("an older system install hands over to the newer app in the person's folder when it is started", async () => {
    const h = home(), r = await S.installUserApp({ appImage: appImage("2.5.90"), version: "2.5.90", home: h, run });
    expect(S.handover("/opt/VideoStudio/videostudio", h, "2.5.80", newer)).toBeNull(); // not until that app has shown it can start
    S.markOk(h, "2.5.90");
    expect(S.handover("/opt/VideoStudio/videostudio", h, "2.5.80", newer)).toMatchObject({ exe: r.exe });
    expect(S.handover("/opt/VideoStudio/videostudio", h, "2.5.90", newer)).toBeNull();   // not newer: stay
    expect(S.handover(r.exe, h, "2.5.80", newer)).toBeNull();                              // already the one in the folder
    expect(S.handover("/opt/VideoStudio/videostudio", home(), "2.5.80", newer)).toBeNull(); // nothing unpacked
  });
  it("picks the AppImage of a release, and says how the app is to be started", () => {
    const rel = { assets: [{ name: "VideoStudio-2.5.90-amd64.deb", browser_download_url: "d", size: 5 }, { name: "VideoStudio-2.5.90-x86_64.AppImage", browser_download_url: "a", size: 7 }] };
    expect(S.imageOf(rel)).toMatchObject({ name: "VideoStudio-2.5.90-x86_64.AppImage", url: "a", size: 7 });
    expect(S.imageOf({ assets: [rel.assets[0]] })).toBeNull();
    expect(S.startArgs("/x/app/2.5.90", ["--foo", "--no-sandbox", "--vsd-relaunch-wait"])).toEqual(["--no-sandbox", "--foo", "--vsd-relaunch-wait"]);
  });
});
