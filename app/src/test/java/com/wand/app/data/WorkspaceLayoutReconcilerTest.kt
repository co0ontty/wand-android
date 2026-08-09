package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局 reconcile 纯函数测试。对齐 Web window-layout.ts：
 * - 过滤已删除 session 引用，保留 editor/preview/unknown tab
 * - 每个有效 session 最多出现一次
 * - 新 session 创建独立 window 并设为活动
 * - 保留 split / ratio / 非会话 tab
 */
class WorkspaceLayoutReconcilerTest {

    private fun sessionTab(id: String, sessionId: String = id): PaneTab.Session =
        PaneTab.Session("tab-$sessionId", sessionId)

    private fun pane(vararg tabs: PaneTab): LayoutNode.Pane =
        LayoutNode.Pane(tabs.toList(), 0)

    // MARK: - reconcileTaskWindowLayout

    @Test
    fun reconcile_nullPersisted_emptySessions_emptyLayout() {
        val layout = reconcileTaskWindowLayout(null, emptyList(), null)
        assertTrue(layout.windows.isEmpty())
        assertNull(layout.activeWindowId)
    }

    @Test
    fun reconcile_nullPersisted_addsMissingSessionsAsWindows() {
        val layout = reconcileTaskWindowLayout(null, listOf("s1", "s2"), null)
        assertEquals(2, layout.windows.size)
        assertEquals(listOf("window-s1", "window-s2"), layout.windows.map { it.id })
    }

    @Test
    fun reconcile_filtersDeletedSessions_keepsEditorTabs() {
        val persisted = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout(
                    "w1",
                    pane(sessionTab("s1"), PaneTab.Editor("e1", "/file")),
                    "tab-s1",
                ),
            ),
            activeWindowId = "w1",
        )
        // s1 已被删除（不在有效集合中），但 editor tab 应保留
        val layout = reconcileTaskWindowLayout(persisted, emptyList(), null)
        assertEquals(1, layout.windows.size)
        val tabs = layoutTabs(layout.windows[0].layout)
        assertEquals(1, tabs.size)
        assertTrue(tabs[0] is PaneTab.Editor)
    }

    @Test
    fun reconcile_deduplicatesSessionAcrossWindows() {
        // s1 在两个 window 里都引用 —— 去重后只出现一次
        val persisted = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout("w1", pane(sessionTab("s1")), "tab-s1"),
                WorkWindowLayout("w2", pane(sessionTab("s1")), "tab-s1"),
            ),
            activeWindowId = "w1",
        )
        val layout = reconcileTaskWindowLayout(persisted, listOf("s1"), null)
        val allSessionIds = layout.windows.flatMap { layoutSessionIds(it.layout) }
        assertEquals(1, allSessionIds.size)
        assertEquals("s1", allSessionIds[0])
    }

    @Test
    fun reconcile_preservesSplitStructure() {
        val persisted = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout(
                    "w1",
                    LayoutNode.Split(
                        dir = LayoutSplitDir.Horizontal,
                        ratio = 0.4,
                        children = pane(sessionTab("s1")) to pane(sessionTab("s2")),
                    ),
                    "tab-s1",
                ),
            ),
            activeWindowId = "w1",
        )
        val layout = reconcileTaskWindowLayout(persisted, listOf("s1", "s2"), null)
        assertEquals(1, layout.windows.size)
        val root = layout.windows[0].layout
        assertTrue(root is LayoutNode.Split)
        assertEquals(0.4, (root as LayoutNode.Split).ratio, 0.001)
    }

    @Test
    fun reconcile_preferredSessionSelectsActiveWindow() {
        val persisted = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout("w1", pane(sessionTab("s1")), "tab-s1"),
                WorkWindowLayout("w2", pane(sessionTab("s2")), "tab-s2"),
            ),
            activeWindowId = "w1",
        )
        val layout = reconcileTaskWindowLayout(persisted, listOf("s1", "s2"), "s2")
        assertEquals("w2", layout.activeWindowId)
    }

    // MARK: - addSessionWindow

    @Test
    fun addSession_emptyLayout_createsSingleWindow() {
        val layout = TaskWindowLayout(emptyList(), null)
        val next = addSessionWindow(layout, "new-session")
        assertEquals(1, next.windows.size)
        assertEquals("window-new-session", next.windows[0].id)
        assertEquals("window-new-session", next.activeWindowId)
    }

    @Test
    fun addSession_existingSession_onlyActivates() {
        val layout = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout("window-s1", pane(sessionTab("s1")), "tab-s1"),
                WorkWindowLayout("window-s2", pane(sessionTab("s2")), "tab-s2"),
            ),
            activeWindowId = "window-s1",
        )
        val next = addSessionWindow(layout, "s2")
        // 不新增 window，只切活动窗口
        assertEquals(2, next.windows.size)
        assertEquals("window-s2", next.activeWindowId)
    }

    @Test
    fun addSession_newSession_appendedAsActive() {
        val layout = TaskWindowLayout(
            windows = listOf(WorkWindowLayout("window-s1", pane(sessionTab("s1")), "tab-s1")),
            activeWindowId = "window-s1",
        )
        val next = addSessionWindow(layout, "s2")
        assertEquals(2, next.windows.size)
        assertEquals("window-s2", next.activeWindowId)
    }

    // MARK: - activateWorkWindow

    @Test
    fun activateWorkWindow_unknownId_returnsSameLayout() {
        val layout = TaskWindowLayout(
            windows = listOf(WorkWindowLayout("w1", pane(sessionTab("s1")), "tab-s1")),
            activeWindowId = "w1",
        )
        val next = activateWorkWindow(layout, "nonexistent")
        assertEquals(layout, next)
    }

    @Test
    fun activateWorkWindow_sameId_returnsSameLayout() {
        val layout = TaskWindowLayout(
            windows = listOf(WorkWindowLayout("w1", pane(sessionTab("s1")), "tab-s1")),
            activeWindowId = "w1",
        )
        val next = activateWorkWindow(layout, "w1")
        assertEquals(layout, next)
    }

    @Test
    fun activateWorkWindow_differentId_updatesActive() {
        val layout = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout("w1", pane(sessionTab("s1")), "tab-s1"),
                WorkWindowLayout("w2", pane(sessionTab("s2")), "tab-s2"),
            ),
            activeWindowId = "w1",
        )
        val next = activateWorkWindow(layout, "w2")
        assertEquals("w2", next.activeWindowId)
    }
}
