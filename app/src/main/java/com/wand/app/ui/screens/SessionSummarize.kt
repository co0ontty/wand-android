package com.wand.app.ui.screens

import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceTaskCreation

/**
 * 未分组会话「归纳为新任务」的建卡方式（纯逻辑）：
 * 真实项目在项目下建卡；合成目录与全局空间没有可建卡的项目实体，挂会话所在目录建独立任务。
 * 名字一律留空 —— 服务端先按会话内容写临时标题，再交给模型改写。
 */
internal suspend fun summarizeSessionIntoNewTask(
    port: WorkspacePort,
    group: TaskDirectoryGroup,
    sessionId: String,
): WorkspaceTaskCreation {
    val creation = if (!group.synthetic && !group.isGlobal && group.workspaceId != GLOBAL_WORKSPACE_ID) {
        port.createWorkspaceTask(
            workspaceId = group.workspaceId,
            name = "",
            worktree = false,
            cwd = null,
        )
    } else {
        port.createStandaloneTask(
            name = "",
            worktree = false,
            cwd = group.workspaceCwd.ifBlank { null },
        )
    }
    port.moveWorkspaceSession(creation.id, sessionId)
    return creation
}
