#!/usr/bin/env node
// Builds asset-index.json for the Android app: the download URLs of every
// /assets/<rel> file referenced by the upstream data/assets.json manifest.
//
// The game art is not shipped in the APK; the connection page downloads it on
// the device from the same sources the upstream tools/fetch-assets.mjs uses.
// The plan is rebuilt here from the same inputs as fetch-assets.mjs main()
// (offline, from the upstream .cache indexes), so the URLs match exactly — including the operator battle
// voice, which plan.mjs needs as the cached .cache/gamedata/excel/charword_table.json (see the buildPlan call).
//
// Sizes: `bytes` prefers the upstream .cache/assets-ledger.json (only a machine that ran tools/fetch-assets.mjs has
// it) and falls back to the plan's declared size. Neither can be assumed current — the ledger is a snapshot of when
// that machine last fetched, the plan's size is a snapshot of when upstream generated its research dump — so with
// STRONGHOLD_VERIFY_ASSET_SIZES=1 (the CI release build) every entry is checked against the source's own
// Content-Length and corrected where it differs. Ledger/plan values survive only as the fallback for URLs that do not
// answer; a file that has neither and does not answer keeps `bytes` undefined.
//
// Missing URLs: every /assets/<rel> the manifest references should end up in `files`. Two offline gaps used to open up
// and are closed here — the plan inputs v0.2.0 added (dataExtras / localTokenSpines, see the buildPlan call) and the
// extra Spine atlas pages the model index does not list (see addExtraAtlasPages). Whatever still has no URL is reported
// and written to the index's `missing` field, left out of `files` so the client falls back to the network for that
// path; STRONGHOLD_ASSET_INDEX_STRICT=1 turns it back into a hard failure for local runs.
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
  const [planM, manifestM, cacheM, audioM, spineM, formatsM, sourcesM, atlasM, fetchM] = await Promise.all([
    ...['plan', 'manifest', 'cache', 'audio', 'spine', 'formats', 'sources', 'atlas'].map(load),
    // dataExtras 住在 fetch-assets.mjs（它只在被当作脚本运行时才执行 main），补位与自选编队的干员、
    // 召唤物和模组图标都由它给出，直接复用以免把那段规则复制一份。
    import(pathToFileURL(join(root, 'tools/fetch-assets.mjs')).href),
  ]);
  const [{ buildPlan }, { collectLeaves }, { loadIndexes }, { indexAudio }, { loadLocalSpines, LOCAL_ENEMY_SPINES_FILE, LOCAL_TOKEN_SPINES_FILE }, { kindOf }, { mirrorUrl, safeName, urlDir }] =
    [planM, manifestM, cacheM, audioM, spineM, formatsM, sourcesM];
  const { atlasInfo } = atlasM;
  const { dataExtras } = fetchM;
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
  const [dataEnemies, dataTokens, dataBosses, dataBackups, dataChess] = await Promise.all(
    ['data/enemies.json', 'data/tokens.json', 'data/bosses.json', 'data/backups.json', 'data/chess.json']
      .map((f) => readJson(f).catch(() => null)));
  const extraHandbook = {};
  for (const b of Object.values(dataBosses || {})) if (b?.enemyKey && typeof b.handbookId === 'string') extraHandbook[b.enemyKey] = b.handbookId;
  // 补位与自选编队（v0.2.0）把自己的干员、召唤物和模组图标带进计划；与上游 fetch-assets.mjs 读同一份数据。
  const extras = dataExtras(dataBackups, dataChess);
  const localEnemySpines = await loadLocalSpines(join(root, LOCAL_ENEMY_SPINES_FILE));
  const localTokenSpines = await loadLocalSpines(join(root, LOCAL_TOKEN_SPINES_FILE));
  const plan = buildPlan({
    assets07, ops03, enemies05, maps05, audio, modelsData: indexes.modelsData,
    // 上游 fetch-assets.mjs main() 同样传 charword + voiceLang：manifest 自 v0.1.2 起引用动作语音
    // (audio/voice/**)，不传这两个参数时 plan 里没有 voice 的 URL，这些文件会落进下面的 missing
    // 而从下载列表消失。voiceSlots 保持默认（plan.mjs 的 VOICE_BATTLE_SLOTS，即实战可播的槽位）。
    charword: indexes.charword, voiceLang: 'cn',
    extraEnemyIds: Object.keys(dataEnemies || {}),
    extraTokenIds: [...Object.keys(dataTokens || {}), ...extras.tokenIds],
    extraHandbook,
    localEnemySpines,
    localTokenSpines,
    extraOperators: extras.extraOperators,
    moduleTypes: extras.moduleTypes,
  });

  // 上游 fetch-assets.mjs 的 processModels 在下载 atlas 之后才发现它引用的额外纹理页（索引通常只列第一页），
  // 这里用同一规则自己读一遍这些 atlas 文本，否则第二页起（例如 char_1052_kalts22.png）拿不到下载 URL。
  await addExtraAtlasPages(plan.models, { kindOf, safeName, urlDir, atlasInfo, mirrorUrl });

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
  // 正常情况下这里是空的：plan 入参对齐上游、atlas 增补页都做完之后，manifest 引用的每个文件都该有 URL。
  // 出现缺口只有两个来由 —— plan 输入没跟上上游版本（见上面的 dataExtras/localTokenSpines），或者 atlas 里
  // 列了模型索引没有的页（见 addExtraAtlasPages）。剩下的写进索引的 missing 字段并排除出下载列表
  // （客户端请求这些路径时回源），STRONGHOLD_ASSET_INDEX_STRICT=1 可在本地把这类情况变回硬失败。
  if (missing.length) {
    const head = missing.slice(0, 10).join(', ');
    const rest = missing.length > 10 ? ` … (+${missing.length - 10})` : '';
    console.warn(`[asset-index] ${missing.length} 个文件没有下载 URL（已从下载列表排除，客户端回源）: ${head}${rest}`);
    if (process.env.STRONGHOLD_ASSET_INDEX_STRICT === '1') {
      throw new Error(`no download URL for: ${missing.slice(0, 10).join(', ')}`);
    }
  }

  if (process.env.STRONGHOLD_VERIFY_ASSET_SIZES === '1') await verifySizes(files, mirrorUrl);
  const totalBytes = files.reduce((sum, f) => sum + (f.bytes || 0), 0);

  await mkdir(dirname(out), { recursive: true });
  await writeFile(`${out}.tmp`, JSON.stringify({ version: 1, manifestHash: manifest.hash, files, missing }));
  await rename(`${out}.tmp`, out);
  console.log(`[asset-index] ${files.length} files, ${totalBytes} bytes → ${out}`);
}

/**
 * 上游 fetch-assets.mjs 的 processModels 在下载 atlas 之后，把 atlas 里列出、而索引（models_data）没有的
 * 额外页补进模型 —— `char_1052_kalts22.png` 就是这么来的，索引只列了 `char_1052_kalts2.png`。
 * 这里离线做同一件事：只抓 atlas 文本（每个模型一个，几十 KB），URL 目录沿用 atlas 自己的来源。
 */
async function addExtraAtlasPages(models, { kindOf, safeName, urlDir, atlasInfo, mirrorUrl }) {
  const list = [...models.values()];
  const concurrency = Math.max(1, Math.min(16, Number(process.env.STRONGHOLD_ATLAS_CONCURRENCY) || 16));
  let next = 0;
  let read = 0;
  let added = 0;
  let unreachable = 0;
  const worker = async () => {
    while (true) {
      const i = next++;
      if (i >= list.length) return;
      const m = list[i];
      const text = await fetchFirstText(m.atlas?.urls || [], mirrorUrl);
      if (text == null) {
        unreachable++;
        continue;
      }
      read++;
      for (const page of atlasInfo(text).pages) {
        const rel = m.dir + safeName(page);
        if (m.pngs.some((p) => p.rel === rel)) continue;
        const dirs = [...new Set([m.baseUrl, ...(m.atlas?.urls || []).map(urlDir)].filter(Boolean))];
        m.pngs.push({ rel, urls: dirs.map((d) => d + encodeURIComponent(page)), kind: kindOf(page) });
        added++;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, list.length) }, worker));
  console.log(`[asset-index] atlas pages: ${read}/${list.length} atlases read, ${added} extra pages, ${unreachable} unreachable`);
}

/** 依次尝试候选 URL 与其镜像，返回第一份能读到的文本；全都失败返回 null。 */
async function fetchFirstText(urls, mirrorUrl) {
  for (const url of urls) {
    for (const src of [url, mirrorUrl(url)].filter(Boolean)) {
      for (let attempt = 1; attempt <= 2; attempt++) {
        try {
          const res = await fetch(src, { signal: AbortSignal.timeout(30000) });
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          return await res.text();
        } catch {
          if (attempt === 2) break;
          await new Promise((resolve) => setTimeout(resolve, 400 * attempt));
        }
      }
    }
  }
  return null;
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
