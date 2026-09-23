package com.wand.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wand.app.data.WandHttp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import okhttp3.OkHttpClient

/**
 * 聊天会话的服务端 base URL（用于拼 /api/file-raw 取图 URL）。
 * 在 ChatScreen 顶层用 ChatStore.api.baseUrl 提供，下游聊天块组件直接读，
 * 免得把 baseUrl 一路当参数穿过 TurnView → SegmentBlocks → ToolCard。
 * 空串表示未提供（此时内联图片不渲染，安全降级）。
 */
val LocalServerBaseUrl = compositionLocalOf { "" }

/**
 * 聊天内联图片支持：对齐网页端 renderUserAttachmentBlock / Read 读图缩略图。
 * 图片必须经对应 endpoint 的 WandHttp client 加载（自签名证书放行 + 会话 cookie），
 * 否则 /api/file-raw 的 requireAuth 与 HTTPS 校验都会失败。
 */
object WandImage {

    private data class LoaderEntry(
        val client: OkHttpClient,
        val loader: ImageLoader,
    )

    // 对齐网页 IMAGE_PATH_RE：去掉 ?query/#hash 后判后缀。
    private val IMAGE_PATH_RE: Pattern =
        Pattern.compile("\\.(png|jpe?g|gif|webp|svg|avif|bmp|ico|heic|heif)$", Pattern.CASE_INSENSITIVE)

    /** 路径是否指向图片（大小写不敏感，先剥离 query/hash）。 */
    fun isImagePath(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val clean = value.trim().split('?', '#').firstOrNull().orEmpty()
        return IMAGE_PATH_RE.matcher(clean).find()
    }

    /**
     * 构造 /api/file-raw 取图 URL。用 Uri.Builder 追加 query，path 自动正确编码
     * （空格 %20 而非 +，对齐网页 encodeURIComponent）。
     */
    fun fileRawUrl(baseUrl: String, path: String): String =
        Uri.parse("${WandHttp.normalizeBaseUrl(baseUrl)}/api/file-raw")
            .buildUpon()
            .appendQueryParameter("path", path)
            .build()
            .toString()

    /** 每个 endpoint 一个 ImageLoader；resetClient 后会按新 client 自动替换。 */
    private val loaders = ConcurrentHashMap<String, LoaderEntry>()

    fun imageLoader(context: Context, baseUrl: String): ImageLoader {
        val endpoint = WandHttp.normalizeBaseUrl(baseUrl)
        val endpointClient = WandHttp.clientFor(endpoint)
        return loaders.compute(endpoint) { _, current ->
            if (current?.client === endpointClient) {
                current
            } else {
                LoaderEntry(
                    client = endpointClient,
                    loader = ImageLoader.Builder(context.applicationContext)
                        .okHttpClient { endpointClient }
                        .crossfade(true)
                        .build(),
                )
            }
        }!!.loader
    }
}

/**
 * 取图状态：图像 loader 与请求模型都按 (baseUrl, path) 记忆。
 * 内联缩略图与全屏预览共用，避免两处各拼一次请求。
 */
@Composable
private fun rememberRemoteImage(path: String, baseUrl: String): Pair<ImageLoader, ImageRequest> {
    val context = LocalContext.current
    val loader = remember(baseUrl) { WandImage.imageLoader(context, baseUrl) }
    val model = remember(baseUrl, path) {
        ImageRequest.Builder(context).data(WandImage.fileRawUrl(baseUrl, path)).build()
    }
    return loader to model
}

/**
 * 内联图片缩略图：经对应 endpoint 的 WandHttp client 加载，圆角 + Fit，点击放大到全屏预览。
 * 加载失败时整块隐藏（对齐网页 onerror → display:none），绝不崩溃或留占位。
 */
@Composable
fun WandAsyncImage(
    path: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    maxWidth: Int = 240,
    maxHeight: Int = 200,
) {
    val (loader, model) = rememberRemoteImage(path, baseUrl)
    var failed by remember(baseUrl, path) { mutableStateOf(false) }
    var showViewer by remember { mutableStateOf(false) }

    if (failed) return

    AsyncImage(
        model = model,
        imageLoader = loader,
        contentDescription = path.substringAfterLast('/'),
        contentScale = ContentScale.Fit,
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) failed = true
        },
        modifier = modifier
            .widthIn(max = maxWidth.dp)
            .heightIn(max = maxHeight.dp)
            .clip(WandShapes.sm)
            .border(1.dp, WandColors.border, WandShapes.sm)
            .clickable { showViewer = true },
    )

    if (showViewer) {
        FullscreenImageViewer(
            path = path,
            baseUrl = baseUrl,
            onDismiss = { showViewer = false },
        )
    }
}

/**
 * 内联工具结果图片：服务端下发的站内取图 URL（/api/sessions/…/tool-images/…）
 * 或 data URI。只读缩略图，与网页 `inline-tool-image` 对齐（加载失败整块隐藏）。
 */
@Composable
fun WandAsyncToolImage(
    source: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    maxWidth: Int = 240,
    maxHeight: Int = 200,
) {
    var failed by remember(source) { mutableStateOf(false) }
    var decoded by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var showViewer by remember(source) { mutableStateOf(false) }
    val shapeModifier = modifier
        .widthIn(max = maxWidth.dp)
        .heightIn(max = maxHeight.dp)
        .clip(WandShapes.sm)
        .border(1.dp, WandColors.border, WandShapes.sm)

    if (source.startsWith("data:")) {
        LaunchedEffect(source) {
            decoded = withContext(Dispatchers.IO) { decodeDataUriImage(source) }
            if (decoded == null) failed = true
        }
        if (failed) return
        val bitmap = decoded ?: return
        Image(
            bitmap = bitmap,
            contentDescription = "工具返回图片",
            contentScale = ContentScale.Fit,
            modifier = shapeModifier.clickable { showViewer = true },
        )
        if (showViewer) FullscreenImageViewer(source, baseUrl, { showViewer = false }, bitmap = bitmap)
        return
    }

    val context = LocalContext.current
    val endpoint = WandHttp.normalizeBaseUrl(baseUrl)
    val loader = remember(endpoint) { WandImage.imageLoader(context, endpoint) }
    val absolute = if (source.startsWith("http://") || source.startsWith("https://")) {
        source
    } else {
        endpoint + if (source.startsWith("/")) source else "/$source"
    }
    val model = remember(absolute) { ImageRequest.Builder(context).data(absolute).build() }
    if (failed) return
    AsyncImage(
        model = model,
        imageLoader = loader,
        contentDescription = "工具返回图片",
        contentScale = ContentScale.Fit,
        onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
        modifier = shapeModifier.clickable { showViewer = true },
    )
    if (showViewer) FullscreenImageViewer(absolute, baseUrl, { showViewer = false }, directUrl = true)
}

/** 解析 data:image/…;base64,…. 失败返回 null（不崩溃）。 */
private fun decodeDataUriImage(source: String): ImageBitmap? {
    val comma = source.indexOf(',')
    if (comma < 0) return null
    return try {
        val bytes = Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/**
 * 全屏图片预览：黑底覆盖层，支持双指缩放 / 拖动 / 双击放大，右上角关闭按钮 + 返回键关闭
 * （对齐 iOS WandImageViewer：缩放 1–5×，缩回 1× 时归位，未放大时单击空白也可关闭）。
 */
@Composable
fun FullscreenImageViewer(
    path: String,
    baseUrl: String,
    onDismiss: () -> Unit,
    directUrl: Boolean = false,
    bitmap: ImageBitmap? = null,
) {
    val context = LocalContext.current
    val loader = remember(baseUrl) { WandImage.imageLoader(context, baseUrl) }
    val model = remember(baseUrl, path, directUrl) {
        ImageRequest.Builder(context)
            .data(if (directUrl) path else WandImage.fileRawUrl(baseUrl, path))
            .build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                // 单击空白关闭（仅未放大时，避免与拖动/捏合冲突）+ 双击在 1× / 2.5× 间切换。
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale <= 1.01f) onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                // 双指捏合缩放 + 单指拖动平移；缩回 1× 时归位（对齐 iOS）。
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                },
        ) {
            val imageModifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "工具返回图片",
                    contentScale = ContentScale.Fit, modifier = imageModifier)
            } else {
                AsyncImage(model = model, imageLoader = loader,
                    contentDescription = path.substringAfterLast('/'),
                    contentScale = ContentScale.Fit, modifier = imageModifier)
            }
            // 关闭按钮：右上角安全区内的半透明圆钮（对齐 iOS xmark）。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable { onDismiss() },
            ) {
                Icon(
                    WandIcons.close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 非图片附件的小文件块：文件图标 + 文件名（对齐网页 user-attachment-file）。 */
@Composable
fun WandFileChip(path: String, modifier: Modifier = Modifier) {
    val name = remember(path) { path.substringAfterLast('/').ifEmpty { path } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(WandShapes.sm)
            .background(WandColors.surfaceSoft)
            .border(1.dp, WandColors.border, WandShapes.sm)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Icon(
            WandIcons.attach,
            contentDescription = null,
            tint = WandColors.textSecondary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            name,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * 用户消息的附件前缀解析结果（对齐网页 ATTACHMENT_PREFIX_RE）。
 * paths 为附件路径列表（图片渲缩略图、其余渲文件块），body 为剥离前缀后的正文。
 */
data class ParsedUserText(val paths: List<String>, val body: String)

// ^\s*[附件已上传，请查看以下文件:\n<paths>]\n+ —— 与网页正则等价。
private val ATTACHMENT_PREFIX_RE: Regex =
    Regex("^\\s*\\[附件已上传，请查看以下文件:\\n([\\s\\S]*?)\\]\\n+")

/** 剥离附件前缀；无前缀时 paths 为空、body 即原文（行为与无附件时一致）。 */
fun parseUserAttachmentText(text: String): ParsedUserText {
    val match = ATTACHMENT_PREFIX_RE.find(text) ?: return ParsedUserText(emptyList(), text)
    val paths = match.groupValues[1]
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val body = text.substring(match.range.last + 1)
    return ParsedUserText(paths, body)
}
