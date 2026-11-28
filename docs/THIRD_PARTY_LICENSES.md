# 第三方依赖与许可证

MobileIDE 使用以下第三方组件。版本以 `app/build.gradle.kts` 与 Gradle 解析结果为准。

## 1. 随 APK 分发的依赖

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Kotlin Standard Library | 2.0.21 | Apache-2.0 | Kotlin 运行时 |
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | Android 基础扩展 |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | Compose Activity 宿主 |
| AndroidX Lifecycle（runtime/compose/viewmodel） | 2.8.7 | Apache-2.0 | 生命周期与 ViewModel |
| AndroidX Navigation Compose | 2.8.4 | Apache-2.0 | 页面导航 |
| Jetpack Compose（BOM） | 2024.10.01 | Apache-2.0 | 声明式 UI |
| Compose Material 3 | 由 BOM 管理 | Apache-2.0 | Material 3 组件 |
| AndroidX Room（runtime/ktx/compiler） | 2.6.1 | Apache-2.0 | 本地数据库 |
| Sora Editor（editor、language-java） | 0.23.5 | **LGPL-2.1** | 代码编辑器与 Java 词法器 |
| XZ for Java (`org.tukaani:xz`) | 1.10 | 0BSD | `.deb` / rootfs 的 XZ 解压 |
| Apache Commons Compress | 1.26.2 | Apache-2.0 | tar/zip/ar 解析 |
| Apache Commons Codec（传递依赖） | 1.17.0 | Apache-2.0 | Commons Compress 依赖 |
| Apache Commons IO（传递依赖） | 2.16.1 | Apache-2.0 | Commons Compress 依赖 |
| Kotlin Coroutines | 由 Kotlin/AndroidX 传递 | Apache-2.0 | 异步与协程 |

## 2. 仅测试使用

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| JUnit | 4.13.2 | Eclipse Public License 1.0 |
| org.json:json | 20240303 | Public Domain |
| kotlinx-coroutines-test | 1.8.1 | Apache-2.0 |

## 3. 运行时下载、不随 APK 分发

以下内容由用户在设备上按需下载，安装于应用私有目录：

| 组件 | 来源 | 许可证 / 说明 |
| --- | --- | --- |
| Debian 12 (bookworm) arm64 rootfs | Linux Containers images | Debian 各软件包各自许可证（DFSG） |
| PRoot | Termux 仓库（aarch64 构建） | GPL-2.0 |
| libtalloc / libandroid-shmem | Termux 仓库 | 以 Termux 上游许可证为准 |
| clangd | Debian 仓库 | Apache-2.0 with LLVM Exceptions |
| python-lsp-server (pylsp) | Debian 仓库 | MIT |
| Eclipse JDT Language Server | Eclipse 官方归档 | EPL-2.0 |
| 各语言工具链（gcc、g++、OpenJDK、Go、Rust、Node.js、TypeScript） | Debian 仓库 | 以 Debian 软件包许可证为准 |

> APK 本身不包含上述二进制；下载地址与 SHA-256 校验值位于 `app/src/main/assets/runtime_catalog.properties` 与 `lsp_catalog.properties`。

## 4. LGPL-2.1 合规说明（Sora Editor）

- MobileIDE 以**未修改的 AAR 库**形式链接 Sora Editor。
- Sora Editor 源码与许可证：
  - https://github.com/Rosemoe/sora-editor
  - https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
- 应用不修改 Sora 的源码；如需替换该库，可在修改 `app/build.gradle.kts` 中的依赖版本/来源后重新构建。
- 分发本应用时应保留本说明，并可提供 Sora Editor 的许可证文本与源码地址。

## 5. 其他说明

- 本项目**尚未添加**自己的 LICENSE 文件；在项目所有者确认许可证之前，仓库内容默认保留所有权利。
- 如果引入新的第三方依赖，请同时更新本文件与 `README.md` 的许可证说明。
- 本文件不构成法律意见；正式分发前建议由项目所有者复核 LGPL/ GPL 组件的分发方式。
