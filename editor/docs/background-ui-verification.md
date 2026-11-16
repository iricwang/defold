# 后台交互与视觉验证

入口：`editor/scripts/headless_editor.py`。它启动独立编辑器进程，通过只绑定
`127.0.0.1` 的临时 nREPL 执行 `cljfx.plorer` 输入。验证对象是传入的真实编辑器
JAR，不是重写的界面，也不通过直接调用按钮回调伪造操作结果。

## 两种后端

| 后端 | 用途 | 已确认的边界 |
| --- | --- | --- |
| `background` | 保留原生 Metal/OpenGL 渲染；虚拟窗口焦点使锁屏期间仍可操作菜单、下拉框、骨骼网格等 | 需要可用的图形会话和驱动，不是无显示服务器运行；解锁后测试窗口可能可见。系统原生文件对话框、系统级拖放没有覆盖。 |
| `headless` | JavaFX 虚拟屏幕与软件渲染；适合控件、菜单和流程回归 | 当前包内 JavaFX 后端不支持 Scene3D。虚拟屏幕为 1000×1000；默认窗口 960×900，超限直接失败。不能用它验收骨骼贴图/网格画面。 |

`background` 通过 Glass 的焦点通知更新虚拟输入状态，同时保留真实窗口事件处理链。
只设置 JavaFX `focusedProperty` 不够，会导致弹出菜单的 `grabFocus()` 异常；本实现
在独立测试进程中从 Glass 层通知焦点。它不解锁系统、不移动桌面鼠标。

测试把同一组菜单放进窗口内部，绑定和命令仍是原来的菜单项；macOS 顶部系统菜单栏
本身不在这组验证范围内。软件渲染与 Metal 的逐像素一致性不作保证；报告记录实际
渲染管线、Scene3D 和 OpenGL 能力，不把 UI-only 通过当作 GPU 场景通过。

## 运行

在仓库根目录运行。JDK 需兼容当前编辑器；`lein deps` 提供固定版本的
nREPL 1.7.0 与 plorer 1.31。`--jar` 可指向本地新编译的 standalone JAR 或安装包 JAR。

```sh
python3.12 editor/scripts/headless_editor.py \
  --jar editor/target/editor-arm64-macos-standalone.jar \
  --java /opt/homebrew/opt/openjdk@25/bin/java \
  --backend background \
  --scenario editor/test/headless/menus.edn \
  --output tmp/qa-menu-001
```

不提供 `--project` 时创建含有效启动集合的最小项目。提供该参数时复制项目再打开；
原项目不会作为编辑器工作目录。复制排除 `.git`、`.internal`、`build`、`.DS_Store`
和符号链接。输出目录必须位于源项目之外，并且是新目录或空目录。

这是一套可信本地测试工具，不是文件系统安全沙箱。含外部绝对路径的工程需准备独立
测试夹具；例如 `.spine` 原工程路径应从测试副本移除。不会自动复制或重定向所有外部依赖。

骨骼样例脚本 `editor/test/headless/skeletal.edn` 要求夹具根目录有 `tail.skeleton`，
图片路径仍然有效，并包含 `idle_soft_tail` 动画。此次本机证据目录
`tmp/background-skeletal-final/project` 保留了可再次运行的夹具副本。

```sh
python3.12 editor/scripts/headless_editor.py \
  --jar editor/target/editor-arm64-macos-standalone.jar \
  --java /opt/homebrew/opt/openjdk@25/bin/java \
  --backend background \
  --project tmp/background-skeletal-final/project \
  --scenario editor/test/headless/skeletal.edn \
  --output tmp/qa-skeletal-002
```

纯 headless 控件测试使用 `--backend headless`。如果 OpenGL 不可用，默认立即失败。
只有显式添加 `--allow-no-gl`，才会通过原来的“Disable Scene Editor”按钮继续 UI-only
测试；该能力缺失仍写入报告。

## 场景格式

```clojure
{:viewport [1920 1080]
 :steps [{:op :click :selector "#some-control"}
         {:op :keys :keys [:space]}
         {:op :wait :selector "#result" :property :visible :equals true :timeout-ms 10000}
         {:op :drag :button :secondary
          :path [{:selector "#canvas" :at [0.4 0.4]}
                 {:selector "#canvas" :at [0.5 0.5]}
                 {:selector "#canvas" :at [0.6 0.6]}]}
         {:op :scroll :selector "#canvas" :delta [0 80]}
         {:op :assert :selector "#result" :property :text :equals "Ready"}]}
```

- `selector` 支持 plorer 的 id、样式类和属性映射；必须唯一命中。
- 有 selector 时 `at` 是节点内的相对坐标；没有 selector 时是场景逻辑像素坐标。
- `window-index` 指定弹出窗口；省略时使用主窗口。每步保存所有显示窗口的截图。
- `keys` 支持组合键；拖拽也可以带 `keys` 保持修饰键，结束时保证释放。
- `remember` 用 `as` 保存控件属性或上下文状态，后续 `assert` 用 `differs-from` 检查变化。
- 读取状态时指定 `context-key` 和 `state-path`，例如骨骼的 `[:state :zoom]`。
  这是只读断言，不会直接修改状态以替代交互。
- `wait` 根据状态等待并有超时；每步额外等待两次 JavaFX pulse，再截图。
- 点击实际落到其他控件、选择器不唯一、断言失败、计时器或处理器被禁用时失败。

## 证据与退出码

输出包含 `run.json`（JAR SHA-256、来源与配置）、`report.json`、逐步 PNG、
`editor.log` 和 `scenario.log`。启动失败保存 `launcher-error.json`。
成功退出 0，失败退出 1。执行结束只关闭自己创建的验证进程，保留项目副本与证据。

此次 macOS 锁屏验证：后台 Metal/Scene3D 骨骼样例 24 步通过，包括文件打开、动画
选择、右键平移、滚轮缩放、长动画名选择、空格播放/暂停。处理器和计时器异常数均为 0。
纯 headless 菜单样例 4 步通过；它明确不具备 Scene3D。这些是已执行场景的覆盖范围，
不是对整个编辑器所有功能或所有操作系统的完整验收。

官方背景：JavaFX 从 26 引入 headless 原型后端：
https://openjfx.io/highlights/26/

附：同一虚拟焦点机制已在锁屏状态完成本机 patch.127 → patch.128 的内置更新器下载、安装和重启。安装后 169 个文件清单完全匹配，原骨骼文档内容保留；这是本地更新源验证，未发布 GitHub 更新。
