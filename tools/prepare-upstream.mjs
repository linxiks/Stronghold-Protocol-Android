#!/usr/bin/env node
// 构建前的最少上游输入准备：只抓 Android 打包真正需要的上游产物，不跑 tools/fetch-assets.mjs
// （那会下载约 250 MB 游戏素材，而素材不进 APK）。
//
//   1. .cache/gamedata/excel/audio_data.json + .cache/ark-models/models_data.json
//      （build-asset-index.mjs 以 offline:true 读取，缺失会直接失败）
//   2. public/fonts 的 3 个字体文件 + 由 buildFonts() 生成的 .woff2 / fonts.css
//      （syncStrongholdFonts 需要其中两个 .otf；fonts.css 供游戏页面使用）
//
// 复用上游自己的模块与 URL 定义，避免在 CI 里复制一份地址清单。
//
// Usage: node tools/prepare-upstream.mjs <upstreamRoot>

import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const argv = process.argv.slice(2);
if (argv.length !== 1) {
  console.error('usage: node tools/prepare-upstream.mjs <upstreamRoot>');
  process.exit(2);
}
const root = resolve(argv[0]);
const log = (m) => console.log(m);

const load = (rel) => import(pathToFileURL(join(root, 'tools', 'assets', rel)).href);
const [{ loadIndexes }, { fontJobs, buildFonts, FONTS }, { Downloader }] = await Promise.all(
  ['cache.mjs', 'fonts.mjs', 'downloader.mjs'].map(load)
);

await loadIndexes(root, { log });

const fontsDir = join(root, 'public', 'fonts');
const downloader = new Downloader({
  root: fontsDir,
  ledgerPath: join(root, '.cache', 'fonts-ledger.json'),
  concurrency: 4,
  log,
});
await downloader.run(fontJobs(), 'fonts');
const { errors } = await buildFonts(fontsDir, log);

const missing = FONTS.map((f) => `${f.name}.${f.ext}`).filter((rel) => !existsSync(join(fontsDir, rel)));
if (missing.length) {
  throw new Error(`字体准备失败: ${missing.join(', ')}${errors.length ? ` (${errors.join('; ')})` : ''}`);
}
console.log(`[prepare-upstream] 就绪: ${fontsDir} 与 ${join(root, '.cache')}`);
