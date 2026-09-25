package com.wand.app.data

/** 工作空间接口。WandApi 实现该端口；测试用 fake 实现。 */
interface WorkspacePort : TaskChangeSource {
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
     * [worktree] 为 null 时交由服务端默认（仅逻辑分组，不建目录）；
     * 显式 false 跳过隔离，会话直接跑在项目目录。
     */
    suspend fun createWorkspaceTask(
        workspaceId: String,
        name: String,
        baseRef: String? = null,
        worktree: Boolean? = null,
        cwd: String? = null,
        description: String? = null,
    ): WorkspaceTaskCreation {
        throw UnsupportedOperationException("创建任务接口不可用")
    }

    /** POST /api/tasks —— 不挂项目的独立任务；cwd 为空时使用全局临时目录。 */
    suspend fun createStandaloneTask(
        name: String,
        cwd: String? = null,
        worktree: Boolean? = null,
        description: String? = null,
    ): WorkspaceTaskCreation {
        throw UnsupportedOperationException("创建独立任务接口不可用")
    }

    /** GET /api/tasks —— 跨目录任务聚合（目录组一级容器）。 */
    suspend fun listTaskGroups(): List<TaskDirectoryGroup> {
        throw UnsupportedOperationException("任务聚合接口不可用")
    }

    /** GET /api/tasks?revision= —— 轮询时若未变则 unchanged，避免整表重绘。 */
    suspend fun listTaskGroupsPage(revision: String? = null): TaskGroupsPage {
        return TaskGroupsPage(listTaskGroups())
    }

    /** 新建任务时使用的服务端默认目录。 */
    suspend fun taskDefaultCwd(): String? = null

    /** 新建任务时展示的最近目录。 */
    suspend fun recentTaskPaths(): List<RecentPath> = emptyList()

    /** 目录浏览：返回当前路径下的目录与文件。 */
    suspend fun listDirectory(path: String): DirectoryListing {
        throw UnsupportedOperationException("目录浏览接口不可用")
    }

    /** 读取服务端偏好（会话类型、独立 worktree 等）。 */
    suspend fun serverConfig(): ServerConfigInfo {
        throw UnsupportedOperationException("配置接口不可用")
    }

    /** 把新建任务/窗口的选择写回服务端，下次默认沿用。 */
    suspend fun updateCreationDefaults(
        defaultProvider: String? = null,
        defaultSessionKind: String? = null,
        defaultTaskWorktree: Boolean? = null,
    ) {
        throw UnsupportedOperationException("配置接口不可用")
    }

    /** PATCH /api/workspace-tasks/:taskId —— 重命名任务。 */
    suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask

    /**
     * PATCH /api/workspaces/:id —— 重命名项目（工作区显示名）。
     * 服务端会把目录自定义名一起写，保证各端显示同一个名字。
     */
    suspend fun renameWorkspace(workspaceId: String, name: String): Workspace {
        throw UnsupportedOperationException("重命名项目接口不可用")
    }

    /**
     * DELETE /api/workspaces/:id —— 删除项目。
     * [cascade] 为 true 时连同其会话一起删除；false 只解绑（会话退回未分组）。
     */
    suspend fun deleteWorkspace(workspaceId: String, cascade: Boolean) {
        throw UnsupportedOperationException("删除项目接口不可用")
    }

    /**
     * PUT /api/workspaces/order —— 保存首页目录组的展示顺序（拖动排序）。
     * 传客户端看到的组 id 列表（真实工作区 id / 合成目录 `cwd:...`），服务端持久化。
     */
    suspend fun saveWorkspaceGroupOrder(ids: List<String>) {
        throw UnsupportedOperationException("保存目录顺序接口不可用")
    }

    /**
     * PUT /api/session-directories/name —— 重命名没有项目实体的合成目录。
     * [name] 为空时恢复目录名。
     */
    suspend fun renameSessionDirectory(cwd: String, name: String?) {
        throw UnsupportedOperationException("重命名目录接口不可用")
    }

    /** DELETE /api/workspace-tasks/:taskId?cascade=1 —— 删除任务、会话和隔离 worktree。 */
    suspend fun deleteWorkspaceTask(taskId: String)

    /**
     * POST /api/workspace-tasks/:taskId/archive —— 归档任务（软删除）。
     * 终端继续运行、worktree 保留，只从侧栏隐藏并移入看板归档。
     */
    suspend fun archiveWorkspaceTask(taskId: String): WorkspaceTask {
        throw UnsupportedOperationException("归档任务接口不可用")
    }

    /** 删除任务内全部会话，但保留任务与 worktree。 */
    suspend fun clearWorkspaceTaskSessions(taskId: String): Int {
        throw UnsupportedOperationException("清空任务会话接口不可用")
    }

    /** 结束并删除指定会话（POST /api/sessions/batch-delete）。 */
    suspend fun deleteWorkspaceSessions(sessionIds: List<String>): Int {
        throw UnsupportedOperationException("删除会话接口不可用")
    }

    /** Exclusive reassignment; preserves the running CLI, history and cwd. */
    suspend fun moveWorkspaceSession(taskId: String, sessionId: String) {
        throw UnsupportedOperationException("当前服务不支持移动会话，请更新服务端")
    }

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
     * - 其它 target + 结构化 → POST /api/structured-sessions
     * - 其它 target + PTY → POST /api/commands，provider 对应 CLI（qoder → qodercli）
     *
     * 模型 / thinking effort 可按这次新建的显式选择覆盖，否则使用服务端默认值；
     * 不调用 updateNewSessionDefaults，避免任务快捷选择器持久化全局新建偏好。
     */
    suspend fun createWorkspaceTaskWindow(
        target: WorkspaceSessionTarget,
        binding: WorkspaceBinding,
        kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
        prompt: String? = null,
        model: String? = null,
        thinkingEffort: String? = null,
    ): SessionSnapshot
}
