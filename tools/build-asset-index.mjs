#!/usr/bin/env node
// Builds asset-index.json for the Android app: the download URLs of every
// /assets/<rel> file referenced by the upstream data/assets.json manifest.
//
// The game art is not shipped in the APK; the connection page downloads it on
// the device from the same sources the upstream tools/fetch-assets.mjs uses.
// The plan is rebuilt here from the same inputs as fetch-assets.mjs main()
// (offline, from the upstream .cache indexes), so the URLs match exactly.
//
// Usage: node tools/build-asset-index.mjs <upstreamRoot> <outFile>
// Output: {"version":1,"manifestHash":…,"files":[{"rel","urls","kind","bytes"?,"pma"?}]}

import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const ASSET_PREFIX = '/assets/';
const KINDS = new Set(['png', 'mp3', 'atlas', 'skel']);

async function main(argv) {
  if (argv.length !== 2) throw new Error('usage: node tools/build-asset-index.mjs <upstreamRoot> <outFile>');
  const root = resolve(argv[0]);
  const out = resolve(argv[1]);
  const load = (name) => import(pathToFileURL(join(root, 'tools/assets', `${name}.mjs`)).href);
  const [{ buildPlan }, { collectLeaves }, { loadIndexes }, { indexAudio }, { loadLocalEnemySpines, LOCAL_ENEMY_SPINES_FILE }, { kindOf }] =
    await Promise.all(['plan', 'manifest', 'cache', 'audio', 'spine', 'formats'].map(load));
  const readJson = async (rel) => JSON.parse(await readFile(join(root, rel), 'utf8'));

  // Same plan inputs as upstream tools/fetch-assets.mjs main().
  const [assets07, ops03, enemies05, maps05] = await Promise.all([
    readJson('docs/research/07-assets.json'),
    readJson('docs/research/03-operators.json'),
    readJson('docs/research/05-enemies.json'),
    readJson('docs/research/05-maps.json'),
  ]);
  let indexes;
  try {
    indexes = await loadIndexes(root, { offline: true, log: () => {} });
  } catch (e) {
    throw new Error(`${e?.message || e} — run "node tools/setup.mjs" in Stronghold-Protocol first`);
  }
  const audio = indexAudio(indexes.audioData);
  const [dataEnemies, dataTokens, dataBosses] = await Promise.all(
    ['data/enemies.json', 'data/tokens.json', 'data/bosses.json'].map((f) => readJson(f).catch(() => null)));
  const extraHandbook = {};
  for (const b of Object.values(dataBosses || {})) if (b?.enemyKey && typeof b.handbookId === 'string') extraHandbook[b.enemyKey] = b.handbookId;
  const localEnemySpines = await loadLocalEnemySpines(join(root, LOCAL_ENEMY_SPINES_FILE));
  const plan = buildPlan({
    assets07, ops03, enemies05, maps05, audio, modelsData: indexes.modelsData,
    extraEnemyIds: Object.keys(dataEnemies || {}),
    extraTokenIds: Object.keys(dataTokens || {}),
    extraHandbook,
    localEnemySpines,
  });

  /** rel → { urls: string[], bytes?: number } */
  const planned = new Map();
  const addAlt = (alt) => {
    if (!alt || typeof alt.rel !== 'string') return;
    let entry = planned.get(alt.rel);
    if (!entry) planned.set(alt.rel, entry = { urls: [], bytes: undefined });
    for (const u of alt.urls || []) if (!entry.urls.includes(u)) entry.urls.push(u);
    if (entry.bytes === undefined && alt.bytes) entry.bytes = alt.bytes;
  };
  for (const l of collectLeaves(plan.template)) for (const alt of l.leaf.alts || []) addAlt(alt);
  for (const m of plan.models.values()) for (const alt of [m.skel, m.atlas, ...(m.pngs || [])]) addAlt(alt);

  const manifest = await readJson('data/assets.json');
  const rels = new Set();
  const pma = new Map();
  const walk = (node) => {
    if (typeof node === 'string') { if (node.startsWith(ASSET_PREFIX)) rels.add(node.slice(ASSET_PREFIX.length)); return; }
    if (!node || typeof node !== 'object') return;
    if (typeof node.atlas === 'string' && typeof node.pma === 'boolean' && node.atlas.startsWith(ASSET_PREFIX)) {
      pma.set(node.atlas.slice(ASSET_PREFIX.length), node.pma);
    }
    for (const v of Object.values(node)) walk(v);
  };
  walk(manifest);

  const ledger = await readJson('.cache/assets-ledger.json').then((j) => j?.files || {}).catch(() => ({}));
  const files = [];
  const missing = [];
  let totalBytes = 0;
  for (const rel of [...rels].sort()) {
    const p = planned.get(rel);
    const urls = p ? [...p.urls] : [];
    const led = ledger[rel];
    if (typeof led?.url === 'string' && !urls.includes(led.url)) urls.push(led.url);
    if (!urls.length) { missing.push(rel); continue; }
    const kind = kindOf(rel);
    const file = { rel, urls, kind: KINDS.has(kind) ? kind : 'other' };
    const bytes = Number.isFinite(led?.bytes) && led.bytes > 0 ? led.bytes : p?.bytes;
    if (bytes) { file.bytes = bytes; totalBytes += bytes; }
    if (file.kind === 'atlas' && pma.get(rel) === true) file.pma = true;
    files.push(file);
  }
  if (missing.length) throw new Error(`no download URL for: ${missing.slice(0, 10).join(', ')}`);

  await mkdir(dirname(out), { recursive: true });
  await writeFile(`${out}.tmp`, JSON.stringify({ version: 1, manifestHash: manifest.hash, files }));
  await rename(`${out}.tmp`, out);
  console.log(`[asset-index] ${files.length} files, ${totalBytes} bytes → ${out}`);
}

main(process.argv.slice(2)).catch((e) => {
  console.error(`[asset-index] FAILED: ${e?.message || e}`);
  process.exit(1);
});
