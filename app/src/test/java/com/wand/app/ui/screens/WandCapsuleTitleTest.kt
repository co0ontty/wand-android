package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏胶囊标题（与 iOS `TopCapsuleTitle` 同语义）：左侧圆圈头像略微探出胶囊，右侧是标题与副标题。
 * 任务顶栏与终端顶栏都走这一个实现，身份来源与 iOS 保持同一口径。
 */
class WandCapsuleTitleTest {
    private fun source(path: String): String = File("src/main/java/com/wand/app/$path").readText()

    @Test fun capsuleKeepsOneIdentityOwnerAndProtrudingAvatar() {
        val capsule = source("ui/components/WandCapsuleTitle.kt")
        assertTrue(capsule.contains("fun WandCapsuleTitle("))
        assertTrue("胶囊头像没有走统一头像组件", capsule.contains("EmployeeAvatar("))
        assertTrue("胶囊不是圆角胶囊形", capsule.contains("RoundedCornerShape(percent = 50)"))
        // 头像圆心与胶囊左端圆弧同心，四周留白等宽。
        assertTrue("头像没有按同心圆摆位", capsule.contains("capsuleHeight / 2 - avatarSize / 2"))
        assertTrue("胶囊高度没有跟着头像走", capsule.contains("avatarSize + capsuleInset * 2"))
        assertFalse("头像还探在胶囊外", capsule.contains("Spacer(Modifier.width(7.dp))"))
    }

    @Test fun capsuleCarriesFileActivityAndThinkingEffects() {
        val capsule = source("ui/components/WandCapsuleTitle.kt")
        val bar = source("ui/screens/ChatTopIdentityBar.kt")
        val screen = source("ui/screens/ChatScreen.kt")
        assertTrue("胶囊没有接读/写活动", capsule.contains("fileActivity = fileActivity"))
        assertTrue("胶囊没有接思考状态", capsule.contains("thinking && fileActivity == AvatarFileActivity.Idle"))
        assertTrue("会话顶栏没有接活动", bar.contains("fileActivity = fileActivity"))
        assertTrue("会话页没有把读/写活动传给顶栏", screen.contains("fileActivity = avatarActivity"))
        assertTrue("会话页没有把思考状态传给顶栏", screen.contains("thinking = companionThinking("))
    }

    @Test fun taskAndTerminalTopBarsUseTheCapsule() {
        assertTrue(source("ui/screens/WorkspaceTaskScreen.kt").contains("WandCapsuleTitle("))
        assertTrue(source("ui/screens/PtyTerminalScreen.kt").contains("WandCapsuleTitle("))
        assertTrue(source("ui/components/WandChrome.kt").contains("heading: (@Composable () -> Unit)? = null"))
    }
}
