package com.wand.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.wand.app.ui.screens.LocalChatViewportHeightPx
import com.wand.app.ui.screens.markdownImagePages
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.network.HttpException
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wand.app.data.WandHttp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
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
private fun rememberRemoteImage(path: String, baseUrl: String, retry: Int = 0): Pair<ImageLoader, ImageRequest> {
    val context = LocalContext.current
    // WandImage already caches by the real endpoint client; don't freeze an old
    // loader across authentication/client resets with remember(baseUrl).
    val loader = WandImage.imageLoader(context, baseUrl)
    val model = remember(baseUrl, path, retry, loader) {
        ImageRequest.Builder(context).data(WandImage.fileRawUrl(baseUrl, path))
            .setParameter("wandImageRetry", retry).build()
    }
    return loader to model
}

internal fun wandImageFailureMessage(error: Throwable): String = when ((error as? HttpException)?.response?.code) {
    401, 403 -> "没有图片访问权限，请核对连接。"
    404 -> "图片未找到，请核对消息来源。"
    else -> "图片加载失败，请检查连接后重试。"
}

@Composable
private fun ImageLoadFeedback(message: String? = null, dark: Boolean = false, onRetry: () -> Unit = {}) {
    val color = if (dark) Color.White else WandColors.textSecondary
    if (message == null) {
        Column(Modifier.widthIn(min = 96.dp).heightIn(min = 64.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
            Text("加载图片…", fontSize = 12.sp, color = color)
        }
    } else {
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 64.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = color)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message, fontSize = 12.sp)
                Text("重试加载图片", fontSize = 11.sp)
            }
        }
    }
}

/**
 * 聊天内联图片的自适应上限：按图片自身比例在框内完整显示（Fit，不拉伸不裁切），
 * 横图吃满宽度、竖图吃满高度，小图不放大。与 iOS `WandImageLayout` 同一处真源，
 * 两端一起调，避免各写一套像素值。
 */
object WandImageLayout {
    const val thumbnailMaxWidthDp = 320
    const val thumbnailMaxHeightDp = 360
    const val figureWidthFraction = 0.8f
    const val figureHeightFraction = 0.4f

    fun figureMaxWidth(availableWidth: Float): Float =
        (availableWidth.coerceAtLeast(0f) * figureWidthFraction).coerceAtMost(thumbnailMaxWidthDp.toFloat())

    fun figureMaxHeight(viewportHeight: Float): Float =
        if (viewportHeight > 0f) (viewportHeight * figureHeightFraction).coerceAtMost(thumbnailMaxHeightDp.toFloat())
        else thumbnailMaxHeightDp.toFloat()
}

/** Constrain the native Fit image's proposal; never crop a screenshot to fake a smaller preview. */
@Composable
private fun Modifier.imageFigureBounds(): Modifier {
    val viewportPx = LocalChatViewportHeightPx.current
    val density = LocalDensity.current
    val viewportHeight = if (viewportPx > 0) viewportPx / density.density else LocalConfiguration.current.screenHeightDp.toFloat()
    val heightLimit = with(density) { WandImageLayout.figureMaxHeight(viewportHeight).dp.roundToPx() }
    return layout { measurable, constraints ->
        val widthLimit = with(density) {
            WandImageLayout.figureMaxWidth(constraints.maxWidth / density.density).dp.roundToPx()
        }
        val placeable = measurable.measure(constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = widthLimit.coerceAtMost(constraints.maxWidth),
            maxHeight = heightLimit.coerceAtMost(constraints.maxHeight),
        ))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

/** 全屏查看器的一页：站内路径（走 /api/file-raw）或直链（工具图片 URL / data URI）。 */
data class ViewerImagePage(val source: String, val directUrl: Boolean)

/** Only the currently loaded conversation; opening the viewer freezes this projection. */
val LocalImageGallery = compositionLocalOf<() -> List<ViewerImagePage>> { { emptyList() } }

internal fun conversationImagePages(turns: List<ConversationTurn>): List<ViewerImagePage> {
    val results = turns.flatMap { it.content }.filterIsInstance<ContentBlock.ToolResult>().associateBy { it.toolUseId }
    val pages = linkedSetOf<ViewerImagePage>()
    for (turn in turns) for (block in turn.content) {
        when (block) {
            is ContentBlock.Text -> if (turn.role == "user") {
                parseUserAttachmentText(block.text).paths.filter(WandImage::isImagePath)
                    .forEach { pages.add(ViewerImagePage(it, false)) }
            } else {
                pages.addAll(markdownImagePages(block.text))
            }
            is ContentBlock.ToolUse -> {
                val result = results[block.id]
                if (result != null && !result.isError) {
                    if (result.images.isNotEmpty()) result.images.forEach { pages.add(ViewerImagePage(it, true)) }
                    else if (block.name == "Read") {
                        val path = block.input.optString("file_path").ifBlank { block.input.optString("path") }
                        if (WandImage.isImagePath(path)) pages.add(ViewerImagePage(path, false))
                    }
                }
            }
            is ContentBlock.ToolResult -> if (!block.isError) block.images.forEach { pages.add(ViewerImagePage(it, true)) }
            else -> Unit
        }
    }
    return pages.toList()
}

internal data class ImageGallerySelection(val pages: List<ViewerImagePage>, val startIndex: Int)

internal fun imageGallerySelection(gallery: List<ViewerImagePage>, group: List<String>, current: String, direct: Boolean, localIndex: Int = 0): ImageGallerySelection {
    val page = ViewerImagePage(current, direct || current.startsWith("data:"))
    val index = gallery.indexOf(page)
    if (index >= 0) return ImageGallerySelection(gallery.toList(), index)
    val pages = viewerPages(group, current, direct)
    return ImageGallerySelection(pages,
        if (pages.getOrNull(localIndex) == page) localIndex else pages.indexOf(page).coerceAtLeast(0))
}

/** 全屏查看器的起始页下标钳制：分组为空或越界都退回第一张（对齐 iOS wandImageViewerStart）。 */
internal fun viewerStart(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

/** 缩略图分组 → 全屏查看页列表；未分组时只含当前一张。 */
internal fun viewerPages(group: List<String>, current: String, directUrl: Boolean): List<ViewerImagePage> {
    val sources = if (group.isNotEmpty()) group else listOf(current)
    return sources.map { ViewerImagePage(source = it, directUrl = directUrl || it.startsWith("data:")) }
}

/**
 * 内联图片缩略图：经对应 endpoint 的 WandHttp client 加载，圆角 + Fit，点击放大到全屏预览。
 * 复用 Coil 原生 loading/error 槽；失败明确保留原因和重试，不无解释地消失。
 * `group` 是同一条消息内的全部图片（含自己），全屏后左右滑动切换。
 */
@Composable
fun WandAsyncImage(
    path: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    maxWidth: Int = WandImageLayout.thumbnailMaxWidthDp,
    maxHeight: Int = WandImageLayout.thumbnailMaxHeightDp,
    group: List<String> = emptyList(),
    groupIndex: Int = 0,
    onFailure: (() -> Unit)? = null,
) {
    RemoteImageContent(
        path = path,
        baseUrl = baseUrl,
        group = group,
        groupIndex = groupIndex,
        onFailure = onFailure,
        modifier = modifier.widthIn(max = maxWidth.dp).heightIn(max = maxHeight.dp),
        shape = WandShapes.sm,
        bordered = true,
    )
}

/** Read 图片直接展示，按聊天阅读区占比收口；保留原始比例和点击查看原图。 */
@Composable
fun WandImageFigure(
    path: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    group: List<String> = emptyList(),
    groupIndex: Int = 0,
) {
    RemoteImageContent(
        path = path,
        baseUrl = baseUrl,
        group = group,
        groupIndex = groupIndex,
        onFailure = null,
        modifier = modifier.imageFigureBounds(),
        shape = WandShapes.md,
        bordered = true,
    )
}

@Composable
private fun RemoteImageContent(
    path: String,
    baseUrl: String,
    group: List<String>,
    groupIndex: Int,
    onFailure: (() -> Unit)?,
    modifier: Modifier,
    shape: Shape,
    bordered: Boolean,
) {
    var retry by remember(baseUrl, path) { mutableStateOf(0) }
    val (loader, model) = rememberRemoteImage(path, baseUrl, retry)
    var showViewer by remember(baseUrl, path) { mutableStateOf(false) }

    SubcomposeAsyncImage(
        model = model,
        imageLoader = loader,
        contentDescription = path.substringAfterLast('/'),
        contentScale = ContentScale.Fit,
        loading = { ImageLoadFeedback() },
        error = { state -> ImageLoadFeedback(wandImageFailureMessage(state.result.throwable), onRetry = { retry += 1 }) },
        onError = { onFailure?.invoke() },
        modifier = modifier
            .clip(shape)
            .then(if (bordered) Modifier.border(1.dp, WandColors.border, shape) else Modifier)
            .clickable { showViewer = true },
    )

    val gallery = LocalImageGallery.current
    if (showViewer) {
        val selection = remember { imageGallerySelection(gallery(), group, path, direct = false, localIndex = groupIndex) }
        FullscreenImageViewer(
            pages = selection.pages,
            baseUrl = baseUrl,
            startIndex = selection.startIndex,
            onDismiss = { showViewer = false },
        )
    }
}

/**
 * 工具图片直接展示，不套工具调用卡片；按阅读区占比限制长宽并用细边框标明预览，
 * 加载/失败/重试与全屏查看入口保持不变。
 */
@Composable
fun WandToolImageFigure(
    source: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    group: List<String> = emptyList(),
    groupIndex: Int = 0,
    directUrl: Boolean = true,
    contentDescription: String = "工具返回图片",
) {
    ToolImageContent(
        source = source,
        baseUrl = baseUrl,
        group = group,
        groupIndex = groupIndex,
        modifier = modifier.imageFigureBounds(),
        shape = WandShapes.md,
        bordered = true,
        directUrl = directUrl,
        contentDescription = contentDescription,
    )
}

@Composable
private fun ToolImageContent(
    source: String,
    baseUrl: String,
    group: List<String>,
    groupIndex: Int,
    modifier: Modifier,
    shape: Shape,
    bordered: Boolean,
    directUrl: Boolean = true,
    contentDescription: String = "工具返回图片",
) {
    var retry by remember(baseUrl, source) { mutableStateOf(0) }
    var failed by remember(baseUrl, source, retry) { mutableStateOf(false) }
    var decoded by remember(baseUrl, source, retry) { mutableStateOf<ImageBitmap?>(null) }
    var showViewer by remember(baseUrl, source) { mutableStateOf(false) }
    val gallery = LocalImageGallery.current
    val selection = remember(showViewer) {
        if (showViewer) imageGallerySelection(gallery(), group, source, direct = directUrl, localIndex = groupIndex)
        else ImageGallerySelection(emptyList(), 0)
    }
    val shapeModifier = modifier
        .clip(shape)
        .then(if (bordered) Modifier.border(1.dp, WandColors.border, shape) else Modifier)

    if (source.startsWith("data:")) {
        LaunchedEffect(baseUrl, source, retry) {
            decoded = withContext(Dispatchers.IO) { decodeDataUriImage(source) }
            if (decoded == null) failed = true
        }
        val bitmap = decoded
        if (bitmap == null) {
            Box(shapeModifier, contentAlignment = Alignment.Center) {
                ImageLoadFeedback(if (failed) "图片格式不支持或内容无效。" else null, onRetry = { retry += 1 })
            }
            return
        }
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = shapeModifier.clickable { showViewer = true },
        )
        if (showViewer) FullscreenImageViewer(selection.pages, baseUrl, { showViewer = false }, selection.startIndex)
        return
    }

    val context = LocalContext.current
    val endpoint = WandHttp.normalizeBaseUrl(baseUrl)
    val loader = WandImage.imageLoader(context, endpoint)
    val absolute = if (!directUrl) WandImage.fileRawUrl(endpoint, source)
        else absoluteToolImageSource(source, endpoint)
    val model = remember(absolute, retry, loader) {
        ImageRequest.Builder(context).data(absolute).setParameter("wandImageRetry", retry).build()
    }
    SubcomposeAsyncImage(
        model = model,
        imageLoader = loader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        loading = { ImageLoadFeedback() },
        error = { state -> ImageLoadFeedback(wandImageFailureMessage(state.result.throwable), onRetry = { retry += 1 }) },
        modifier = shapeModifier.clickable { showViewer = true },
    )
    if (showViewer) FullscreenImageViewer(selection.pages, baseUrl, { showViewer = false }, selection.startIndex)
}

/** 工具图片可能是绝对 URL 或 `/api/…` 站内路径，统一成可直接加载的绝对地址。 */
internal fun absoluteToolImageSource(source: String, baseUrl: String): String = when {
    source.startsWith("http://") || source.startsWith("https://") || source.startsWith("data:") -> source
    else -> WandHttp.normalizeBaseUrl(baseUrl) + if (source.startsWith("/")) source else "/$source"
}

/** 解析 data:image/…;base64,…. 失败返回 null（不崩溃）。 */
internal fun decodeDataUriImage(source: String): ImageBitmap? {
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
 * 全屏图片预览：黑底覆盖层，同一组图片左右滑动切换（带页码），双指缩放 1–5×、
 * 放大后单指平移，双击在 1× / 2.5× 间切换，右上角关闭按钮 + 单击空白 + 返回键关闭
 * （对齐 iOS `WandImageViewer` 的方向锁、缩放钳制与分组翻页）。
 */
@Composable
fun FullscreenImageViewer(
    pages: List<ViewerImagePage>,
    baseUrl: String,
    onDismiss: () -> Unit,
    startIndex: Int = 0,
) {
    if (pages.isEmpty()) return
    val context = LocalContext.current
    val loader = WandImage.imageLoader(context, baseUrl)
    val pagerState = rememberPagerState(
        initialPage = viewerStart(startIndex, pages.size),
        pageCount = { pages.size },
    )
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 换页回到 1×：上一页的缩放与平移不该带到下一页。
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

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
                },
        ) {
            HorizontalPager(
                state = pagerState,
                // No off-screen image downloads; the neighbor is composed only as a swipe needs it.
                beyondViewportPageCount = 0,
                // 放大后横向手势让给平移，不触发翻页。
                userScrollEnabled = scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                key(pageIndex) {
                    var retry by remember(pageIndex) { mutableStateOf(0) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .imagePinchPan(
                                isZoomed = { scale > 1.01f },
                                onGesture = { zoom, pan ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset = if (scale > 1f) offset + pan else Offset.Zero
                                },
                            )
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    ) {
                        ViewerPageImage(pages[pageIndex], baseUrl, loader, retry) { retry += 1 }
                    }
                }
            }
            if (pages.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${pages.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // 关闭按钮：右上角安全区内的半透明圆钮（对齐 iOS xmark）。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
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

/** 全屏页的一张图片：data URI 本地解码，其余按直链或 /api/file-raw 经 endpoint client 加载。 */
@Composable
private fun ViewerPageImage(
    page: ViewerImagePage,
    baseUrl: String,
    loader: ImageLoader,
    retry: Int,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val source = page.source
    if (source.startsWith("data:")) {
        var failed by remember(source, retry) { mutableStateOf(false) }
        var bitmap by remember(source, retry) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(source, retry) {
            bitmap = withContext(Dispatchers.IO) { decodeDataUriImage(source) }
            if (bitmap == null) failed = true
        }
        val decodedBitmap = bitmap
        if (decodedBitmap == null) {
            ImageLoadFeedback(if (failed) "图片格式不支持或内容无效。" else null, dark = true, onRetry = onRetry)
        } else {
            Image(
                bitmap = decodedBitmap,
                contentDescription = "工具返回图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    val model = remember(baseUrl, source, page.directUrl, retry, loader) {
        ImageRequest.Builder(context)
            .data(if (page.directUrl) absoluteToolImageSource(source, baseUrl) else WandImage.fileRawUrl(baseUrl, source))
            .setParameter("wandImageRetry", retry).build()
    }
    SubcomposeAsyncImage(
        model = model,
        imageLoader = loader,
        contentDescription = source.substringAfterLast('/'),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { ImageLoadFeedback(dark = true) },
        error = { state ->
            ImageLoadFeedback(wandImageFailureMessage(state.result.throwable), dark = true, onRetry = onRetry)
        },
    )
}

/**
 * 图片页手势：双指捏合缩放，放大后单指平移。未放大且单指时不消费事件，
 * 把横向拖动让给 HorizontalPager 翻页——`detectTransformGestures` 会无条件消费单指拖动，
 * 直接用会让图片能缩放但翻不了页。
 */
private fun Modifier.imagePinchPan(
    isZoomed: () -> Boolean,
    onGesture: (zoom: Float, pan: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previousDistance = 0f
        var previousCentroid = Offset.Zero
        var previousCount = 0
        var claimed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) claimed = true
            if (!claimed && !isZoomed()) {
                previousCount = pressed.size
                previousCentroid = Offset.Zero
                previousDistance = 0f
                continue
            }
            val centroid = Offset(
                pressed.map { it.position.x }.average().toFloat(),
                pressed.map { it.position.y }.average().toFloat(),
            )
            val distance = if (pressed.size >= 2) (pressed[0].position - pressed[1].position).getDistance() else 0f
            val zoom = if (previousDistance > 0f && distance > 0f) distance / previousDistance else 1f
            // 指针数刚变化时不产生位移，避免第二根手指落下时图片跳一下。
            val pan = if (previousCentroid != Offset.Zero && pressed.size == previousCount) {
                centroid - previousCentroid
            } else {
                Offset.Zero
            }
            onGesture(zoom, pan)
            pressed.forEach { it.consume() }
            previousDistance = distance
            previousCentroid = centroid
            previousCount = pressed.size
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

// 附件单独发送会被 trim() 去掉结尾换行，也应识别为附件消息。
private val ATTACHMENT_PREFIX_RE: Regex =
    Regex("^\\s*\\[附件已上传，请查看以下文件:\\n([\\s\\S]*?)\\](?:\\n+|$)")

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
