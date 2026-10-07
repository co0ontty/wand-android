package com.wand.app.ui.screens

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort

/** 历史身份只用于展示；新对话必须交给仍可用的真实员工，绝不回退到 CLI。 */
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

/** 加号的一次空白对话创建。无首条输入；未知回执不能靠重复点击盲目重建。 */
internal class RecentEmployeeConversation(
    val employeeId: String,
    val binding: WorkspaceBinding,
) : ConversationCreation<SessionSnapshot>("对话") {
    val snapshot: SessionSnapshot? get() = created

    suspend fun create(api: WorkspacePort, employee: SiliconEmployee?): SessionSnapshot? {
        if (!canCreate) return null
        if (employee?.id != employeeId || employee.archived || employee.agents.isEmpty()) {
            error = "员工已不存在或不可用，请刷新后重试。"
            return null
        }
        return createOnce {
            api.createEmployeeWorkspaceTaskWindow(employeeId, binding).also { created ->
                check(created.id.isNotBlank() && created.isStructured && created.employeeId == employeeId) {
                    "未收到有效的员工对话回执"
                }
            }
        }
    }
}
