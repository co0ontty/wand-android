package com.wand.app.ui.screens

import com.wand.app.data.SiliconEmployee
import com.wand.app.data.WorkspaceBinding

/** 历史身份只用于展示；发起新员工对话时进入统一待发送页面。 */
internal fun homeGroupAssignableEmployee(
    group: HomeGroup,
    employees: List<SiliconEmployee>,
): SiliconEmployee? = if (group.kind == HomeGroupKind.Employee) {
    group.employeeId?.let { contactAssignableEmployee(it, employees) }
} else null

/** 沿用最近对话的项目目录，不把新对话挂到旧任务或旧 worktree。 */
internal fun homeGroupConversationBinding(group: HomeGroup): WorkspaceBinding? {
    val recent = group.conversations.firstOrNull() ?: return null
    val cwd = recent.group.workspaceCwd.takeIf { it.isNotBlank() }
        ?: recent.session.cwd?.takeIf { it.isNotBlank() } ?: return null
    return WorkspaceBinding(
        workspaceId = recent.group.takeUnless { it.synthetic || it.isGlobal }?.workspaceId,
        cwd = cwd,
    )
}
