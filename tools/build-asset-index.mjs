#!/usr/bin/env node
// Builds asset-index.json for the Android app: the download URLs of every
// /assets/<rel> file referenced by the upstream data/assets.json manifest.
//
// The game art is not shipped in the APK; the connection page downloads it on
// the device from the same sources the upstream tools/fetch-assets.mjs uses.
// The plan is rebuilt here from the same inputs as fetch-assets.mjs main()
// (offline, from the upstream .cache indexes), so the URLs match exactly.
//
// Sizes: `bytes` prefers the upstream .cache/assets-ledger.json (only a machine that ran tools/fetch-assets.mjs has
// it) and falls back to the plan's declared size. Neither can be assumed current — the ledger is a snapshot of when
// that machine last fetched, the plan's size is a snapshot of when upstream generated its research dump — so with
// STRONGHOLD_VERIFY_ASSET_SIZES=1 (the CI release build) every entry is checked against the source's own
// Content-Length and corrected where it differs. Ledger/plan values survive only as the fallback for URLs that do not
// answer; a file that has neither and does not answer keeps `bytes` undefined.
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
  const [{ buildPlan }, { collectLeaves }, { loadIndexes }, { indexAudio }, { loadLocalEnemySpines, LOCAL_ENEMY_SPINES_FILE }, { kindOf }, { mirrorUrl }] =
    await Promise.all(['plan', 'manifest', 'cache', 'audio', 'spine', 'formats', 'sources'].map(load));
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
  for (const rel of [...rels].sort()) {
    const p = planned.get(rel);
    const urls = p ? [...p.urls] : [];
    const led = ledger[rel];
    if (typeof led?.url === 'string' && !urls.includes(led.url)) urls.push(led.url);
    if (!urls.length) { missing.push(rel); continue; }
    const kind = kindOf(rel);
    const file = { rel, urls, kind: KINDS.has(kind) ? kind : 'other' };
    const bytes = Number.isFinite(led?.bytes) && led.bytes > 0 ? led.bytes : p?.bytes;
    if (bytes) file.bytes = bytes;
    if (file.kind === 'atlas' && pma.get(rel) === true) file.pma = true;
    files.push(file);
  }
  if (missing.length) throw new Error(`no download URL for: ${missing.slice(0, 10).join(', ')}`);

  if (process.env.STRONGHOLD_VERIFY_ASSET_SIZES === '1') await verifySizes(files, mirrorUrl);
  const totalBytes = files.reduce((sum, f) => sum + (f.bytes || 0), 0);

  await mkdir(dirname(out), { recursive: true });
  await writeFile(`${out}.tmp`, JSON.stringify({ version: 1, manifestHash: manifest.hash, files }));
  await rename(`${out}.tmp`, out);
  console.log(`[asset-index] ${files.length} files, ${totalBytes} bytes → ${out}`);
}

/**
 * Check every file's `bytes` against the size its URL actually serves, and correct the ones that differ. Enabled by
 * STRONGHOLD_VERIFY_ASSET_SIZES=1, i.e. the CI release build. The source is authoritative because the client compares
 * exactly this number against the Content-Length it sees when checking for updates (import mode) and sums it for the
 * pre-download space check: a stale value means a permanent false "updated" and a wrong size estimate. URLs are tried
 * in order with the same raw→jsDelivr mirror the client uses. A file whose URLs all fail keeps what it already had —
 * the ledger/plan value, or `bytes` undefined if it had none.
 */
async function verifySizes(files, mirrorUrl) {
  const concurrency = Math.max(1, Math.min(32, Number(process.env.STRONGHOLD_VERIFY_SIZE_CONCURRENCY) || 16));
  let next = 0;
  let confirmed = 0;
  let filled = 0;
  let kept = 0;
  let unknown = 0;
  const corrected = [];
  const worker = async () => {
    for (let i = next++; i < files.length; i = next++) {
      const file = files[i];
      const before = file.bytes;
      const size = await headContentLength(file.urls, mirrorUrl);
      if (!size) {
        if (before === undefined) unknown++; else kept++;
        continue;
      }
      if (before === undefined) { file.bytes = size; filled++; }
      else if (before === size) confirmed++;
      else { file.bytes = size; corrected.push(`${file.rel}: ${before} → ${size}`); }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, files.length) }, worker));
  console.log(
    `[asset-index] size check: ${confirmed} confirmed, ${corrected.length} corrected, ${filled} filled, ` +
      `${kept} kept (unreachable), ${unknown} unknown (of ${files.length})`,
  );
  for (const line of corrected.slice(0, 10)) console.log(`[asset-index] size corrected: ${line}`);
  if (corrected.length > 10) console.log(`[asset-index] size corrected: … and ${corrected.length - 10} more`);
}

/** Content-Length of the first URL that answers a HEAD request, or null. */
async function headContentLength(urls, mirrorUrl) {
  for (const url of urls) {
    for (const src of [url, mirrorUrl(url)].filter(Boolean)) {
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          // 必须显式要 identity：Node 的 fetch 默认带 accept-encoding: gzip，这时响应里的 Content-Length
          // 是压缩后大小（文本类 atlas 只有真实大小的约 1/4），直接当成文件大小会写错。
          const res = await fetch(src, {
            method: 'HEAD',
            headers: { 'accept-encoding': 'identity' },
            signal: AbortSignal.timeout(30000),
          });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          // 服务器若无视 identity 仍然压缩，Content-Length 不等于实体大小：宁可留空，也不能写错。
          const encoding = res.headers.get('content-encoding');
          if (encoding) throw new Error(`compressed despite identity: ${encoding}`);
          const length = Number(res.headers.get('content-length'));
          if (Number.isFinite(length) && length > 0) return length;
          throw new Error('no content-length');
        } catch (e) {
          if (attempt === 3) break;
          await new Promise((resolve) => setTimeout(resolve, 400 * attempt));
        }
      }
    }
  }
  return null;
}

main(process.argv.slice(2)).catch((e) => {
  console.error(`[asset-index] FAILED: ${e?.message || e}`);
  process.exit(1);
});
