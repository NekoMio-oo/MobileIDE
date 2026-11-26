# MobileIDE 项目开发规范

## 1. 项目定位

开发一款 Android 原生通用型 IDE，支持在 Android 手机上进行本地代码编辑、构建和运行。

## 2. 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- MVVM
- Kotlin Coroutines
- Room
- DataStore
- Android NDK
- Sora Editor

### 原生构建（终端 PTY）

- 唯一的原生代码是 `app/src/main/jni/mobileide_pty.c`（forkpty 桥接），使用 `ndk-build` 构建。
- 需要 **Android NDK 27.2.12479018**；AGP 通过 `ndkVersion = "27.2.12479018"` 查找，缺失时构建会直接失败。
- ABI 仅 `arm64-v8a` 与 `armeabi-v7a`。不要为终端功能再引入第三个原生库。
- 除该文件外，其余功能一律 Kotlin，新增 JNI 前先说明必要性。

### 语言智能（LSP）

- 分层：`domain/lsp`（模型与 `LspRepository`）、`data/lsp`（协议、进程、会话、安装器）、`feature/lsp`（设置页）。
- 编辑器与设置页只依赖 `LspRepository`，不得直接启动进程或拼 JSON-RPC。
- 语言服务器在 Debian PRoot 内运行，通过 **stdio** 通信；不监听端口、不使用 root，项目只以 `/workspace` 绑定可见。
- 协议层必须是 `Content-Length` 字节帧 + JSON-RPC 2.0；不要改成按行读取，也不要引入第二套 JSON 库（使用平台 `org.json`）。
- 服务器目录在 `assets/lsp_catalog.properties`，设备可用 `files/lsp-config/catalog.properties` 覆盖。只登记有真实 ARM64 来源的服务器；其余必须保持 `unsupported` 并写明原因。
- 诊断使用 Sora 的 `DiagnosticRegion` 字符偏移，不要直接传行列。
- 全局同时最多 2 个会话（可配 1–3），空闲 10 分钟回收；新增语言前先确认内存与启动成本。

## 3. 支持语言

- C
- C++
- Python
- Java
- Kotlin
- JavaScript
- TypeScript
- Go
- Rust
- HTML
- CSS

## 4. 运行方式

所有代码优先在 Android 本地设备运行，不依赖远程服务器。

## 5. UI 设计原则

- 手机优先，适配不同屏幕尺寸。
- 使用 Material 3。
- 界面简洁、现代、专业。
- 使用统一的颜色、间距、圆角和字体规范。
- 支持深色模式。
- 避免堆砌功能和复杂菜单。

## 6. 开发规范

- 优先采用稳定、成熟的 Android 原生技术。
- 避免不必要的第三方依赖。
- 遵循单一职责原则。
- UI、业务逻辑、数据层分离。
- 使用 Kotlin Coroutines 处理异步任务。
- 所有功能必须能够实际运行，不能只制作 UI 假象。
- 修改代码后检查编译结果。
- 不得随意删除已有功能。
- 不得未经确认修改项目整体架构。
- 不确定的 API 或依赖版本必须先核实。

## 7. 开发流程

每次任务按照以下顺序：

1. 分析现有项目结构。
2. 明确本次任务的实现方案。
3. 编写代码。
4. 执行 Gradle 编译检查。
5. 修复错误。
6. 总结修改的文件和实现的功能。

一次只实现一个明确的功能。
