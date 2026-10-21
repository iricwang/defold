# Windows 本地构建与安装

统一入口：在仓库根目录运行 `scripts\windows\editor.cmd`，或使用
`python -X utf8 scripts/windows/editor.py`。不再为每个版本另写临时构建脚本。
需要 Python 3.12+、Java 25、Git Bash，以及 MinGW GCC 或 Visual Studio C++ Build Tools。
依赖下载沿用仓库缓存。脚本使用短临时目录，后台安装不弹命令行窗口。

## 常用命令

```powershell
# 查看源码版本与本机安装版本
scripts\windows\editor.cmd status --app X:\Programs\Defold

# 从当前源码完整编译，生成含 JDK 的完整便携 ZIP（无需已有安装）
scripts\windows\editor.cmd full

# 把已安装的版本导出为完整便携 ZIP；不重新编译
scripts\windows\editor.cmd full --from-installed X:\Programs\Defold

# 从当前源码完整编译，按本机精确文件基线生成增量 patch
scripts\windows\editor.cmd patch --app X:\Programs\Defold

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

`install` 只接受增量包，要求先保存项目、关闭该安装的编辑器与游戏。
它先执行包内回归，再使用现有 PatchBootstrap 校验、备份、替换和重启，
最后核对安装文件清单。脚本不主动结束用户进程，不自动保存项目。
“请求重启”不等于项目功能验收；安装后仍应打开项目试用。
完整包用于解压到新目录，不通过 `install` 覆盖已有安装。

所有命令均不提交、推送或发布 GitHub。CI 继续使用 `build-patch.py` 的默认增量模式；
本地完整打包不会改变远端仅发布 patch 的流程。校验失败时进程返回非零，不能把该产物当作已验证版本。
