# 分支增量更新

`patch/` 管理 dev 分支的补丁版本、生成增量包和本地测试源。应用入口是欢迎页的 **Patch 更新**，或项目内的 **帮助 → 检查更新**。

## 工作方式

- `channel.json` 是版本来源：`revision` 必须单调递增，`version` 是展示名称，`notes` 是更新说明。首次版本为 `1.14.1-patch.1`。
- 本地与 CI 构建都先执行 `manage.py stage`，把版本和独立更新源写入应用资源。此类构建关闭官方更新器，避免覆盖分支修改。
- 增量包包含变更文件和前后版本的文件清单。应用下载后验证包的 SHA-256、已安装文件的 SHA-256，再在同一目录复制并组装新应用；原应用保持可用。
- 点击“应用补丁并重启”会先保存当前项目。独立 Java 助手等待编辑器进程退出，再重命名切换到新应用并启动它。切换或启动命令失败会恢复原应用；旧应用保留在旁边的 `.patch-backup-*` 目录。
- 这是**文件级增量**：未变的 JDK、资源不下载；变化的编辑器 JAR 作为一个文件传输。更新需要应用所在目录可写，并留有至少一份应用副本的空间。
- 本地与 CI 的同版本构建可能因时间戳等内容不同而无法匹配 SHA-256 基线。弹窗提供“完整安装包”作为切换入口；接收远端增量更新时建议先安装 CI 发布的基线。
- 当前生成“上一已发布版本 → 当前版本”的补丁。跨多个版本、JDK 改变或平台没有增量包时，弹窗提供完整 DMG。首次启用需要安装本次生成的新 DMG，以获得更新器。
- 回滚保护针对文件切换和启动命令失败；不检测新版本启动之后的业务异常。需要手动回退时，退出应用，用保留的备份替换当前应用。

## 发布新版本

1. 每次需要同步的新变动先运行 `python3.12 patch/manage.py bump --notes "本次更新说明"`，自动递增 `revision`、更新展示版本和说明，再构建应用。
2. 将变更推送到本 fork 的 `dev` 分支。
3. `macOS DMG` 工作流在 Apple Silicon 和 Intel 上构建、运行增量更新测试，保留 DMG、校验文件和完整 ZIP。
4. 两个平台都成功后，发布作业下载上一版本 ZIP，生成两个平台的增量包，上传不可变的 `patch-dev-<revision>` 预发布版本；最后更新 `patch-dev` 下的 `manifest.json`。

已发布的 revision 不会被新构建替换。不同 commit 复用已发布 revision 时发布作业会失败，要求先递增版本，防止变更没有同步到客户端。同一 commit 中断后重跑会修复更新清单。PR、其他分支和手动构建只生成产物，不发布。只使用仓库的 `GITHUB_TOKEN`；无需官方签名、S3 凭据。开发包仍未签名公证。

## 本地开发与更新演练

先构建 revision 1，保存其 ZIP 并安装到一个可写目录（不能直接在只读 DMG 内更新）。然后递增 revision，再构建 revision 2；不要用新 DMG覆盖旧应用，以便测试增量更新。

```sh
bash scripts/macos/build-dmg.sh
mkdir -p patch/local/baseline
cp editor/target/editor/Defold-arm64-macos.zip patch/local/baseline/
# 安装基线应用，然后编辑 channel.json 的 revision、version 和 notes，再构建：
bash scripts/macos/build-dmg.sh
python3.12 patch/manage.py local \
  --base-zip patch/local/baseline/Defold-arm64-macos.zip \
  --target-zip editor/target/editor/Defold-arm64-macos.zip \
  --dmg editor/target/editor/Defold-arm64-macos.dmg
python3.12 -m http.server 8765 --bind 127.0.0.1 --directory patch/local
```

工具会读取两个 ZIP 内嵌的真实版本，生成 `patch/local/build.json`、`manifest.json`、`preview-patch.zip` 和 DMG。`patch/local/` 已忽略，不提交构建产物。

在另一终端启动已安装的基线应用（把路径替换为实际位置）：

```sh
JDK_JAVA_OPTIONS="-Ddefold.patch.directory=$PWD/patch/local" \
  /path/to/Defold.app/Contents/MacOS/Defold
```

正式启动器会清空 `JAVA_TOOL_OPTIONS` 和 `_JAVA_OPTIONS`，本地演练应使用上述 `JDK_JAVA_OPTIONS`。该参数只替换已安装应用的更新源；重启后版本号仍来自新应用本身。开发 REPL 可用同一参数预览弹窗，但不允许覆盖源码目录。正常远端使用时不设置此参数。

更新器只接受 HTTPS 或本机回环 HTTP；网络、清单、基线或校验错误均停止更新并显示错误。首次远端发布之前，默认源返回 404，弹窗会如实显示检查失败。

## 验证

```sh
python3.12 -m unittest discover -s patch -p 'test_*.py'
# 在 editor/ 下，使用已有 JDK/Lein 环境：
LEIN_HOME="$PWD/build/lein" bash scripts/lein test editor.patch-test
```

测试覆盖版本与平台选择、下载校验、增量文件集合、应用副本组装、回滚，以及独立助手等待旧进程退出后启动新程序。
