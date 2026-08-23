package com.wand.app.data

/** 工作空间接口。WandApi 实现该端口；测试用 fake 实现。 */
interface WorkspacePort {
    /** GET /api/workspaces —— 列出所有项目（按最近打开排序）。 */
    suspend fun listWorkspaces(): List<Workspace>

    /** POST /api/workspaces —— 创建项目（不自动开会话）。 */
    suspend fun createWorkspace(name: String, cwd: String): Workspace {
        throw UnsupportedOperationException("创建项目接口不可用")
    }

    /** GET /api/workspaces/:id/worktrees —— 任务 worktree 合并概览。 */
    suspend fun workspaceWorktreeOverview(workspaceId: String): WorkspaceWorktreeOverview {
        throw UnsupportedOperationException("worktree 概览接口不可用")
    }

    /** 启动只绑定项目的 Worktree 合并 Agent（POST /api/commands，mode=managed）。 */
    suspend fun startWorktreeMergeAgent(
        workspace: Workspace,
        provider: String,
        prompt: String,
    ): SessionSnapshot {
        throw UnsupportedOperationException("worktree 合并接口不可用")
    }

    /** GET /api/workspaces/:id/tasks —— 列出某项目下的任务。 */
    suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask>

    /**
     * POST /api/workspaces/:id/tasks —— 创建任务。
     * [worktree] 为 null 时交由服务端默认（git 仓库自动隔离）；
     * 显式 false 跳过隔离，会话直接跑在项目目录。
     */
    suspend fun createWorkspaceTask(
        workspaceId: String,
        name: String,
        baseRef: String? = null,
        worktree: Boolean? = null,
    ): WorkspaceTaskCreation {
        throw UnsupportedOperationException("创建任务接口不可用")
    }

    /** GET /api/tasks —— 跨目录任务聚合（目录组一级容器）。 */
    suspend fun listTaskGroups(): List<TaskDirectoryGroup> {
        throw UnsupportedOperationException("任务聚合接口不可用")
    }

    /** PATCH /api/workspace-tasks/:taskId —— 重命名任务。 */
    suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask

    /** DELETE /api/workspace-tasks/:taskId?cascade=1 —— 删除任务、会话和隔离 worktree。 */
    suspend fun deleteWorkspaceTask(taskId: String)

    /** GET /api/workspace-tasks/:taskId —— 任务详情（含会话列表与派生字段）。 */
    suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail

    /** PUT /api/workspace-tasks/:taskId/layout —— 保存任务的窗口/分屏布局。 */
    suspend fun saveWorkspaceTaskLayout(
        taskId: String,
        layout: TaskWindowLayout?,
    ): TaskWindowLayout?

    /**
     * 在任务 worktree 内创建一个绑定工作窗口。
     *
     * - [WorkspaceSessionTarget.Shell] → POST /api/commands `{shell:true}`
     * - 其它 target → POST /api/commands PTY，provider 对应 CLI（qoder → qodercli）
     *
     * 模式、模型、thinking effort 使用服务端默认值；不调用 updateNewSessionDefaults，
     * 避免任务快捷选择器持久化全局新建偏好。
     */
    suspend fun createWorkspaceTaskWindow(
        target: WorkspaceSessionTarget,
        binding: WorkspaceBinding,
    ): SessionSnapshot
}
