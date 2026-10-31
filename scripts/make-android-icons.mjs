// Writes legacy launcher PNGs and web icons from the VideoStudio mark.
// Run: node scripts/make-android-icons.mjs   (needs sharp: npm i --no-save sharp)
import sharp from "sharp";
import { rmSync } from "node:fs";

const densities = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const svg = (size, round) => {
  const r = round ? size / 2 : size * 0.22;
  const k = size / 32;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
    <rect width="${size}" height="${size}" rx="${r}" fill="#0b0d12"/>
    <g transform="scale(${k})">
      <path d="M12.2 9.2 L22.5 16 L12.2 22.8 Z" fill="#4f7cff"/>
      <path d="M9.5 25 C13 23.2 16.8 26.4 22.6 24" fill="none" stroke="#ffb74d" stroke-width="1.8" stroke-linecap="round"/>
    </g>
  </svg>`;
};
for (const [name, size] of Object.entries(densities)) {
  const dir = `android/app/src/main/res/mipmap-${name}`;
  await sharp(Buffer.from(svg(size, false))).png().toFile(`${dir}/ic_launcher.png`);
  await sharp(Buffer.from(svg(size, true))).png().toFile(`${dir}/ic_launcher_round.png`);
  rmSync(`${dir}/ic_launcher_foreground.png`, { force: true });
}
for (const s of [192, 512]) await sharp(Buffer.from(svg(s, false))).png().toFile(`public/icon-${s}.png`);
console.log("icons written");
