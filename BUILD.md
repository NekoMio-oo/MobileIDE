# 构建指南

## 1. 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21（构建与 Gradle 运行） |
| Gradle | 8.10.2（使用仓库内 Wrapper） |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.21 |
| Android SDK Platform | 35 |
| Android Build-Tools | 35.0.0 |
| Android NDK | 27.2.12479018 |

NDK 版本由 `app/build.gradle.kts` 的 `ndkVersion` 固定，仅用于构建 `app/src/main/jni/mobileide_pty.c`（forkpty 桥接）。缺失该 NDK 时构建会直接失败。

## 2. 配置 SDK 路径

在仓库根目录创建 `local.properties`（已被 `.gitignore` 忽略）：

```properties
sdk.dir=/home/you/Android/Sdk
```

或者设置环境变量：

```bash
export ANDROID_HOME=/home/you/Android/Sdk
export JAVA_HOME=/path/to/jdk-21
```

## 3. 常用命令

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# Lint（Debug）
./gradlew :app:lintDebug

# Debug APK
./gradlew :app:assembleDebug

# Release APK
./gradlew :app:assembleRelease

# Release AAB
./gradlew :app:bundleRelease
```

产物位置：

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK：`app/build/outputs/apk/release/app-release.apk`
- Release AAB：`app/build/outputs/bundle/release/app-release.aab`
- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`

## 4. 依赖镜像

`settings.gradle.kts` 在**非 CI** 环境下优先使用阿里云镜像，在 CI（`CI=true` 或 `GITHUB_ACTIONS=true`）下只使用 Google/Maven Central 官方仓库。

如果本地镜像缓存异常，可临时以 CI 模式构建：

```bash
CI=true ./gradlew :app:assembleDebug
```

## 5. Release 签名

签名配置位于 `app/build.gradle.kts`，按以下顺序读取：

1. 环境变量
2. 仓库根目录的 `keystore.properties`（已被 `.gitignore` 忽略）

| 配置项 | 环境变量 | properties 键 |
| --- | --- | --- |
| 密钥库路径 | `MOBILEIDE_KEYSTORE_FILE` | `storeFile` |
| 密钥库密码 | `MOBILEIDE_KEYSTORE_PASSWORD` | `storePassword` |
| 密钥别名 | `MOBILEIDE_KEY_ALIAS` | `keyAlias` |
| 密钥密码 | `MOBILEIDE_KEY_PASSWORD` | `keyPassword`（缺省时使用 `storePassword`） |

`storeFile` 为相对路径时按仓库根目录解析，也可使用绝对路径。

### 5.1 生成密钥（只在仓库外保存）

```bash
mkdir -p ~/.mobileide
keytool -genkeypair -v \
  -keystore ~/.mobileide/mobileide-release.jks \
  -alias mobileide \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=MobileIDE, OU=Release, O=MobileIDE, C=CN"
```

随后创建 `~/.mobileide/keystore.properties`（权限 600），内容示例：

```properties
storeFile=/home/you/.mobileide/mobileide-release.jks
storePassword=********
keyAlias=mobileide
keyPassword=********
```

构建时通过环境变量注入：

```bash
export MOBILEIDE_KEYSTORE_FILE="$HOME/.mobileide/mobileide-release.jks"
export MOBILEIDE_KEYSTORE_PASSWORD='********'
export MOBILEIDE_KEY_ALIAS=mobileide
export MOBILEIDE_KEY_PASSWORD='********'
./gradlew :app:assembleRelease :app:bundleRelease
```

也可以把上述 `keystore.properties` 复制到仓库根目录（不要提交）。

> 安全要求：
> - 不要把 `.jks` / `.keystore` / `.p12`、`keystore.properties` 提交到 Git。
> - 不要在源码、CI 日志或文档中写死密码。
> - 发布用密钥应离线备份；丢失密钥将无法覆盖升级已安装的应用。

### 5.2 未配置签名时

没有签名配置时，`assembleRelease` 仍会生成 **unsigned** 的 `app-release-unsigned.apk`，不能作为正式发布包。请先完成签名配置。

### 5.3 验证签名

```bash
export PATH="$JAVA_HOME/bin:$PATH"
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

当前发布包使用 APK Signature Scheme v2（minSdk 26），证书主题为 `CN=MobileIDE`。

## 6. 构建配置说明

- `minSdk = 26`，`targetSdk = 28`，`compileSdk = 35`。
- `targetSdk = 28` 是刻意选择：本地 PRoot 运行时需要在应用私有目录执行二进制，targetSdk 30+ 会限制该行为。
- `abiFilters`：`arm64-v8a`、`armeabi-v7a`、`x86_64`。本地 Debian 运行时仅支持 arm64。
- Release 暂不启用 R8 / 资源压缩（`isMinifyEnabled = false`），以避免在未做真机回归前引入不可验证的问题。
- 只有一个原生模块：`app/src/main/jni/Android.mk`（ndk-build）。

## 7. 测试与检查

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

当前基线：307 个单元测试通过，Lint 0 error / 13 warning。Lint 警告包括：

- AndroidX 依赖有更新版本（未升级，避免引入未经回归的变更）。
- 库自带 lint 检查版本提示。
- `LinuxRuntimeInstaller` 的 `UsableSpace` 建议（当前实现主动检查可用空间）。

## 8. 常见问题

**构建报错找不到 NDK `27.2.12479018`**

```bash
sdkmanager "ndk;27.2.12479018" "platforms;android-35" "build-tools;35.0.0"
```

**Gradle 下载/依赖解析失败**

检查网络与代理；本地默认走阿里云镜像。CI 上请设置 `CI=true` 使用官方仓库。

**`mipmap/ic_launcher` 找不到**

本仓库已关闭 Gradle build cache（`org.gradle.caching=false`）。如本地仍遇到陈旧资源，执行：

```bash
./gradlew --stop
rm -rf app/build
./gradlew :app:assembleDebug
```

**Release 包是 unsigned**

说明没有读到签名配置。检查环境变量是否导出、`keystore.properties` 是否位于仓库根目录、密码是否为空。
