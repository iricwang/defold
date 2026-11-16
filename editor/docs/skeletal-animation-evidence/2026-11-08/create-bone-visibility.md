# 拖拽创建骨骼显示修复

2026-11-08。对照 https://esotericsoftware.com/spine-tools#Create-tool ，设置模式下选父骨骼后拖拽设置位置、方向和长度；N 切换创建工具，Shift 保留父选择。

实际用户 bone1 已创建，长度 67.4774674573573，但同名已删除骨骼在 spine-source 中残留 skin=true，且不在当前皮肤的 bones 中。重用名字让新骨骼被隐藏。另一个问题是拖动时 last-document 已记录草稿，释放时文档相等导致层级刷新被跳过。

修复：add-bone 复用旧名称时清理旧骨骼元数据、皮肤成员及原始骨骼动画引用，保留 source 索引。层级树使用独立 tree-document 缓存，拖拽完成后按实际树版本刷新。

验证：
- 220 测试，1627 断言，零失败/错误；无新增 clj-kondo 诊断。
- 当前角色副本、独立图系统和临时画布，通过 cljfx.plorer 鼠标按下 [200,200]、拖到 [270,170]、松开，创建 tail 的子骨骼 bone2，长度 76.15773105863906。预览期间资源未提交，生成骨骼 active=true，撤销恢复原副本，重做恢复结果。
- 当前编辑器已热加载。刚创建的 bone1 经 plorer 属性复选框取消残留的 skin-required 标记，骨骼几何完全未变，该修复可撤销。未保存用户文件。
- 最终 bone1-visible=true，tree-refreshed=true，禁用计时器 0。截图见同目录。

仅编辑器变更，运行时不改；未打包，当前为会话热加载。
