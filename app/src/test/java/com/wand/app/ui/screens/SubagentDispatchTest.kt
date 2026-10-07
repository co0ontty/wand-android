package com.wand.app.ui.screens

import com.wand.app.data.SubagentMeta
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：pi `subagent` 的 workflow 形态派发（名字是 `Pi/subagent`、没有 `agent`/`task`，
 * 也没有 `subagent_type`）必须被认成派发块，既不占正文活动轨迹步骤、也不重复渲染。
 * 判据与 iOS/macOS `isSubagentDispatchBlock` 同源：服务端 `__subagent` 盖章是唯一来源。
 */
class SubagentDispatchTest {
    private fun workflowMeta() =
        SubagentMeta(taskId = "call-wf", agentType = "workflow", taskDescription = "continuation.js")

    @Test
    fun workflowDispatchBlockIsClaimedByTheAgentRun() {
        assertTrue(isSubagentDispatchBlock(id = "call-wf", subagent = workflowMeta()))
    }

    @Test
    fun claudeDispatchBlockIsClaimedByTheAgentRun() {
        assertTrue(
            isSubagentDispatchBlock(
                id = "task-1",
                subagent = SubagentMeta(taskId = "task-1", agentType = "Explore", taskDescription = "查看依赖"),
            ),
        )
    }

    @Test
    fun innerCallsOfASubagentStayInTheTranscript() {
        assertFalse(isSubagentDispatchBlock(id = "read-1", subagent = workflowMeta()))
        assertFalse(isSubagentDispatchBlock(id = "bash-1", subagent = workflowMeta()))
    }

    @Test
    fun unstampedOrEmptyIdentityIsNotADispatch() {
        assertFalse(isSubagentDispatchBlock(id = "call-wf", subagent = null))
        assertFalse(isSubagentDispatchBlock(id = "call-wf", subagent = SubagentMeta(null, null, null)))
        assertFalse(isSubagentDispatchBlock(id = "call-wf", subagent = SubagentMeta("", null, null)))
        assertFalse(isSubagentDispatchBlock(id = "", subagent = SubagentMeta("call-wf", null, null)))
        assertFalse(isSubagentDispatchBlock(id = "call-wf", subagent = SubagentMeta(null, "workflow", null)))
    }
}
