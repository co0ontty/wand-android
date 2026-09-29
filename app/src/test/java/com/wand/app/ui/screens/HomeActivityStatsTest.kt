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
 * 1. **唯一整行门** [homeActivityStripVisible]——它就是调用处实际用的那个门（D13），
 *    所以另有一条读源码的接线守卫（[callSiteWiresTheOnlyStripGate]）保证没有第二层 gate；
 * 2. **统计对象**在默认/只看等你两态下的计数、两枚胶囊与选中语义，必须全部来自
 *    最终可见会话，分母才是全量数；
 * 3. **空态文案**指向可关闭的「已选等你」筛选。
 */
class HomeActivityStatsTest {

    // MARK: - 唯一整行门

    @Test
    fun quietHomeWithoutAnyFilterRendersNoStrip() {
        val stats = statsFor(quietGroups(), attentionOnly = false)

        assertFalse(homeActivityStripVisible(showingBoard = false, stats = stats))
    }

    @Test
    fun filterOnWithZeroWaitingSessionsKeepsTheClosePathVisible() {
        // 这批数据里一个等待的都没有，但开关必须还在原地，否则用户点开就再也关不掉。
        val stats = statsFor(quietGroups(), attentionOnly = true)

        assertEquals("0 / 1 条待处理", stats.countLabel)
        assertTrue(stats.showsAttentionPill())
        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
        assertFalse("同一批数据在未筛选时仍然不给空壳", homeActivityStripVisible(showingBoard = false, stats = statsFor(quietGroups(), false)))
    }

    @Test
    fun taskBoardNeverRendersTheSessionStrip() {
        // 会话段该渲染的同一批数，到了任务段必须不渲染；看板有自己的计数。
        val stats = statsFor(fullGroups(), attentionOnly = false)

        assertTrue(homeActivityStripVisible(showingBoard = false, stats = stats))
        assertFalse(homeActivityStripVisible(showingBoard = true, stats = stats))
    }

    // MARK: - 两态计数、两枚胶囊与选中语义

    @Test
    fun idleHomeReportsGlobalCountAndBothPillsUnselected() {
        val stats = statsFor(fullGroups(), attentionOnly = false)

        assertEquals("4 个会话", stats.countLabel)
        assertTrue(stats.showsRunningPill())
        assertEquals("2 个在跑", stats.runningPillLabel())
        assertTrue(stats.showsAttentionPill())
        assertEquals("1 个等你", stats.attentionPillLabel())
        assertEquals("只看需要处理的会话", stats.attentionPillDescription())
        assertEquals("未开启", stats.attentionPillStateDescription())
    }

    @Test
    fun attentionFilterCountsTheWaitingBatchAsPendingNotMatches() {
        val stats = statsFor(fullGroups(), attentionOnly = true)

        // 只看等你只保留 NeedsYou，所以只读的在跑胶囊随可见列表变 0 而消失。
        assertEquals("1 / 4 条待处理", stats.countLabel)
        assertFalse(stats.showsRunningPill())
        assertTrue(stats.attentionOnly)
        assertEquals("1 个等你", stats.attentionPillLabel())
        assertEquals("取消只看需要处理的会话", stats.attentionPillDescription())
        assertEquals("已开启", stats.attentionPillStateDescription())
    }

    @Test
    fun selectedPillWithZeroWaitingReadsAsFilterOnNotAsImperative() {
        // 选中且零结果是「已选等你」+ 前导勾，沿用同一枚可点胶囊；「只看等你」那套未选中式文案不再出现。
        val stats = statsFor(quietGroups(), attentionOnly = true)

        assertFalse(stats.showsRunningPill())
        assertEquals("已选等你", stats.attentionPillLabel())
        assertEquals("取消只看需要处理的会话", stats.attentionPillDescription())
        assertEquals("已开启", stats.attentionPillStateDescription())
        // 固定触控槽把报数省略时，读屏仍要拿到完整数字与动作。
        assertEquals("取消只看需要处理的会话，0 个等你", stats.attentionPillFullDescription())
    }

    // MARK: - 空态文案

    @Test
    fun emptyCopyPointsAtTheAttentionFilter() {
        val copy = homeSessionEmptyCopy()

        assertEquals(
            "没有需要处理的会话" to "当前没有会话等你处理。再点「已选等你」查看全部会话。",
            copy.title to copy.subtitle,
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

    /** 走和生产代码完全一样的链路：全量 → 只看等你 → 统计。 */
    private fun statsFor(
        groups: List<TaskDirectoryGroup>,
        attentionOnly: Boolean,
    ): HomeActivityStats {
        val visible = if (attentionOnly) attentionOnlyGroups(groups) else groups
        return homeActivityStats(
            globalOverview = homeOverview(groups),
            finalOverview = homeOverview(visible),
            attentionOnly = attentionOnly,
        )
    }

    /** alpha / delta 在跑，beta 等你，gamma 空闲。 */
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
