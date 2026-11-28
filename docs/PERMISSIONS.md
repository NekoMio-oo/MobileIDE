# 权限说明

MobileIDE 的权限清单来自 `app/src/main/AndroidManifest.xml`。应用不含广告、统计或推送 SDK。

## 1. 权限清单

| 权限 | 声明方式 | 用途 |
| --- | --- | --- |
| `android.permission.WRITE_EXTERNAL_STORAGE` | `maxSdkVersion="29"` | Android 8–10 上访问共享存储中的项目目录 |
| `android.permission.INTERNET` | 普通权限 | 下载 Debian rootfs、PRoot、apt 包，Git Fetch/Pull/Push |
| `android.permission.ACCESS_NETWORK_STATE` | 普通权限 | 在下载/Git 操作前判断网络可用性 |
| `android.permission.MANAGE_EXTERNAL_STORAGE` | 特殊权限 | Android 11+ 直接读写共享存储中的项目，并供终端/PRoot 使用真实路径 |
| `dev.mobileide.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 由 AndroidX 自动合并 | AndroidX 内部广播保护，不是用户可见权限 |

此外 Manifest 声明了 `queries`（`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`），仅用于在部分系统上找到“所有文件访问”设置页面，不涉及数据访问。

应用**不申请**：通知、相机、麦克风、位置、通讯录、日历、电话、短信、悬浮窗、无障碍、读取应用列表等权限。

## 2. 存储权限设计

### Android 8–10（API 26–29）

- 运行时请求 `WRITE_EXTERNAL_STORAGE`。
- `requestLegacyExternalStorage="true"`（targetSdk 28）使应用在 Android 10 上继续使用传统文件路径。
- 项目默认根目录：共享存储 `AndroidProjects/`。

### Android 11+（API 30+）

- `WRITE_EXTERNAL_STORAGE` 不再提供共享存储写权限，应用改用 `MANAGE_EXTERNAL_STORAGE`（“所有文件访问”）。
- 该权限不能在运行时弹窗授予，必须跳转系统设置页由用户手动打开：
  - `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
  - 回退：`Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`
- 未授权时项目创建/浏览不可用，应用会给出提示并提供跳转入口。

### 为什么需要“所有文件访问”

MobileIDE 不是简单的文档编辑器，而是需要：

1. 以**真实 POSIX 路径**把项目目录绑定进 PRoot（`/workspace`），供编译器、终端、Git 使用。
2. 让终端、构建进程、LSP 服务器直接读写项目文件。
3. 在文件管理器中执行重命名、删除、Git 状态扫描等基于 `java.io.File` 的操作。

SAF（`DocumentFile`/URI）无法提供可被 PRoot 绑定的真实路径，也无法让 Linux 工具链直接访问；改用 SAF 需要重写文件系统、终端、构建、Git 全链路，本阶段不实施。

## 3. Google Play 政策评估

> 结论：**当前版本不适合直接上架 Google Play**，通过 GitHub 分发。

原因：

1. `MANAGE_EXTERNAL_STORAGE` 属于 Play 的“特殊应用访问权限”。上架需要提交权限声明表，并证明应用的核心用途属于允许类别（文件管理器、备份恢复等）。IDE 是否被接受存在不确定性，Google 保留最终裁量权。
2. 应用 `targetSdk = 28`，不满足 Play 当前的 targetSdk 要求；而提高 targetSdk 到 30+ 会限制应用私有目录中的 `execve()`，直接破坏 PRoot 运行环境。
3. 若要在 Play 上架，需要：
   - 将主要文件访问改为 SAF，并提供可用的无 PRoot 降级模式；或
   - 将运行环境改为不依赖 `execve` 的方案（工作量大，未验证）。
   - 同时满足最新 targetSdk 与 64 位、AAB 等要求。

因此本项目选择 GitHub / 侧载分发，并在文档中明确说明权限用途。

## 4. 数据存放位置

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| 项目文件 | 共享存储 `AndroidProjects/` 或用户指定目录 | 卸载应用不会删除 |
| 项目数据库 | 应用私有 `databases/mobileide.db` | 记录名称、路径、时间 |
| Debian 运行环境 | 应用私有 `files/runtime/` | 卸载即删除 |
| 运行时/LSP 配置覆盖 | 应用私有 `files/runtime-config/`、`files/lsp-config/` | 卸载即删除 |
| 下载缓存 | 应用缓存 `cache/runtime-downloads/`、`cache/runtime-temp/` | 安装后/启动时清理 |
| Git 凭据 | 应用私有文件 + Android Keystore 密钥 | AES-GCM 加密后落盘 |
| 终端/LSP 设置 | 应用私有 `files/` | 不含明文密码 |

## 5. 凭据与日志

- HTTPS Token/密码、SSH 私钥通过 `AndroidKeystoreCredentialCrypto` 使用 AES-GCM 加密；Keystore 密钥不可导出。
- 凭据不会出现在日志、UI 状态或 SavedState 中（远程 URL 输入框也不会把含内嵌凭据的 URL 写入 SavedState）。
- 应用日志仅输出错误上下文（文件路径、服务器 ID、错误原因），不输出密码或 Token。
- `android:allowBackup="true"`：系统云备份可能包含数据库与设置；由于 Keystore 密钥不随备份迁移，备份中的凭据密文在其它设备上无法解密，应用会自动丢弃该条目。

## 6. 用户如何撤销权限

- 系统设置 → 应用 → MobileIDE → 权限：关闭“所有文件访问”或存储权限。
- 撤销后应用仍可启动，但项目相关操作会提示权限缺失；重新授权即可恢复，不会删除项目文件。
