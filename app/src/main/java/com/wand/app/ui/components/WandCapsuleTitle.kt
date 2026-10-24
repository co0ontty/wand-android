package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.components.AvatarFileActivity

/**
 * 顶栏胶囊标题：左侧是圆圈里的头像（允许略微探出胶囊边缘），右侧是当前标题与副标题。
 * 与 iOS `TopCapsuleTitle` 同语义：同一身份在两端拿到同一张脸。
 */
@Composable
fun WandCapsuleTitle(
    title: String,
    subtitle: String? = null,
    avatarId: String?,
    avatarName: String? = null,
    avatar: String? = null,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 30.dp,
    /** 头像圆与胶囊边框之间四周等宽的留白。 */
    capsuleInset: Dp = 6.dp,
    fill: Color = WandColors.bgElevated,
    stroke: Color = WandColors.border,
    titleColor: Color = WandColors.textPrimary,
    subtitleColor: Color = WandColors.textMuted,
    titleWidth: Dp? = null,
    /** 头像活动效果：思考落定、查看文件、编写文件（与 Web `fileActivity` / `thinking` 同口径）。 */
    fileActivity: AvatarFileActivity = AvatarFileActivity.Idle,
    thinking: Boolean = false,
    backdrop: GlassBackdrop? = null,
) {
    // 胶囊高度：头像直径 + 四周等宽的留白（头像圆心与胶囊左端圆弧同心）。
    val capsuleHeight = avatarSize + capsuleInset * 2
    val renderSize = maxOf(48.dp, avatarSize)
    val overhang = (renderSize - avatarSize) / 2
    Box(modifier = modifier.height(capsuleHeight)) {
        // Only the background is clipped by the native material. Never mask the portrait.
        Box(Modifier.matchParentSize().glassSurface(backdrop, RoundedCornerShape(percent = 50),
            WandGlass.clear.copy(tint = fill, rimShade = stroke), drawRim = true))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(capsuleInset))
            Box(Modifier.size(avatarSize), contentAlignment = Alignment.Center) {
                EmployeeAvatar(id = avatarId, name = avatarName, avatar = avatar,
                    size = renderSize, modifier = Modifier.requiredSize(renderSize), animated = true,
                    floating = true, fileActivity = fileActivity, thinking = thinking && fileActivity == AvatarFileActivity.Idle)
            }
        Column(
            modifier = Modifier
                .padding(start = 8.dp + overhang, end = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleWidth != null) Modifier.width(titleWidth) else Modifier,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = if (titleWidth != null) Modifier.width(titleWidth) else Modifier.fillMaxWidth(),
                )
            }
        }
        }
    }
}
