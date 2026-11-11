# 应用内插件市场

实现日期：2026-11-10。市场复用现有 GitHub 插件安装流程，目录由维护者在仓库中维护，各平台提供独立的市场页面。

## 目录与维护

[Issue #2798](https://github.com/flaresocialapp/Flare/issues/2798) 用于持续收集插件。维护者核对投稿后修改 [目录 JSON](../plugins/index.json)，通过 PR 审查并部署文档站。正式地址为：

```text
https://flareapp.moe/plugins/index.json
```

`docs/` 是站点根目录，URL 不带 `docs/`。[现有 Pages 工作流](../../.github/workflows/static.yml) 使用 `workflow_dispatch` 部署该目录；目录更新后还需执行部署，客户端按缓存策略读取更新，无需发布新版应用。

| 字段 | 约定 |
| --- | --- |
| `schemaVersion` | 当前为 `1`，独立于插件 API 和 manifest 版本；不支持的格式保留旧缓存并显示失败。 |
| `plugins[].id` | 必须与插件 `manifest.id` 一致，不能使用 `platform.id` 或仓库名称。 |
| `name` / `description` | 人工整理的普通文本，当前不提供多语言字段。 |
| `repository` | 公开 GitHub HTTPS 安装地址；子目录沿用 `https://github.com/owner/repo?path=/plugins/foo`。 |
| `icon` | 作者维护的 `raw.githubusercontent.com` HTTPS 图片地址，优先固定 commit；不在 Flare 仓库保存副本。 |

目录不保存版本、下载地址或权限副本。安装时按现有规则选择最高稳定 SemVer tag，解析 commit 并校验实际插件包。删除市场条目仅影响发现，已安装插件继续按原来源管理。

初始条目来自 [知乎插件投稿](https://github.com/flaresocialapp/Flare/issues/2798#issuecomment-6733035369)：

| 内容 | 核对来源 |
| --- | --- |
| ID `dev.maxlec.zhihu`、版本 `0.1.8` | [v0.1.8 manifest](https://github.com/anamaxlec/flare-zhihu-plugin/blob/v0.1.8/manifest.json) |
| 名称与简介 | [作者中文 locale](https://github.com/anamaxlec/flare-zhihu-plugin/blob/main/locales/zh-Hans.json) |
| 安装仓库 | [作者 README](https://github.com/anamaxlec/flare-zhihu-plugin/blob/main/README.md) |
| 256 × 256 PNG 图标，3024 字节 | [已核对 commit 中的图标](https://raw.githubusercontent.com/anamaxlec/flare-zhihu-plugin/557b6195db413fecc65468195c6646c668b64d64/assets/icon.png) |

投稿信息与 tag 状态为本次调研时的结果。收录不代表已验证第三方插件的真实账号功能或后续版本。

## 共享加载与安装

[PluginMarketplace](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/marketplace/PluginMarketplace.kt) 由 `PluginSubsystemV1` 懒加载持有，初始化不请求网络。目录和图标请求使用应用的全局代理，不携带社交账号凭据，也不为每个目录条目额外查询 GitHub API。

- 首次打开市场时读取有效缓存；缓存超过 1 小时后刷新，用户也可手动刷新。目录与刷新时间原子写入独立的 `plugin-marketplace-v1` 缓存，失败不替换已有有效目录。
- 目录最多 1 MiB、500 条，校验 schema、唯一插件 ID、字符串长度、GitHub 仓库地址和图标地址。请求拒绝重定向，20 秒超时转换为可展示的加载失败；调用方取消仍向上传播。
- 图标仅接受无凭据、查询参数或 fragment 的标准 HTTPS GitHub raw 地址。沿用安装器的格式、体积和 1024 × 1024 像素限制，按 URL 的 SHA-256 缓存，列表按需加载。失败显示占位图，不影响浏览或安装。
- `PluginManagementStateV1.marketplace` 独立保存目录加载状态与错误。加载不占用安装操作的 `busy`，也不影响本地管理。页面关闭取消其加载任务，保留共享缓存。

[管理 presenter](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/management/PluginManagementPresenterV1.kt) 从已验证目录中查找安装条目，在已有操作锁内检查已安装、本地草稿和开发中的同 ID 插件，再进入原 GitHub 下载与权限预览。安装来源、敏感权限、包变化与来源变化提示保持原有规则。

市场预期 ID 单独传递到下载预览与大仓库再次确认流程；实际插件 ID 不匹配时丢弃预览并报错。不能直接使用安装器原有 `expectedPluginId`，因为它同时要求插件已从相同 GitHub 来源安装，属于更新约束。失败后可从同一市场条目重试，取消仍使用原安装取消逻辑。

`isInstalled` / `isManaged` 由共享管理状态提供，各端只渲染按钮。已安装插件显示“已安装”和“管理”；同 ID 本地草稿或开发中的插件显示“管理”，避免覆盖本地工作。安装成功后通过原插件状态流更新按钮，不另存一份已安装列表。

## 页面与搜索

原插件管理页保留现有列表、空状态、更新检查、设置和扩展菜单。只在右上角 More 左侧增加商店图标，导航到独立市场页面。市场不提供投稿按钮或空的顶部 More；条目的 More 保留“查看仓库”。

| 平台 | 页面与搜索 | 刷新 |
| --- | --- | --- |
| Android | 现有 `FlareScaffold`、`SegmentedListItem`；使用历史记录页同款 Material `SearchBar` 和 `ExpandedFullScreenSearchBar`。从展开搜索结果安装前先收起搜索。 | 下拉刷新 |
| iOS | 原生导航、`List` 和 `.searchable` | 原生下拉刷新 |
| macOS | 原生导航、`List` 和 `.searchable`；保留设置窗口 pane 工具栏，操作按钮位于内容顶部。 | 保留刷新按钮 |
| Compose Desktop | 现有设置页 `CardExpanderItem`，使用历史记录页同款 Fluent `TextField` | 保留刷新按钮 |

Apple 商店符号来自 [Open Symbols / Font Awesome store](https://opensymbols.dev/font-awesome/store)，接入现有 `FontAwesomeIcon` 和 asset catalog，SVG 保留来源及 CC BY 4.0 归属说明。

搜索只匹配名称和简介，由 `PluginMarketplaceStateV1.search(query)` 在本地执行。忽略大小写与首尾空白，空查询显示全部，保持目录顺序；ID、仓库和图标地址不参与匹配。刷新保留输入，离线仍可搜索有效缓存，无匹配时使用各平台现有搜索空状态。

加载失败、空目录和有缓存时刷新失败分别展示。目录错误与安装错误分开显示，安装继续复用原权限确认。当前不增加详情页、账号、评分、下载统计、分类、服务器或自定义市场源。

## 验证范围

共享测试覆盖目录校验、响应与图片边界、缓存/重启/刷新、超时和调用方取消、名称/简介搜索、市场安装 ID 校验、大仓库确认后校验、取消、重试及本地插件归属。仓库内 JSON 也使用与下载目录相同的解析与校验。

Android 和桌面界面测试覆盖入口位于 More 左侧、安装/管理/仓库操作、搜索与清空、离线重试，以及原有 GitHub 安装对话框。Android 另外覆盖展开搜索后的安装、真实下拉刷新手势和刷新保留查询。

同步最新 `feature/plugin-v2` 后，插件 JVM 全量测试 488 项通过、1 项既有 live 测试跳过，桌面相关界面测试 13 项通过，Android 模拟器界面测试 16 项通过。Android Debug 与测试 APK 构建通过，变更的 Kotlin 文件已执行 ktlint 格式化。

最终 iOS Simulator 与 macOS arm64 的 `FlareAppleUI` 构建均通过，覆盖最新共享 Kotlin 框架和原生 Swift 界面。本轮 Apple 验证为构建检查。

此前已在 iOS Simulator 和原生 macOS 的独立预览应用中运行真实 `PluginManagementView`、共享 presenter 和目录服务，并保存各端截图。macOS 实际输入名称、简介及无匹配词，确认筛选与原生清空按钮。预览应用只写自身缓存，临时 target 和启动逻辑不进入产品代码；Compose Desktop 截图在 macOS 上渲染。

正式目录在本次检查时返回 HTTP 404，合并后仍需部署 Pages。界面验证使用本地 JSON 或测试缓存；未验证第三方插件真实账号登录与业务功能，未运行 Windows/Linux 或实体设备。
