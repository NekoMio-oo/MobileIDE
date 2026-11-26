# MobileIDE — 项目约定

## 形态
原生 Android 单模块应用（`:app`），Kotlin + Jetpack Compose + Material 3。不要引入 WebView / HTML / React Native 实现界面。

## 版本矩阵（已在本机验证）
- AGP 8.7.2 · Gradle 8.10.2 · Kotlin 2.0.21（+ compose 插件）· KSP 2.0.21-1.0.28
- Compose BOM 2024.10.01 · compileSdk 35 · **targetSdk 28（刻意）** · minSdk 26 · JVM target 17
- targetSdk 28 的原因：targetSdk≥29 的 SELinux W^X 禁止 execve app 私有目录，PRoot/Debian 本地运行依赖该能力；应用只走 GitHub 分发，不上 Play。lint 已禁用 ExpiredTargetSdkVersion。
- Room 2.6.1（runtime/ktx/compiler），schema 导出到 `app/schemas`
- Sora Editor 0.23.5（editor + language-java）。**不要升 0.23.6**：它用 Kotlin 2.1.21 编译，与项目 Kotlin 2.0.21 元数据不兼容。
- 依赖为适配 compileSdk 35 的保守组合；lint 提示的更高版本需要 compileSdk 36，先别动。
- 归档/运行时：`org.tukaani:xz:1.10` + `org.apache.commons:commons-compress:1.26.2`（解 tar.xz/.deb/zip）。
- 本机 SDK 已装 **NDK 27.2.12479018**（终端 PTY 用），仍然没有 CMake；除此之外不要新增 JNI。运行时走 PRoot 而非嵌入 libpython/libnode。

## 构建
```
JAVA_HOME=/home/Tsuki/tools/jdk-21.0.13.1+2 \
ANDROID_HOME=/home/Tsuki/tools/android-sdk \
LD_PRELOAD=/tmp/shim3.so \
./gradlew :app:assembleDebug --no-daemon
```
`LD_PRELOAD` 仅用于绕过本机沙箱对 `utimes` 的 EPERM，不是项目依赖；CI 上不需要。
`local.properties` 指向 `~/tools/android-sdk`，不入库。
`org.gradle.caching=false`：资源目录改名后曾复用过期产物导致 aapt 报资源缺失。
单测：`./gradlew :app:testDebugUnitTest`（当前 219 个 JVM 测试）。

## 代码结构约定
- 分层：`data/local|repository`、`domain/model|repository|template|validation`、`core/filesystem`、`core/storage`、`feature/*`、`navigation`、`di/AppContainer`（手写 DI，未引 Hilt）。
- 存储已彻底移除 SAF：统一 `NativeFileSystem`（绝对路径），只允许共享存储根（primary + 可移动卷），拒绝 `..`、`/Android/data`、`/Android/obb`；禁止 `content://`、DocumentFile、ContentResolver 读项目文件。
- 项目根目录固定 `/storage/emulated/0/AndroidProjects`，`ProjectRootStore.ensureRoot()` 在获得权限后创建；权限用 `MANAGE_EXTERNAL_STORAGE`（API 30+），26–29 用 `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`。
- 数据库 `ProjectEntity.path` 存绝对路径（可空）；旧 SAF 记录迁移后 `path = NULL`、`legacyUri` 保留，用应用内文件夹选择器重新定位；移除记录不删磁盘文件。
- 数据库 v2 + `MIGRATION_1_2`（重建表、保留所有行、`path` 唯一索引）；**不要改成破坏性迁移**，也不能清空旧数据。
- 删除项目目录前会检查是否包含其他已登记项目，包含则拒绝；`NativeFileSystem` 拒绝删除存储根本身。
- 项目文件读写经 `ProjectRepository`（2 MB 上限、二进制拒绝、相对路径越界校验），UI 不直接读文件。
- 文件管理器操作也走 `ProjectRepository`：`createProjectEntry / renameProjectEntry / deleteProjectEntry / countProjectEntries / searchProjectFiles`。`resolveProjectPath` 对根与目标都做 `canonicalFile`，符号链接无法逃出项目；删除根路径直接拒绝。
- 扩展名映射唯一来源是 `domain/files/FileType`，`EditorLanguage.fromFileName` 委托给它；加语言只改这一处。
- 文件/文件夹名称规则用 `EntryNameValidator`（允许 `.gitignore` 这类前导点，禁止 `/ \ : * ? " < > |`、结尾点、`.`/`..`）。
- 浏览采用「点击进入 + 面包屑/上一级返回」，不做内联树展开（手机窄屏 + 需求同时要求点击进入）。
- 搜索走 `NativeFileSystem.searchFiles`（限深 12、限条目 12000、结果 200、跳过符号链接与隐藏目录/node_modules，`ensureActive` 支持取消）；扩展名筛选只在文件上生效。
- 文件管理器 UI：`feature/fileexplorer`（Screen/ViewModel/model/components）；项目路由仍是 `workspace/{projectId}`。
- 多标签：`data/editor/EditorTabManager` 是唯一真相源（StateFlow，按项目），标签 id = 绝对路径；`EditorController` 为每个标签缓存 Sora `Content`（文本/撤销/光标），用 `setText(content, true, Bundle())` 复用。VM 不碰 Sora 类型。
- 会话恢复：`EditorSessionStore` 写 `filesDir/editor_sessions/project-<id>.session`（二进制、原子替换、只存脏标签文本）；进入编辑器时 restore，切标签/关闭/保存/ON_STOP 持久化。不写 Room。
- 保存前用 `getProjectFileInfo` 比对 `baselineModified`，磁盘已变则弹覆盖/重载；文件删除后 `writeTab` 直接 Missing，不重建文件。
- 文件管理器重命名/删除成功后调用 `tabManager.onEntryRenamed/onEntryDeleted`；删除项目调用 `onProjectRemoved`。
- `feature/<name>`：`XxxScreen.kt` + `XxxViewModel.kt` + `model/`（UiState）+ `components/`；无状态内容抽成私有 Composable。
- 图标用手绘 24dp stroke vector，不要引 material-icons-extended；文案进 `strings.xml`，数量用 plurals；内容最大宽度 640dp 居中。
- 编辑器：`feature/editor` + `EditorController` 封装 Sora，VM 不碰 Sora 类型；`AndroidView` 只创建一次，文本只在 factory 注入（主题变化不重置撤销栈）。
- MainActivity 声明了 `configChanges`（orientation/screenSize/uiMode 等），避免旋转或深色切换重建 Activity 丢失未保存内容；状态栏图标外观需在 Compose 里跟随 `isSystemInDarkTheme` 重设。
- 编辑器/工作区路由参数用 Base64 URL-safe 编码（`Routes.editor`），不要依赖 Navigation 的 URI 解码。
- 文件读写走 `ProjectRepository` 的 list/read/write（内部走 NativeFileSystem），2 MB 上限、二进制拒绝，禁止在 UI 直接读文件。

## 状态
- 已实现：创建项目（11 类模板，固定根目录）、NativeFileSystem 打开目录、Projects 搜索/重命名/移除/删文件、Home 真实最近项目、访问失效提示、旧 SAF 项目重新定位、应用内文件夹选择器、完整项目文件管理器（浏览/面包屑/搜索+扩展名筛选/新建文件与文件夹/重命名/删除/详情/权限门禁/二进制拦截）、Sora 代码编辑器（打开/编辑/行号/保存/撤销重做/查找/快捷输入/未保存确认）。
- 高亮：C/C++/Java/Kotlin/JS/TS 走 Sora `JavaLanguage`；Python/Go/Rust/HTML/CSS 为纯文本（0.23 只维护 Java 语言模块，旧 language-python 0.8.4 接口已废弃）。曾写过自研 PythonHighlighter/PythonLanguage（见 2026-11-27 日志的 API 坑），**整轮修复被用户要求回退**。
- 运行环境（第七阶段）：`domain/runtime` + `data/runtime` + `feature/runtimes`。目录 `files/runtime`、`cache/runtime-downloads`、`cache/runtime-temp`、`files/runtime-config`；catalog 在 assets `runtime_catalog.properties`，设备可覆盖 `files/runtime-config/catalog.properties`。Linux 环境 = Debian 12 arm64 rootfs（LXC 快照，SHA256）+ Termux proot deb + libtalloc/libandroid-shmem；`ProotCommand` 用 `PROOT_LOADER`/`LD_LIBRARY_PATH` 执行。语言工具链用 apt 安装、`command -v` + `--version` 检测。执行器 `ProcessExecutor` 流式输出/超时/强杀。
- 构建系统（第八阶段）：`domain/build` + `data/build` + `feature/build`。`BuildManager` 是唯一执行入口（全局单任务），命令一律 argv 经 `LinuxRuntime` + `ProcessExecutor`，项目根 bind 到 guest `/workspace`。配置 `.mobileide/build.properties`、任务 `.mobileide/tasks/*.properties`、完整日志 `.mobileide/logs/*.log`、产物 `build/<mode>/`。provider：C/C++（gcc/clang 探测）、Java（javac+jar）、Go（go.mod，linux/arm64 固定）、Rust（Cargo.toml，CARGO_TARGET_DIR）、TS（tsconfig 或显式源）；Kotlin/脚本语言明确未支持。
- 诊断解析：clang/gcc、javac、go、rustc、tsc；点击问题经 `EditorRevealRequests` 打开编辑器并定位行列。取消构建会杀掉整个进程树（pid 文件 + /proc children）。
- 终端（第九阶段）：唯一原生代码 `app/src/main/jni/mobileide_pty.c`（forkpty）+ `core/process/PtyNative`；构建需要 **NDK 27.2.12479018** 与 ndk-build，ABI arm64-v8a/armeabi-v7a。`domain/terminal`（VT100 子集模拟器 + 模型）、`data/terminal`（Session/Process/Manager/Preferences）、`feature/terminal`。会话是 application 作用域，最多 3 个；Android 用 `/system/bin/sh`，Linux 用 proot `-0`（fake root，非真 root）绑项目到 `/workspace` 再起 bash。- 输入是 Termux 式直通：`TerminalInputView`（不可编辑 EditText + InputConnection）把每个按键写入 pty，辅助键条发送控制序列。用户真机反馈：点击终端空白不唤起输入法——现用 FocusRequester 对 AndroidView wrapper 请求焦点，wrapper 没有 InputConnection；曾改 focusForInput()（真实 EditText 上 requestFocus+showSoftInput）但整轮修复已被用户要求回退。
- 容器 apt：`/etc/apt/apt.conf.d/99mobileide` 关闭 `Acquire::Check-Valid-Until`（设备时钟偏移会让 Release 被判过期），安装器与启动 Linux 会话时都会写入；签名校验仍然开启。
- 语言智能（第十阶段）：`domain/lsp` + `data/lsp` + `feature/lsp`。`LspRepository` 是唯一入口；`LspConnection` 自己实现 Content-Length 帧 + JSON-RPC 2.0（请求 ID/超时/取消/服务器反向请求），`LspSession` 管一个 PRoot 内进程，`LspSessionManager` 按 (项目, 服务器) 复用、最多 2 个会话、空闲 10 分钟回收、崩溃重启 2 次。
- 服务器运行在 Debian PRoot 内，项目 bind 到 `/workspace`，HOME 绑到 `files/lsp/home`；URI 映射走 `GuestUri`（只接受 `/workspace` 内路径）。目录 `files/lsp/{servers,home,workspaces}`、`files/lsp-config/*.properties`、`cache/lsp-downloads`。
- 服务器目录：`assets/lsp_catalog.properties`（设备可覆盖 `files/lsp-config/catalog.properties`）。已接入 clangd 14（apt）、pylsp 1.7（apt，含 jedi/rope/yapf/autopep8/flake8）、Eclipse JDT LS **1.31.0**（归档，SHA256 固定，需 openjdk-17）。**不要升 JDT LS 1.63**：它要求 Java 21，Debian 12 只有 17。Go/Rust/Kotlin/JS/TS/HTML/CSS 明确 unsupported 并写明原因。
- Sora 集成：`LspLanguage` 用函数形式实现 `Language`（Kotlin 不能用属性覆写这些 Java getter），`requireAutoComplete` 里同步取 LSP 补全；诊断用 `DiagnosticRegion` 字符偏移；格式化用 `Content.beginBatchEdit` 合并撤销。菜单项只在会话 READY 时出现。用户真机反馈：接入 LSP 后 C 文件补全消失——原实现从不调 delegate、阻塞 LSP 请求后触碰失效 ContentReference；曾按「先 delegate→阻塞前快照 prefix→validateAccess 守卫」修复但整轮被用户要求回退，修复方案是否引入新问题待查。
- 文档同步：全部全文同步，版本号只增；编辑器侧 300ms 防抖；切标签前先 flush 当前文本；离开编辑器 `closeProject`（只关文档，服务器走空闲回收），删除项目 `stopProject`。
- 未实现：Tree-sitter、Git、重命名/CodeAction/SignatureHelp/折叠、每项目 LSP 配置文件。
