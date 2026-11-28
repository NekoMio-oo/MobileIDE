# 更新日志

本项目的版本记录遵循“一个阶段一个批次”的开发方式。

## [1.0.0] - 2026-11-27

第十二阶段：测试、优化与正式发布。

### 新增 / 变更

- 配置 Release 签名：支持环境变量与仓库外 `keystore.properties`，密钥和密码不进入 Git。
- 版本号更新为 `versionCode 1` / `versionName 1.0.0`。
- 新增发布文档：README、BUILD、INSTALL、RELEASE、CHANGELOG、权限说明、已知问题、第三方许可证、测试报告。
- 新增 12 个 UI 状态单元测试（项目搜索过滤、构建配置映射），单元测试总数 295 → 307。

### 构建与验证

- `:app:testDebugUnitTest`：307 个测试全部通过，0 失败、0 跳过。
- `:app:lintDebug`：0 error，13 warning（均为依赖更新提示、库自带 lint 版本提示与 `UsableSpace` 建议）。
- `:app:assembleRelease` / `:app:bundleRelease`：成功，产物经 `apksigner` 验证。
- Release APK：9.2 MB，包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`。
- Release AAB：8.7 MB。

### 说明

- 本版本未在真机/模拟器上执行端到端测试，相关功能在测试报告中标记为“未验证”。
- 未启用 R8/资源压缩。
- 未添加项目级 LICENSE 文件。

## [0.1.0] - 2026-11-27

前十一个阶段的内部版本，覆盖：

1. 项目初始化与基础架构
2. 首页 UI
3. 项目管理
4. 代码编辑器
5. 文件管理器
6. 多标签页系统
7. 本地运行环境（PRoot + Debian）
8. 构建系统
9. 集成终端（forkpty PTY）
10. LSP 语言智能服务
11. Git 版本控制

各阶段的详细变更见 Git 提交历史（`第十一阶段（n/6）` 等提交信息）。
