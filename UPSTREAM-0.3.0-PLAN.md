# Stronghold-Protocol v0.3.0 Android 跟进方案

状态：v0.3.0 的适配记录；发布目标现已升至 v0.3.1，沿用本方案的资源、插件与界面修复。目标原为让 Android 外壳从上游 v0.2.6（`488db49f11daf862b67ce26fa4cf89aa6cba2466`）跟进到官方上游 v0.3.0（`c24e17eec3af1e6dedfa9935a6d15245cf2f4586`）。此处“官方”指本项目跟随的 `sganggs/Stronghold-Protocol` 上游发布，并非《明日方舟》客户端版本号。

## 已确认的变化

- 上游 v0.3.0 于 2026-10-15 发布，新增插件系统、`/studio/` 工作台、官方战斗特效、英/韩/本土语言语音，并重提取 11 个战场场景。完整包约 800 MB，精简包约 25 MB；上游从 v0.2.6 升级的更新包约 267 MB。
- 上游 `package.json` 新增运行依赖 `ses`、`acorn`。其 `server/index.js` 在启动时加载插件注册表、内容集、工作台和匹配宿主，静态服务还引用 `packs/`、`data/local-fx.json`。
- 当前 Android 构建仅把 `data/`、`public/`、`server/`、`shared/`、`node_modules/` 装进运行时 ZIP；`StrongholdInstaller` 在 APK 更新时整体删除并重建运行时目录。当前资源计划用 `voiceLang: 'cn'`；本地美术包默认只收 `local/{scene,map,mesh}/` 与 `data/local-assets.json`。WebView 未实现文件选择回调。

来源：[上游 v0.3.0 Release](https://github.com/sganggs/Stronghold-Protocol/releases/tag/v0.3.0)、[上游 v0.3.0 package.json](https://raw.githubusercontent.com/sganggs/Stronghold-Protocol/v0.3.0/package.json)、[上游 v0.3.0 server/index.js](https://raw.githubusercontent.com/sganggs/Stronghold-Protocol/v0.3.0/server/index.js)、[上游部署指南](https://github.com/sganggs/Stronghold-Protocol/blob/v0.3.0/docs/DEPLOY.md)。

## 实施顺序

### 1. 固定版本并做差异盘点

1. 在同级 `../Stronghold-Protocol` 获取官方 `v0.3.0` tag，校验它指向上述 SHA，并确认该提交可由 CI 当前的 `master --single-branch` 克隆方式检出；若不能，先调整 CI 克隆 tag 的方式。
2. 按现有 [UPSTREAM.md](UPSTREAM.md) 的耦合表，对比 v0.2.6…v0.3.0 的 `package.json`、锁文件、`data/`、`public/`、`server/`、`shared/`、`packs/`、`plugins/`、`studio/`、`tools/assets/`、`tools/fetch-assets.mjs`。记录新增的启动期必需目录、清单文件、资源路径格式和上游包布局。此差异核查应先于修改打包清单。
3. 把 `.github/workflows/android-release.yml` 的 `UPSTREAM_REF_DEFAULT` 固定到 v0.3.0 SHA；保持 tag 与 APK `versionName` 为 `v0.3.0` / `0.3.0`，现有公式生成 `versionCode=300`。

### 2. 让运行时完整且升级后保留用户数据

1. 以 v0.3.0 的启动导入、静态路由和官方精简包为准，扩展 `packageStrongholdRuntime` 的必需输入。重点核对 `packs/`、工作台静态资源、插件模板/Schema/作者资源；只打包运行必需内容，继续排除海量 `public/assets/**`，不把上游的 `MANIFEST.json` / `UPDATE.json` 塞进内置运行时。
2. 改造 `StrongholdInstaller` 的更新流程：在替换运行时前保住 `plugins/` 下的用户插件、`config.json`、工作台钥匙与草稿；优先使用上游支持的独立插件目录或资源目录配置，若没有再做有校验的迁移/恢复。让模板等只读资源随 APK 更新，让用户数据独立于安装戳。失败时不能静默清空用户插件。
3. 在 Android 嵌入式 Node 上验证 v0.3.0 原版房间能启动。上游玩法插件的匹配宿主、脚本沙箱可能需要子进程；当前外壳只有 `libnode.so` 和进程内 `node::Start`，不能假定系统里有可执行的 `node`。先验证无脚本的外观/界面/数据插件，再决定是否支持脚本和玩法插件；若无法可靠运行，应明确关闭或限制相应入口，避免让用户创建无法开局的房间。

### 3. 更新资源链路

1. 对照 v0.3.0 的 `tools/fetch-assets.mjs` 与 `tools/assets/*`，更新 `tools/build-asset-index.mjs` 的 plan 入参；重点检查英、韩、本土语言语音是否进入索引，以及 `data/assets.json` 的引用是否全部有 URL。资源索引应覆盖客户端会请求的资源，不能因为当前硬编码 `voiceLang: 'cn'` 而让新语音在本地模式下缺失。
2. 对照上游 `shared/assetPaths.js`、`server/http/assetViews.js`、`shared/media.js` 和资源格式代码，检查 Android 的 `RemoteAssets.kt`、`AssetFormats.kt`。确认新清单 `manifestHash`、`/assets/~hash/`、`/media/~hash/` 及任何新增资源路径在本地和外部服务器模式下均可解析。
3. 更新 `tools/fetch-local-art.mjs` 与 `LocalArt.kt`：根据 v0.3.0 完整包确认重提取场景及官方特效的真实目录和清单（包括 `data/local-fx.json`），按清单而非旧版固定三目录裁剪；安装时验证清单引用的文件齐全。新包仍独立于 APK 按需下载，并随 APK 更新自动恢复。旧 v0.2.6 美术包不能冒充 v0.3.0 包。
4. 测量完整资源、特效和多语音的下载及设备占用。先保证索引正确，再决定是否在资源页加按语种/特效分组下载；不要为控制体积删掉运行时需要的清单引用。

### 4. WebView 与工作台

1. 用实际 Android System WebView 检验主页、WebGL2 官方特效、Worker、CSP、插件界面与 `/studio/`；低端设备验收精简特效及 2D 回退。
2. 若要在应用内使用插件导入、工作台拖放/选择 ZIP、标题页“导入素材包”，给 WebView 接入 Android 文件选择结果，并验证取消、权限回收与大文件导入。当前连接页的原生素材导入入口不能替代网页的文件输入。
3. 核对工作台仅本机/局域网可访问的上游策略、钥匙持久化及 Android 本地服务绑定方式，不在升级时放宽访问范围。

### 5. 构建、回归和发布门槛

1. 在干净的 v0.3.0 clone 上 `npm ci`、运行 `tools/prepare-upstream.mjs`、严格生成资源索引，再构建 debug/release APK；CI 已把资源索引设为严格模式，并将构建 watchdog / job 时限调整为 45 / 90 分钟，仍须观察首次实际运行耗时。
2. 检查 APK 内的上游版本、运行时必需文件、资源索引 `manifestHash == data/assets.json.hash`、`missing == 0`、本地美术包清单和引用文件、签名及 `versionCode=300`。
3. 真机回归：全新安装、从 v0.2.6 覆盖升级、离线启动、内置单人/联机、外部 v0.3.0 服务器、本地资源命中/回退、中文及新增语音、3D 场景/特效、插件安装与重启/升级保留、工作台导入与失败回退。优先覆盖 Android 8 和一个较新系统。
4. 完成上述门槛后才打本仓库 `v0.3.0` tag 并发布 APK 与同版本美术包；发布说明注明插件/脚本在 Android 上经实测支持的范围和新增资源大小。

## 实施记录与待验证项

- 已取官方 v0.3.0 tag（与 master 同为上述 SHA），在本仓库被忽略的 `.upstream-v0.3.0/` 完成 `npm ci`、上游输入准备、严格资源索引生成；索引含 16,705 个文件，缺失 URL 为 0。
- 已从官方完整包按范围请求提取 12,765 个素材条目，生成约 153 MB 的本地美术包。`data/local-assets.json` 和 `data/local-fx.json` 的引用均能在包内找到；本仓库的提取器回归测试通过。
- 已适配运行时目录、插件数据迁移、Worker 匹配宿主、统计页素材、多语音资源索引、特效清单、工作台入口与 WebView 文件选择。连接页资源按钮改为两行，左栏可纵向滚动。
- 本机没有 Android SDK 和连接的真机，尚未完成 APK 编译、覆盖安装、WebView/Worker/工作台及插件脚本实测。工作台试运行仍可能依赖上游的子进程功能，发布前必须验证支持范围。
