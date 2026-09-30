package com.wand.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.screens.ChatAvatarSpec
import com.wand.app.ui.screens.memberCoatIndex

@Composable
fun EmployeeAvatar(
    id: String?,
    name: String?,
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val spec = if (avatar?.startsWith("data:image/") == true) {
        ChatAvatarSpec.Upload(avatar)
    } else {
        ChatAvatarSpec.Cat(memberCoatIndex(id, name, avatar))
    }
    TeamMessageAvatar(spec = spec, modifier = modifier, size = size)
}
