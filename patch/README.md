# 分支增量更新

从 patch.5 起，只构建和发布增量 patch，不再生成新的 `.app`、DMG 或完整应用 ZIP。
已安装的应用保留 JDK，仅替换有变化的文件；编辑器 JAR 作为一个文件更新。
这是文件级增量，不是 JAR 内部的二进制差分。

## 日常发布

```sh
python3.12 patch/manage.py bump --notes "本次更新说明"
bash scripts/macos/build-patch.sh
```

`channel.json` 是版本来源。提交并推送到 `codex/editor-docking-patch4` 后，
**Desktop Patch** 工作流构建 Apple Silicon、Intel macOS 和 Windows x64，运行更新器与布局测试，
发布 `patch-dev-<revision>`，最后更新 `patch-dev/manifest.json`。
其他分支及 PR 仅生成构建产物，不更新正式源。

发布内容只有三个平台的增量 ZIP、基线文件清单 JSON 和更新清单。
`Defold-<platform>-state.json` 保存文件哈希、权限、JDK 信息和版本信息，
下次构建直接使用该清单，无需再打包或下载整个应用。
首次从 patch.4 迁移时会读取其已有的完整 ZIP 以生成清单，随后删除临时下载。

应用通过 **帮助 → 检查更新** 或欢迎页的 **Patch 更新** 下载、校验并安装。
安装会保存项目，退出应用，替换文件后重启。patch.5 起支持沿历史更新清单
寻找下一个兼容补丁；跳过多个版本时，重启后再次检查更新，逐版升级。
仍使用 patch.4 的安装请先更新到 patch.5，以获得历史补丁查找能力。
全新机器可以使用最后一次完整安装包 patch.4。若正式源已超过 patch.5，
旧版更新器需要先使用 `patch-dev-5/manifest.json` 作为一次性更新源升级到 patch.5；
完成后完全退出应用，再正常启动以恢复正式更新源。历史发布不会被删除。
一次性更新源的设置方式见下文 `JDK_JAVA_OPTIONS`，本地 `build.json` 只需包含该 `feed` URL。

发布版本不可覆盖。已有 revision 对应不同 commit 时必须先递增版本。
三个平台均成功后才更新正式清单；失败和中断不会把客户端指向未完成的包。
JDK 版本或必需模块变化时构建会失败，需要单独规划运行时迁移，不会自动生成完整应用。

## 本地更新演练

保存与已安装应用完全匹配的基线清单。自编译与 CI 产物可能有不同的文件哈希，
不能混用。构建后创建本地更新源：

```sh
python3.12 patch/manage.py local \
  --patch editor/target/editor/Defold-arm64-macos-patch-4-to-5.zip \
  --state editor/target/editor/Defold-arm64-macos-state.json
python3.12 -m http.server 8765 --bind 127.0.0.1 --directory patch/local
```

用测试副本启动旧版本，替换路径为其实际安装位置：

```sh
JDK_JAVA_OPTIONS="-Ddefold.patch.directory=$PWD/patch/local" \
  /path/to/Defold.app/Contents/MacOS/Defold
```

此选项仅覆盖更新源，安装版本仍来自应用自身。正式使用时不要设置它。
更新器验证下载 SHA-256、基线文件哈希及权限，再在同一目录准备新应用。
替换或启动命令失败时恢复旧应用；旧应用默认保留为 `.patch-backup-*`。
确认新版本启动和项目正常后可清理备份。回滚不检测启动后的业务异常。
下载源仅接受 HTTPS 和本机回环 HTTP，错误会停止更新并在弹窗中报告。

## 验证

```sh
python3.12 -m unittest discover -s patch -p 'test_*.py'
# editor/ 下，使用项目已有 JDK/Lein 环境
LEIN_HOME="$PWD/build/lein" bash scripts/lein test editor.patch-test editor.docking-test
```

测试覆盖只包含变化文件、JDK 不变、版本与平台校验、纯补丁发布、历史补丁链、
下载校验、应用副本组装和独立重启助手。`patch/local/` 仅供本地验证，不提交。

## Windows 首次接入

Windows 初始基线为现有 `1.14.1-patch.8-local` 安装，对应编辑器提交
`320d7e3499807ea5b8e7a95081ed0f4a250e94a2`。`patch/baselines/x86_64-win32.json`
记录该安装的精确文件哈希；不包含可变的 `unpack/` 缓存，也不发布完整应用。
macOS 与 Windows 可以使用不同的初始基线，发布清单会分别记录。

旧 Windows 更新器尚不支持安装，所以首次升级需保存并完全退出 Defold，
将 Windows 增量 ZIP 解压到同名 ZIP 所在目录，运行 `apply-patch.cmd`，
输入安装目录。也可运行 `apply-patch.cmd "X:\Programs\Defold"`。
原始 ZIP 必须与脚本保持在同一目录。该入口拒绝仍在运行的安装，并校验全部基线文件。
此后使用 **帮助 → 检查更新**。

Windows 重启助手使用安装目录之外的临时 Java 运行时，避免自身占用待替换文件。
替换保留原安装备份，失败会恢复原目录。运行时版本和文件保持不变。
构建命令为 `python -X utf8 scripts/windows/build-patch.py`，只生成增量包。
该脚本使用固定归档版本的 Bob 和原生编译工具，并设置
`DM_EDITOR_USE_BOB_PROTOCOLS=1` 直接复用同一 Bob 的协议类，避免当前源码生成的
协议类覆盖归档版本。Windows CI 还会从最终编辑器 JAR 编译包含默认字体的 GUI，
强制生成 HLSL，以检查材质编译时的二进制兼容性。
归档 Bob 还会补入当前编辑器调试入口依赖的 `debugger.lua`、`mobdebug.lua` 和
`edn.lua`，同时更新直接资源和 `lib/builtins.zip`。CI 检查两份资源一致，
并实际编译调试入口及其依赖。
资源解包目录及完成标记同时包含提交 SHA 和编辑器版本，以区分同一提交生成的
不同本地 patch。仅检查 JAR 内容不能验证升级后的编辑器：还应核对实际解包的
`builtins/builtins.zip` 与包内资源一致，并展开、编译调试入口的全部依赖。
Windows 构建脚本会从最终增量 ZIP 提取 JAR，启动真实解包流程，依次验证旧 SHA 缓存迁移、
同版本重启复用，以及同 SHA 的下一版 patch 替换旧资源。测试使用独立临时缓存，
核对解包文件的全部字节和完成标记；本地构建和 CI 均必须通过，失败会阻止
构建成功返回和 CI 发布。
Windows 嵌入进程的检查还会从子进程内部确认没有控制台，并校验参数转义、
环境变量、标准输出/错误输出以及退出码，防止无窗口启动丢失日志。

## 本地 patch.22 一次性接入 CI 更新链

旧的本地 Apple Silicon patch.22 与同版本 CI 产物可能有不同的启动器和 JAR 哈希。
patch.31 会同时发布 `Defold-arm64-macos-patch-22-local-to-31.zip` 和
`manifest-arm64-macos-local22.json`，针对仓库记录的精确本地基线生成增量包。
迁移的目标文件清单与正式 CI patch.31 完全一致，后续可继续使用常规 GitHub 更新源。
普通 CI 安装仍使用原有更新链。

保存项目并完全退出 `/Applications/Defold.app`，然后在仓库根目录运行：

```sh
python3.12 patch/migrate_local22.py
```

其他安装目录使用 `--app /absolute/path/Defold.app`。
脚本检查运行进程和全部基线文件，启动一次兼容更新会话；在欢迎页选择 **Patch Updates**，
或使用 **帮助 → 检查更新**，下载并安装。原进程退出后，脚本原子恢复常规更新源，
重启后的应用继续使用常规源。迁移不替换 JDK，也不生成完整应用或 DMG。
该专用包仅在 patch.31 生成；已发布的历史迁移包会保留。
