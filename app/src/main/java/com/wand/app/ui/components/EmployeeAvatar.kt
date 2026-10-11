package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.WandProvider
import com.wand.app.ui.screens.chatAvatarSpec
import com.wand.app.ui.theme.WandColors
import java.util.Locale

/** 不认识的 CLI 不冒充 Claude；员工列表传首选候选，会话传实际 provider。 */
fun employeeAvatarProvider(provider: String?): String? =
    WandProvider.fromId(provider?.trim()?.lowercase(Locale.ROOT))?.id

fun employeeCliBadgeSize(avatarSize: Dp): Dp = (avatarSize * 0.5f).coerceIn(13.dp, 20.dp)

@Composable
fun EmployeeAvatar(
    id: String?,
    name: String?,
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    provider: String? = null,
    floating: Boolean = false,
    animated: Boolean = false,
    fileActivity: AvatarFileActivity = AvatarFileActivity.Idle,
    thinking: Boolean = false,
    speaking: Boolean = false,
) {
    // Web v1 毛绒身份优先；上传图和显式旧猫继续使用原有渲染器。
    val spec = chatAvatarSpec(id, name, avatar)
    val cli = employeeAvatarProvider(provider)
    val plush = PlushAvatarIdentity.asset(id, name, avatar)
    Box(modifier = modifier.size(size)) {
        if (plush != null && animated) SharedPlushScene(plush, size, fileActivity, thinking, speaking)
        else if (plush != null) PlushAvatar(plush, size, floating)
        else TeamMessageAvatar(spec = spec, size = size)
        if (cli != null) {
            val badgeSize = employeeCliBadgeSize(size)
            val shape = RoundedCornerShape(percent = 35)
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                    .size(badgeSize)
                    .background(WandColors.bgElevated, shape)
                    .border(1.5.dp, WandColors.bgPrimary, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = BrandLogos.painterForProvider(cli),
                    contentDescription = "$cli CLI",
                    tint = BrandLogos.tintForProvider(cli, WandColors.textPrimary),
                    modifier = Modifier.size((badgeSize - 3.dp) * 0.75f),
                )
            }
        }
    }
}
