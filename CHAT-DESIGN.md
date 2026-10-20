# Android 聊天设计基准

2026-10-11；本轮独立 Android/iOS 任务共享基准，Android master 增量实现。根 DESIGN.md 和 ui/theme/Theme.kt 仍持有主题，本文只记录受影响表面。

## 页面

面向本机 AI 工作台的移动端操作：看清执行、阅读回复、继续输入。Compose Material 结构、系统中文字体、现有灰白蓝/深色语义 token；代码继续 JetBrains Mono。以实际工作状态的毛绒头像为唯一视觉表达，不增加背景装饰。

- 顶部：取消整行顶栏、常驻身份标题和状态文案。左侧悬浮返回，右侧文件/更多，猫头浮在同一层。48dp触控、40dp独立圆面、20dp图标。消息阅读视口从顶部按钮与猫头安全槽下方开始，按现有控件实际几何避让，并裁剪到自身边界；首条发送、上翻、回复定位和键盘变化都不能让正文穿入控件区。顶部仍无整行背景，不改变按钮位置与48dp触控尺寸。更多显示真实对象与cwd并复用原对象/目录选择器，变更与提交移入更多；系统返回、文件全屏预览沿用原所有者。浅深色使用语义表面与文字token。
- 消息：用户气泡右侧，助手开放式正文左侧；28dp 静态作者头像、名称、时间、复制与折叠在同一行。工具过程沿用真实合组和原位展开。历史不重播头像/入场。
- 输入：`FocusCapsuleComposer` 持有同一个原生编辑节点；收起胶囊显示草稿摘要，点正文展开（会话顶栏已不再是可点开的伙伴，IM 私聊顶部伙伴仍保留该入口），展开文字整行、设置在下方。右侧独立 48dp 圆形槽由同一个按钮承载：空输入且空闲时隐藏且不可点击/不可无障碍聚焦；有文字或附件显示发送；运行态优先显示停止，即使输入清空或再次输入。加载/回执保留原位短暂反馈。槽位几何固定，显隐不改变输入宽度；键盘关闭、展开、有草稿、仅附件、发送中与未知送达均不遮挡运行态停止。发送回执仍由 SubmitMorphButton/ChatComposer 持有；停止不提交、不清草稿或附件，沿用现有确认与停止入口。群聊非焦点输入布局也使用外侧发送/停止圆钮。
- 点猫或键盘展开原生文字编辑；文本框仅编辑文字，长按交给系统选择/粘贴，附件、模型、推理、目录、更多保留原业务所有者。模型/模式弹层由稳定的输入栏父级持有；弹层、系统附件选择器及窗口暂时失焦不等于结束编辑，关闭后保留原草稿并恢复原编辑焦点。点按猫头展开编辑，横向移动进入拖动换边。成功ACK且捕获草稿空才收起，失败/unknown粘性完整保留；键盘关闭可收起草稿微提示。
- 直接拖猫左右吸附；本机全局SharedPreferences持久化，跨聊天与重启，功能胶囊交换侧别但内部序不变；没有位置设置按钮，不承诺跨设备。无障碍自定义移动动作等价于拖动。
- 头像与操作共用同一中心线；状态利用两者之间的空间，不再额外保留48/64dp状态底行。区域正常字号88dp，大字号按主题行高为最多四行状态留足空间，高度不随工具/文本流/回执变化。角色实际尺寸依可用宽度，给读写道具与紧贴头顶思考轨道留空间，不遮正文/嘴/输入。仅当前轮未完成Thinking块显示与Web同式8瓣漫画orbit，使用实际3D投影头顶锚点；读写和纯文字流不显示。待机/思考/文本流闭嘴。停止沿用原确认路径。
- 任务进度折叠态突出当前事项，最多两行，右侧显示步数；完整任务和用量通过WandInlinePanel向上展开，列表有界滚动。折叠时不另铺一行用量；有任务/子代理进度时不重复泛化的“正在处理”。断线、回执与真实读写状态仍可见。
- 消息 LazyColumn 在顶栏与输入栏之间持有滚动；手动上翻不自动抢回，IME/navigationBarsInsets 沿用。平板正文居中限宽，字号使用sp。
- 空/错误/加载使用原生共享状态；错误保留输入，明确重试入口。长代码横向滚动与复制保留。

## 头像

消费 Web 最终 renderer.ts + plush-cat-appearance.ts（初二连续下巴、下唇/下颌体积，虎妞轻修整）及读写 v3 file-props.ts，不另做猫脸。赛博虎妞=plush-cat:v1:silver，勤劳的初二=plush-cat:v1:orange，系统 ID 优先锁定；其他员工 UTF-16 稳定ID hash/plush:v1 参数与Web一致，自定义图仍保留。嘟嘟=plush-dog:v1:shiba:red-scarf（柴犬头 + 红针织围巾），离线肖像与共享 WebGL 场景同源。

一处解析、处处同一张脸：所有头像位只调 `components/EmployeeAvatar.kt`，页面只传 id/name/avatar（群聊工位传 `employeeId ?: id`），不再各写一份 spec。优先级固定为 锁定系统 ID > 上传图 `data:image/` > 定位不到身份才回落 APP logo > 显式旧猫 `cat:<n>` > 毛绒 token（dog/cat/六段捏脸）> 按身份生成的默认毛绒。绑定通讯录身份的群聊成员在 `displayTeamTurn` 按 employeeId 写回毛绒 token（Web `displayChatTurn` 同口径），所以同一个人在通讯录、会话列表、工位条和群聊消息是同一张脸；未绑定员工的成员不改写头像取值，脸同样按成员 id 一处生成。

输入区通过 APK 内离线打包的共同 WebGL 场景显示：只头与两独立团手、真正3D书/本/笔、弯曲页、书写面向角色。Kotlin 只投影 idle/reading/writing + thinking/speaking。历史与列表仍 PNG，无额外WebView。失败回落静态PNG；网络、文件任意访问、JS bridge及外部导航关闭。前台且可见才播放，低动效静态、退出释放、后台暂停。

工具投影：只当前用户轮次未完成、无subagent的 read_file/edit_file。结果（含错误）按tool ID退休；completedAt/取消/等待审批/非running回idle。并发使用最后一个仍活跃的调用；不凭工具名或回复文字猜。

## 路径与验收

components/EmployeeAvatar.kt、PlushAvatar.kt + 新 SharedPlushScene.kt；screens/ChatScreen.kt、ChatBlocks.kt、NativeComposerSurface.kt、SharedMessageComposer.kt、FloatingComposerCompanion.kt；导出脚本位于 android/tools，APK assets/plush-scene 使用源hash锁定，Web不双写。

最新独立猫头预览与结果：主仓 output/architecture-stage2/android-minimal-composer/；android-chat-redesign/仅保留此前布局证据。动态录像是模拟器实际渲染，静态图不冒充动效。最终安装服务流程只读验收；模拟/fixture不替代真实会话验收。

## 实施状态（本轮）

原生预览使用 -PWAND_UI_PREVIEW=1 与独立 applicationIdSuffix .preview，仅该构建包含fixture Activity；普通/带版本分发不包含预览。模型调用为0。

## 图片自适应放大与全屏查看器翻页（2026-10-19）

与 iOS 同组改动，尺寸与语义一致：`WandImageLayout.thumbnailMaxWidthDp/thumbnailMaxHeightDp = 320/360` 是聊天内联图片的唯一上限来源，`WandAsyncImage`、`WandAsyncToolImage` 都按自身比例在框内完整显示，不再各写像素值。两者新增 `group`/`groupIndex`，由 `viewerPages` + `viewerStart`（越界钳制到首张）换算成全屏查看器的页集合与起始页；`ChatBlocks.kt` 的上传附件与工具结果、`ComposerHelpers.kt` 的待发送附件都按同一条消息内的图片成组，`ToolActivityDetail.kt`、`ChatMarkdown.kt` 等单图入口传单页。`FullscreenImageViewer` 改为 `HorizontalPager`：未放大时横向滑动翻页、多张时右下角 `n / N`，双击/捏合缩放至 1–5×，放大后横向手势改判为平移且 `userScrollEnabled` 关翻页，纵向仍下滑关闭；每页独立重试状态，data URI 页在页内解码。缩放用自写的 `Modifier.imagePinchPan` 而非 `detectTransformGestures`——后者无条件消费单指拖动，会让图片能缩放但翻不了页。回归见 `WandImageFeedbackTest`（`inlineImagesShareOneAdaptiveBoundingBoxWithIos`、`viewerStartClampsAndGroupKeepsEveryImage`、`toolImageSourcesBecomeAbsoluteOnlyWhenRelative`）。本轮无可用 Android 设备/模拟器，真机触控与视觉未验收，不以编译与单测通过替代。

## 会话顶栏改成身份栏：任务名 + 员工·目录（2026-10-19）

与 iOS 同组改动，语义一致：会话顶栏原先是「头像 + 员工名 + 一句状态」，读起来像「赛博虎妞 正在处理」，主位被员工名占走，状态那句又和权限卡、连接横幅、待办进度条重复。新增 `screens/ChatTopIdentityBar.kt`，`ChatScreen` 的顶部覆盖层由 `TopChatCompanion` 换成 `ChatTopIdentityBar`：

- 主标题 = 导航路由带进来的任务名 `taskName`（此前已解析但未使用），独立会话回落 `store.snapshot?.displayTitle`；`currentTaskTitle` 仍不参与顶栏，与 iOS 同守「响应期间不替换标题」。
- 小字 = 「员工名 · 目录缩写」，目录复用既有的 `sessionCwdLeaf`（只取末级名，Windows 反斜杠同样处理），缺项省掉分隔符。
- 头像取消：会话页不再画 `EmployeeAvatar`，顶部伙伴的左右拖动与点按展开入口随之退出会话页；展开输入仍有胶囊正文自己的「展开键盘输入」触控行，不是只剩头像一个入口。IM 私聊以头像为身份，继续用 `TopChatCompanion`。
- 运行与异常只以一个 6dp 状态点呈现（brand=运行、permission=等待确认、danger=异常，空闲不画点），呼吸复用组件库的 `Modifier.statusBreath()`（需要时它自己遵守 reduce motion），不为顶栏另起动画；`chatTopReadback` 把「标题，小字，状态」完整交给读屏，`LiveRegionMode.Polite` 保留。
- 高度改为两行文字的 `chatTopIdentityClearance()`（`max(ChatFloatingControlsClearance, textHeight + 8)`），不再为头像占位，阅读视口上边界 `chatPadding` 同源；左右按 `FloatingControlsBackInset`/`FloatingControlsActionsInset` 让开返回键与文件/更多按钮，宽度与头像版一致。
- 状态点判定走 `chatTopAttention(connected:blocked:sending:responding:failed:)`，只读真实状态位（`store.connected`、`pendingEscalation`/`legacyPermissionPrompt`、`SendPhase.Sending`、`store.isResponding`、`store.status == "failed"`），不从展示文案反推。

回归见 `ChatTopIdentityTest`（任务名优先、目录末级名与缺项、状态点判定矩阵、读屏播报、顶栏无头像且 `chat-visible` 身份只有一个持有者）与 `ComposerStatusLayoutTest`（视口上边界改用 `chatTopIdentityClearance()`）。本轮无可用 Android 设备/模拟器，真机触控与视觉未验收，不以编译与单测通过替代。

## 空任务直接内嵌空白对话（2026-10-20）

与 iOS 同组改动：任务详情在无会话时原先是 `EmptyTaskWelcome`（「开始这个任务」/执行对象列表），现 `WorkspaceTaskScreen` 的空会话分支改为调用注入的 `emptyTaskContent(cwd)`，由 `WandApp` 传入绑定当前任务的 `PendingConversationScreen(embedded = true)`。embedded 只收掉重复的 chrome：不再画自己的 `WandDetailTopBar`，任务页仍是唯一一条顶栏；创建、草稿、排队与未知结果确认继续由 `PendingConversationScreen` + `PendingCreationStore` 原链路负责，不另写状态机。`workspaceTaskId = screen.taskId` 固定归属，创建后按既有路由进入 Chat 或 PTY。回归见 `WorkspaceEmptyTaskChatTest`（空任务注入绑定会话、不再出现旧欢迎页、embedded 保留单条顶栏）。本轮无可用设备，真机视觉未验收。
