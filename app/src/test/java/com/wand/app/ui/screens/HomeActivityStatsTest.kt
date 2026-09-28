package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页状态行的口径与门函数（设计规格 §2.8 B2、§2.28 S24、§6.2）。
 *
 * 三件事各自只测一次，互不复制（D14）：
 * 1. **唯一整行门** [homeActivityStripVisible] 的五态——它就是调用处实际用的那个门（D13），
 *    所以另有一条读源码的接线守卫（[callSiteWiresTheOnlyStripGate]）保证没有第二层 gate；
 * 2. **统计对象**在四种筛选组合下的计数、两枚胶囊与选中语义，必须全部来自
 *    「搜索 ∩ 只看等你」的最终交集，分母才是全量数；
 * 3. **空态文案**三组合各一份，建议指向真正挡着列表的那一层。
 */
class HomeActivityStatsTest {

    // MARK: - 唯一整行门（D13 五态）

    @Test
    fun quietHomeWithoutAnyFilterRendersNoStrip() {
        val stats = statsFor(quietGroups(), query = "", attentionOnly = false)

        assertFalse(homeActivityStripVisible(showingBoard = false, stats = stats))
    }

    @Test
    fun searchWithZeroHitsStillRendersTheRow() {
        // 命中 0 条也要把「0 / M 条匹配」显示出来，否则计数会和空态互相矛盾。
        val stats = statsFor(fullGroups(), query = "zzzqqq", attentionOnly = false)

        assertEquals("0 / 4 条匹配", stats.countLabel)
        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
    }

    @Test
    fun filterOnWithZeroWaitingSessionsKeepsTheClosePathVisible() {
        // 这批数据里一个等待的都没有，但开关必须还在原地，否则用户点开就再也关不掉。
        val stats = statsFor(quietGroups(), query = "", attentionOnly = true)

        assertTrue(stats.showsAttentionPill())
        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
        assertFalse("同一批数据在未筛选时仍然不给空壳", homeActivityStripVisible(showingBoard = false, stats = statsFor(quietGroups(), "", false)))
    }

    @Test
    fun combinedFilterWithZeroIntersectionRendersTheRow() {
        val stats = statsFor(fullGroups(), query = "alpha", attentionOnly = true)

        assertEquals(0, stats.overview.sessions)
        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
    }

    @Test
    fun taskBoardNeverRendersTheSessionStrip() {
        // 会话段该渲染的同一批数，到了任务段必须不渲染；看板有自己的计数。
        val stats = statsFor(fullGroups(), query = "", attentionOnly = false)

        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
        assertFalse(homeActivityStripVisible(showingBoard = true, stats = stats))
    }

    // MARK: - 四组合的计数、两枚胶囊与选中语义

    @Test
    fun idleHomeReportsGlobalCountAndBothPillsUnselected() {
        val stats = statsFor(fullGroups(), query = "", attentionOnly = false)

        assertEquals("4 个会话", stats.countLabel)
        assertTrue(stats.showsRunningPill())
        assertEquals("2 个在跑", stats.runningPillLabel())
        assertTrue(stats.showsAttentionPill())
        assertEquals("1 个等你", stats.attentionPillLabel())
        assertEquals("只看需要处理的会话", stats.attentionPillDescription())
        assertEquals("未开启", stats.attentionPillStateDescription())
    }

    @Test
    fun searchSwitchesAllThreeNumbersToTheFilteredBatch() {
        // 搜索词只命中在跑的 alpha：筛完 1 条（1 在跑 / 0 等你），不能挂全局的「1 个等你」。
        val stats = statsFor(fullGroups(), query = "alpha", attentionOnly = false)

        assertEquals(1, stats.overview.running)
        assertEquals(0, stats.overview.needsYou)
        assertEquals("1 / 4 条匹配", stats.countLabel)
        assertFalse("等你会话不在最终交集里，胶囊就不该出现", stats.showsAttentionPill())
    }

    @Test
    fun attentionFilterCountsTheWaitingBatchAsPendingNotMatches() {
        val stats = statsFor(fullGroups(), query = "", attentionOnly = true)

        // 只看等你的交集只保留 NeedsYou，所以只读的在跑胶囊随交集变 0 而消失。
        assertEquals("1 / 4 条待处理", stats.countLabel)
        assertFalse(stats.showsRunningPill())
        assertTrue(stats.attentionOnly)
        assertEquals("1 个等你", stats.attentionPillLabel())
        assertEquals("取消只看需要处理的会话", stats.attentionPillDescription())
        assertEquals("已开启", stats.attentionPillStateDescription())
    }

    @Test
    fun combinedFilterNeverBorrowsTheGlobalRunningPill() {
        // 第 16 步的回归：列表 0 条却显示「11 / 27 条匹配 + 1 个在跑」。
        // 搜索命中的是在跑的 alpha，再经「只看等你」后交集为空 → 三个数都得是 0 批次。
        val stats = statsFor(fullGroups(), query = "alpha", attentionOnly = true)

        assertEquals("0 / 4 条匹配", stats.countLabel)
        assertEquals(0, stats.overview.running)
        assertFalse("交集为 0 时绝不挂只读的在跑胶囊", stats.showsRunningPill())
        assertTrue(stats.showsAttentionPill())
    }

    @Test
    fun selectedPillWithZeroWaitingReadsAsFilterOnNotAsImperative() {
        // 选中且零结果是「已选等你」+ 前导勾，沿用同一枚可点胶囊；「只看等你」那套未选中式文案不再出现。
        val combined = statsFor(fullGroups(), query = "alpha", attentionOnly = true)

        assertEquals("已选等你", combined.attentionPillLabel())
        assertEquals("取消只看需要处理的会话", combined.attentionPillDescription())
        assertEquals("已开启", combined.attentionPillStateDescription())
        // 固定触控槽把报数省略时，读屏仍要拿到完整数字与动作。
        assertEquals("取消只看需要处理的会话，0 个等你", combined.attentionPillFullDescription())
    }

    @Test
    fun cancellingOneLayerNeverDisturbsTheOther() {
        // 取消筛选：搜索词仍在，计数从「匹配」口径换成搜索结果数。
        val filterOff = statsFor(fullGroups(), query = "alpha", attentionOnly = false)
        assertTrue(filterOff.searching)
        assertEquals("1 / 4 条匹配", filterOff.countLabel)
        assertFalse(filterOff.attentionOnly)

        // 取消搜索：筛选仍是选中，计数立刻换成「待处理」口径。
        val searchOff = statsFor(fullGroups(), query = "", attentionOnly = true)
        assertTrue(searchOff.attentionOnly)
        assertEquals("1 / 4 条待处理", searchOff.countLabel)
        assertFalse(searchOff.searching)
    }

    // MARK: - 空态三组合（§2.28）

    @Test
    fun emptyCopyPointsAtTheLayerThatActuallyHidesTheList() {
        val searchOnly = homeSessionEmptyCopy(searching = true, attentionOnly = false)
        val filterOnly = homeSessionEmptyCopy(searching = false, attentionOnly = true)
        val both = homeSessionEmptyCopy(searching = true, attentionOnly = true)

        assertEquals("没有匹配的会话" to "换个词试试，或者关掉搜索看全部。", searchOnly.title to searchOnly.subtitle)
        // 只开了筛选却叫用户「关掉搜索」就是找不到关闭点，所以两份建议都必须指回胶囊。
        assertEquals(
            "没有需要处理的会话" to "当前没有会话等你处理。再点「已选等你」查看全部会话。",
            filterOnly.title to filterOnly.subtitle,
        )
        assertEquals(
            "没有匹配的待处理会话" to "换个词试试，或者再点「已选等你」查看搜索结果。",
            both.title to both.subtitle,
        )
    }

    // MARK: - 调用点接线守卫（无 Compose / Robolectric 依赖）

    @Test
    fun callSiteWiresTheOnlyStripGate() {
        val taskList = readSource("TaskListScreen.kt")
        val chrome = readSource("HomeChrome.kt")
        val callSite = stripCallSite(taskList)

        assertTrue(
            "调用处必须直接使用唯一纯门 homeActivityStripVisible(showingBoard, activityStats)，否则纯函数测试盖不住实际显隐",
            callSite.contains("homeActivityStripVisible(showingBoard, activityStats)"),
        )
        assertFalse("渲染点之前不得再叠一层分段/筛选门", callSite.contains("if (!showingBoard") || callSite.contains("&& !attentionOnly"))
        assertFalse("旧的 selecting/attentionOnly 组合门不得回归", taskList.contains("if (!showingBoard && (selecting || !attentionOnly))"))
        assertFalse("组件内不得再有第二个整行早退", chrome.contains("if (!stats.showsRow("))
        assertFalse("showsRow 已删除，不得留任何引用", taskList.contains("showsRow") || chrome.contains("showsRow"))
    }

    // MARK: - fixtures

    /** 走和生产代码完全一样的链路：全量 → 搜索 → 只看等你 → 统计。 */
    private fun statsFor(
        groups: List<TaskDirectoryGroup>,
        query: String,
        attentionOnly: Boolean,
    ): HomeActivityStats {
        val searched = homeSearchGroups(groups, query)
        val visible = if (attentionOnly) attentionOnlyGroups(searched) else searched
        return homeActivityStats(
            globalOverview = homeOverview(groups),
            finalOverview = homeOverview(visible),
            searching = query.isNotBlank(),
            attentionOnly = attentionOnly,
        )
    }

    /** alpha / delta 在跑，beta 等你，gamma 空闲；查询词只可能命中 alpha。 */
    private fun fullGroups() = listOf(
        group(
            "wand",
            listOf(
                task(
                    "t1",
                    listOf(session("alpha", "running"), session("beta", "permission"), session("gamma", "idle")),
                ),
            ),
        ),
        group("tmp", listOf(task("t2", listOf(session("delta", "running"))))),
    )

    private fun quietGroups() = listOf(
        group("wand", listOf(task("t1", listOf(session("gamma", "idle"))))),
    )

    /** 渲染 `HomeActivityStrip(` 那一处的调用点上下文：门函数必须就写在这里。 */
    private fun stripCallSite(source: String): String {
        val start = source.indexOf("HomeActivityStrip(")
        assertTrue("首页必须渲染 HomeActivityStrip", start >= 0)
        return source.substring(maxOf(0, start - 200), start)
    }

    /**
     * 从 Gradle 工作目录定位生产源码：`android/app` 与 `android` 两种工作目录各一个候选，
     * 只允许命中一个，否则直接失败——跳过等于守卫没跑。
     * （用 `java.io.File` 而不是 `Files.readString`：单元测试的编译类路径是 android.jar 桩，
     * 那个 API 在桩里没有。）
     */
    private fun readSource(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/wand/app/ui/screens", fileName),
            File("app/src/main/java/com/wand/app/ui/screens", fileName),
        ).filter { it.isFile }
        assertEquals("源码守卫定位 $fileName 必须恰好命中一个候选", 1, candidates.size)
        return candidates[0].readText()
    }

    private fun group(
        id: String,
        tasks: List<WorkspaceTaskSummary>,
        standalone: List<WorkspaceSessionSummary> = emptyList(),
    ) = TaskDirectoryGroup(
        workspaceId = id,
        workspaceName = id,
        workspaceCwd = "/tmp/$id",
        synthetic = false,
        tasks = tasks,
        standaloneSessions = standalone,
        createdAt = null,
        global = false,
    )

    private fun task(id: String, sessions: List<WorkspaceSessionSummary>) = WorkspaceTaskSummary(
        task = WorkspaceTask(
            id = id,
            workspaceId = "workspace-1",
            name = id,
            worktree = null,
            layout = null,
            status = WorkspaceTaskStatus.Active,
            createdAt = null,
            lastOpenedAt = null,
        ),
        cwd = "/tmp/$id",
        isolated = false,
        worktreeError = null,
        sessions = sessions,
        totalSessions = sessions.size,
    )

    /** structured 会话只有 `inFlight = true` 才算真的在跑，否则会被归成 idle。 */
    private fun session(id: String, status: String, inFlight: Boolean? = status == "running") = WorkspaceSessionSummary(
        id = id,
        provider = "claude",
        sessionKind = "structured",
        runner = null,
        title = id,
        status = status,
        cwd = "/tmp/$id",
        startedAt = "2026-09-25T11:55:00Z",
        inFlight = inFlight,
    )
}
