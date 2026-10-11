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
import com.wand.app.ui.screens.explicitCatAvatarIndex
import com.wand.app.ui.screens.memberCoatIndex
import com.wand.app.ui.theme.WandColors
import java.util.Locale

/** 不认识的 CLI 不冒充 Claude；员工列表传首选候选，会话传实际 provider。 */
fun employeeAvatarProvider(provider: String?): String? =
    WandProvider.fromId(provider?.trim()?.lowercase(Locale.ROOT))?.id

fun employeeCliBadgeSize(avatarSize: Dp): Dp = (avatarSize * 0.5f).coerceIn(13.dp, 20.dp)

/**
 * 端上唯一的「身份 → 脸」结果。所有头像位都只经 [EmployeeAvatar] 拿脸，
 * 不再各自挑字段、各写一套兜底（同一个人换页面就换脸，就是这么来的）。
 */
sealed interface EmployeeAvatarFace {
    /** 毛绒造型的离线资产名：柴犬、猫毛色、六段捏脸或按身份生成的默认造型。 */
    data class Plush(val asset: String) : EmployeeAvatarFace

    /** 上传的小图，存的是 `data:image/…` 原值。 */
    data class Upload(val src: String) : EmployeeAvatarFace

    /** 旧的 `cat:<n>` 像素猫（捏脸器还在提供这一档，取值不改写）。 */
    data class PixelCat(val coat: Int) : EmployeeAvatarFace

    /** 定位不到身份的发言（「我」）回落默认 APP logo。 */
    data object Brand : EmployeeAvatarFace
}

/**
 * 一份取值只解一次，顺序固定：锁定系统身份的猫 > 上传图 > 定位不到身份才给 APP logo >
 * 显式旧猫 `cat:<n>` > 毛绒 token > 按身份生成的默认毛绒。
 * 各档与 Web `fixedEmployeeAvatar` / `resolveEmployeeAvatar` / `chatAvatarSpec` 同口径；
 * 拿不到可认取值的员工按身份生成，所以通讯录、会话列表、工位条和群聊消息是同一张脸。
 */
fun employeeAvatarFace(id: String?, name: String?, avatar: String?): EmployeeAvatarFace {
    PlushAvatarIdentity.fixedAsset(id)?.let { return EmployeeAvatarFace.Plush(it) }
    val value = avatar.orEmpty()
    if (value.startsWith("data:image/")) return EmployeeAvatarFace.Upload(value)
    if (id.isNullOrBlank() && name.isNullOrBlank()) return EmployeeAvatarFace.Brand
    if (explicitCatAvatarIndex(value) != null) return EmployeeAvatarFace.PixelCat(memberCoatIndex(id, name, value))
    return EmployeeAvatarFace.Plush(PlushAvatarIdentity.tokenAsset(value) ?: PlushAvatarIdentity.defaultAsset(id, name))
}

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
    val cli = employeeAvatarProvider(provider)
    Box(modifier = modifier.size(size)) {
        when (val face = employeeAvatarFace(id, name, avatar)) {
            // 列表和消息用离线肖像，只有悬浮在输入框上的那张脸才起共享 3D 场景。
            is EmployeeAvatarFace.Plush -> if (animated) {
                SharedPlushScene(face.asset, size, fileActivity, thinking, speaking)
            } else PlushAvatar(face.asset, size, floating)
            else -> TeamMessageAvatar(face = face, size = size)
        }
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
