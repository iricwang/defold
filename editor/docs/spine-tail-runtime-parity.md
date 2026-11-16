# 05_tail：官方运行时逐帧对照与动画模式操作

2026-11-10，针对当前 `tail_ik_example.skeleton`，只修改编辑器源码。

## 实测范围与结果

官方基准为本地固定版本 `@esotericsoftware/spine-core@4.2.120`，使用其原始
SkeletonJson、Animation、Skeleton、VertexAttachment 实现，未修改官方求值代码。
对两边显式启用相同的 `default` 皮肤，然后比较编辑器原始文档与其导出 JSON 的
官方求值。该案例的 tail 骨骼属于皮肤，省略皮肤激活会使对照失效。

- 8 段动画：attack、death、hit、idle、stagger、walk、idle_soft_tail、tail_ik_demo。
- 60 FPS，含各动画终点，共 617 帧。
- 每帧比较全部 18 根骨骼世界矩阵、05_tail 的 26 个网格顶点、11 个插槽的附件与绘制顺序。
- 骨骼最大绝对误差：修复前 0.037722074，修复后 0.000057372。
- 尾巴顶点最大绝对误差：修复前 0.044147881，修复后 0.000064320。
- 阈值 0.0001；最终全部通过；附件与绘制顺序错误数为 0。
- 306 项回归测试，9048 条断言，0 失败、0 错误。
- 独立后台编辑器通过 53 步真实输入验证，Metal / Scene3D 正常，无停用的计时器或事件处理器。

这验证了案例中的单动画、权重、FFD 和 IK，不代表所有 Spine 功能完全等价。
未在本轮逐帧对比多轨混合、事件、物理约束或最终像素渲染。相关模块运行了回归测试。
原项目只读；测试编辑发生在项目副本中。本机安装包没有替换。

## 修复

1. 自建骨骼曲线原来求精确三次贝塞尔，Spine 运行时用十段折线采样。
   统一到现有 `skeletal-animation-interpolation` 的运行时采样；保留设计曲线的
   精确分割，用于曲线编辑。循环偏移的几何回归独立检验解析曲线，不能误把重新分段后的
   运行时近似当成完全相同的采样点。
2. 变换／路径约束的多个混合通道在 Spine 导出时共享帧时刻。某通道插入关键帧会切分
   其他通道的采样区间。编辑器现在按与导出相同的区间采样，避免往返导出时预览变化。
3. 动画模式隐藏并禁用约束目标、绑定骨骼、执行顺序和设置偏移；名称不可改。
   Mix、弯曲、软度等动画参数保持可编辑。物理动画范围／重置入口与求解器设置分组分离。
4. 结构编辑在提交时再次检查模式，阻止旧属性面板回调或已打开菜单跨模式修改结构。
   创建骨骼／约束、导入图片、绑定及附件设置适用同一限制。
5. 网格、路径、包围框和裁剪附件共用变形入口；进入动画模式重置网格工具为修改，
   不保留创建顶点模式。普通图片的动画仍通过所属骨骼变换完成。

原有普通附件属性实际上已经被外层动画面板隐藏，早期审查只看内部面板函数而误判了
这一点。本次针对真实可达入口和延迟回调修复，并增加测试。

## 复跑

在 `editor/` 目录使用工程依赖 classpath 运行源码采样（不启动用户的编辑器）：

```sh
java -cp "$(cat ../tmp/patch-classpath.txt)" clojure.main \
  scripts/spine_tail_sample.clj /absolute/path/tail_ik_example.skeleton /tmp/tail-check
node scripts/spine_tail_oracle.mjs \
  ../tmp/spine-constraint-oracle/package/dist/index.js /tmp/tail-check
```

采样输出 `input.json`、`export.json`、`actual.json`；官方脚本输出 `expected.json` 和
`report.json`，超过阈值返回非零退出码。运行时须是上述固定版本。
测试启动时必须使用兼容当前编辑器的 Java 25 和可用的 Node。

后台 UI 通过仓库的 `headless_editor.py --backend background`，传入包含本次修改的
编辑器 JAR 和隔离夹具。场景文件为 `test/headless/skeletal_animation_modes.edn`，
要求根目录有 `tail.skeleton`，包含 `05_tail`、`tail_tip_ik`、`idle_soft_tail`。
场景验证画布平移缩放、播放暂停、IK 结构锁定、关闭自动关键帧后的暂存／撤销、网格变形入口。
拖拽起点位于画布上部空白，避开随窗口布局变化的浮动变换工具栏。

## 证据

- [修复前](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/before.json)
- [修复后](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/after.json)
- [后台交互报告](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/background-report.json)
- [IK 属性截图](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/animation-ik.png)
- [尾巴变形入口截图](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/animation-deform.png)
- [回归测试日志](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/tests.log)
- [临时验证 JAR 的非编辑器内容不变检查](skeletal-animation-evidence/2026-11-10/tail-runtime-parity/runtime-inventory.json)
