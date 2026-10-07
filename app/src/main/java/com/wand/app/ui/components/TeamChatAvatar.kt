package com.wand.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.decodeDataUriImage
import com.wand.app.ui.screens.ChatAvatarSpec
import com.wand.app.ui.screens.GeneratedAvatar
import com.wand.app.ui.screens.catCoatGrid
import com.wand.app.ui.theme.WandColors

/**
 * 群聊发言头像（设计 §5）：32dp 圆角方块 —— 成员没自定义过头像时是按身份生成的渐变底 + 名字首字、
 * 挑过 `cat:<n>` 毛色或有上传图就各按各的画，定位不到身份的发言（「我」）用系统 APP logo。
 *
 * 与团队页/工位/侧栏的 `TeamAvatar` 分开：那边的 `wand-team-avatar*` 语义与状态环（工作中 /
 * 完成 / 失败 / 皇冠）是团队页的一部分，群聊消息头像按设计 D3 **不带**状态环与角标，
 * 所以这里另画一套，避免动到团队页的使用点。
 */
val TeamMessageAvatarSize: Dp = 32.dp

@Composable
fun TeamMessageAvatar(
    spec: ChatAvatarSpec,
    modifier: Modifier = Modifier,
    size: Dp = TeamMessageAvatarSize,
) {
    // 30% 圆角与 Web 的 `border-radius: 30%` 同比例（32dp → 9.6dp，取材料圆角一档）。
    val shape = RoundedCornerShape(size * 0.3f)
    // 自带底色的两种（APP logo、生成脸）不再套第二层；猫与上传图衬团队页同款底色。
    val carriesBackground = spec is ChatAvatarSpec.Brand || spec is ChatAvatarSpec.Generated
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (carriesBackground) Color.Transparent else WandColors.surfaceSoft)
            // 装饰性头像：不读数、不单独聚焦（读屏只报署名行里的名字）。
            .clearAndSetSemantics {},
    ) {
        when (spec) {
            is ChatAvatarSpec.Upload -> UploadAvatarFace(spec.src)
            is ChatAvatarSpec.Cat -> PixelCatFace(spec.coat, size)
            is ChatAvatarSpec.Generated -> GeneratedAvatarFace(spec.face, size)
            ChatAvatarSpec.Brand -> WandBrandMark(size = size.value.toInt())
        }
    }
}

/** 生成脸：135° 渐变底 + 名字首字，字号与 Web `GeneratedAvatarGlyph` 的 `size * 0.42` 同口径。 */
@Composable
private fun GeneratedAvatarFace(face: GeneratedAvatar, size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(face.from), Color(face.to)))),
    ) {
        Text(
            text = face.glyph,
            color = Color(face.text),
            fontSize = (size.value * 0.42f).coerceAtLeast(10f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 像素猫：与 Web 逐格同一张 10×10 表，占位 74%（不留白边，也不裁掉耳朵）。 */
@Composable
private fun PixelCatFace(coat: Int, size: Dp) {
    // 毛色和网格都是纯数据，换了才重算；同一条消息反复重组的概率很高。
    val rows = remember(coat) { catCoatGrid(coat) }
    Canvas(modifier = Modifier.size(size * 0.74f)) {
        val cell = this.size.minDimension / 10f
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, argb ->
                if (argb != null) {
                    drawRect(
                        color = Color(argb),
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** 上传头像：`data:image/…;base64,…` 解不出来就留底色（不渲染空图、不崩）。 */
@Composable
private fun UploadAvatarFace(src: String) {
    val bitmap = remember(src) { decodeDataUriImage(src) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
