# 本机编译同步：patch.106

2026-10-29，拉取所有已配置远程，合并 `origin/codex/editor-docking-patch4` 的
`58eacb4a3a`，保留本地三个已提交的 MCP 修复和验证记录。合并提交为
`ddda9eeb4e`；补丁版本记录冲突采用远程版本后递增至 `1.14.1-patch.106`。
未推送远程，也未发布 GitHub 补丁。

## 构建与安装

- 本地编译 Apple Silicon 引擎、Bob、Remote 原生组件和编辑器。
- 基于已安装 patch.103 的精确文件清单，通过本地 HTTP 源下载、校验并安装
  103→106 增量包，保留原有 JDK。
- 安装副本及最终应用的 168 项文件哈希和权限全部匹配。
- 通过实际 PatchRestart 退出旧编辑器、启动新版；Dragon Burst 项目加载完成，
  未保存资源为零，编辑器未禁用。清理本次旧 app 备份，仅保留
  `/Applications/Defold.app`。
- 同步完整本机 Extender SDK，保留旧 SDK 备份。通过 `127.0.0.1:9011` 和隔离缓存
  实际编译 Dragon Burst 的 Spine 和 Game 桥接扩展，Proto 生成、编译、链接成功。
  新引擎在 GUI fixture 中初始化 Metal、注册 Spine，并完成初始化及更新回调。

## 验证

- 编辑器更新器、布局、Game、引擎缓存、后台预构建及真实资源缓存失效测试：
  **71 tests / 1526 assertions，0 failures / 0 errors**。
- Python patch：48 tests，15 项按环境条件跳过，其余通过。
- Remote host：10 tests 全部通过。
- MCP 基础与新增任务控制：51 tests 全部通过。
- MCP sidecar 已重编译并指向新应用；实际 stdio 测试发现 50 个工具，验证隔离构建、
  隐藏游戏启动、640×480 截图方向、点击、非对称拖动、报告导出和停止会话。
  游戏日志确认 `BUTTON_CLICKED 1`，拖动后的目标像素断言通过。

日志、基线、补丁、安装截图和 MCP 证据位于 `tmp/local-update-20261029/`。
实际编辑器截图：`tmp/local-update-20261029/installed106.png`。

原项目加载时仍有 `assets/raw/spine/.../*.atlas` 的既有资源解析警告，旧版日志也有
同类记录。本次验证覆盖编辑器启动、定向回归及测试场景运行，不代表整个游戏的
全部关卡和资源均通过回归；未转换或改写原项目素材。
