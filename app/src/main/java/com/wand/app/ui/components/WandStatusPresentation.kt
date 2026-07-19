package com.wand.app.ui.components

/** 状态展示的纯数据模型；颜色在主题层解析，业务页面不再维护字符串映射。 */
enum class WandStatusTone {
    Success,
    Permission,
    Danger,
    Warning,
    Neutral,
}

data class WandStatusPresentation(
    val normalized: String,
    val label: String,
    val tone: WandStatusTone,
    val breathing: Boolean,
)

fun wandStatusPresentation(status: String?): WandStatusPresentation {
    val normalized = status.orEmpty().trim().lowercase().replace('_', '-')
    return when (normalized) {
        "running" -> WandStatusPresentation(normalized, "运行中", WandStatusTone.Success, true)
        "thinking" -> WandStatusPresentation(normalized, "思考中", WandStatusTone.Success, true)
        "waiting-input" -> WandStatusPresentation(normalized, "等待输入", WandStatusTone.Permission, true)
        "permission" -> WandStatusPresentation(normalized, "等待授权", WandStatusTone.Permission, true)
        "reconnecting" -> WandStatusPresentation(normalized, "重连中", WandStatusTone.Warning, true)
        "stopped" -> WandStatusPresentation(normalized, "已停止", WandStatusTone.Warning, false)
        "failed" -> WandStatusPresentation(normalized, "已失败", WandStatusTone.Danger, false)
        "idle" -> WandStatusPresentation(normalized, "空闲", WandStatusTone.Neutral, false)
        "exited" -> WandStatusPresentation(normalized, "已退出", WandStatusTone.Neutral, false)
        "archived" -> WandStatusPresentation(normalized, "已归档", WandStatusTone.Neutral, false)
        else -> WandStatusPresentation(
            normalized = normalized,
            label = status?.takeIf { it.isNotBlank() } ?: "未知状态",
            tone = WandStatusTone.Neutral,
            breathing = false,
        )
    }
}
