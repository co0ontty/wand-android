package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.WandApiException
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import kotlinx.coroutines.CancellationException

/** 历史身份只用于展示；新对话必须交给仍可用的真实员工，绝不回退到 CLI。 */
internal fun homeGroupAssignableEmployee(
    group: HomeGroup,
    employees: List<SiliconEmployee>,
): SiliconEmployee? = employees.firstOrNull {
    group.kind == HomeGroupKind.Employee && it.id == group.employeeId &&
        !it.archived && it.agents.isNotEmpty()
}

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
) {
    var busy by mutableStateOf(false)
        private set
    var snapshot by mutableStateOf<SessionSnapshot?>(null)
        private set
    var creationUnconfirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun create(api: WorkspacePort, employee: SiliconEmployee?): SessionSnapshot? {
        if (busy || snapshot != null || creationUnconfirmed) return null
        if (employee?.id != employeeId || employee.archived || employee.agents.isEmpty()) {
            error = "员工已不存在或不可用，请刷新后重试。"
            return null
        }
        busy = true
        error = null
        try {
            val created = api.createEmployeeWorkspaceTaskWindow(employeeId, binding)
            check(created.id.isNotBlank() && created.isStructured && created.employeeId == employeeId) {
                "未收到有效的员工对话回执"
            }
            snapshot = created
            return created
        } catch (failure: Exception) {
            val status = (failure as? WandApiException)?.status
            creationUnconfirmed = status == null || status >= 500 || status == 408 || status == 409
            error = if (creationUnconfirmed) {
                "创建结果未确认，请刷新列表并打开新对话核对，勿重复新建。"
            } else {
                failure.message ?: "创建对话失败，请稍后重试。"
            }
            if (failure is CancellationException) throw failure
            return null
        } finally {
            busy = false
        }
    }
}
