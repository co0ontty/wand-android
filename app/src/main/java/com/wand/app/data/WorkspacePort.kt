package com.wand.app.data

/**
 * 第一批（只读 + 创建）工作空间接口。WandApi 实现该端口；测试用 fake 实现。
 * 不把危险的删除/重命名接口放入此端口 —— 第二批 CRUD 再扩展，避免页面误触发。
 */
interface WorkspacePort {
    /** GET /api/workspaces —— 列出所有项目（按最近打开排序）。 */
    suspend fun listWorkspaces(): List<Workspace>

    /** GET /api/workspaces/:id/tasks —— 列出某项目下的任务。 */
    suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask>

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
