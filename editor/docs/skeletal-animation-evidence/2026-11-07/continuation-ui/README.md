# 续作最终编辑器验收

本机 Defold 内的隔离窗口，使用 cljfx.plorer 实际点击。截图是运行中的原生控件，
不是设计稿。详细观察值与源码摘要见 [results.json](results.json)。

- 全套源码回归：30 个命名空间，376 tests / 7586 assertions，零失败和错误。
- 最后的紧凑标签调整后，workspace + view workflow 定向回归 12 tests / 134 assertions 全通过。
  这两组有重叠，不将数量相加。
- 通过动画设置菜单选择 overlay，权重 0.5，得到预期 35°；初始 sound 事件保留，
  播放 21.52 秒得到 22 次循环事件，暂停成功，无状态错误。
- 使用真实项目 WAV，音量设为 0 验证 AudioClip 加载和触发，不声称完成主观试听或全部编解码验证。
- 视图菜单启用其他骨架参照。混合暂停状态点击 companion 名称，切到 setup，退出混合。
  左右边缘名称保持完整可见，避开左上模式控件；当前骨架正常编辑，其他骨架灰显。
- 验证窗口和图节点已清理；只移除已确认属于这些临时节点的撤销项。
  新资源与视图入口已注册到当前会话，未修改用户游戏文件或运行时引擎。

[混合暂停截图](mixer-paused.png) · [切换骨架截图](workspace-selected.png)

全套日志 [full-regression.log](full-regression.log)，最终布局日志
[layout-regression.log](layout-regression.log)，源码加载顺序与测试命名空间见
[full-regression.clj](full-regression.clj)。运行需要 Defold 编辑器完整测试 classpath、
JavaFX/native 库与正常显示环境。新增 audio/project/media 模块 lint 为 0 errors / 0 warnings；
主 view 的历史 lint warnings 未在本轮全面清理。

本轮是源码和会话热加载，没有持久增量安装包；编辑器重启后不会保留新增代码。
