package com.wand.app.ui.screens

import com.wand.app.data.GitStatusResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷提交 lens 的状态矩阵（设计规格 §2.11 B5/S13a）：分支、改动数、↑↓ 从头部摘要
 * 搬到这里以后，这里就是全 sheet 唯一的一处 —— 加载 / 非 Git / 取不到状态都不许留白，
 * 已经报过的数字也不许在第二行再报一遍。
 */
class CommitLensStateTest {

    @Test
    fun loadingStateReadsInlineInsteadOfBlank() {
        val lens = commitLensState(status = null, loading = true)

        assertEquals("读取中…", lens.statusLine)
        assertEquals(CommitLensTone.Neutral, lens.tone)
        assertNull("还没拿到分支就不能编造分支名", lens.branch)
    }

    @Test
    fun missingStatusIsReportedAsNeutralNotQuiet() {
        val lens = commitLensState(status = null, loading = false)

        assertEquals("未获取到仓库状态", lens.statusLine)
        assertEquals(CommitLensTone.Neutral, lens.tone)
    }

    @Test
    fun nonGitRepoDegradesToNeutralTone() {
        val lens = commitLensState(gitStatus(isGit = false, branch = null), loading = false)

        assertEquals("非 Git 仓库", lens.statusLine)
        assertEquals(CommitLensTone.Neutral, lens.tone)
        assertNull(lens.branch)
    }

    @Test
    fun changesAreTheBrandStateAndAheadRidesAlong() {
        val lens = commitLensState(gitStatus(branch = "master", modifiedCount = 9, ahead = 2), loading = false)

        assertEquals("master", lens.branch)
        assertEquals("9 个改动待处理", lens.statusLine)
        assertEquals(CommitLensTone.Brand, lens.tone)
        assertEquals("↑2", lens.aheadBehindLine)
    }

    @Test
    fun aheadIsNotReportedTwiceWhenThereIsNothingToCommit() {
        // 状态行此时讲的就是「2 个 commit 待推送」，第二行再挂 ↑2 就是这条目要消灭的重复。
        val lens = commitLensState(gitStatus(branch = "master", modifiedCount = 0, ahead = 2), loading = false)

        assertEquals("2 个 commit 待推送", lens.statusLine)
        assertEquals(CommitLensTone.Success, lens.tone)
        assertNull(lens.aheadBehindLine)
    }

    @Test
    fun behindAlwaysAddsInformation() {
        val lens = commitLensState(gitStatus(modifiedCount = 3, ahead = 2, behind = 1), loading = false)

        assertEquals("↑2 ↓1", lens.aheadBehindLine)
    }

    @Test
    fun quietCleanRepoSaysSoWithoutAnyNumbers() {
        val lens = commitLensState(gitStatus(branch = "master", modifiedCount = 0), loading = false)

        assertEquals("工作区干净", lens.statusLine)
        assertEquals(CommitLensTone.Success, lens.tone)
        assertNull(lens.aheadBehindLine)
    }

    @Test
    fun missingBranchStillNamesTheFallback() {
        val lens = commitLensState(gitStatus(branch = null, modifiedCount = 1), loading = false)

        assertEquals("未识别分支", lens.branch)
    }

    @Test
    fun everyStateRendersANonBlankStatusLine() {
        val cases = listOf(
            commitLensState(status = null, loading = true),
            commitLensState(status = null, loading = false),
            commitLensState(gitStatus(isGit = false, branch = null), loading = false),
            commitLensState(gitStatus(), loading = false),
            commitLensState(gitStatus(modifiedCount = 4, ahead = 2, behind = 1), loading = false),
        )

        cases.forEach { assertTrue("lens 不允许出现空状态行", it.statusLine.isNotBlank()) }
    }

    private fun gitStatus(
        isGit: Boolean = true,
        branch: String? = "master",
        modifiedCount: Int? = 0,
        ahead: Int? = 0,
        behind: Int? = 0,
    ) = GitStatusResult(
        isGit = isGit,
        branch = branch,
        modifiedCount = modifiedCount,
        files = emptyList(),
        initialCommit = false,
        upstream = "origin/master",
        ahead = ahead,
        behind = behind,
        lastCommitShortHash = "7fb0b70",
        lastCommitSubject = "忽略本地一次性临时脚本",
        latestTag = "v1.2.3",
        hasSubmodule = false,
        error = null,
    )
}
