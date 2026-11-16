# Skill 尾巴摆动闭环验证

在实际打开的 tail_path_example.skeleton 中新增 tail_sway_skill（2.4 秒、3 条轨道、27 关键帧）。

实际执行读取 → 预演 → 提交 → 5 个关键帧截图 → 播放/暂停 → 145 帧网格采样 → 撤销恢复原文档 revision → 重新应用 → 定向保存。保存后重新解析原文件，确认旧动画和其他字段未改、dirty=false。

新增 preview/snapshot/sample/save；预览通过 plorer 的真实虚拟键鼠事件打开折叠菜单、选动画并定位帧。save 使用 Defold 的限定资源保存快照与锁，只写目标文档并校验磁盘 SHA-256。未修改运行时引擎。

截图为真实编辑器 viewport。数值是当前编辑器求值结果，不作为本次未执行的官方 Spine runtime 或游戏进包验证的替代。
