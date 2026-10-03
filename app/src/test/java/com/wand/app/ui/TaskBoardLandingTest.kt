package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 任务详情面包屑首段「回看板列表」的落点决策测试。
 *
 * 钉的是纯函数 [taskBoardLanding] 的两个分支，不需要模拟器走到那一层：
 * 下一层已经是看板列表 → Pop；栈里没有看板层（或下一层还是某个卡片详情）→ ReplaceTop。
 */
class TaskBoardLandingTest {

    @Test
    fun boardListBelowPops() {
        assertEquals(
            TaskBoardLanding.Pop,
            taskBoardLanding(Screen.TaskBoard()),
        )
    }

    /** 工作区收窄的看板列表也是「看板列表」，同样 pop，不新建一层。 */
    @Test
    fun workspaceScopedBoardListBelowPops() {
        assertEquals(
            TaskBoardLanding.Pop,
            taskBoardLanding(Screen.TaskBoard(workspaceId = "ws-1")),
        )
    }

    /** 下一层是另一张卡片详情：pop 会落到旧卡片上，所以要换栈顶。 */
    @Test
    fun anotherTaskDetailBelowReplacesTop() {
        assertEquals(
            TaskBoardLanding.ReplaceTop,
            taskBoardLanding(Screen.TaskBoard(workspaceId = "ws-1", taskId = "t-other")),
        )
    }

    /** 从首页会话列表「打开完整任务页」进来，栈里只有 [SessionList, 详情]，换栈顶而不是退回首页。 */
    @Test
    fun sessionListBelowReplacesTop() {
        assertEquals(
            TaskBoardLanding.ReplaceTop,
            taskBoardLanding(Screen.SessionList),
        )
    }

    @Test
    fun otherScreensBelowReplacesTop() {
        assertEquals(TaskBoardLanding.ReplaceTop, taskBoardLanding(Screen.Contacts()))
        assertEquals(TaskBoardLanding.ReplaceTop, taskBoardLanding(Screen.Settings))
        assertEquals(TaskBoardLanding.ReplaceTop, taskBoardLanding(Screen.Missions()))
        assertEquals(TaskBoardLanding.ReplaceTop, taskBoardLanding(Screen.AiTeamChat("run-1")))
    }

    /** 详情就是栈底（理论上取不到下一层）：换栈顶，不做无栈可退的 pop。 */
    @Test
    fun emptyBelowReplacesTop() {
        assertEquals(TaskBoardLanding.ReplaceTop, taskBoardLanding(null))
    }
}
