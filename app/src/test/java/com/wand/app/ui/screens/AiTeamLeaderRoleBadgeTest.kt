package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.3 F1（v5）：详情/编辑器成员卡标题——名字原样保留，负责人角色改用同一枚 13dp 矢量星。
 * 缺口是「成员名字恰为负责人」时标题行出现两遍「负责人」；这里锁住替换后的形态、语义与热区不被顺手动。
 * 用读源码的方式断言（本项目 JVM 单测无 Compose/Robolectric 依赖）。
 */
class AiTeamLeaderRoleBadgeTest {

    /** 规格 §2.3 F1 指定的唯一徽标形态：星形 13dp、与名字尾部间距 8dp、角色语义写进 contentDescription。 */
    private val badge =
        "if(member.isLeader){Icon(WandIcons.leader,contentDescription=\"团队角色：负责人\"," +
            "tint=WandColors.brand,modifier=Modifier.padding(start=8.dp).size(13.dp),)}"

    @Test
    fun detailCardReplacesTheRoleWordWithTheVectorStar() {
        val region = cardRegion("AiTeamDetailScreen.kt", "private fun AiTeamMemberCard")
        assertLeaderBadge(region, "详情成员卡")
    }

    @Test
    fun editorCardReplacesTheRoleWordWithTheVectorStar() {
        val region = cardRegion("AiTeamEditorScreen.kt", "private fun TeamMemberCard")
        assertLeaderBadge(region, "编辑器成员卡")
    }

    @Test
    fun bothCardsShareExactlyOneBadgeAndNoBareStarCharacter() {
        // 「同一个」星形：两处折叠后的徽标片段必须逐字符相同，不能各自长得不一样。
        val detail = collapse(cardRegion("AiTeamDetailScreen.kt", "private fun AiTeamMemberCard"))
        val editor = collapse(cardRegion("AiTeamEditorScreen.kt", "private fun TeamMemberCard"))
        assertEquals(1, detail occurrencesOf badge)
        assertEquals(1, editor occurrencesOf badge)
        assertEquals(detail.substringBetween("Icon(WandIcons.leader", "size(13.dp),)"), editor.substringBetween("Icon(WandIcons.leader", "size(13.dp),)"))
        // 旧稿的裸字符 `★` 不得在任何一处回归（§2.3 现状 2）。
        for (fileName in listOf("AiTeamDetailScreen.kt", "AiTeamEditorScreen.kt")) {
            assertFalse("$fileName 不得再用裸字符 ★ 表负责人", readSource(fileName).contains("★"))
        }
    }

    @Test
    fun editorKeepsTheSetLeaderControlAndTheUntouchedTriggerRow() {
        val region = cardRegion("AiTeamEditorScreen.kt", "private fun TeamMemberCard")
        // 展开编辑区是角色唯一的「可操作」入口，F1 只换标题行的表达，不得顺手拆掉它。
        assertTrue(region.contains("\"设为负责人\""))
        assertTrue(region.contains("\"负责人（团队里最多一位）\""))
        // 卡/chevron 触发点与既有展开倒放不动：整行点击、同一实例旋转、原位面板。
        assertTrue(region.contains(".clickable(enabled = enabled, onClick = onToggle)"))
        assertTrue(region.contains("WandIcons.expand"))
        assertTrue(region.contains(".size(18.dp)"))
        assertTrue(region.contains(".rotate(arrowAngle)"))
        assertTrue(region.contains("WandInlinePanel(visible = expanded, growFrom = Alignment.Top)"))
    }

    @Test
    fun titlesStillRenderTheRealMemberNameAndTheFallbackLabel() {
        // 不能靠改用户名字掩盖重复：详情直接渲染 member.name，编辑器沿用既有显示名回退。
        val detail = cardRegion("AiTeamDetailScreen.kt", "private fun AiTeamMemberCard")
        assertTrue("详情标题仍取原始成员名", detail.contains("Text(\n                member.name,"))
        val editor = cardRegion("AiTeamEditorScreen.kt", "private fun TeamMemberCard")
        assertTrue("编辑器标题仍走 teamMemberDisplayName 回退", editor.contains("teamMemberDisplayName(member, index)"))
        assertTrue(editor.contains("Text(\n                        label,"))
    }

    @Test
    fun dutyAndCandidateLinesAreNotCaughtUpInTheFix() {
        val detail = cardRegion("AiTeamDetailScreen.kt", "private fun AiTeamMemberCard")
        assertTrue(detail.contains("member.duty.isNotBlank()"))
        assertTrue(detail.contains("aiTeamCandidateRoleLabel(index)"))
        assertTrue(detail.contains("aiTeamAgentLabel(agent, models)"))
        val editor = cardRegion("AiTeamEditorScreen.kt", "private fun TeamMemberCard")
        assertTrue(editor.contains("member.duty.ifBlank { \"未填职责\" }"))
        assertTrue(editor.contains("TeamCandidateRow("))
    }

    // MARK: - 断言与取段

    private fun assertLeaderBadge(region: String, where: String) {
        val collapsed = collapse(region)
        assertTrue(
            "$where 标题行的负责人标记必须是 13dp 矢量星 + 角色语义（§2.3 F1），实际：$collapsed",
            collapsed.contains(badge),
        )
        val code = codeOnly(region)
        assertFalse("$where 不得再把角色写成一份文字（会与成员名重复）", code.contains("\"负责人\""))
        assertTrue("$where 角色标记仍只对负责人渲染", collapsed.contains("if(member.isLeader){Icon("))
    }

    /** 成员卡整段：从函数头到下一个顶层声明。 */
    private fun cardRegion(fileName: String, marker: String): String {
        val source = readSource(fileName)
        val start = source.indexOf(marker)
        assertTrue("在 $fileName 里找不到 $marker（函数被改名或删除时要先回来核对）", start >= 0)
        val rest = source.substring(start)
        val end = listOf("\n@Composable", "\n/**")
            .map { rest.indexOf(it) }
            .filter { it > 0 }
            .minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    /** 去掉注释行与缩进差异，只比结构。 */
    private fun collapse(region: String): String =
        region.replace(Regex("//[^\\n]*"), "").replace(Regex("\\s+"), "")

    private fun codeOnly(region: String): String =
        region.lines()
            .filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") }
            .joinToString("\n")

    private infix fun String.occurrencesOf(needle: String): Int =
        generateSequence(this) { if (it.contains(needle)) it.substringAfter(needle) else null }.count() - 1

    private fun String.substringBetween(first: String, last: String): String {
        val start = indexOf(first)
        assertTrue("片段缺少 $first", start >= 0)
        val end = indexOf(last, startIndex = start)
        assertTrue("片段缺少 $last", end >= 0)
        return substring(start, end + last.length)
    }

    private fun readSource(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/wand/app/ui/screens", fileName),
            File("app/src/main/java/com/wand/app/ui/screens", fileName),
        ).filter { it.isFile }
        // 恰好命中一个候选：定位不到就 fail，不允许静默跳过源码守卫。
        assertEquals("源码守卫定位 $fileName 必须恰好命中一个候选", 1, candidates.size)
        return candidates[0].readText()
    }
}
