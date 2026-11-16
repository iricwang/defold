# 验证、保存和交付

## 验证层次

1. **数据**：结构化 edit 默认预演调用当前 `data/validate-document`。查看骨骼数、顶点数、权重影响和动画时长；读目标对象核对关键帧。数据有效不等于画面自然。
2. **编辑器**：播放/拖动控制点，检查关键姿势、循环首尾、皮肤切换和撤销。使用 plorer 的截图；不要以生成图片代替真实结果。
3. **运行时**：按目标扩展版本导出后实际进包播放。Spine 的“4.3”标记不能靠改版本字符串就视为兼容 4.2。
4. **性能**：使用当前实际案例、目标贴图缩放、骨骼/顶点数量；区分编辑器拖动与游戏运行时间，不沿用别的模型的推荐值。

需要逐帧对照时，查本 checkout 的 `editor/scripts/spine_tail_oracle.mjs`、`spine_runtime_gaps_oracle.mjs`、`spine_tail_sample.clj` 和现有证据。读取脚本参数并确认固定的 Spine runtime 版本，再对新资产运行。未执行就标记未验证。

## 锁屏/后台交互

仓库已有 `editor/scripts/headless_editor.py`，实际输入走 cljfx.plorer。

```sh
python3 editor/scripts/headless_editor.py \
  --jar /absolute/validated/editor.jar \
  --java /absolute/java \
  --project /absolute/game-fixture \
  --scenario /absolute/scenario.edn \
  --output /absolute/new-evidence-directory \
  --backend background --timeout 240 --scenario-timeout 180
```

提供的项目会复制后再操作。`background` 保留实际 GPU/Scene3D，依赖图形会话与驱动；不是彻底无显示服务器运行。`headless` JavaFX 软件屏幕不覆盖 Scene3D，所以不能用于验收贴图网格渲染。结果看 report.json、逐步 PNG、错误日志及 disabled handlers/stopped timers。先读 `editor/docs/background-ui-verification.md`。

这里已有 `.skeleton` 资源页签打开、选择、按键、拖动、缩放等范例；只改目标资源和必要步骤，不从头搭另一套 UI 模拟。

## 保存 / 导出

结构化 `edit` 只改内存；显式 `save` 走编辑器定向保存流程（见连接协议），保留用户预期的路径及原始图片；不要手写磁盘文件覆盖 dirty 文档。保存/导出属于用户创作交付范围时可直接执行，不为每个常规步骤另设审批。

- `.skeleton` 是本 fork 的编辑数据；`.spine` 原生文件读写需要安装并已授权的 Spine CLI。不能把 JSON 改扩展名伪装为原生工程。
- `skeletal-animation-io/export-project!` 产出 `.spinejson`、atlas、scene、model；检查函数返回的文件与 warnings，并实际构建。目标目录必须在游戏项目内，避免覆盖无关资产。
- 贴图缩放配置影响导出/进包，原 PNG 保持；检查实际导出 PNG 尺寸与显存，不能只看面板数值。
- 两色 tint 等受默认 Defold Spine material 限制；读 `export-compatibility`，不把预览支持等同默认材质支持。

交付说明实际改动的资源、动画、关键参数、验证结果、保存状态。明确“会话热更”和“已安装持久更新”的区别。

## 维护技能

Python 协议回归：`python3 scripts/test_client.py`。
适配器测试：启动 JavaFX 后 load-file `defold_spine_agent.clj` 和 `defold_spine_agent_test.clj`，运行 `clojure.test/run-tests 'defold-spine-agent-test`；使用该 fork 的验证 JAR/测试 classpath。测试创建隔离图系统，覆盖预演、失败原子性、旧基线、历史保护、网格、关键帧、实际采样以及保存失败锁释放。测试启动需使用与编辑器相同的 `--add-opens=java.base/sun.nio.fs=ALL-UNNAMED` 参数。不要在用户正在编辑的 live REPL 运行测试或重绑定全局图系统。
