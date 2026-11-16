// Packs the film engine of Code Studio as one release file: node build/pack-kit.cjs [out folder = release]
// → <out>/film-kit.json.gz and <out>/film-kit.json.gz.sha256. An installed app fetches it with kit.update and draws
// with it from then on, without an installer or a password (electron/film-kit.cjs).
// Its version is the app version of this release (package.json); film/KIT.json names the oldest app it works with:
// raise "minApp" to the release being made whenever the engine starts to need something new in the app itself.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { packKit, ASSET } = require("../electron/film-kit.cjs");

const here = path.join(__dirname, ".."), out = path.resolve(process.argv[2] || path.join(here, "release"));
const version = JSON.parse(fs.readFileSync(path.join(here, "package.json"), "utf8")).version;
const { minApp } = JSON.parse(fs.readFileSync(path.join(here, "film", "KIT.json"), "utf8"));
const buf = packKit(path.join(here, "film"), version, minApp);
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, ASSET), buf);
fs.writeFileSync(path.join(out, `${ASSET}.sha256`), `${crypto.createHash("sha256").update(buf).digest("hex")}  ${ASSET}\n`);
console.log(`film engine ${version} (for VideoStudio ${minApp} and newer): ${(buf.length / 1024).toFixed(0)} KB → ${path.join(out, ASSET)}`);
