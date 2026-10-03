package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApiException
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import kotlinx.coroutines.CancellationException

/** 常驻终端加号：只建 PTY，不建任务、不发送输入、不修改全局默认。 */
internal class RecentTerminalConversation(
    val target: WorkspaceSessionTarget,
    val binding: WorkspaceBinding?,
) {
    var busy by mutableStateOf(false)
        private set
    var snapshot by mutableStateOf<SessionSnapshot?>(null)
        private set
    var creationUnconfirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun create(api: WorkspacePort): SessionSnapshot? {
        if (busy || snapshot != null || creationUnconfirmed) return null
        busy = true
        error = null
        var requestStarted = false
        try {
            // 空分组没有历史目录，读取服务端默认；空串让服务端使用自己的目录兜底。
            val context = binding ?: WorkspaceBinding(cwd = api.taskDefaultCwd()?.trim().orEmpty())
            check(context.workspaceTaskId == null) { "快捷终端不能挂到旧任务" }
            requestStarted = true
            val created = api.createWorkspaceTaskWindow(target, context, WorkspaceSessionKind.Pty)
            check(created.id.isNotBlank() && !created.isStructured &&
                if (target.isShell) created.provider.isNullOrBlank() || created.provider == "shell"
                else created.provider == target.raw
            ) { "未收到有效的终端回执" }
            snapshot = created
            return created
        } catch (failure: Exception) {
            val status = (failure as? WandApiException)?.status
            creationUnconfirmed = requestStarted &&
                (status == null || status >= 500 || status == 408 || status == 409)
            error = if (creationUnconfirmed) {
                "创建结果未确认，请刷新列表并打开新终端核对，勿重复新建。"
            } else {
                failure.message ?: "创建终端失败，请稍后重试。"
            }
            if (failure is CancellationException) throw failure
            return null
        } finally {
            busy = false
        }
    }
}
