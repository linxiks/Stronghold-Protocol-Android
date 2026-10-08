# 上游跟进指南（本仓库 ← Stronghold-Protocol）

本仓库是 Stronghold-Protocol 的 Android 外壳。上游（`sganggs/Stronghold-Protocol`）发布新版本后，
按本指南把外壳跟上去并发版。

## 1. 两者的耦合关系

外壳**不复制**上游源码：构建时直接从同级目录 `../Stronghold-Protocol` 读取，因此本地上游 clone 必须
停在目标版本上。

| Gradle 任务 | 读取的上游内容 | 产物 |
|---|---|---|
| `packageStrongholdRuntime` | `package.json`、`data/`、`public/`（排除 `public/assets/**`）、`server/`、`shared/`、`node_modules/`（剔除 dev 包） | `assets/stronghold-runtime.zip` |
| `generateStrongholdAssetIndex` | 由 `tools/build-asset-index.mjs` 读取上游 `tools/assets/{plan,manifest,cache,audio,spine,formats,sources,atlas}.mjs`、`tools/fetch-assets.mjs`、`data/assets.json`、`data/{enemies,tokens,bosses,backups,chess}.json`、`docs/research/{03-operators,05-enemies,05-maps,07-assets}.json`、`.cache/` | `assets/asset-index.json` |
| `syncStrongholdFonts` | `public/fonts/{novecento-wide-normal,bender-regular}.otf` | `assets/fonts/*` |

CI 里另跑 `tools/prepare-upstream.mjs`，负责准备 `.cache/`（音频/角色词条/模型索引）与字体（含
`fonts.css` / `.woff2`）。**`asset-index.json` 不入库**，每次构建现生成；仓库里唯一记录上游版本的地方是
`.github/workflows/android-release.yml` 的 `UPSTREAM_REF_DEFAULT`（固定 SHA）。

客户端运行时的资源判定依赖 `asset-index.json` 的 `manifestHash`（= 上游 `data/assets.json` 的 `hash`）：
外部服务器模式下它与服务器 `data/assets.json` 的 hash 不一致时，本机资源会被判定为不同版而回退网络
（`MainActivity.kt`、`RemoteAssets.kt`）。所以每次跟进都必须让新索引与目标上游 hash 一致。

## 2. 跟进流程

约定：本地上游 clone 在 `../Stronghold-Protocol`；Windows 下用 `gradlew.bat`（`./gradlew` 无扩展名会被
Windows 判为无效程序，报 os error 193）。

### 步骤 0 — 拉取上游并确认版本

Git 全局可能配了失效代理（`http.proxy=127.0.0.1:5447`）。fetch 报连不上代理时用 `-c` 临时清空：

```bash
cd ../Stronghold-Protocol
git -c http.proxy= -c https.proxy= fetch origin --tags
git -c http.proxy= -c https.proxy= ls-remote --tags origin   # 远端 tag 权威列表
git log --oneline -1 origin/master                            # master 是否领先 tag
```

用**官方 tag**（可复现）而非 master，除非 master 上有影响产物的修复；#311 这类「只改维护工具/测试」的提交
按需判断。

### 步骤 1 — 评估集成面（只 diff 这几个路径）

```bash
git diff --stat <old_tag> <new_tag> -- tools data docs/research public/fonts package.json package-lock.json
```

对照上表判断影响：

- `tools/assets/{plan,manifest,cache,audio,formats,sources,atlas}.mjs`、`tools/fetch-assets.mjs` 变了 →
  `tools/build-asset-index.mjs` 的调用签名/入参可能要同步（它逐条对齐上游 `fetch-assets.mjs` 的 plan 入参）。
- `tools/assets/formats.mjs` / `atlas.mjs` / `sources.mjs` 变了 → `AssetFormats.kt` 是逐条照搬，需要同步。
- `data/assets.json` 变了 → 重新生成索引即可（rel/URL 集合通常不变，`manifestHash` 必变）。
- `tools/{build-data,balance,golden,doctor,package,package-update}.mjs`、`test/` → 上游构建/维护工具，
  外壳不经过，一般无需动。
- `package.json` 版本变化但依赖未变 → 无需 `npm ci`。

记录：`data/assets.json` 的 `hash`（新 `manifestHash` 期望值）、`stats.files`。

### 步骤 2 — 更新固定 ref

编辑 `.github/workflows/android-release.yml`：

```yaml
UPSTREAM_REF_DEFAULT: <目标 tag 的 40 位 SHA>
```

确认该 SHA 在 master 线上（CI 用 `git clone --branch master --single-branch` 后 `checkout <sha>`）：

```bash
git merge-base --is-ancestor <sha> origin/master && echo OK
```

### 步骤 3 — 本地上游切到目标版本

```bash
cd ../Stronghold-Protocol && git checkout --detach <tag>
```

### 步骤 4 — 本地构建验证

干净环境（无 `.cache/`）先准备上游输入，需联网：

```bash
node tools/prepare-upstream.mjs ../Stronghold-Protocol
```

已有 `.cache/` 时可直接构建：

```bash
./gradlew.bat --no-daemon --stacktrace :app:assembleDebug
```

### 步骤 5 — 校验 APK 内容

```bash
python - <<'EOF'
import zipfile, json, io
z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
idx = json.loads(z.read('assets/asset-index.json'))
rt = zipfile.ZipFile(io.BytesIO(z.read('assets/stronghold-runtime.zip')))
man = json.loads(rt.read('data/assets.json'))
pkg = json.loads(rt.read('package.json'))
print('index hash :', idx['manifestHash'], 'files', len(idx['files']), 'missing', len(idx.get('missing', [])))
print('runtime hash:', man['hash'])
print('runtime ver :', pkg['version'])
print('UPDATE.json:', 'UPDATE.json' in rt.namelist(), '| MANIFEST.json:', 'MANIFEST.json' in rt.namelist())
EOF
```

期望：两个 hash 相同、等于上游 `data/assets.json` 的 `hash`；`missing` 为 0；`package.json` 版本 = 目标版本；
runtime 内**不应**有 `UPDATE.json` / `MANIFEST.json`（否则会触发上游 `server/update.js` 的启动校验）。

### 步骤 6 — 提交、打 tag、推送

tag 版本**跟随上游版本**（`vX.Y.Z`）；CI 由 push tag `v*` 触发 release，并从 tag 名派生 `versionName` /
`versionCode`。

```bash
git add .github/workflows/android-release.yml
git commit -m "ci: 上游固定版本升到官方 vX.Y.Z (<sha>)"
git tag -a vX.Y.Z -m "跟随上游 Stronghold-Protocol vX.Y.Z (<sha>)"
git push origin master && git push origin vX.Y.Z
```

## 3. 常见坑

- **代理**：见步骤 0。`git ls-remote`/`fetch` 报 `Failed to connect to 127.0.0.1 port 5447` 都是它。
- **`gradlew` 报 os error 193**：Windows 下用 `gradlew.bat`。
- **索引 `bytes` 与上次不同**：多半是 `.cache/assets-ledger.json` 更新所致（本机跑过 fetch-assets 后才有），
  与上游版本无关；CI 用 `STRONGHOLD_VERIFY_ASSET_SIZES=1` 会逐个以源站 Content-Length 校正。
- **`missing` 非 0**：说明 plan 入参没跟上上游版本（`dataExtras` / `localTokenSpines`）或 atlas 多出页面。
  CI 里 `STRONGHOLD_ASSET_INDEX_STRICT=1` 可把这种情况变回硬失败。
- **上游新增的启动期校验**（如 0.2.1 的 `server/update.js`）：只在安装目录存在 `UPDATE.json` 时才生效，
  runtime zip 不含它，`applyPendingUpdate` 返回 `none`，不影响 Android 启动。
- **不要忘**：`AssetFormats.kt` 与上游 `formats/atlas/sources.mjs` 是手工对照关系，上游改这三个文件要同步。
