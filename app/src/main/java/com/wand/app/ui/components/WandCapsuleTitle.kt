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
) {
    // 胶囊高度：头像直径 + 四周等宽的留白（头像圆心与胶囊左端圆弧同心）。
    val capsuleHeight = avatarSize + capsuleInset * 2
    Row(
        modifier = modifier
            .height(capsuleHeight)
            .background(fill, RoundedCornerShape(percent = 50))
            .border(0.5.dp, stroke, RoundedCornerShape(percent = 50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 圆心落在胶囊左端圆弧的圆心上，圆弧与圆圈同形、留白等宽。
        Spacer(Modifier.width(capsuleHeight / 2 - avatarSize / 2))
        Box(
            modifier = Modifier.size(avatarSize),
            contentAlignment = Alignment.Center,
        ) {
            // 透明头像贴在胶囊上时需要一层落地阴影，否则角色像浮在胶囊上。
            Box(
                modifier = Modifier
                    .size(width = avatarSize * 0.66f, height = avatarSize * 0.16f)
                    .shadow(
                        elevation = 6.dp,
                        shape = CircleShape,
                        ambientColor = WandColors.textPrimary.copy(alpha = 0.18f),
                        spotColor = WandColors.textPrimary.copy(alpha = 0.12f),
                    )
                    .offset(y = avatarSize * 0.38f),
            )
            // 顶栏用 3D 场景（与 iOS 同一张脸、同一场景），场景未就绪时先显示静态立绘。
            EmployeeAvatar(id = avatarId, name = avatarName, avatar = avatar, size = avatarSize, animated = true,
                fileActivity = fileActivity, thinking = thinking && fileActivity == AvatarFileActivity.Idle)
        }
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 16.dp),
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
