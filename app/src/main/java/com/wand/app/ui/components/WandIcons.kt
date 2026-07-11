package com.wand.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 统一图标层（重设计规范 v1 第 2.2 节）：替换 UI 中所有 emoji 图标。
 * 依赖 material-icons-extended，全部用 Outlined 风格。
 * 屏幕代码统一从 WandIcons.* 取场景图标、用 toolIcon(name) 取工具图标。
 */
object WandIcons {
    // —— 工具图标 ——
    /** bash / command / shell。 */
    val terminal: ImageVector = Icons.Outlined.Terminal

    /** edit / write / multiedit。 */
    val edit: ImageVector = Icons.Outlined.EditNote

    /** read。 */
    val read: ImageVector = Icons.Outlined.Description

    /** grep / glob / search。 */
    val search: ImageVector = Icons.Outlined.Search

    /** web / fetch / websearch；也用作「打开网页版」。 */
    val web: ImageVector = Icons.Outlined.Language

    /** 会话设置（模型 / 思考深度），对称 iOS slider.horizontal.3。 */
    val tune: ImageVector = Icons.Outlined.Tune

    /** 发送（对称 iOS arrow.up 圆钮）。 */
    val arrowUp: ImageVector = Icons.Outlined.ArrowUpward

    /** 附件（对称 iOS paperclip）。 */
    val attach: ImageVector = Icons.Outlined.AttachFile

    /** Git 变更统计（对称 iOS arrow.triangle.branch）。 */
    val commit: ImageVector = Icons.Outlined.Commit

    /** 探索卡列表的三态（对称 iOS checkmark/xmark.circle.fill 与 circle.dotted）。 */
    val statusDone: ImageVector = Icons.Outlined.CheckCircle
    val statusFail: ImageVector = Icons.Outlined.Cancel
    val statusPending: ImageVector = Icons.Outlined.RadioButtonUnchecked

    /** task / agent / subagent。 */
    val agent: ImageVector = Icons.Outlined.Groups

    /** todo 列表。 */
    val todo: ImageVector = Icons.Outlined.Checklist

    /** 其他工具兜底。 */
    val genericTool: ImageVector = Icons.Outlined.Build

    // —— 场景图标 ——
    /** 思考块。 */
    val thinking: ImageVector = Icons.Outlined.Psychology

    /** 权限审批。 */
    val permission: ImageVector = Icons.Outlined.Lock

    /** 工具结果。 */
    val toolResult: ImageVector = Icons.AutoMirrored.Outlined.Notes

    /** 单轮 token / 费用用量。 */
    val usage: ImageVector = Icons.Outlined.DataUsage

    /** 错误。 */
    val error: ImageVector = Icons.Outlined.ErrorOutline

    /** 目录 / 浏览。 */
    val folder: ImageVector = Icons.Outlined.FolderOpen

    /** 最近路径。 */
    val history: ImageVector = Icons.Outlined.History

    /** 收起/展开箭头（配合 rotate 动画）。 */
    val expand: ImageVector = Icons.Outlined.ExpandMore

    /** 发送。 */
    val send: ImageVector = Icons.AutoMirrored.Outlined.Send

    /** 按住说话麦克风。 */
    val mic: ImageVector = Icons.Outlined.Mic

    /** 语音模式下切回键盘输入。 */
    val keyboard: ImageVector = Icons.Outlined.Keyboard

    /** 停止。 */
    val stop: ImageVector = Icons.Outlined.Stop

    /** 断线（断线提示条用）。 */
    val wifiOff: ImageVector = Icons.Outlined.WifiOff

    /** 运行中旋转指示。 */
    val refresh: ImageVector = Icons.Outlined.Refresh

    /** 设置。 */
    val settings: ImageVector = Icons.Outlined.Settings

    /** 外观与主题。 */
    val appearance: ImageVector = Icons.Outlined.Palette

    /** 通知、音量与触感反馈。 */
    val notification: ImageVector = Icons.Outlined.NotificationsNone
    val volume: ImageVector = Icons.AutoMirrored.Outlined.VolumeUp
    val haptic: ImageVector = Icons.Outlined.Vibration

    /** 设置页中的客户端能力。 */
    val keepAlive: ImageVector = Icons.Outlined.CloudSync
    val beta: ImageVector = Icons.Outlined.Science
    val server: ImageVector = Icons.Outlined.Storage
    val connectionCode: ImageVector = Icons.Outlined.Key

    /** 切换服务器。 */
    val swapServer: ImageVector = Icons.Outlined.SwapHoriz

    /** 检查更新。 */
    val update: ImageVector = Icons.Outlined.SystemUpdate

    /** 断开连接。 */
    val logout: ImageVector = Icons.AutoMirrored.Outlined.Logout

    /** 行尾右箭头。 */
    val chevronRight: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight

    /** 删除。 */
    val delete: ImageVector = Icons.Outlined.Delete

    /** 选中勾。 */
    val check: ImageVector = Icons.Outlined.Check

    /** AskUserQuestion 提问卡。 */
    val question: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline

    /** 关闭。 */
    val close: ImageVector = Icons.Outlined.Close

    /** 新建。 */
    val add: ImageVector = Icons.Outlined.Add

    /** 更多菜单。 */
    val more: ImageVector = Icons.Outlined.MoreVert

    /** 空态大图标 / 会话列表空态。 */
    val sparkle: ImageVector = Icons.Outlined.AutoAwesome

    /** 聊天会话（runner 类型徽章）。 */
    val chat: ImageVector = Icons.Outlined.ChatBubbleOutline

    /** 时钟（历史会话相对时间徽章，对称 iOS clock）。 */
    val clock: ImageVector = Icons.Outlined.Schedule
}

/**
 * 工具名 → Material 图标映射（大小写不敏感，按子串匹配）。
 * 覆盖 Claude 真实工具名：Bash/BashOutput/KillShell、Edit/Write/MultiEdit/NotebookEdit、
 * Read、Grep/Glob、WebFetch/WebSearch、Task、TodoWrite、mcp__* 等；未知工具落到 Build。
 * 注意匹配顺序：todo 在 edit/write 之前（TodoWrite），web 在 search 之前（WebSearch）。
 */
fun toolIcon(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        lower.contains("todo") || lower.contains("checklist") -> WandIcons.todo
        lower.contains("web") || lower.contains("fetch") || lower.contains("http") -> WandIcons.web
        lower.contains("grep") || lower.contains("glob") ||
            lower.contains("search") || lower.contains("find") -> WandIcons.search
        lower.contains("bash") || lower.contains("command") ||
            lower.contains("terminal") || lower.contains("shell") -> WandIcons.terminal
        lower.contains("edit") || lower.contains("write") -> WandIcons.edit
        lower.contains("read") || lower.contains("notebook") -> WandIcons.read
        lower.contains("task") || lower.contains("agent") || lower.contains("subagent") -> WandIcons.agent
        lower.contains("think") -> WandIcons.thinking
        else -> WandIcons.genericTool
    }
}
