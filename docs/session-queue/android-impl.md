# Android 队列取回编辑实现

## 本轮结果

- 仅修改 android/；保留平台工作区原有改动，尤其 WandApi.kt 已有任务评论修改。没有提交、push、发布、安装或启动设备，没有调用真实模型。
- ChatScreen.QueueBar 进入待办/输入的共同区域；编辑按钮不再弹独立 dialog，触发同一个 ChatInputPresentation.edit()，使用 ChatComposer 的输入节点。展开列表有界滚动，系统返回和动作选择关闭；箭头旋转使用 WandMotion 并尊重 reduced motion。
- 取回走 DELETE /api/structured-sessions/:id/queued/:index，body 携带精确 expectedText。严格要求返回相同 session id 和 queuedMessages 数组后才承认成功；不新增 API。
- 禁止队列 DELETE 网络层连接失败重试；未知结果（网络、408/409/5xx、取消、无效 snapshot）保留原文并阻止再次取回。
- ChatComposer 持有取回操作；SessionDraftStore 的会话级内存恢复记录跨页面重建保留。确认成功且原空草稿 revision 未变才自动回填；已有/新草稿需要显式“加入当前输入继续编辑”，按最新草稿追加，不覆盖文本或附件。
- 未知结果显示完整可选择原文；只有用户明确“确认已移出且未执行，恢复原文”才恢复，不再次 DELETE，不自动发送。明确拒绝只允许关闭提示，不把仍在队列中的原文自动复制到可发送输入。
- 普通单条删除也携带 expectedText、应用确认 snapshot，避免旧乐观队列失败回滚覆盖新事件。队列各类 mutation 共享 pending 门禁。

## 证据入口

- app/src/main/java/com/wand/app/data/WandApi.kt：queueRemovalClient / deleteQueued。
- app/src/main/java/com/wand/app/ui/ChatStore.kt：retrieveQueued / deleteQueued / queueMutationPending。
- app/src/main/java/com/wand/app/ui/ChatComposer.kt：retrieveQueued / restoreQueuedOriginal / shutdown。
- app/src/main/java/com/wand/app/ui/SessionDraftStore.kt：QueueRecovery 与 session-keyed 内存恢复记录。
- app/src/main/java/com/wand/app/ui/screens/ChatScreen.kt：QueueBar / QueueItemRow / BottomBar。
- app/src/test/java/com/wand/app/ui/QueueRetrievalTest.kt：5 个新测试，覆盖确认前不写入、单飞、精确原文、最新草稿与附件、未知不重试、取消与跨会话隔离、明确拒绝。

## 实际验证

执行：

```bash
cd android
./gradlew :app:testDebugUnitTest --tests com.wand.app.ui.QueueRetrievalTest --tests com.wand.app.ui.ChatComposerTest --console=plain
```

结果：BUILD SUCCESSFUL in 48s，29 actionable tasks（16 executed、13 up-to-date）；包含 compileDebugKotlin、compileDebugUnitTestKotlin、verifyWandDesignSystem。

日志：/tmp/wand-android-queue-gradle.log。

`git -C android diff --check` 通过。默认 app/build/test-results 路径不存在（项目将构建输出外置），未单独统计 XML 测试数量，不将该读取失败算作测试失败或通过证据。

模型/工具：OpenAI/gpt-6.1-sol；bash、read、edit、write。

## 剩余（待安排）

- 未增加真实 HTTP fixture 的 DELETE 契约测试；expectedText 和 snapshot 校验目前由源码与编译验证，后续补回包缺字段、错误 id、409 和连接中断用例。
- 未做 Android 实机/模拟器的布局、触控、键盘与返回验收，也未连接本机已安装 Wand 服务验收真实队列。因此本轮只声明源码与 JVM 定点通过。
- 未构建部署 Beta；本轮任务显式允许下一轮执行。未做 iOS 改动，交由 captain 对照其本轮队友交付确认移动端语义一致。
- 恢复记录只在进程内存保存，进程结束后不会自动持久化未知结果原文。保留此边界避免不明送达的排队原文被恢复成可重复发送草稿。
- 编译出现既有 ComposerDraftAdoption 内部构造器与生成 copy() 可见性警告（SessionDraftStore.kt:11），Kotlin 2.5 升级时会成为错误；本轮未调整该不相关公开 API。
- 既有 promote/clear 的整队乐观回滚仍存在；本轮只改取回和单条删除，不宣称完成所有队列 mutation 并发语义审计。
