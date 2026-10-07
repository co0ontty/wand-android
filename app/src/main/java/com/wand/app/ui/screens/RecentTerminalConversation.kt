package com.wand.app.ui.screens

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget

/** 常驻终端加号：只建 PTY，不建任务、不发送输入、不修改全局默认。 */
internal class RecentTerminalConversation(
    val target: WorkspaceSessionTarget,
    val binding: WorkspaceBinding?,
) : ConversationCreation<SessionSnapshot>("终端") {
    val snapshot: SessionSnapshot? get() = created

    suspend fun create(api: WorkspacePort): SessionSnapshot? {
        var context = binding
        return createOnce(
            prepare = {
                // 空分组没有历史目录，读取服务端默认；空串让服务端使用自己的目录兜底。
                context = binding ?: WorkspaceBinding(cwd = api.taskDefaultCwd()?.trim().orEmpty())
                check(context?.workspaceTaskId == null) { "快捷终端不能挂到旧任务" }
            },
        ) {
            api.createWorkspaceTaskWindow(target, checkNotNull(context), WorkspaceSessionKind.Pty).also { created ->
                check(created.id.isNotBlank() && !created.isStructured &&
                    if (target.isShell) created.provider.isNullOrBlank() || created.provider == "shell"
                    else created.provider == target.raw
                ) { "未收到有效的终端回执" }
            }
        }
    }
}
