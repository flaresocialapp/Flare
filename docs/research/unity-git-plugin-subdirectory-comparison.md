# Unity Git 子目录包与 Flare 同仓多插件

核对日期：2026-11-09。依据 Unity 6.0 / 6.3 LTS 官方文档；Unity 7.0 Alpha 文档仅作交叉核对，不作为必要依据。没有运行 Unity，也没有检查未公开的 Package Manager 下载器实现。

## Unity 明确规定的行为

| 项目 | Unity 的做法 | 一手依据 |
| --- | --- | --- |
| 子目录 | `?path=/目录` 指向相对于仓库根的包目录，该目录必须含 `package.json`；省略路径时使用仓库根。 | [Git dependencies：子目录](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| Git revision | `#revision` 接受任意 tag、分支或完整 commit SHA；不支持缩写 SHA。省略时取默认分支最新 commit 并锁定。 | [Git dependencies：revision](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 组合语法 | 路径查询必须在 revision 锚点之前：`repo.git?path=/目录#revision`。 | [Git dependencies：组合语法](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 同仓多包 | 在项目依赖中分别添加包名和各自 URL；只注册所选目录的包。 | [Git dependencies：多个子包](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 下载范围 | 使用 Git 克隆；官方明确警告，同一仓库多项依赖会重复克隆。`path` 没有承诺只下载该目录。 | [Git dependencies：重复克隆](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 包身份与版本 | `package.json.name` 是包的唯一标识；`version` 是包元数据，必须遵守 SemVer。 | [Package manifest](https://docs.unity.com/en-us/engine/6000.0/manual/packages-list/cus-pkg-development/upm-manifest-pkg) |
| Git 锁定 | 分支、tag 解析出的 commit hash 会写入锁文件。 | [Git dependencies：锁定](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 锁文件位置 | 项目的解析结果保存在 `Packages/packages-lock.json`；默认启用，建议纳入版本控制。 | [Lock files](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/dependencies-lp/upm-conflicts-auto) |
| 更新入口 | 用户点击 Update，或重新输入 Git URL；重新提交相同 URL 可以重新解析该 ref，绕过先前锁定的 hash，也可以改成新的 revision。 | [安装和更新](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/managing-packages-window/upm-ui-actions/upm-ui-giturl)、[锁定与重新解析](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git) |
| 记录的两个值 | `GitInfo.revision` 是请求的 revision；`GitInfo.hash` 是解析得到的 commit hash。 | [GitInfo](https://docs.unity3d.com/6000.0/Documentation/ScriptReference/PackageManager.GitInfo.html) |

原创示例：同仓库的两个包可以使用不同目录和不同 ref。

```json
{
  "dependencies": {
    "dev.flare.pixelfed": "https://github.com/example/flare-adapters.git?path=/plugins/pixelfed#pixelfed-stable",
    "dev.flare.other": "https://github.com/example/flare-adapters.git?path=/plugins/other#other-v2"
  }
}
```

### 版本规则应如何理解

下列两点是从官方接口模型得出的结论，不是声称文档逐字规定了某个内部算法：

- 任意 Git ref 与包自身的 SemVer 是两项信息；官方未要求 Git tag 字符串等于 `package.json.version`。例如分支 `main` 本身就不可能等于 SemVer。这里不能推出 manifest 无需版本校验。[revision 规则](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git)、[manifest 规则](https://docs.unity.com/en-us/engine/6000.0/manual/packages-list/cus-pkg-development/upm-manifest-pkg)
- `#v1.0.0` 定位的是这个 ref，更新入口重新解析它，或由用户换成另一 ref。该模型没有定义“扫描更高 SemVer tag 并自动切换”的机制；同理，不要求同仓包统一版本，也不需要 Package Manager 自己理解插件 tag 前缀。[Git 锁定及更新](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git)

因此分支新增 commit、同名 tag 被移动，都可能在显式刷新时解析到新 SHA；完整 SHA 则固定该次选择。不能把锁定理解成永远追踪远端最新内容。[Git 锁定](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git)

### 其他能力及适用限制

- Unity 支持多个 Git 协议和私有仓库，但依赖机器上的 Git、凭据助手或 SSH 环境；Git LFS 仓库还依赖 LFS 客户端。这些桌面环境能力不能直接视为 Flare 移动端已有能力。[安装先决条件](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/managing-packages-window/upm-ui-actions/upm-ui-giturl)
- UPM 使用浅克隆；LFS 内容可能获取不完整。它有可选 LFS 缓存，且复制包到项目缓存后不保留克隆仓库。这不是通用的同仓多包去重下载承诺。[Git LFS](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git)
- Git 依赖只能在项目 manifest 中声明；包的 `package.json` 不支持继续声明 Git 依赖。因此同仓多包不等于具备 Git 子包相互依赖系统。[Git dependencies](https://docs.unity.com/en-us/engine/6000.3/manual/packages-list/upm-git-dependencies/upm-git)

## Flare 改动前的约束

本次改动前，Flare 的仓库输入不接受 query/fragment，版本目录只收录稳定 SemVer tag，并要求 tag 版本等于 `manifest.version`；更新优先选择更高的 SemVer。现有 GitHub 来源记录保存仓库、tag 和 SHA。以下迁移建议基于这些实际约束。[地址与版本解析](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/github/GitHubPluginClient.kt)、[安装校验](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/installer/PluginInstaller.kt)、[更新选择](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/management/PluginManagementPresenterV1.kt)、[来源模型](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/lifecycle/PluginStateModels.kt)

插件包 hash 根据筛选出的运行文件重新生成的规范 ZIP 计算，包含完整 manifest，不包含原仓库 ZIP 的时间戳或压缩差异。因此即使只修改 `manifest.version`，包 hash 也会变化。[规范归档](../../feature/plugin/src/commonMain/kotlin/dev/dimension/flare/feature/plugin/installer/FppArchiveWriter.kt)

## Flare 最终产品规则

以下由用户在 2026-11-09 确认，优先于 Unity 的默认分支/revision 模型：

1. 默认继续选择仓库最新稳定 SemVer tag，并按其 SHA 下载固定快照。
2. 各插件版本独立，来自目录内的 `manifest.version`，不再要求等于仓库 tag 版本。
3. 支持 `?path=/plugins/example`；路径缺省和旧记录缺少路径均表示仓库根。每个目录是完整插件包根，运行资源位于该目录内。
4. 同仓插件使用各自唯一的插件/平台 ID，独立安装、更新和卸载。自动更新保留仓库身份与目录；目录移动需要显式来源变更审阅。
5. 更新先比较所选运行文件的 Git blob SHA，再在下载后比较规范插件包 hash。manifest、脚本、图标和翻译均参与；仓库其他文件、ZIP 时间戳和压缩格式不参与。
6. 所选内容相同则不提示更新，缓存不可变 SHA 的目录元数据，保留原安装来源。检查失败和列表不完整会报告问题。
7. 安装时检测下载量，超过 20 MiB 请求用户确认。已知总大小时提前提示；未知时在累计下载超过阈值时提示。确认仅适用于当前固定快照，更新下载使用相同规则。
8. 下载、安装或更新失败/取消时保留原版本并清理临时文件。最新 tag 缺少目录或包无效时报告错误，不自动切目录或回退旧 tag。

## 实现采用的限制与兼容性

- 仓库 ZIP 流式写入临时文件，最大 200 MiB 压缩大小、512 MiB 声明解压大小、50,000 条目；超出绝对上限会报错。
- 单插件原有大小、文件数、manifest/脚本/图标/翻译限制继续独立校验，下载确认不会绕过这些校验。
- 继续校验越界路径、符号链接、ZIP 目录范围、重复路径及多个仓库根；目录路径只解码一次，保存规范的相对路径。
- 根目录旧安装记录使用可选路径字段的默认值读取，索引 schemaVersion 不变；安装来源保留 tag/SHA，并新增目录。
- Android、Desktop、Apple 都保留插件权限审阅；大仓库下载确认与插件安装授权是两个步骤。
- 本次增加路径安装；来源范围继续为公共 GitHub HTTPS，branch/SHA URL fragment 仍不接受。

GitHub 的 Git Trees 接口提供路径、类型及 blob/tree SHA，可用于运行文件摘要初筛。列表截断会作为检查失败显示，不会据此宣称没有更新。这是 Flare 的实现选择。[GitHub Git Trees](https://docs.github.com/en/rest/git/trees#get-a-tree)

## 验证范围

实现包含独立插件版本、同仓双插件安装/更新/卸载、来源目录迁移、旧记录、无内容变化、新权限、非法路径、大小确认/取消、临时文件清理与归档上限的回归覆盖。
PR 前审查将更新候选和错误集合改为原子发布的不可变快照，避免后台检查与管理页观察并发读写可变集合；补充仓库身份异常、重试恢复后清除错误的断言。PR 基于 `feature/plugin-v2`。

本次执行结果：

| 检查 | 结果 |
| --- | --- |
| 插件完整 JVM 测试 | 404 通过，0 失败，1 个 live test 跳过 |
| Desktop 安装弹窗与管理页面测试 | 12 通过，0 失败 |
| SDK 测试 | 6 通过；含 88 个 manifest 用例及打包/生成项目构建检查 |
| Android | 应用和测试 APK 构建通过；模拟器安装弹窗/管理菜单 7 项通过；另完成 19 种状态截图 |
| iOS | 最终 Apple 共享框架 arm64 模拟器链接通过；前一轮完整应用构建及 19 种状态截图通过，包含 PluginManagementView |
| macOS | 最终 Apple 共享框架 arm64 链接通过；前一轮原生 macOS 截图构建及 19 种状态截图通过 |
| 静态检查 | 修改的 16 个 Kotlin 文件通过 ktlint 1.8.0；资源 JSON/XML 与 git diff --check 通过 |

未运行 Unity、真机或真实账号安装/更新。对 Unity 的路径归一化、编码异常、重复包名及 tag/branch 歧义没有从公开文档推断内部实现。
