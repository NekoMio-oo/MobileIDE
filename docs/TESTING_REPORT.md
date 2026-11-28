# MobileIDE 第十二阶段测试报告

- 版本：`1.0.0`（versionCode `1`）
- 日期：2026-11-27
- 提交基线：`2376ad0`（另含本阶段发布配置与文档改动）
- 报告性质：真实构建与真实 JVM 测试结果；**无真机/模拟器**，设备侧行为单独标注为“未验证”。

## 1. 测试环境

| 项目 | 值 |
| --- | --- |
| 构建主机 | Linux x86_64 |
| JDK | 21.0.13.1+2 (Eclipse Adoptium) |
| Gradle | 8.10.2（Wrapper） |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 28 / 26 |
| Build-Tools | 35.0.0 |
| NDK | 27.2.12479018 |
| 设备 | 无（`adb devices` 为空；未安装模拟器） |

测试命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

## 2. 工程检查结论

### 2.1 完成度

| 阶段 | 状态 |
| --- | --- |
| 1. 项目初始化与基础架构 | 已实现 |
| 2. 首页 UI | 已实现 |
| 3. 项目管理 | 已实现 |
| 4. 代码编辑器 | 已实现 |
| 5. 文件管理器 | 已实现 |
| 6. 多标签页 | 已实现 |
| 7. 本地运行环境 | 已实现（设备行为未验证） |
| 8. 构建系统 | 已实现（设备行为未验证） |
| 9. 集成终端 | 已实现（设备行为未验证） |
| 10. LSP | 已实现 3 种语言（设备行为未验证） |
| 11. Git | 已实现（设备行为未验证） |
| 12. 测试与发布 | 已完成构建、签名、文档与自动化测试 |

工程规模：主源码约 205 个 Kotlin 文件 + 1 个原生 C 文件（`mobileide_pty.c`）；47 个测试源文件；Room 数据库版本 2（含 1→2 非破坏性迁移）。

### 2.2 已知问题与缺失功能

| 类别 | 内容 |
| --- | --- |
| 功能缺失 | Kotlin 工具链；npm LSP 安装通道；HTML/CSS 预览；Go/Rust/Kotlin/JS/TS/HTML/CSS 的 LSP |
| 能力限制 | 语法高亮仅 Java 系 + Python；PRoot 仅 arm64 |
| 测试缺失 | 无 `androidTest` / Compose UI 测试；无性能剖析数据 |
| 分发限制 | targetSdk 28 与 `MANAGE_EXTERNAL_STORAGE` 不适合 Google Play |
| 文档/法律 | 未添加项目级 LICENSE；Sora Editor 为 LGPL-2.1 |
| 构建 | Release 未启用 R8/资源压缩 |

### 2.3 风险清单

- **编译风险**：低。Debug/Release/AAB 均在本机构建成功；CI 只构建 Debug。
- **权限与兼容性风险**：中。Android 11+ 依赖特殊权限；厂商后台限制未验证。
- **性能风险**：中。无真机数据；已通过 IO 调度、缓存上限、会话上限做静态控制。
- **发布风险**：中。Release 包为本地密钥签名；未做安装/启动验证；Play 政策不兼容。

## 3. 单元测试结果

| 指标 | 结果 |
| --- | --- |
| 测试源文件 | 47 |
| 测试用例 | **307** |
| 失败 | **0** |
| 错误 | **0** |
| 跳过 | **0** |
| 执行方式 | JVM（`testDebugUnitTest`） |

本阶段新增 12 个 UI 状态测试：

- `ProjectsUiStateTest`（6）：项目搜索按名称/位置/类型过滤、空查询、无匹配、待重新关联项目。
- `BuildConfigUiStateTest`（6）：自动/手动源文件、多行路径清理、entry/output 规范化、语言/模式/目标映射、classpath 适用范围。

覆盖重点：

| 模块 | 代表测试 |
| --- | --- |
| 文件系统 | `NativeFileSystemImplTest`、`ProjectRepositoryTest`（32 项：创建/打开/重命名/删除/搜索/越界与符号链接防护/2 MB 上限/二进制拒绝） |
| 多标签页 | `EditorTabManagerTest`（14 项）、`FileEditorSessionStoreTest` |
| 构建 | `BuildManagerTest`、`BuildProviderTest`、`BuildCodecTest`、`BuildPathsTest`、`DiagnosticCollectorTest` |
| 运行环境 | `LinuxRuntimeTest`、`ArchiveExtractorTest`、`DebExtractorTest`、`RuntimeCatalogLoaderTest`、`RuntimeManifestStoreTest`、`ProcessExecutorTest`、`Sha256Test` |
| 终端 | `TerminalEmulatorTest`、`TerminalKeyTest`、`TerminalPreferencesTest` |
| LSP | `LspMessageCodecTest`、`LspProtocolTest`、`LspConnectionTest`、`LspDocumentTrackerTest`、`LatestRequestRunnerTest`、`LspStoresTest`、`LspCatalogLoaderTest`、`GuestUriTest`、`LspTextTest` |
| Git | `GitRepositoryImplTest`（20 项）、状态/分支/日志/Diff/Remote/进度解析、凭据存储、`.gitignore` 模板 |
| 领域规则 | 文件类型、项目名称校验、语言识别、Python 高亮/补全、终端按键 |

## 4. 集成测试结果

未运行 Android instrumentation 测试。以下跨模块交互通过 JVM 层集成测试或代码审查验证：

| 交互 | 验证方式 | 结果 |
| --- | --- | --- |
| 项目管理 ↔ 文件管理器 | `ProjectRepositoryTest` 使用真实临时目录 | 通过（JVM） |
| 文件管理器 ↔ 编辑器 | 文件读写、大小/二进制限制测试 | 通过（JVM） |
| 编辑器 ↔ 多标签页 | `EditorTabManagerTest`、`FileEditorSessionStoreTest` | 通过（JVM） |
| 编辑器 ↔ LSP | `LspDocumentTrackerTest`、`LatestRequestRunnerTest`、URI 测试 | 通过（JVM） |
| 运行环境 ↔ 构建系统 | 构建命令生成与工具链探测测试（真实进程未启动） | 部分通过（JVM） |
| 运行环境 ↔ 终端 | 会话/偏好测试；PTY 未启动 | 部分通过（JVM） |
| 构建系统 ↔ 终端 | 命令与日志解析测试；真实执行未验证 | 部分通过（JVM） |
| 文件管理器/编辑器 ↔ Git | Git 仓库实现与状态解析测试；真实 git 进程未运行 | 部分通过（JVM） |

## 5. UI 测试结果

**未执行。** 仓库不包含 `androidTest`，环境无设备/模拟器。

因此以下项目均未验证：页面导航、对话框、按钮/输入框、标签切换、文件操作反馈、错误提示、深浅色模式、横竖屏与不同屏幕尺寸。

## 6. 完整功能回归矩阵

状态说明：`通过（JVM）` = 有自动化测试；`代码审查` = 逻辑与错误路径已检查但未运行；`未验证` = 依赖设备/网络，当前无法执行；`不支持` = 明确未实现。

| 功能 | 子项 | 状态 |
| --- | --- | --- |
| 首页 | 启动、最近项目、新建/打开入口、导航、设置 | 代码审查；`ProjectsUiState` 搜索通过（JVM）；启动/布局未验证 |
| 项目管理 | 创建、打开、删除记录、重命名、路径管理、数据库一致性 | 通过（JVM）；Room 迁移代码审查；真实升级未验证 |
| 文件管理器 | 浏览、创建、重命名、删除、搜索、信息、类型识别 | 通过（JVM）；UI 操作未验证 |
| 编辑器 | 打开、读取、编辑、保存、行号、高亮、撤销重做、错误处理 | 通过（JVM，文件/标签/高亮词法）；Sora 实际渲染未验证 |
| 多标签页 | 多开、切换、关闭、未保存状态、内容隔离、光标/滚动恢复 | 通过（JVM）；UI 未验证 |
| 本地运行环境 | 检测、安装、卸载、版本检查、状态管理 | 通过（JVM，目录/清单/解压/校验）；真实下载与 PRoot 启动未验证 |
| 构建系统 | 配置、启动、日志、错误定位、取消、产物 | 通过（JVM，命令/解析/状态）；真实编译未验证 |
| 集成终端 | 启动、执行、实时输出、Ctrl+C、多会话、退出 | 通过（JVM，模拟器/按键/偏好）；真实 PTY 未验证 |
| LSP | 启动、补全、诊断、悬停、定义、引用 | 通过（JVM，协议/文档/请求）；真实服务器未验证；仅 C/C++/Python/Java |
| Git | 初始化、状态、暂存、提交、分支、远程、Fetch/Pull/Push | 通过（JVM，解析/仓库/凭据加密）；真实 git 与网络未验证 |

**未实现功能没有被标记为通过**：Kotlin 构建、Go/Rust/Kotlin/JS/TS/HTML/CSS 的 LSP、HTML/CSS 预览、npm 通道均明确列为不支持。

## 7. 性能测试

未进行设备性能剖析（无设备），以下为静态检查与设计约束：

| 项目 | 现状 |
| --- | --- |
| 主线程 IO | 仓库层 47 处 `withContext(Dispatchers.IO)`；文件/搜索/构建/Git 操作不在主线程 |
| 大文件 | 可编辑文件上限 2 MB，超限拒绝打开 |
| 日志 | 单个构建日志上限 2 MB；UI 只保留最近若干行 |
| 终端 | 回滚缓冲有上限；默认最多 3 个会话（1–5 可配） |
| LSP | 全局最多 2 个服务器（1–3 可配）；空闲 10 分钟回收 |
| 搜索 | 由文件系统实现返回并限制条目数量；未做设备耗时测量 |
| 缓存 | 下载缓存安装后清理；临时目录独立于项目 |
| 启动 | 未测量冷启动/热启动时间、首页加载与数据库初始化耗时 |

内存、CPU、滚动帧率、编辑器输入延迟、构建期间占用均**没有数据**，不得视为已优化或已达标。

## 8. Android 兼容性

| 项目 | 结论 |
| --- | --- |
| 最低/目标 SDK | 26 / 28；API 26–35 均可安装（targetSdk 28 在 Android 14+ 侧载通常可行） |
| 存储权限 | API 26–29 使用运行时存储权限；API 30+ 使用 `MANAGE_EXTERNAL_STORAGE` |
| 所有文件访问 | 必须由用户在系统设置授予；跳转逻辑已实现，未真机验证 |
| 后台进程限制 | 终端/构建/LSP 依赖前台页面与协程；厂商杀进程行为未验证 |
| Activity 生命周期 | 声明 `configChanges` 处理旋转/深色模式；草稿与未保存状态有确认机制；进程回收恢复未验证 |
| 文件读写 | 真实路径 + `java.io.File`；符号链接越界与根目录保护有单测 |
| 通知权限 | 未申请（当前不需要） |
| 网络权限 | 已声明 INTERNET / ACCESS_NETWORK_STATE；无明文流量策略覆盖（targetSdk 28 默认允许） |
| 应用数据目录 | 私有目录存运行时/数据库/凭据；共享存储存项目 |
| 深色模式 | Compose + Material 3 + 编辑器配色随主题切换；视觉未验证 |
| 屏幕方向/尺寸 | 未锁定方向；分屏/折叠屏/字体缩放未验证 |

`MANAGE_EXTERNAL_STORAGE` 的 Play 政策评估见 [PERMISSIONS.md](PERMISSIONS.md)。

## 9. 安全检查

| 检查项 | 结果 |
| --- | --- |
| 项目文件误删 | 删除项目文件需二次确认；项目根目录受保护；包含其他项目的目录拒绝删除（单测） |
| 数据库升级 | Room 1→2 迁移保留记录并保留旧 URI；未做真实旧数据升级测试 |
| 未保存内容 | 关闭标签/退出时对脏标签弹出确认；支持保存、放弃、取消 |
| 运行环境卸载 | 仅删除应用私有运行时目录，不触碰项目文件 |
| Git 破坏性操作 | 丢弃更改、删除分支等有确认对话框 |
| 下载完整性 | rootfs / PRoot / LSP 归档均校验 SHA-256；无校验的条目不安装 |
| 凭据存储 | AES-GCM + Android Keystore；密文落盘，密钥不可导出 |
| 日志泄密 | 未发现 Token/密码日志；URL 输入不写入 SavedState |
| 临时文件 | 下载缓存与临时目录在应用缓存中，安装后清理 |
| 卸载行为 | 应用私有数据删除，共享存储项目保留（文档已说明） |
| Release 配置 | `debuggable=false`；未打包测试依赖；签名密钥不在仓库 |
| 权限最小化 | 仅存储与网络权限；无通知/相机/位置等 |

未验证项：Keystore 在真实设备上的生成/解密、备份恢复后的密文行为、厂商系统的权限撤销流程。

## 10. Release 构建结果

| 项目 | 结果 |
| --- | --- |
| `assembleDebug` | 成功，14 MB |
| `assembleRelease` | 成功，9.2 MB |
| `bundleRelease` | 成功，8.7 MB |
| Release Lint（`lintVitalRelease`） | 通过 |
| APK 签名 | APK Signature Scheme v2，证书 `CN=MobileIDE` |
| AAB 签名 | `jarsigner -verify` 退出码 0 |
| versionName / versionCode | 1.0.0 / 1 |
| applicationId | `dev.mobileide` |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`（每个 2 个原生库） |
| 产物校验 | `dist/SHA256SUMS` |
| 安装/启动 | **未验证（无设备）** |

证书 SHA-256 指纹：

```
55:07:F0:28:C1:1D:F5:38:E1:A1:85:4E:28:31:9A:EE:F4:00:79:17:4D:47:11:37:0D:F4:4D:AD:1F:B1:BE:9E
```

## 11. 发布前最终验收

| # | 要求 | 状态 |
| --- | --- | --- |
| 1 | Gradle Release 构建成功 | 通过 |
| 2 | Release APK 能正常安装 | 未验证（无设备） |
| 3 | Release APK 能正常启动 | 未验证（无设备） |
| 4 | 核心功能回归测试 | JVM 通过；设备回归未执行 |
| 5 | 数据库迁移正常 | 代码审查；真实升级未验证 |
| 6 | 文件读写正常 | JVM 通过；设备未验证 |
| 7 | 编辑器工作正常 | JVM 通过；Sora 渲染未验证 |
| 8 | 多标签页工作正常 | JVM 通过；UI 未验证 |
| 9 | 运行环境工作正常 | JVM 通过；安装/启动未验证 |
| 10 | 构建系统工作正常 | JVM 通过；真实编译未验证 |
| 11 | 终端工作正常 | JVM 通过；PTY 未验证 |
| 12 | LSP 已支持语言功能正常 | 协议层通过；服务器未验证 |
| 13 | Git 基本操作正常 | JVM 通过；真实 git/网络未验证 |
| 14 | 无已知阻断性崩溃 | 未发现代码级阻断问题；未做设备稳定性测试 |
| 15 | 签名配置正确 | 通过 |
| 16 | 安装包架构正确 | 通过（3 ABI，arm64 优先） |
| 17 | 发布文档完整 | 通过 |
| 18 | 权限与第三方许可证检查 | 通过（见 PERMISSIONS / THIRD_PARTY_LICENSES） |

## 12. 结论

- 代码、单元测试、Lint、Debug/Release/AAB 构建与签名均真实执行并通过。
- 设备侧功能（安装、启动、PRoot、终端、LSP、Git 网络、Keystore、UI 视觉）在当前环境无法验证，报告中已逐项标注，未伪造通过。
- 当前 Release 包可作为**内部测试/侧载发布**使用；面向公众发布前，建议在至少一台 arm64 真机上完成第 11 节中的第 2、3、5–14 项。
- 后续优先事项：真机回归 → Android 兼容性适配 → LSP/工具链扩展 → 评估 targetSdk 与 Play 分发方案。
