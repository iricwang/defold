# 尾巴摆动：完整接入配方

2026-11-15 在当前 `tail_path_example.skeleton` 的真实 Defold 会话验证。这里的 ID 是案例说明，不是全项目通用约定；始终先 list/inspect/read。

1. **读取**：inspect 得到版本与 dirty 状态；read 两个尾巴控制点、尾巴路径约束和已有动画。案例有 8 根驱动骨骼，路径 `rotate-mode:"chain"` 保持长度。先备份当前文件；不要重建已有绑定。
2. **创作提案**：新增 `tail_sway_skill`，时长 2.4 秒，保留旧动画。在现有设置姿势基础上，让尾尖控制点绕根控制点沿 ±11° 圆弧摆动；根控制点旋转 ±3°，相位提前 0.45 弧度。只有两个现有控制点、三条轨道；不直接改受驱动链。
3. **平滑关键帧**：每 0.3 秒设一帧，共 9 帧×3 轨道。内部 Bezier 控制点是绝对 `[time1,value1,time2,value2]`。用相邻关键帧的导数匹配切线，首尾相同。不要把 Spine 的归一化控制点直接填进内部模型。
4. **提交**：28 个操作（新建动画＋27 个 key.set）先 dry-run，再以同一 revision、apply=true 一次提交，保存返回的 undo-token。
5. **预览**：preview(animation, frame=0, fit=true)，再定位 36/72/108/144 帧；每帧 snapshot。preview(playing=true) 后 inspect 确认 time 推进；preview(frame=36, playing=false) 后再次 inspect 确认停止。
6. **验证网格**：sample(times=0…2.4，步长 1/60，slot=05_tail，bones=8 根驱动骨骼，vertices=true)。比较首尾顶点、运动幅度及骨骼矩阵轴长。实测 145 帧，网格最大位移约 46.74 项目单位，首尾误差小于 1e-12，无拉伸漂移。
7. **撤销闭环**：undo 使用自己的最新 revision 和 undo-token；inspect 必须恢复原 revision，新增动画消失。重新以原基线提交已验证操作，重新得到版本。若期间出现其他编辑则停止重放、重新分析。
8. **保存**：inspect 确认版本还是自己的最终结果；save(apply=true, revision, disk-revision)。验证 dirty=false，重新解析磁盘文件确认新动画存在，旧动画及其他字段未变。保留原动画，不用关闭重开页签证明保存，以免丢失用户历史。

所有上述动作由结构化脚本完成，不需要预先存在的 REPL 变量。保存后在动画菜单选择 `tail_sway_skill` 即可手工继续编辑。

此案例验证了当前编辑器的创作、求值、真实 UI 预览、撤销和持久保存；没有据此宣称完成新动画的官方 Spine runtime 对照或进包播放。
