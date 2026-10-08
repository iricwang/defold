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
**macOS Patch** 工作流构建 Apple Silicon 和 Intel，运行更新器与布局测试，
发布 `patch-dev-<revision>`，最后更新 `patch-dev/manifest.json`。
其他分支及 PR 仅生成构建产物，不更新正式源。

发布内容只有两个架构的增量 ZIP、基线文件清单 JSON 和更新清单。
`Defold-<platform>-state.json` 保存文件哈希、权限、JDK 信息和版本信息，
下次构建直接使用该清单，无需再打包或下载整个应用。
首次从 patch.4 迁移时会读取其已有的完整 ZIP 以生成清单，随后删除临时下载。

应用通过 **帮助 → 检查更新** 或欢迎页的 **Patch 更新** 下载、校验并安装。
安装会保存项目，退出应用，替换文件后重启。patch.5 起支持沿历史更新清单
寻找下一个兼容补丁；跳过多个版本时，重启后再次检查更新，逐版升级。
仍使用 patch.4 的安装请先更新到 patch.5，以获得历史补丁查找能力。
全新机器可以使用最后一次完整安装包 patch.4，再依次更新；历史发布不会被删除。

发布版本不可覆盖。已有 revision 对应不同 commit 时必须先递增版本。
两个架构均成功后才更新正式清单；失败和中断不会把客户端指向未完成的包。
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
