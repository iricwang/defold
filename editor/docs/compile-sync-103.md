# 本机编译同步：patch.103

2026-10-28，从 `codex/editor-docking-patch4` 拉取到 `61ce841dbe0b7fee2ed4ee6f9e41f4c0e2dd7657`，完整编译 Apple Silicon 引擎、Bob、编辑器及 Remote 原生组件。

- 通过本地 HTTP 更新源下载并验证 99→103 增量包，安装至 `/Applications/Defold.app`，保留现有 JDK。
- 安装副本和安装后的 168 项文件哈希与权限全部匹配；新版正常启动并加载 Dragon Burst 项目，未保存内容为零，编辑器功能未禁用。
- 更新器／布局／Game／原生引擎缓存：47 tests、1321 assertions，0 failures、0 errors。
- Python patch：35 tests，15 项因平台／测试环境条件跳过，其余通过；Remote：10 tests 全部通过。
- 用新 Bob 重建隔离项目，新引擎实际通过 7 个 GUI 定位用例和 3 轮自动化输入／帧确认。
- 日志、增量包、基线清单和实际安装截图保存在 `tmp/local-update/`；截图为 `installed103.png`。

原项目包含 Spine 4.1.20 资源，但其现有扩展使用 4.2 runtime；加载时仍会报告该既有兼容错误，旧 patch.99 也有同样日志。本次没有转换游戏素材、修改项目依赖或向原生扩展服务上传项目。

本次安装验证使用本地源，未发布 GitHub 补丁。

## Codex MCP 接入

工程的 `defold-background` 已注册到本机 Codex，使用隔离的 Python 依赖和本地编译的 macOS worker／guard；绑定测试 fixture 与当前 Dragon Burst 项目。修复了 macOS `lipo -verify_arch` 输入文件位置错误。

实际 stdio 验证通过：49 个工具、项目发现、隔离构建、隐藏游戏启动、640×480 PNG、点击 HID 确认、报告导出和停止会话；基础测试 42 项通过。测试工程画面与报告位于 `tmp/local-update/mcp-smoke-2/`。点击回执仅证明 HID 已接收，不声称业务逻辑确认；Mac 同帧场景几何不支持，按服务能力列表处理。
