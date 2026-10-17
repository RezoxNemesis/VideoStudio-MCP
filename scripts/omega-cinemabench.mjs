#!/usr/bin/env node
/** CLI over user-selected/project-owned native PNG evidence. Never enumerates Gallery. */
import { readFile, stat, writeFile } from 'node:fs/promises';
import { auditNativeFrameManifest, verifyFramePngs } from '../src/omega/cinemabench.js';

const USAGE = 'Usage: node scripts/omega-cinemabench.mjs --manifest <native-proof.json> [--frames-dir <owned-frames-dir>] [--metrics <stage-metrics.json> --max-contact-gap-px <pixels>] [--out <report.json>]';
const ALLOWED = new Set(['--manifest', '--frames-dir', '--metrics', '--max-contact-gap-px', '--out']);

function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i], value = argv[i + 1];
    if (!ALLOWED.has(key) || !value || value.startsWith('--') || options[key] !== undefined) throw new Error(USAGE);
    options[key] = value;
  }
  if (!options['--manifest']) throw new Error(USAGE);
  if (options['--max-contact-gap-px'] !== undefined && !options['--metrics']) {
    throw new Error('--max-contact-gap-px requires --metrics');
  }
  return options;
}

async function boundedJson(file) {
  const details = await stat(file);
  if (!details.isFile() || details.size > 10 * 1024 * 1024) throw new Error('JSON must be a regular file no larger than 10 MiB: ' + file);
  return JSON.parse(await readFile(file, 'utf8'));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifest = await boundedJson(args['--manifest']);
  const metrics = args['--metrics'] ? await boundedJson(args['--metrics']) : null;
  const frameVerification = args['--frames-dir'] ? await verifyFramePngs(manifest, args['--frames-dir']) : null;
  const maxContactGapPx = args['--max-contact-gap-px'] === undefined ? null : Number(args['--max-contact-gap-px']);
  const report = auditNativeFrameManifest(manifest, { frameVerification, stageMetrics: metrics, maxContactGapPx });
  const formatted = JSON.stringify(report, null, 2) + '\n';
  if (args['--out']) await writeFile(args['--out'], formatted, { flag: 'w', mode: 0o600 });
  process.stdout.write(formatted);
  if (report.gate === 'BLOCK') process.exitCode = 1;
}

main().catch(error => {
  console.error('OMEGA CinemaBench:', error.message);
  process.exitCode = 2;
});
