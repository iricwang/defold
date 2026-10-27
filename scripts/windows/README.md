# Windows 本地构建与安装

统一入口：在仓库根目录运行 `scripts\windows\editor.cmd`，或使用
`python -X utf8 scripts/windows/editor.py`。不再为每个版本另写临时构建脚本。
需要 Python 3.12+、Java 25、Git Bash，以及 MinGW GCC 或 Visual Studio C++ Build Tools。
依赖下载沿用仓库缓存。脚本使用短临时目录，后台安装不弹命令行窗口。

## 日常功能验证：先热加载，验收后再打包

普通 Clojure 逻辑修改先运行相关测试，再向当前编辑器热加载整个命名空间，
检查实际行为后即可试用。这个过程不升 patch、不合并 JAR、不生成 ZIP、不重启。
只有明确需要安装、打包或发布时才运行下面的 `patch` / `full` 流程。

```powershell
# port 和 pid 使用本次运行实际确认的 socket REPL 端口与 Java 进程号
scripts\windows\editor.cmd reload --port 19941 --pid <Java进程号> --namespace editor.game-preview

# 多个文件按依赖顺序加载；随后执行本次功能的刷新与验收脚本
scripts\windows\editor.cmd reload --port 19941 --pid <Java进程号> --namespace editor.game-preview --after <刷新与验收.clj>
```

`reload` 连接已启用的本机 **Clojure socket REPL**，不是 nREPL；不自行附加进程、
开启端口或改写安装配置。当前测试会话已启用此入口。开发会话也可在其现有 REPL
执行下面的表达式显式开启（仅绑定回环地址，使用完毕后关闭）：

```clojure
(require 'clojure.core.server)
(clojure.core.server/start-server
  {:name "local-validation" :address "127.0.0.1" :port 19941
   :accept 'clojure.core.server/repl :server-daemon true})
;; 查看当前 PID
(.pid (java.lang.ProcessHandle/current))
;; 验证结束后关闭入口
(clojure.core.server/stop-server "local-validation")
```

脚本先核对目标 PID 与编辑器命名空间，再加载源码。重启后需重新确认 PID 与端口。
连接失败不会退回完整构建。加载出错或超时可能留下部分改动，不自动重试，也不自动回滚。
热加载仅对本次会话有效，重启恢复已安装版本；它不等于功能测试通过或已经安装新版本。

已有闭包、事件处理器可能仍持有旧实现，需要通过 `--after` 重新注册，
并断言实际行为；涉及 UI 的刷新仍使用 `cljfx.plorer` 在 JavaFX 线程执行。
图节点定义、record/class 结构、Java、原生库及生成协议的修改不能依赖普通热加载，
须执行相应编译和重启验证。正式发布仍执行完整的精确基线与最终包校验。

## 常用命令

后台 Game MCP 的开发编译入口是 `automation-build --app X:\Programs\Defold`，仅构建独立 Java/native worker，不打包或安装编辑器。使用方式和能力边界见 [后台 MCP](../automation/README.md)。

```powershell
# 查看源码版本与本机安装版本
scripts\windows\editor.cmd status --app X:\Programs\Defold

# 从当前源码完整编译，生成含 JDK 的完整便携 ZIP（无需已有安装）
scripts\windows\editor.cmd full

# 把已安装的版本导出为完整便携 ZIP；不重新编译
scripts\windows\editor.cmd full --from-installed X:\Programs\Defold

# 从当前源码完整编译，按本机精确文件基线生成增量 patch
scripts\windows\editor.cmd patch --app X:\Programs\Defold

# 修改过原生引擎或协议时，先按引擎构建指南编译引擎和 Bob，再使用配套产物
scripts\windows\editor.cmd patch --app X:\Programs\Defold --engine-home tmp\dynamo_home

# 保存项目并关闭 Defold 后，构建、校验并安装
scripts\windows\editor.cmd patch --app X:\Programs\Defold --install

# 对已有的完整 ZIP 或 patch ZIP 执行最终包回归检查
scripts\windows\editor.cmd verify editor\target\editor\Defold-x86_64-win32-patch-55-to-60.zip --app X:\Programs\Defold

# 安装已有 patch；从外部下载时可传 --sha256 <可信源给出的哈希>
scripts\windows\editor.cmd install editor\target\editor\Defold-x86_64-win32-patch-55-to-60.zip --app X:\Programs\Defold
```

输出位于 `editor/target/editor/`，成功时生成同名 `.sha256` 文件。
源码完整包为 `Defold-x86_64-win32.zip`，导出安装包带 `local-版本号`，
增量包文件名包含起止版本。完整 ZIP 解压后从 `Defold/Defold.exe` 启动。
`--from-installed` 只打包程序和 JDK，排除缓存、项目和用户文件；它适合备份和迁移当前可用版本。

`patch` 要求源码版本高于安装版本，不自动改版本号。需要发布新修改时先显式运行
`python patch/manage.py bump --notes "更新说明"`。同版本需要重编译时使用 `full`。
基线包含所有应用文件哈希；本地与 CI 即使同版本也不能随意混用。

`--engine-home` 同时支持 `patch` 和源码 `full`，指向完整的本地 `DYNAMO_HOME`。
必须包含 Bob、引擎 EXE/PDB、编辑器启动器、粒子/鼠标原生库和参考文档。
脚本检查 Bob 内的 Windows 引擎与该目录的 EXE 完全一致，并统一复用 Bob 的协议类，
防止 GUI 新字段只在编辑器生效或旧引擎在运行时忽略新功能。原生符号另由最终包测试验证。
该参数不会回退下载旧引擎；未传时沿用原有官方归档构建流程。

`install` 只接受增量包，要求先保存项目、关闭该安装的编辑器与游戏。
它先执行包内回归，再使用现有 PatchBootstrap 校验、备份、替换和重启，
最后核对安装文件清单。脚本不主动结束用户进程，不自动保存项目。
“请求重启”不等于项目功能验收；安装后仍应打开项目试用。
完整包用于解压到新目录，不通过 `install` 覆盖已有安装。

所有命令均不提交、推送或发布 GitHub。CI 继续使用 `build-patch.py` 的默认增量模式；
本地完整打包不会改变远端仅发布 patch 的流程。校验失败时进程返回非零，不能把该产物当作已验证版本。
