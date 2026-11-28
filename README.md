# MobileIDE

MobileIDE 是一款 Android 原生通用型 IDE：在手机上直接编辑、构建并运行本地代码，不依赖远程服务器。

- 应用 ID：`dev.mobileide`
- 当前版本：`1.0.0`（versionCode `1`）
- 最低系统：Android 8.0（API 26）
- 目标 SDK：28（刻意选择，见下文“已知限制”）
- 分发方式：GitHub / 本地 APK（不上架 Google Play）

> 说明：当前版本已完成代码、单测、Lint、Debug/Release 构建与签名验证，但**尚未在真机或模拟器上执行端到端测试**。PRoot 环境安装、LSP 服务器启动、终端 PTY、Git 网络操作等依赖设备的行为均标记为“未验证”。详见 [已知问题](docs/KNOWN_ISSUES.md) 与 [测试报告](docs/TESTING_REPORT.md)。

## 功能概览

### 已实现

- **首页 / 项目管理**：最近项目、项目列表、搜索、创建、打开、重命名、移除记录、删除项目文件、路径失联重新关联。
- **文件管理器**：目录浏览、新建文件/文件夹、重命名、删除、搜索、文件信息、按扩展名识别类型、Git 状态标记。
- **代码编辑器**：Sora Editor、行号、撤销/重做、查找、光标与滚动位置恢复、未保存状态提示、关闭前确认。
- **多标签页**：多文件同时打开、切换、关闭、关闭其他/全部、标签内容与编辑状态隔离。
- **本地运行环境**：Debian 12（bookworm）arm64 PRoot 环境，下载校验（SHA-256）、安装、卸载、版本检测、状态管理。
- **构建系统**：按语言生成构建命令、构建日志、错误/警告解析、取消构建、产物列表、构建记录持久化。
- **集成终端**：基于 `forkpty` 的原生 PTY、多会话（默认 3，可配置 1–5）、实时输出、Ctrl+C、进程退出处理。
- **语言智能（LSP）**：C/C++（clangd）、Python（pylsp）、Java（JDT LS）的补全、悬停、跳转定义、查找引用、符号、格式化。
- **Git**：仓库初始化/检测、状态、暂存/取消暂存、丢弃、提交、历史、提交详情与 Diff、分支管理、Remote 管理、Fetch/Pull/Push、HTTPS/SSH 凭据（Android Keystore 加密）、`.gitignore` 模板。

### 有限支持或未实现

- **语法高亮**：C/C++/Java/Kotlin/JavaScript/TypeScript 复用 Sora 的 Java 词法器；Python 有独立扫描器；Go、Rust、HTML、CSS 目前为纯文本。
- **LSP**：Go、Rust、Kotlin、JavaScript、TypeScript、HTML、CSS 暂无可用 ARM64 服务器（catalog 中标记为 `unsupported` 并写明原因）。
- **Kotlin 工具链**：Debian 12 不提供 `kotlinc`，暂不能本地编译 Kotlin。
- **HTML/CSS**：可编辑，但没有浏览器预览与构建流程。
- **npm 安装通道**：尚未实现，因此依赖 npm 的 LSP 服务器暂不可用。
- **UI 自动化测试**：未提供 `androidTest`，需要真机/模拟器执行。

## 支持的语言与工具链

| 语言 | 编辑 / 高亮 | 构建 / 运行 | LSP |
| --- | --- | --- | --- |
| C | 支持（Java 词法器） | `gcc`（apt 安装） | clangd |
| C++ | 支持（Java 词法器） | `g++`（apt 安装） | clangd |
| Python | 支持（专用高亮 + 本地补全） | `python3`（apt 安装） | pylsp |
| Java | 支持（Java 词法器） | `javac`（OpenJDK 17） | JDT LS |
| Kotlin | 支持（Java 词法器） | 暂不支持（无 `kotlinc`） | 暂不支持 |
| JavaScript | 支持（Java 词法器） | `node` 直接运行脚本 | 暂不支持 |
| TypeScript | 支持（Java 词法器） | `tsc`（Debian `node-typescript`） | 暂不支持 |
| Go | 纯文本 | `go build`（apt 安装） | 暂不支持（Debian 12 的 gopls 过旧） |
| Rust | 纯文本 | `rustc` / `cargo`（apt 安装） | 暂不支持（无 ARM64 发布包） |
| HTML / CSS | 纯文本 | 无构建流程 | 暂不支持（npm 通道未实现） |

工具链与 LSP 服务器均在设备上的 Debian PRoot 环境中通过 apt 或带 SHA-256 校验的归档安装，不随 APK 打包。目录文件：

- `app/src/main/assets/runtime_catalog.properties`
- `app/src/main/assets/lsp_catalog.properties`

设备端可通过 `files/runtime-config/catalog.properties`、`files/lsp-config/catalog.properties` 覆盖。

## 系统要求

- Android 8.0（API 26）及以上。
- **arm64-v8a 设备**：完整功能（PRoot 运行时只提供 arm64 rootfs）。
- `armeabi-v7a`：APK 可安装，但本地 Linux 运行环境不可用。
- `x86_64`：APK 可安装，主要用于界面/编辑器验证；PRoot 运行时不可用。
- 建议预留约 2 GB 可用空间（Debian rootfs 下载约 95 MB，解压与工具链会继续占用）。
- 首次安装运行环境需要网络连接。

## 安装

1. 从 [dist/](dist/) 或 GitHub Releases 获取 `MobileIDE-1.0.0-release.apk`。
2. 在系统设置中允许当前文件管理器/浏览器“安装未知应用”。
3. 安装后首次启动，授予“所有文件访问权限”（Android 11+）或存储权限（Android 8–10）。
4. 在“运行环境”页面下载并安装 Debian 环境（需要网络）。

完整步骤与卸载说明见 [INSTALL.md](INSTALL.md)。

## 开发环境

- JDK 21
- Android SDK Platform 35、Build-Tools 35.0.0
- Android NDK `27.2.12479018`（原生 PTY 桥接）
- Gradle Wrapper 8.10.2（无需单独安装）
- 仅一个原生文件：`app/src/main/jni/mobileide_pty.c`

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

构建、签名与常见问题见 [BUILD.md](BUILD.md)。

## 测试与构建

```bash
# 单元测试（307 个）
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# Debug APK
./gradlew :app:assembleDebug

# Release APK / AAB（需要签名配置）
./gradlew :app:assembleRelease :app:bundleRelease
```

当前结果：307 个 JVM 单元测试全部通过，Lint 0 error / 13 warning，Release APK 9.2 MB，AAB 8.7 MB。详见 [测试报告](docs/TESTING_REPORT.md)。

## 项目结构

```
app/src/main/java/dev/mobileide/
├── core/          # 设计系统、文件系统、进程、存储、工具
├── data/          # Room、仓库实现、构建、运行时、终端、LSP、Git
├── domain/        # 模型、仓库接口、语言与校验规则
├── feature/       # Compose 页面与 ViewModel
├── di/            # 手写依赖容器
└── navigation/    # 导航图
app/src/main/jni/  # forkpty 原生桥接
app/src/test/      # JVM 单元测试
```

## 文档

- [BUILD.md](BUILD.md)：开发环境、编译、签名
- [INSTALL.md](INSTALL.md)：安装、权限、卸载
- [RELEASE.md](RELEASE.md)：发布流程与检查清单
- [CHANGELOG.md](CHANGELOG.md)：版本更新记录
- [已知问题](docs/KNOWN_ISSUES.md)
- [权限说明](docs/PERMISSIONS.md)
- [第三方依赖与许可证](docs/THIRD_PARTY_LICENSES.md)
- [第十二阶段测试报告](docs/TESTING_REPORT.md)

## 权限与隐私

- 项目文件保存在共享存储的 `AndroidProjects/` 目录（或用户通过系统文件选择器指定的目录）。
- 应用私有数据（数据库、运行时、日志、加密后的 Git 凭据）位于应用内部存储。
- Git 密码/Token 与 SSH 私钥使用 Android Keystore 的 AES-GCM 密钥加密后落盘，日志不输出凭据。
- 应用不含广告、统计 SDK，不上传项目内容。
- 权限用途与 `MANAGE_EXTERNAL_STORAGE` 的政策评估见 [docs/PERMISSIONS.md](docs/PERMISSIONS.md)。

## 已知限制

- 未在真机验证：安装/启动、PRoot 安装、apt、终端、LSP、Git 网络操作、Keystore、深浅色视觉。
- `targetSdk = 28` 是为保证应用私有目录内 `execve()` 可用（PRoot 依赖），因此不满足 Google Play 当前 targetSdk 要求。
- Release 暂未启用 R8/资源压缩（未做真机回归，避免不可验证的收缩问题）。
- 未添加开源许可证文件；在确认许可证前，仓库默认保留所有权利。第三方组件许可见上述文档。

## FAQ

**Q：为什么安装包里有三种 ABI？**
A：`arm64-v8a` 与 `armeabi-v7a` 来自项目原生配置，`x86_64` 用于模拟器上的界面验证。只有 arm64 设备能使用本地 Debian 环境。

**Q：为什么 targetSdk 是 28？**
A：本地运行环境通过 PRoot 执行应用私有目录中的可执行文件，targetSdk 30+ 会限制该行为。应用通过 GitHub 分发，不面向 Google Play。

**Q：卸载应用会删除项目吗？**
A：不会。项目位于共享存储，卸载只移除应用私有数据（数据库、运行时、凭据）。请自行备份项目目录。

**Q：Release APK 的签名密钥在哪里？**
A：密钥在仓库之外（本机构建使用 `~/.mobileide/mobileide-release.jks`），密码通过环境变量或未跟踪的 `keystore.properties` 提供，绝不提交仓库。详见 [RELEASE.md](RELEASE.md)。
