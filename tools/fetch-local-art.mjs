#!/usr/bin/env node
// 从上游完整包的 Release zip 里，用 HTTP Range 只取出「本地客户端美术」（3D 棋盘 + 战场场景等），
// 重新打包成一个小的美术包 zip 供 Android 端下载。零依赖：只用 Node 内置模块与全局 fetch。
//
// 上游 v0.3.1 完整包约 846 MB，里面是 local-extract 从官方客户端提取出来的本地素材；Android 端只要其中的
// 棋盘 / 场景部分。整包下载再筛掉大部分字节是浪费，于是这里不落地整包：依次 Range 取末尾的 EOCD、
// 一次取整个中央目录、再按条目取局部头与压缩字节，边取边重打包。
//
//   对 GitHub release 资产，范围请求必须用显式区间 `bytes=<start>-<end>`（206 正常）；
//   后缀式 `bytes=-N` 会返回 501。总长度从响应的 `Content-Range` 拿到。
//
// Usage: node tools/fetch-local-art.mjs <upstreamRoot> <outFile> [--all]
//   <upstreamRoot>  上游 clone 目录（读它的 package.json 的 version）
//   <outFile>       输出 zip 路径（目录会被创建）
//   --all           默认只收 public/assets/local/{scene,map,mesh,fx}/ 与两个本地清单，
//                   并据此过滤清单；加 --all 则 public/assets/local/ 全收、清单原样保留
//   环境变量 STRONGHOLD_LOCAL_ART_URL 可覆盖源 URL（离线复现 / 换镜像时用）。
//
// 输出 zip 里额外加一个根级 local-art.json：{version, scope, files, bytes}。

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import { setTimeout as sleep } from 'node:timers/promises';

const UPSTREAM_REPO = 'sganggs/Stronghold-Protocol';
/** 默认选集：场景、棋盘和官方特效目录下的全部条目，加上清单本身。 */
const DEFAULT_DIRS = ['public/assets/local/scene/', 'public/assets/local/map/', 'public/assets/local/mesh/', 'public/assets/local/fx/'];
const ALL_DIR = 'public/assets/local/';
/** 清单里 path 用的前缀（去掉了 public/）。 */
const MANIFEST_DIRS = DEFAULT_DIRS.filter((d) => !d.endsWith('/fx/')).map((d) => `/${d.slice('public/'.length)}`);
const MANIFEST_PATH = 'data/local-assets.json';
const FX_MANIFEST_PATH = 'data/local-fx.json';
/** 上游 zip 内所有条目的顶层前缀。 */
const ZIP_PREFIX = 'Stronghold-Protocol/';
const TAIL_BYTES = 128 * 1024;
const CONCURRENCY = 8;
const MAX_RETRIES = 3;
/** 一个固定的 DOS 时间戳（1980-01-01 00:00），让输出 zip 逐字节确定。 */
const DOS_TIME = 0;
const DOS_DATE = 0x21;

const log = (m) => console.log(`[local-art] ${m}`);

function usage() {
  console.error('usage: node tools/fetch-local-art.mjs <upstreamRoot> <outFile> [--all]');
}

// ---------------------------------------------------------------------------
// HTTP Range 读取

/**
 * 取 [start, end]（含端点）的字节。GitHub release 资产要求显式区间，后缀式会 501。每个请求最多重试
 * MAX_RETRIES 次，指数退避。返回 { buf, total }，total 来自 Content-Range 的总长度（探测时用得上）。
 */
async function rangeFetch(url, start, end, label) {
  const want = end - start + 1;
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(url, {
        headers: { range: `bytes=${start}-${end}`, 'accept-encoding': 'identity' },
        signal: AbortSignal.timeout(120000),
      });
      // 先看状态再读体：若服务器无视 Range 回了 200，绝不能把整包吞进内存。
      if (res.status !== 206) {
        res.body?.cancel().catch(() => {});
        throw new Error(`HTTP ${res.status} (expected 206 for ${start}-${end})`);
      }
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length !== want) throw new Error(`short read ${buf.length}/${want} for ${start}-${end}`);
      return { buf, total: totalFromContentRange(res.headers.get('content-range')) };
    } catch (e) {
      if (attempt >= MAX_RETRIES) throw new Error(`${label}: ${e?.message || e}`);
      await sleep(300 * 2 ** attempt);
    }
  }
}

function totalFromContentRange(value) {
  const m = value && /\/(\d+)\s*$/.exec(value);
  return m ? Number(m[1]) : null;
}

// ---------------------------------------------------------------------------
// ZIP 读取（只读，够用即止：不做解压到磁盘、也不支持加密）

/** 末尾 128 KiB 里从后往前找 EOCD（PK\x05\x06）；注释长度要和文件末尾对齐才算真命中。 */
function findEocd(buf) {
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) !== 0x06054b50) continue;
    if (i + 22 + buf.readUInt16LE(i + 20) === buf.length) return i;
  }
  return -1;
}

/**
 * 读中央目录清单。EOCD 里 cd offset/size/count 是占位 0xFFFFFFFF/0xFFFF 时走 ZIP64：
 * 用 locator（PK\x06\x07）里的偏移取 EOCD64（PK\x06\x06）拿真实值。
 */
async function readCentralDirectory(url, total) {
  const tailStart = Math.max(0, total - TAIL_BYTES);
  const tail = (await rangeFetch(url, tailStart, total - 1, 'EOCD')).buf;
  const at = findEocd(tail);
  if (at < 0) throw new Error('EOCD not found in the last 128 KiB (not a zip?)');
  let entries = tail.readUInt16LE(at + 10);
  let cdSize = tail.readUInt32LE(at + 12);
  let cdOffset = tail.readUInt32LE(at + 16);
  if (entries === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
    const loc = at - 20;
    if (loc < 0 || tail.readUInt32LE(loc) !== 0x07064b50) throw new Error('ZIP64 locator not found');
    const eocd64 = Number(tail.readBigUInt64LE(loc + 8));
    const rec = (await rangeFetch(url, eocd64, eocd64 + 55, 'ZIP64 EOCD')).buf;
    if (rec.readUInt32LE(0) !== 0x06064b50) throw new Error('ZIP64 EOCD signature mismatch');
    entries = Number(rec.readBigUInt64LE(32));
    cdSize = Number(rec.readBigUInt64LE(40));
    cdOffset = Number(rec.readBigUInt64LE(48));
  }
  if (!entries) throw new Error('the zip lists no entries');
  log(`中央目录：${entries} 条目，${cdSize} 字节 @${cdOffset}（源文件 ${total} 字节）`);

  const cd = (await rangeFetch(url, cdOffset, cdOffset + cdSize - 1, 'central directory')).buf;
  const out = [];
  let p = 0;
  for (let i = 0; i < entries; i++) {
    if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) throw new Error(`bad central header #${i} @${p}`);
    const method = cd.readUInt16LE(p + 10);
    let compSize = cd.readUInt32LE(p + 20);
    let uncompSize = cd.readUInt32LE(p + 24);
    const nameLen = cd.readUInt16LE(p + 28);
    const extraLen = cd.readUInt16LE(p + 30);
    const commentLen = cd.readUInt16LE(p + 32);
    let localOffset = cd.readUInt32LE(p + 42);
    const name = cd.toString('utf8', p + 46, p + 46 + nameLen);
    if (compSize === 0xffffffff || uncompSize === 0xffffffff || localOffset === 0xffffffff) {
      // ZIP64 扩展字段 0x0001，字段顺序：uncomp、comp、offset、disk（只在对应占位时出现）。
      let r = -1;
      for (let q = p + 46 + nameLen, end = q + extraLen; r < 0 && q + 4 <= end; q += 4 + cd.readUInt16LE(q + 2)) {
        if (cd.readUInt16LE(q) === 0x0001) r = q + 4;
      }
      if (r < 0) throw new Error(`entry #${i} has ZIP64 size placeholders but no 0x0001 extra field`);
      if (uncompSize === 0xffffffff) { uncompSize = Number(cd.readBigUInt64LE(r)); r += 8; }
      if (compSize === 0xffffffff) { compSize = Number(cd.readBigUInt64LE(r)); r += 8; }
      if (localOffset === 0xffffffff) localOffset = Number(cd.readBigUInt64LE(r));
    }
    out.push({ name, method, compSize, uncompSize, localOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

/** 取一个条目的解压后内容：先读局部头拿 name/extra 长度，再取压缩字节并解压。 */
async function readEntry(url, entry) {
  const lh = (await rangeFetch(url, entry.localOffset, entry.localOffset + 29, `local header ${entry.name}`)).buf;
  if (lh.readUInt32LE(0) !== 0x04034b50) throw new Error(`bad local header for ${entry.name}`);
  const dataStart = entry.localOffset + 30 + lh.readUInt16LE(26) + lh.readUInt16LE(28);
  const raw = entry.compSize
    ? (await rangeFetch(url, dataStart, dataStart + entry.compSize - 1, `data ${entry.name}`)).buf
    : Buffer.alloc(0);
  return decodeEntry(entry, raw);
}

function decodeEntry(entry, raw) {
  const data = entry.method === 0 ? raw : entry.method === 8 ? inflateRawSync(raw) : null;
  if (!data) throw new Error(`${entry.name}: unsupported compression method ${entry.method}`);
  if (data.length !== entry.uncompSize) throw new Error(`${entry.name}: ${data.length} != ${entry.uncompSize} bytes`);
  return data;
}

/** 相邻的小条目合并成一次 Range 请求；v0.3.0 的特效包有数千文件，逐个取会产生过多请求。 */
async function readEntries(url, allEntries, picked) {
  const sorted = [...allEntries].sort((a, b) => a.localOffset - b.localOffset);
  const ends = new Map(sorted.map((entry, i) => [entry, (sorted[i + 1]?.localOffset ?? Infinity) - 1]));
  const groups = [];
  const maxSpan = 8 * 1024 * 1024;
  for (const entry of [...picked].sort((a, b) => a.localOffset - b.localOffset)) {
    const end = ends.get(entry);
    const prev = groups.at(-1);
    if (end === Infinity || end - entry.localOffset + 1 > maxSpan) {
      groups.push({ entries: [entry], single: true });
    } else if (prev && !prev.single && entry.localOffset - prev.end <= 65536 && end - prev.start + 1 <= maxSpan) {
      prev.entries.push(entry);
      prev.end = end;
    } else {
      groups.push({ entries: [entry], start: entry.localOffset, end });
    }
  }
  log(`合并为 ${groups.length} 个范围请求（${picked.length} 个条目）`);
  let done = 0;
  const batches = await mapLimit(groups, CONCURRENCY, async (group) => {
    if (group.single) {
      const entry = group.entries[0];
      return [{ name: relOf(entry.name), data: await readEntry(url, entry) }];
    }
    const block = (await rangeFetch(url, group.start, group.end, `entries ${group.entries[0].name}`)).buf;
    const files = group.entries.map((entry) => {
      const at = entry.localOffset - group.start;
      if (block.readUInt32LE(at) !== 0x04034b50) throw new Error(`bad local header for ${entry.name}`);
      const dataStart = at + 30 + block.readUInt16LE(at + 26) + block.readUInt16LE(at + 28);
      const raw = block.subarray(dataStart, dataStart + entry.compSize);
      if (raw.length !== entry.compSize) throw new Error(`short compressed data for ${entry.name}`);
      return { name: relOf(entry.name), data: decodeEntry(entry, raw) };
    });
    done += files.length;
    if (done % 100 < files.length) log(`  …${done}/${picked.length}`);
    return files;
  });
  return batches.flat();
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      for (let i = next++; i < items.length; i = next++) out[i] = await fn(items[i], i);
    }),
  );
  return out;
}

// ---------------------------------------------------------------------------
// 清单过滤

const relOf = (name) => (name.startsWith(ZIP_PREFIX) ? name.slice(ZIP_PREFIX.length) : name);

/** 默认只收场景与特效目录及两个清单；--all 收整个 public/assets/local/。目录条目不收。 */
function selectEntries(entries, scope) {
  const dirs = scope === 'all' ? [ALL_DIR] : DEFAULT_DIRS;
  return entries.filter((e) => {
    if (e.name.endsWith('/')) return false;
    const rel = relOf(e.name);
    return rel === MANIFEST_PATH || rel === FX_MANIFEST_PATH || dirs.some((d) => rel.startsWith(d));
  });
}

/**
 * 默认模式：只保留「所有条目 path 都以 scene/ map/ mesh/ 开头」的分组，删掉空分组，重算 count。
 * --all 模式原样保留。
 */
function filterManifest(doc, scope) {
  if (scope === 'all') return doc;
  const groups = doc?.groups;
  if (!groups || typeof groups !== 'object') throw new Error(`${MANIFEST_PATH} has no groups`);
  const kept = {};
  for (const [group, entries] of Object.entries(groups)) {
    const names = entries && typeof entries === 'object' ? Object.keys(entries) : [];
    if (!names.length) continue;
    const ok = names.every((n) => {
      const p = entries[n]?.path;
      return typeof p === 'string' && MANIFEST_DIRS.some((d) => p.startsWith(d));
    });
    if (ok) kept[group] = entries;
  }
  const count = Object.values(kept).reduce((n, g) => n + Object.keys(g).length, 0);
  const out = {};
  for (const [k, v] of Object.entries(doc)) {
    out[k] = k === 'groups' ? kept : k === 'count' ? count : v;
  }
  if (!('groups' in out)) out.groups = kept;
  if (!('count' in out)) out.count = count;
  return out;
}

// ---------------------------------------------------------------------------
// ZIP 写出（自实现，无依赖）

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const UTF8_FLAG = 0x800;

/** files: [{name, data:Buffer}]，按 name 升序（调用方排好）。返回 Buffer。 */
export function buildZip(files) {
  if (files.length >= 0xffff) throw new Error(`too many entries for a non-ZIP64 archive: ${files.length}`);
  const parts = [];
  const head = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name, 'utf8');
    const comp = deflateRawSync(f.data, { level: 9 });
    if (f.data.length >= 0xffffffff || comp.length >= 0xffffffff) throw new Error(`${f.name} exceeds 4 GiB`);
    if (offset >= 0xffffffff) throw new Error('output offset exceeds 4 GiB; ZIP64 needed');
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);
    lh.writeUInt16LE(20, 4); // version needed (2.0, deflate)
    lh.writeUInt16LE(UTF8_FLAG, 6);
    lh.writeUInt16LE(8, 8); // method: deflate
    lh.writeUInt16LE(DOS_TIME, 10);
    lh.writeUInt16LE(DOS_DATE, 12);
    lh.writeUInt32LE(crc32(f.data), 14);
    lh.writeUInt32LE(comp.length, 18);
    lh.writeUInt32LE(f.data.length, 22);
    lh.writeUInt16LE(name.length, 26);
    lh.writeUInt16LE(0, 28);
    parts.push(lh, name, comp);
    head.push({ name, crc: lh.readUInt32LE(14), comp: comp.length, uncomp: f.data.length, offset });
    offset += lh.length + name.length + comp.length;
  }

  const cdStart = offset;
  let cdSize = 0;
  const cdParts = [];
  for (const e of head) {
    const h = Buffer.alloc(46);
    h.writeUInt32LE(0x02014b50, 0);
    h.writeUInt16LE(20, 4); // version made by
    h.writeUInt16LE(20, 6); // version needed
    h.writeUInt16LE(UTF8_FLAG, 8);
    h.writeUInt16LE(8, 10);
    h.writeUInt16LE(DOS_TIME, 12);
    h.writeUInt16LE(DOS_DATE, 14);
    h.writeUInt32LE(e.crc, 16);
    h.writeUInt32LE(e.comp, 20);
    h.writeUInt32LE(e.uncomp, 24);
    h.writeUInt16LE(e.name.length, 28);
    h.writeUInt32LE(e.offset, 42);
    cdParts.push(h, e.name);
    cdSize += 46 + e.name.length;
  }
  if (cdStart + cdSize >= 0xffffffff) throw new Error('central directory offset exceeds 4 GiB; ZIP64 needed');

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdStart, 16);
  return Buffer.concat([...parts, ...cdParts, eocd]);
}

// ---------------------------------------------------------------------------

async function main(argv) {
  const flags = argv.filter((a) => a.startsWith('--'));
  const positional = argv.filter((a) => !a.startsWith('--'));
  if (positional.length !== 2 || flags.some((f) => f !== '--all') || flags.length > 1) {
    usage();
    process.exit(2);
  }
  const scope = flags.includes('--all') ? 'all' : 'board+scene+fx';
  const root = resolve(positional[0]);
  const out = resolve(positional[1]);

  const { version } = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  if (typeof version !== 'string' || !version) throw new Error(`${join(root, 'package.json')} has no version`);
  const url =
    process.env.STRONGHOLD_LOCAL_ART_URL ||
    `https://github.com/${UPSTREAM_REPO}/releases/download/v${version}/Stronghold-Protocol-v${version}.zip`;
  log(`源：${url}`);
  log(`范围：${scope}${scope === 'all' ? '（public/assets/local/ 全收）' : '（scene/ map/ mesh/ fx/）'}`);

  const probe = await rangeFetch(url, 0, 0, 'probe');
  const total = probe.total;
  if (!total) throw new Error('the source did not report a total length in Content-Range');

  const entries = await readCentralDirectory(url, total);
  const picked = selectEntries(entries, scope);
  if (!picked.length) throw new Error('no matching entries in the source zip');
  log(`命中 ${picked.length} 个条目，并发 ${CONCURRENCY} 取回…`);
  const fetched = await readEntries(url, entries, picked);
  const manifestRaw = fetched.find((f) => f.name === MANIFEST_PATH);
  if (!manifestRaw) throw new Error(`${MANIFEST_PATH} not found in the source zip`);
  const fxManifestRaw = fetched.find((f) => f.name === FX_MANIFEST_PATH);
  if (!fxManifestRaw) throw new Error(`${FX_MANIFEST_PATH} not found in the source zip`);

  const filtered = filterManifest(JSON.parse(manifestRaw.data.toString('utf8')), scope);
  manifestRaw.data = Buffer.from(JSON.stringify(filtered));
  const fxManifest = JSON.parse(fxManifestRaw.data.toString('utf8'));
  if (!fxManifest.groups || !fxManifest.count) throw new Error(`${FX_MANIFEST_PATH} has no effects`);

  const payload = [...fetched].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const included = new Set(payload.map((file) => file.name));
  for (const [manifest, doc] of [[MANIFEST_PATH, filtered], [FX_MANIFEST_PATH, fxManifest]]) {
    let count = 0;
    for (const group of Object.values(doc.groups)) {
      for (const entry of Object.values(group)) {
        const path = entry?.path;
        if (typeof path !== 'string' || !path.startsWith('/assets/local/') || !included.has(`public${path}`)) {
          throw new Error(`${manifest} references an absent file: ${path}`);
        }
        count++;
      }
    }
    if (count !== doc.count) throw new Error(`${manifest} count ${doc.count} != ${count}`);
  }
  const meta = {
    version,
    scope,
    files: payload.length,
    bytes: payload.reduce((n, f) => n + f.data.length, 0),
  };
  const files = [...payload, { name: 'local-art.json', data: Buffer.from(`${JSON.stringify(meta)}\n`) }].sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0,
  );

  const zip = buildZip(files);
  await mkdir(dirname(out), { recursive: true });
  await writeFile(out, zip);

  const uncompressed = files.reduce((n, f) => n + f.data.length, 0);
  log(`完成：${files.length} 条目，压缩 ${zip.length} 字节，未压缩 ${uncompressed} 字节 → ${out}`);
  log(`清单：${Object.keys(filtered.groups || {}).length} 组 / ${filtered.count ?? 0} 条目`);
  log(`源：${url}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch((e) => {
    console.error(`[local-art] FAILED: ${e?.message || e}`);
    process.exit(1);
  });
}
