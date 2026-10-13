# Android 聊天与持续通话设计基准

2026-10-11；本轮独立 Android/iOS 任务共享基准，Android master 增量实现。根 DESIGN.md 和 ui/theme/Theme.kt 仍持有主题，本文只记录受影响表面。

## 页面

面向本机 AI 工作台的移动端操作：看清执行、阅读回复、继续输入。Compose Material 结构、系统中文字体、现有灰白蓝/深色语义 token；代码继续 JetBrains Mono。以实际工作状态的毛绒头像为唯一视觉表达，不增加背景装饰。

- 顶部：取消整行顶栏、常驻身份标题和状态文案。左侧悬浮返回，右侧通话/文件/更多，猫头浮在同一层。48dp触控、40dp独立圆面、20dp图标。消息阅读视口从顶部按钮与猫头安全槽下方开始，按现有控件实际几何避让，并裁剪到自身边界；首条发送、上翻、回复定位和键盘变化都不能让正文穿入控件区。顶部仍无整行背景，不改变按钮位置与48dp触控尺寸。更多显示真实对象与cwd并复用原对象/目录选择器，变更与提交移入更多；系统返回、文件全屏预览沿用原所有者。浅深色使用语义表面与文字token。
- 消息：用户气泡右侧，助手开放式正文左侧；28dp 静态作者头像、名称、时间、复制与折叠在同一行。工具过程沿用真实合组和原位展开。历史不重播头像/入场。
- 输入：`FocusCapsuleComposer` 持有同一个原生编辑节点；收起胶囊显示草稿摘要，点正文或顶部伙伴展开，展开文字整行、设置在下方。语音始终留在胶囊内，与发送/停止独立。右侧独立 48dp 圆形槽由同一个按钮承载：空输入且空闲时隐藏且不可点击/不可无障碍聚焦；有文字或附件显示发送；运行态优先显示停止，即使输入清空或再次输入。加载/回执保留原位短暂反馈。槽位几何固定，显隐不改变输入宽度；键盘关闭、展开、有草稿、仅附件、发送中与未知送达均不遮挡运行态停止。发送回执仍由 SubmitMorphButton/ChatComposer 持有；停止不提交、不清草稿或附件，沿用现有确认与停止入口。群聊非焦点输入布局也使用外侧发送/停止圆钮。
- 点猫或键盘展开原生文字编辑；文本框仅编辑文字，长按交给系统选择/粘贴，不在框内保留麦克风或长按录音覆盖层。语音只由独立猫头/麦克风与通话入口承接。附件、模型、推理、目录、更多保留原业务所有者。模型/模式弹层由稳定的输入栏父级持有；弹层、系统附件选择器及窗口暂时失焦不等于结束编辑，关闭后保留原草稿并恢复原编辑焦点。300ms未移动进入按住录音；阈值前横向移动进入拖动并永不录音；录音开始后上滑仅取消，不能换边。松手只转写进绑定会话和revision的草稿，用户手动发送。权限回调不续录；取消、空录音保留旧稿。成功ACK且捕获草稿空才收起，失败/unknown粘性完整保留；键盘关闭可收起草稿微提示。
- 直接拖猫左右吸附；本机全局SharedPreferences持久化，跨聊天与重启，功能胶囊交换侧别但内部序不变；没有位置设置按钮，不承诺跨设备。无障碍自定义移动动作等价于拖动。
- 头像与操作共用同一中心线；状态利用两者之间的空间，不再额外保留48/64dp状态底行。区域正常字号88dp，大字号按主题行高为最多四行状态及48dp暂停朗读操作留足空间，高度不随工具/文本流/回执变化。角色实际尺寸依可用宽度，给读写道具与紧贴头顶思考轨道留空间，不遮正文/嘴/输入。仅当前轮未完成Thinking块显示与Web同式8瓣漫画orbit，使用实际3D投影头顶锚点；读写和纯文字流不显示。待机/思考/文本流闭嘴，仅平台TTS实际onStart到onDone/onError张嘴。暂停朗读不停止任务，停止沿用原确认路径。
- 任务进度折叠态突出当前事项，最多两行，右侧显示步数；完整任务和用量通过WandInlinePanel向上展开，列表有界滚动。折叠时不另铺一行用量；有任务/子代理进度时不重复泛化的“正在处理”。断线、语音、回执与真实读写状态仍可见。
- 消息 LazyColumn 在顶栏与输入栏之间持有滚动；手动上翻不自动抢回，IME/navigationBarsInsets 沿用。平板正文居中限宽，字号使用sp。
- 空/错误/加载使用原生共享状态；错误保留输入，明确重试入口。长代码横向滚动与复制保留。

## 头像

消费 Web 最终 renderer.ts + plush-cat-appearance.ts（初二连续下巴、下唇/下颌体积，虎妞轻修整）及读写 v3 file-props.ts，不另做猫脸。赛博虎妞=plush-cat:v1:silver，勤劳的初二=plush-cat:v1:orange，系统 ID 优先锁定；其他员工 UTF-16 稳定ID hash/plush:v1 参数与Web一致，自定义图仍保留。嘟嘟=plush-dog:v1:shiba:red-scarf（柴犬头 + 红针织围巾），离线肖像与共享 WebGL 场景同源。

一处解析、处处同一张脸：所有头像位只调 `components/EmployeeAvatar.kt`，页面只传 id/name/avatar（群聊工位传 `employeeId ?: id`），不再各写一份 spec。优先级固定为 锁定系统 ID > 上传图 `data:image/` > 定位不到身份才回落 APP logo > 显式旧猫 `cat:<n>` > 毛绒 token（dog/cat/六段捏脸）> 按身份生成的默认毛绒。绑定通讯录身份的群聊成员在 `displayTeamTurn` 按 employeeId 写回毛绒 token（Web `displayChatTurn` 同口径），所以同一个人在通讯录、会话列表、工位条和群聊消息是同一张脸；未绑定员工的成员不改写头像取值，脸同样按成员 id 一处生成。

输入区通过 APK 内离线打包的共同 WebGL 场景显示：只头与两独立团手、真正3D书/本/笔、弯曲页、书写面向角色。Kotlin 只投影 idle/reading/writing + thinking/speaking。历史与列表仍 PNG，无额外WebView。失败回落静态PNG；网络、文件任意访问、JS bridge及外部导航关闭。前台且可见才播放，低动效静态、退出释放、后台暂停。

工具投影：只当前用户轮次未完成、无subagent的 read_file/edit_file。结果（含错误）按tool ID退休；completedAt/取消/等待审批/非running回idle。并发使用最后一个仍活跃的调用；不凭工具名或回复文字猜。

## 持续通话最小跨端协议 v1（本地状态，无新增服务协议）

目标=现有 sessionId + employeeId + cwd；只用已有授权会话及目录，开始前展示确认目标；切目标销毁旧回调。复用既有员工/目录选择页。

状态：off → listening → submitting → waiting → speaking → listening；另有 paused、approval、error。

- listening：端侧识别，partial只预览，final非空一次提交；客户端run generation屏蔽迟到回调。
- submitting：走既有会话send/ack；明确失败保留转写；未知送达停止自动链，要求核对文本记录，绝不重试。
- waiting：现有Agent状态裁决；权限问题进入approval并保留原审批UI，不自动确认。
- speaking：完成后的文本通过可替换TTS接口播报；代码/工具长输出只说有可查看的代码/工具结果，正文完整保留；只本地已安装语音，不启用网络语音或新付费服务。
- 用户“插话”立即停TTS并进入listening；不会自动取消正在运行的Agent。停止Agent沿用独立明确停止动作。
- 结束通话停止麦克风/TTS、废弃本轮回调；不删除记录、不暗中停止Agent。
- 麦克风拒绝/识别不可用/TTS语言不可用→error，文本聊天仍可用。
- 进入后台/系统音频焦点丢失/网络故障→paused/error，取消采音及播放；恢复需显式继续，不能后台自动录音。

测试用假识别/TTS/send；不录用户音频、不调用真实模型。真实预览由 debug-only Activity 使用公共 Compose 消息/输入/头像代码和明确示例文本；截图与录像必须来自 Android 模拟器。

## 路径与验收

components/EmployeeAvatar.kt、PlushAvatar.kt + 新 SharedPlushScene.kt；screens/ChatScreen.kt、ChatBlocks.kt、NativeComposerSurface.kt、SharedMessageComposer.kt、FloatingComposerCompanion.kt；speech/VoiceCallController.kt、CallSpeechEngine.kt、LocalTextSpeaker.kt；screens/VoiceCallPanel.kt。导出脚本位于 android/tools，APK assets/plush-scene 使用源hash锁定，Web不双写。

最新独立猫头预览与结果：主仓 output/architecture-stage2/android-minimal-composer/；android-chat-redesign/仅保留此前布局证据。动态录像是模拟器实际渲染，静态图不冒充动效。最终安装服务流程只读验收；模拟/fixture不替代真实会话验收。

## 实施状态（本轮）

通话细化状态与两端同步为 Off/Listening/Transcribing/Submitting/Processing/Speaking/Paused/Approval/Error。Android原发送锁入口是ChatComposer.submit/SendPhase；只有最终转写发送，超时partial不自动派工，静音30秒暂停。后台、断网、音频焦点丢失及按住说话/文本输入切换暂停；回前台不自动开麦。现有员工/目录选择器在通话目标面板复用，历史锁定目录仍提示新会话。

原生预览使用 -PWAND_UI_PREVIEW=1 与独立 applicationIdSuffix .preview，仅该构建包含fixture Activity；普通/带版本分发不包含预览。模型调用与私密录音均为0。平台TTS包可见性遵循 Android 官方 TextToSpeech API 文档，声明 TTS_SERVICE query；只选择已安装、不需要网络的中文voice。
