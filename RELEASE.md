# 发布流程

本文档描述 MobileIDE 的正式发布步骤。发布包必须来自干净的 Git 工作区，并使用仓库外的 Release 密钥签名。

## 1. 版本规则

| 项目 | 当前值 | 位置 |
| --- | --- | --- |
| applicationId | `dev.mobileide` | `app/build.gradle.kts` |
| versionCode | `1` | `app/build.gradle.kts` |
| versionName | `1.0.0` | `app/build.gradle.kts` |
| minSdk | 26 | `app/build.gradle.kts` |
| targetSdk | 28 | `app/build.gradle.kts` |
| compileSdk | 35 | `app/build.gradle.kts` |

每次正式发布：`versionCode` 必须递增，`versionName` 按语义化版本更新，并同步更新 `CHANGELOG.md`。

## 2. 签名安全

- 密钥库：仓库外，例如 `~/.mobileide/mobileide-release.jks`。
- 密码：环境变量或仓库外/未跟踪的 `keystore.properties`。
- `.gitignore` 已忽略：`*.keystore`、`*.jks`、`*.p12`、`keystore.properties`。
- 不要在源码、脚本、CI 配置、日志、聊天记录中写入密码。
- 备份密钥库与密码；丢失后无法对已安装应用做覆盖升级。
- 当前本机构建所用证书指纹（SHA-256）：

```
55:07:F0:28:C1:1D:F5:38:E1:A1:85:4E:28:31:9A:EE:F4:00:79:17:4D:47:11:37:0D:F4:4D:AD:1F:B1:BE:9E
```

> 该密钥由本机生成并保存在 `~/.mobileide/`，没有提交到仓库。若由团队/个人正式接管发布，请生成并离线保管自己的密钥，然后更新本节指纹。

签名配置方法见 [BUILD.md](BUILD.md#5-release-签名)。

## 3. 发布前检查清单

### 3.1 代码与测试

- [ ] `git status` 干净，确认在预期提交上构建。
- [ ] `./gradlew :app:testDebugUnitTest` 通过（当前 307 个测试）。
- [ ] `./gradlew :app:lintDebug` 无 error。
- [ ] `./gradlew :app:assembleDebug` 通过。
- [ ] 关键功能已完成回归（真机部分如未执行，必须在发布说明中标注）。

### 3.2 配置

- [ ] `applicationId`、`versionCode`、`versionName` 正确。
- [ ] `minSdk` / `targetSdk` 变更已评估（targetSdk 28 与 PRoot 的依赖关系）。
- [ ] 权限清单无新增敏感权限。
- [ ] 未引入调试专用配置；Release 包 `android:debuggable=false`。

### 3.3 构建与签名

- [ ] `./gradlew :app:assembleRelease :app:bundleRelease` 成功。
- [ ] `apksigner verify --print-certs` 通过，证书与发布证书一致。
- [ ] APK 包含预期 ABI（当前为 `arm64-v8a`、`armeabi-v7a`、`x86_64`）。
- [ ] 更新 `dist/SHA256SUMS`。

### 3.4 文档

- [ ] README、CHANGELOG、BUILD、INSTALL、RELEASE 与实际行为一致。
- [ ] 已知问题、权限说明、第三方许可证已更新。
- [ ] 未实现功能如实标注，未标记为“已完成”。

## 4. 构建发布包

```bash
# 1. 干净的 Release 构建
export MOBILEIDE_KEYSTORE_FILE="$HOME/.mobileide/mobileide-release.jks"
export MOBILEIDE_KEYSTORE_PASSWORD='********'
export MOBILEIDE_KEY_ALIAS=mobileide
export MOBILEIDE_KEY_PASSWORD='********'

./gradlew clean :app:testDebugUnitTest :app:lintDebug \
  :app:assembleRelease :app:bundleRelease

# 2. 整理产物
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/MobileIDE-1.0.0-release.apk
cp app/build/outputs/bundle/release/app-release.aab dist/MobileIDE-1.0.0-release.aab
cd dist && sha256sum MobileIDE-1.0.0-release.apk MobileIDE-1.0.0-release.aab > SHA256SUMS
```

> `clean` 会移除构建缓存，耗时更长，但可以避免陈旧资源问题（本仓库已关闭 Gradle build cache）。

## 5. 发布产物

| 产物 | 大小（1.0.0） | 说明 |
| --- | --- | --- |
| `MobileIDE-1.0.0-release.apk` | 9.2 MB | 通用 APK，可直接侧载 |
| `MobileIDE-1.0.0-release.aab` | 8.7 MB | App Bundle，需渠道处理，不能直接安装 |

ABI 说明：

- `arm64-v8a`：完整功能（推荐）。
- `armeabi-v7a`：可安装、可编辑，无本地 Linux 运行环境。
- `x86_64`：可安装，主要用于界面验证，无本地 Linux 运行环境。

## 6. 分发

- 当前分发渠道为 GitHub（仓库 Releases 或 `dist/`）。
- 应用**未上架 Google Play**。若计划上架，需要单独评估：
  - `MANAGE_EXTERNAL_STORAGE` 的 Play 政策（仅核心文件管理类应用可申请豁免）。
  - `targetSdk 28` 不满足 Play 的 targetSdk 要求；提高 targetSdk 会影响 PRoot 执行，需要重新设计运行环境方案。
  - 详见 [docs/PERMISSIONS.md](docs/PERMISSIONS.md)。

## 7. 回滚

- 保留上一个版本的 APK/AAB 与签名密钥。
- Android 不允许安装 `versionCode` 更低或签名不同的覆盖包；回滚需要卸载后安装旧包（会清除应用私有数据，项目文件保留）。
- 数据库迁移是单向的，回滚前请备份应用私有数据或确认迁移兼容性。

## 8. 发布说明模板

```markdown
## MobileIDE 1.0.0

- 版本：1.0.0（versionCode 1）
- 支持：Android 8.0+，arm64 设备可用本地运行环境
- 变更：见 CHANGELOG.md
- 安装：下载 MobileIDE-1.0.0-release.apk，校验 SHA-256 后安装
- 已知问题：见 docs/KNOWN_ISSUES.md
- 未验证项：真机安装/启动、PRoot、终端、LSP、Git 网络、Keystore、UI 视觉
```
