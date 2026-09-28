package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.activityStatus
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandSegmentedTrack
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.ui.components.WandStatusPresentation
import com.wand.app.ui.components.WandStatusTone
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandSizes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.wandSelectedRow
import com.wand.app.ui.withLiveTitle

/**
 * 首页（工作台）的成品级外壳。
 *
 * 设计取舍（对齐 Cursor for iOS / Claude Code mobile / Codex mobile 的首页共识）：
 * - 顶部是品牌 + 服务器 + 唯一溢出菜单，不再把「会话模式」做成一个像下拉的胶囊；
 * - 模式切换用真正的分段控件；
 * - 状态优先：先告诉你「几个在跑、几个等你」，再给列表；列表是卡片不是文件树；
 * - 需要动手的会话有明确的状态胶囊，而不是只有一个小圆点；
 * - 主操作固定在底部（输入式启动条），随时可以「开口」。
 * 这里只做展示，所有状态计算走 HomePresentation 的纯函数。
 */

// MARK: - 顶部栏

@Composable
internal fun HomeTopBar(
    serverDisplayName: String,
    interactionEnabled: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onOpenAiTeams: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var restoreMenuFocus by remember { mutableStateOf(false) }
    val menuFocusRequester = remember { FocusRequester() }
    val motionEnabled = !reduceMotionEnabled()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(menuOpen, restoreMenuFocus) {
        if (!menuOpen && restoreMenuFocus) {
            menuFocusRequester.requestFocus()
            restoreMenuFocus = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌标是身份，也是「这是一款客户端」而不是「一个后台工具」的第一眼信号。
        WandBrandMark(size = 30)
        Spacer(Modifier.width(10.dp))
        // 搜索就地展开：服务器胶囊的位置变成输入框，右侧那枚放大镜原地变形成 ✕，
        // 不跳页、不弹新层，收起时同一段动画倒放回去。
        AnimatedContent(
            targetState = searchOpen,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                if (motionEnabled) {
                    (fadeIn(WandMotion.tweenFast()) + slideInHorizontally(
                        animationSpec = WandMotion.tweenEnter(),
                        initialOffsetX = { it / 3 },
                    )) togetherWith (fadeOut(WandMotion.tweenExit()) + slideOutHorizontally(
                        animationSpec = WandMotion.tweenExit(),
                        targetOffsetX = { -it / 4 },
                    ))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "homeTopBarSearch",
        ) { open ->
            if (open) {
                WandInlineSearchField(
                    expanded = true,
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onCollapse = null,
                    placeholder = "搜索工作区 / 任务 / 会话",
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.full)
                        .background(WandColors.surfaceSoft.copy(alpha = 0.55f))
                        .border(0.5.dp, WandColors.border.copy(alpha = 0.6f), WandShapes.full)
                        .clickable(
                            enabled = interactionEnabled,
                            role = Role.Button,
                            onClickLabel = "切换服务器",
                            onClick = onSwitchServer,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        WandIcons.server,
                        contentDescription = null,
                        tint = WandColors.textMuted,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        serverDisplayName.ifBlank { "当前服务器" },
                        style = MaterialTheme.typography.labelLarge,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = 6.dp),
                    )
                    Icon(
                        WandIcons.expand,
                        contentDescription = null,
                        tint = WandColors.textMuted,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                    )
                }
            }
        }
        // 同一个按钮实例承载两种状态：放大镜原地变形成 ✕（规则 4）。
        // 拆成两个分支会让图标瞬切，变形就没了。
        WandMorphIconButton(
            expanded = searchOpen,
            collapsedIcon = WandIcons.search,
            expandedIcon = WandIcons.close,
            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
            onClick = onSearchToggle,
            enabled = interactionEnabled,
            tint = WandColors.textMuted,
            expandedTint = WandColors.brand,
            touchSize = 40.dp,
            iconSize = 19.dp,
            rotationDegrees = 0f,
        )
        // 入口的存在性只由布局决定，不随搜索态变化。
        if (onCollapseSidebar != null) {
            WandIconButton(
                icon = WandIcons.panelCollapse,
                contentDescription = "收起任务侧边栏",
                onClick = onCollapseSidebar,
                enabled = interactionEnabled,
                variant = WandIconButtonVariant.Toolbar,
            )
        }
        Box {
            WandMorphIconButton(
                expanded = menuOpen,
                collapsedIcon = WandIcons.more,
                expandedIcon = WandIcons.close,
                contentDescription = if (menuOpen) "关闭更多选项" else "更多选项",
                // ⋮ 常驻后会在搜索展开时点到：先清焦点收键盘，菜单才不会被 IME 顶起；
                // 搜索词保留，菜单关闭后也不自动重新聚焦。
                onClick = {
                    if (menuOpen) {
                        menuOpen = false
                        restoreMenuFocus = true
                    } else {
                        focusManager.clearFocus()
                        menuOpen = true
                    }
                },
                enabled = interactionEnabled,
                modifier = Modifier.focusRequester(menuFocusRequester),
                touchSize = WandSizes.minTouchTarget,
                iconSize = 20.dp,
                rotationDegrees = 0f,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = {
                    menuOpen = false
                    restoreMenuFocus = true
                },
                containerColor = WandColors.bgElevated,
            ) {
                Text(
                    if (searchOpen) "其他页面与连接" else "其他页面",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                )
                DropdownMenuItem(
                    text = { Text("AI 团队") },
                    leadingIcon = { Icon(WandIcons.agent, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenAiTeams() },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    leadingIcon = { Icon(WandIcons.settings, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
                // 搜索框覆盖了服务器胶囊，此时菜单补上唯一的服务器切换入口。
                if (searchOpen) {
                    DropdownMenuItem(
                        text = { Text("切换服务器") },
                        leadingIcon = { Icon(WandIcons.swapServer, contentDescription = null) },
                        onClick = { menuOpen = false; onSwitchServer() },
                    )
                }
            }
        }
    }
}

// MARK: - 模式分段

/** 真正的分段控件：会话 / 任务。旧的「会话模式 ⌄」看着像下拉，其实是切换，误导性太强。 */
@Composable
internal fun HomeModeTabs(
    mode: HomeListMode,
    enabled: Boolean,
    onChange: (HomeListMode) -> Unit,
) {
    val motionEnabled = !reduceMotionEnabled()
    val modes = HomeListMode.entries
    WandSegmentedTrack(
        itemCount = modes.size,
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        modes.forEach { entry ->
            val selected = entry == mode
            val textColor by animateColorAsState(
                targetValue = if (selected) WandColors.brand else WandColors.textSecondary,
                animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
                label = "homeTabText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(WandShapes.sm)
                    .clickable(
                        enabled = enabled && !selected,
                        role = Role.Tab,
                        onClick = { onChange(entry) },
                    )
                    .semantics {
                        contentDescription = if (selected) {
                            "当前${entry.segmentLabel}"
                        } else {
                            "切换到${entry.segmentLabel}"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.segmentLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - 状态总览

/**
 * 首页状态行要显示的数（§2.8 B2、§2.28 S24）。
 *
 * 口径只有一条：这一行的「在跑 / 等你 / 计数」全部取自已过完整条筛选链的 [overview]
 * （调用处传入的 `visibleGroups` = 搜索 ∩ 只看等你的**最终**结果），[totalCount] 才是全量数 M。
 * 组件不再自己算第二遍，避免出现「列表 0 条、状态行挂着 `11 / 27 条匹配`」的两种批次混排。
 */
internal data class HomeActivityStats(
    val overview: HomeOverview,
    val totalCount: Int,
    val searching: Boolean,
    val attentionOnly: Boolean,
) {
    /**
     * 计数文案的单一来源：搜索词在场时「匹配」优先，其次「待处理」，
     * 两层筛选都不在时只报全量数。三种单位不混用，也不在调用处再拼一遍。
     */
    val countLabel: String
        get() = when {
            searching -> "${overview.sessions} / $totalCount 条匹配"
            attentionOnly -> "${overview.sessions} / $totalCount 条待处理"
            else -> "$totalCount 个会话"
        }

    /** 只读「在跑」胶囊：只看最终交集里是不是真的有在跑。 */
    fun showsRunningPill(): Boolean = overview.running > 0

    /** 只读「在跑」胶囊文案，与等你胶囊同源于 [overview]。 */
    fun runningPillLabel(): String = "${overview.running} 个在跑"

    /**
     * 「只看等你」胶囊是否渲染。胶囊是这个筛选的**唯一开关**，所以选中态必须渲染：
     * 一旦在筛选打开时（含交集为 0）把它藏起来，用户在原地就关不掉了（S24）。
     */
    fun showsAttentionPill(): Boolean = overview.needsYou > 0 || attentionOnly

    /**
     * 胶囊文案的单一来源：有等待数就报数（选中与否同一份），
     * 选中且零结果是「已选等你」——不复用未选中式祈使文案，否则读起来像没开、找不到关闭点。
     */
    fun attentionPillLabel(): String =
        if (overview.needsYou > 0) "${overview.needsYou} 个等你" else "已选等你"

    /** 无障碍动作名同样只从这里出：选中态读「取消」，才知道再点同一坐标就是关闭路径。 */
    fun attentionPillDescription(): String =
        if (attentionOnly) "取消只看需要处理的会话" else "只看需要处理的会话"

    /** 开关本身的状态：读屏靠它知道现在是开着还是关着，不用猜底色。 */
    fun attentionPillStateDescription(): String = if (attentionOnly) "已开启" else "未开启"

    /**
     * 极端大的等待数被固定触控槽省略时补的完整语义（§2.28）：
     * 视觉被截掉的只有报数，动作名仍由 [attentionPillDescription] 给出，这里把完整 W 找回来。
     */
    fun attentionPillFullDescription(): String =
        "${attentionPillDescription()}，${overview.needsYou} 个等你"
}

/**
 * 状态行的数据只在调用处算一次：`globalOverview` 给分母，
 * `finalOverview` 给这一行显示的全部数字。
 */
internal fun homeActivityStats(
    globalOverview: HomeOverview,
    finalOverview: HomeOverview,
    searching: Boolean,
    attentionOnly: Boolean,
): HomeActivityStats = HomeActivityStats(
    overview = finalOverview,
    totalCount = globalOverview.sessions,
    searching = searching,
    attentionOnly = attentionOnly,
)

/**
 * 整行是否渲染的**唯一**门（D13）：调用处直接用它，组件内不得有第二套显隐表达式。
 * 安静且没开任何筛选时不留空壳；但搜索态（含命中 0 条）与筛选选中态必须留在原地，
 * 否则计数会和空态互相矛盾、开关自己无处可点。任务分段不渲染这条会话状态行。
 */
internal fun homeActivityStripVisible(showingBoard: Boolean, stats: HomeActivityStats): Boolean =
    !showingBoard && (
        stats.searching || stats.attentionOnly ||
            stats.overview.running > 0 || stats.overview.needsYou > 0
    )

/** 会话列表空态的两段文案（图标之外）：一行一种组合，只有一份实现。 */
internal data class HomeSessionEmptyCopy(
    val title: String,
    val subtitle: String,
)

/**
 * 有数据、但最终 `visibleGroups` 为空时的空态（§2.28）。
 * 建议必须指向**当前真正挡着列表的那一层**：只看等你时指回「已选等你」这枚胶囊，
 * 而不是叫用户去关一个他根本没开的搜索。不加按钮型 CTA。
 */
internal fun homeSessionEmptyCopy(searching: Boolean, attentionOnly: Boolean): HomeSessionEmptyCopy = when {
    searching && attentionOnly -> HomeSessionEmptyCopy(
        title = "没有匹配的待处理会话",
        subtitle = "换个词试试，或者再点「已选等你」查看搜索结果。",
    )
    searching -> HomeSessionEmptyCopy(
        title = "没有匹配的会话",
        subtitle = "换个词试试，或者关掉搜索看全部。",
    )
    else -> HomeSessionEmptyCopy(
        title = "没有需要处理的会话",
        subtitle = "当前没有会话等你处理。再点「已选等你」查看全部会话。",
    )
}

/** 「等你」筛选胶囊的固定触控槽（§2.28）：两态同宽同坐标，零结果也不缩、不消失。 */
private val AttentionPillSlotWidth = 120.dp

/** 前导标识槽：未选时 7dp 圆点居中、选中时 13dp 矢量勾，都在同一槽里换，不动文字起点。 */
private val PillLeadingSlotSize = 13.dp
private val PillLeadingDotSize = 7.dp

/** 胶囊的纵向内边距，计数文本共用：两者同为 `labelMedium`，首行才能对齐。 */
private val PillVerticalPadding = 5.dp

/**
 * 一行状态：左端第一槽是可点的「等你」筛选胶囊，右边才是只读的在跑与计数。
 * 显隐由调用处的 [homeActivityStripVisible] 唯一决定，这里不再早退。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeActivityStrip(
    stats: HomeActivityStats,
    enabled: Boolean,
    onToggleAttention: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
        // 首行左右两端固定是筛选触发点与计数；放不下时落下去的只有只读在跑胶囊。
        verticalAlignment = Alignment.Top,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = 2,
        ) {
            if (stats.showsAttentionPill()) {
                HomeStatPill(
                    label = stats.attentionPillLabel(),
                    tone = WandStatusTone.Permission,
                    selected = stats.attentionOnly,
                    onClick = { if (enabled) onToggleAttention() },
                    contentDescription = stats.attentionPillDescription(),
                    stateDescription = stats.attentionPillStateDescription(),
                    ellipsizedContentDescription = stats.attentionPillFullDescription(),
                    slotWidth = AttentionPillSlotWidth,
                )
            }
            if (stats.showsRunningPill()) {
                HomeStatPill(
                    label = stats.runningPillLabel(),
                    tone = WandStatusTone.Success,
                    selected = false,
                    onClick = null,
                )
            }
        }
        // 计数文本右端固定：换文案时在同一位置交叉淡入，不推动布局。
        // 纵向补和胶囊一样的内边距，两行时它仍停在首行右端。
        Box(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(vertical = PillVerticalPadding),
            contentAlignment = Alignment.CenterEnd,
        ) {
            WandInPlaceSwap(contentKey = stats.countLabel) {
                Text(
                    stats.countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeStatPill(
    label: String,
    tone: WandStatusTone,
    selected: Boolean,
    onClick: (() -> Unit)?,
    contentDescription: String? = null,
    stateDescription: String? = null,
    ellipsizedContentDescription: String? = null,
    slotWidth: Dp? = null,
) {
    val color = when (tone) {
        WandStatusTone.Success -> WandColors.success
        WandStatusTone.Permission -> WandColors.permission
        WandStatusTone.Danger -> WandColors.danger
        WandStatusTone.Warning -> WandColors.warning
        WandStatusTone.Neutral -> WandColors.textMuted
    }
    val motionEnabled = !reduceMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.18f) else WandColors.surfaceSoft.copy(alpha = 0.5f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.55f) else WandColors.border.copy(alpha = 0.5f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillStroke",
    )
    // 圆点 ⇄ 勾的淡入淡出：只在同一前导槽里换标识，按钮盒与文字起点都不动。
    val dotAlpha by animateFloatAsState(
        targetValue = if (selected) 0f else 1f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillDotAlpha",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillCheckAlpha",
    )
    val slot = if (slotWidth != null) Modifier.width(slotWidth) else Modifier
    val base = slot
        .clip(WandShapes.full)
        .background(fill)
        .border(0.8.dp, stroke, WandShapes.full)
    val clickable = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else {
        base
    }
    // 固定槽把报数挤掉时，读屏改用带完整数字的描述；普通计数不受影响。
    var ellipsized by remember(label) { mutableStateOf(false) }
    val pillDescription = if (ellipsized && ellipsizedContentDescription != null) {
        ellipsizedContentDescription
    } else {
        contentDescription
    }
    val leadingModifier = if (pillDescription != null || stateDescription != null) {
        Modifier.semantics {
            if (pillDescription != null) this.contentDescription = pillDescription
            if (stateDescription != null) this.stateDescription = stateDescription
        }
    } else {
        Modifier
    }
    Row(
        modifier = clickable
            .then(leadingModifier)
            .padding(horizontal = 10.dp, vertical = PillVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Box(
            modifier = Modifier.size(PillLeadingSlotSize),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(PillLeadingDotSize)
                    .graphicsLayer(alpha = dotAlpha)
                    .clip(WandShapes.full)
                    .background(color),
            )
            Icon(
                imageVector = WandIcons.check,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(PillLeadingSlotSize)
                    .graphicsLayer(alpha = checkAlpha),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                val overflow = layout.hasVisualOverflow
                if (overflow != ellipsized) ellipsized = overflow
            },
        )
    }
}

// MARK: - 工作区卡片

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeWorkspaceCard(
    group: TaskDirectoryGroup,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    dragging: Boolean = false,
    standaloneCollapsed: Boolean,
    taskCollapsed: (String) -> Boolean,
    forceExpandTasks: Boolean = false,
    /** 标题行上的长按拖动手势（由列表侧注入，卡片本身不知道排序实现）。 */
    headerDragModifier: Modifier = Modifier,
    selectedTaskId: String?,
    selectedSessionId: String?,
    nowMillis: Long,
    selecting: Boolean = false,
    selectedTaskIds: Set<String> = emptySet(),
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManagedTask: (String) -> Unit = {},
    onToggleManagedSession: (String) -> Unit = {},
    onEnterSelection: (taskId: String?, sessionId: String?) -> Unit = { _, _ -> },
    onToggleGroup: () -> Unit,
    onToggleTask: (String) -> Unit,
    onToggleStandalone: () -> Unit,
    onOpenTask: (WorkspaceTaskSummary) -> Unit,
    onOpenSession: (WorkspaceSessionSummary, WorkspaceTaskSummary?) -> Unit,
    onMoveSession: (WorkspaceSessionSummary) -> Unit,
    onNewTask: () -> Unit,
    onRenameDirectory: () -> Unit,
    onNewWindow: (WorkspaceTaskSummary) -> Unit,
    onRename: (WorkspaceTaskSummary) -> Unit,
    onClear: (WorkspaceTaskSummary) -> Unit,
    onArchive: (WorkspaceTaskSummary) -> Unit,
    onDelete: (WorkspaceTaskSummary) -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
    onDeleteDirectory: () -> Unit,
    onReview: () -> Unit,
) {
    val sessions = group.tasks.flatMap { it.sessions } + group.standaloneSessions
    val counts = homeSessionCounts(sessions)
    val reduceMotion = reduceMotionEnabled()
    var menuOpen by remember { mutableStateOf(false) }
    // 空工作区没有可折叠的内容：渲染成一行紧凑条目，点它直接去建任务，
    // 而不是留一张带箭头、点了没反应的卡片。
    val isEmpty = group.tasks.isEmpty() && group.standaloneSessions.isEmpty()

    WandCard(
        modifier = modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.md)
                .clickable(
                    onClickLabel = when {
                        isEmpty -> "在 ${group.workspaceName} 新建任务"
                        expanded -> "收起工作区"
                        else -> "展开工作区"
                    },
                    onClick = { if (isEmpty) onNewTask() else onToggleGroup() },
                )
                // 长按标题行 = 拿起这张卡片排序；卡片内部的行保留自己的长按多选。
                .then(headerDragModifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(WandShapes.full)
                    .background(if (group.isGlobal) WandColors.textMuted else WandColors.brand),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 6.dp),
            ) {
                Text(
                    group.workspaceName.ifEmpty { "任务目录" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isEmpty) {
                        // 空目录的摘要是「它还没有内容」，不是「0 会话」这种机械计数。
                        androidx.compose.ui.text.AnnotatedString(
                            if (group.isGlobal) "未归属 · 还没有会话" else "还没有会话",
                        )
                    } else {
                        summaryText(group.isGlobal, workspaceSummarySegments(sessions.size, counts))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isEmpty) {
                TreeDisclosureCaret(
                    expanded = expanded,
                    contentDescription = if (expanded) {
                        "收起工作区 ${group.workspaceName}"
                    } else {
                        "展开工作区 ${group.workspaceName}"
                    },
                    onClick = onToggleGroup,
                )
            }
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "工作区操作 ${group.workspaceName}",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Quiet,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    if (!group.isGlobal) {
                        DropdownMenuItem(
                            text = { Text("在这里新建任务") },
                            leadingIcon = { Icon(WandIcons.add, contentDescription = null) },
                            onClick = { menuOpen = false; onNewTask() },
                        )
                    }
                    if (group.tasks.any { it.worktree != null }) {
                        DropdownMenuItem(
                            text = { Text("审查 Worktree") },
                            leadingIcon = { Icon(WandIcons.commit, contentDescription = null) },
                            onClick = { menuOpen = false; onReview() },
                        )
                    }
                    if (group.workspaceId != GLOBAL_WORKSPACE_ID) {
                        DropdownMenuItem(
                            text = { Text("重命名工作区") },
                            leadingIcon = { Icon(WandIcons.rename, contentDescription = null) },
                            onClick = { menuOpen = false; onRenameDirectory() },
                        )
                    }
                    // 合成目录没有项目实体，删除它等于删掉这里所有会话，不给这个入口。
                    if (!group.synthetic && !group.isGlobal) {
                        DropdownMenuItem(
                            text = { Text("删除工作区…", color = WandColors.danger) },
                            leadingIcon = { Icon(WandIcons.delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDeleteDirectory() },
                        )
                    }
                }
            }
        }
        // 抓起时不再组合正文：即使 AnimatedVisibility 正在退场，也不能让其残影
        // 透过悬浮标题行。普通点按收起仍保留完整的反向动画。
        if (isEmpty || dragging) return@WandCard
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = WandMotion.tweenEnter(), expandFrom = Alignment.Top) +
                    fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = WandMotion.tweenExit(), shrinkTowards = Alignment.Top) +
                    fadeOut(WandMotion.tweenExit())
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val visibleTasks = group.tasks
                visibleTasks.forEachIndexed { index, task ->
                    // 任务块不再有底色，兄弟任务之间用一条发丝线分隔，边界才看得清。
                    if (index > 0) HomeHairline()
                    HomeTaskBlock(
                        task = task,
                        parentNames = listOf(group.workspaceName),
                        nowMillis = nowMillis,
                        expanded = forceExpandTasks || isTaskSessionsExpanded(
                            userCollapsed = taskCollapsed(task.id),
                            sessionCount = task.totalSessions,
                            isOnlyTask = visibleTasks.size == 1,
                        ),
                        selected = isTaskRowSelected(
                            taskId = task.id,
                            visibleSessionIds = task.sessions.map { it.id },
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                        ),
                        selectedSessionId = selectedSessionId,
                        selecting = selecting,
                        managedSelected = task.id in selectedTaskIds,
                        selectedSessionIds = selectedSessionIds,
                        onToggleManaged = { onToggleManagedTask(task.id) },
                        onToggleManagedSession = onToggleManagedSession,
                        onEnterTaskSelection = { onEnterSelection(task.id, null) },
                        onEnterSessionSelection = { onEnterSelection(null, it) },
                        onToggle = { onToggleTask(task.id) },
                        onOpen = { onOpenTask(task) },
                        onOpenSession = { onOpenSession(it, task) },
                        onNewWindow = { onNewWindow(task) },
                        onRename = { onRename(task) },
                        onClear = { onClear(task) },
                        onArchive = { onArchive(task) },
                        onDelete = { onDelete(task) },
                        onDeleteSession = onDeleteSession,
                        onMoveSession = onMoveSession,
                    )
                }
                if (group.standaloneSessions.isNotEmpty()) {
                    if (group.tasks.isNotEmpty()) HomeHairline()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(WandShapes.sm)
                                .clickable(onClick = onToggleStandalone)
                                .padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${group.standaloneSessions.size} 个未分组终端",
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.textMuted,
                                modifier = Modifier.weight(1f),
                            )
                            TreeDisclosureCaret(
                                expanded = !standaloneCollapsed,
                                contentDescription = if (standaloneCollapsed) {
                                    "展开未分组终端"
                                } else {
                                    "收起未分组终端"
                                },
                                onClick = onToggleStandalone,
                            )
                        }
                        if (!standaloneCollapsed) {
                            group.standaloneSessions.forEachIndexed { index, session ->
                                HomeSessionRow(
                                    session = session,
                                    label = listSessionLabel(
                                        session.withLiveTitle(),
                                        index,
                                        listOf(group.workspaceName),
                                    ),
                                    nowMillis = nowMillis,
                                    selected = session.id == selectedSessionId,
                                    selecting = selecting,
                                    managedSelected = session.id in selectedSessionIds,
                                    onToggleManaged = { onToggleManagedSession(session.id) },
                                    onEnterSelection = { onEnterSelection(null, session.id) },
                                    onClick = { onOpenSession(session, null) },
                                    onDelete = { onDeleteSession(session) },
                                    onMove = { onMoveSession(session) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 摘要行：数字自己带语义色（运行中绿、待处理金），比在标题右侧再挂一个活动胶囊省地方，
 * 也不会把同一个数字说两遍。
 */
@Composable
private fun summaryText(global: Boolean, segments: List<HomeSummarySegment>): AnnotatedString {
    val separator = " · "
    return buildAnnotatedString {
        if (global) {
            withStyle(SpanStyle(color = WandColors.textMuted)) { append("未归属") }
            append(separator)
        }
        segments.forEachIndexed { index, segment ->
            if (index > 0) append(separator)
            withStyle(
                SpanStyle(
                    color = when (segment.tone) {
                        HomeSummaryTone.Muted -> WandColors.textMuted
                        HomeSummaryTone.Running -> WandColors.success
                        HomeSummaryTone.NeedsYou -> WandColors.permission
                    },
                    fontWeight = if (segment.tone == HomeSummaryTone.Muted) FontWeight.Normal else FontWeight.Medium,
                ),
            ) {
                append(segment.text)
            }
        }
    }
}

/** 卡片内部的发丝分隔线：比整块底色轻，只负责划清兄弟区块的边界。 */
@Composable
private fun HomeHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(WandColors.border.copy(alpha = 0.38f)),
    )
}

// MARK: - 任务块

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTaskBlock(
    task: WorkspaceTaskSummary,
    parentNames: Collection<String>,
    nowMillis: Long,
    expanded: Boolean,
    selected: Boolean,
    selectedSessionId: String?,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManaged: () -> Unit = {},
    onToggleManagedSession: (String) -> Unit = {},
    onEnterTaskSelection: () -> Unit = {},
    onEnterSessionSelection: (String) -> Unit = {},
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenSession: (WorkspaceSessionSummary) -> Unit,
    onMoveSession: (WorkspaceSessionSummary) -> Unit,
    onNewWindow: () -> Unit,
    onRename: () -> Unit,
    onClear: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val reduceMotion = reduceMotionEnabled()
    val done = task.status == WorkspaceTaskStatus.Done
    val counts = homeSessionCounts(task.sessions)
    // 任务块只做「小标题 + 状态引导条」：不再套一层带底色的盒子，
    // 否则工作区卡片里再嵌一张同色卡片，整页看起来像文件树。
    // 引导条的颜色就是这条任务的健康度：等你 > 在跑 > 普通。
    val railColor = when {
        counts.needsYou > 0 -> WandColors.permission.copy(alpha = 0.6f)
        counts.running > 0 -> WandColors.success.copy(alpha = 0.5f)
        else -> WandColors.border.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (done) Modifier.graphicsLayer { alpha = 0.76f } else Modifier),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(WandShapes.full)
                .background(railColor),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .wandSelectedRow(
                    selected = if (selecting) managedSelected else selected,
                    shape = WandShapes.sm,
                )
                .combinedClickable(
                    onClick = { if (selecting) onToggleManaged() else onOpen() },
                    onLongClickLabel = if (selecting) "切换选择任务" else "进入多选任务",
                    onLongClick = { if (!selecting) onEnterTaskSelection() else onToggleManaged() },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                ManageCheck(checked = managedSelected)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.name,
                        // 三级层级：工作区（15 半粗）> 任务（13 半粗·次级色）> 会话标题（14 正文）。
                        // 任务名是这一层的小标题，不能和会话标题长得一样大，
                        // 否则整列文字看起来是平铺的一堆句子。
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) WandColors.textPrimary else WandColors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (task.isIsolated) {
                        Icon(
                            WandIcons.commit,
                            contentDescription = "隔离 worktree",
                            tint = WandColors.success,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(13.dp),
                        )
                    }
                }
                Text(
                    summaryText(global = false, segments = taskSummarySegments(task.sessions.size, done, counts)),
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showsTaskSessionDisclosure(task.totalSessions)) {
                TreeDisclosureCaret(
                    expanded = expanded,
                    contentDescription = if (expanded) "收起会话" else "展开会话",
                    onClick = onToggle,
                )
            }
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "任务操作 ${task.name}",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Quiet,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("新建会话") },
                        leadingIcon = { Icon(WandIcons.add, contentDescription = null) },
                        onClick = { menuOpen = false; onNewWindow() },
                    )
                    DropdownMenuItem(
                        text = { Text("打开任务") },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    if (task.totalSessions > 0) {
                        DropdownMenuItem(
                            text = { Text("清空会话", color = WandColors.danger) },
                            onClick = { menuOpen = false; onClear() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("归档任务") },
                        leadingIcon = { Icon(WandIcons.archive, contentDescription = null) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                    // 归档是软删除；只有隔离任务才在归档之外再提供真删（清理 Worktree）。
                    if (task.isIsolated) {
                        DropdownMenuItem(
                            text = { Text("删除任务并清理 Worktree", color = WandColors.danger) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = WandMotion.tweenEnter(), expandFrom = Alignment.Top) +
                    fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = WandMotion.tweenExit(), shrinkTowards = Alignment.Top) +
                    fadeOut(WandMotion.tweenExit())
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, bottom = 2.dp),
            ) {
                if (task.sessions.isEmpty()) {
                    Text(
                        "＋ 添加第一个会话",
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.brand,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .clickable(onClick = onNewWindow)
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                    )
                } else {
                    task.sessions.forEachIndexed { index, session ->
                        HomeSessionRow(
                            session = session,
                            label = listSessionLabel(session.withLiveTitle(), index, parentNames + task.name),
                            nowMillis = nowMillis,
                            selected = session.id == selectedSessionId,
                            selecting = selecting,
                            managedSelected = session.id in selectedSessionIds,
                            onToggleManaged = { onToggleManagedSession(session.id) },
                            onEnterSelection = { onEnterSessionSelection(session.id) },
                            onClick = { onOpenSession(session) },
                            onDelete = { onDeleteSession(session) },
                            onMove = { onMoveSession(session) },
                        )
                    }
                    if (task.totalSessions > task.sessions.size) {
                        Text(
                            "列表仅显示 ${task.sessions.size}/${task.totalSessions} 个会话，打开任务可查看全部。",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            modifier = Modifier
                                .clickable(onClick = onOpen)
                                .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

// MARK: - 会话行

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(
    session: WorkspaceSessionSummary,
    label: String,
    nowMillis: Long,
    selected: Boolean,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    onToggleManaged: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val live = session.withLiveTitle()
    val status = live.activityStatus()
    // 权限态只在 WS 事件里，轮询摘要没有；首页要显示它必须先看实时 overlay。
    val permissionBlocked = SessionTitleStore.permissionBlockedOf(session.id) == true
    val presentation = wandStatusPresentation(if (permissionBlocked) "permission" else status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandSelectedRow(
                selected = if (selecting) managedSelected else selected,
                shape = WandShapes.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = { if (selecting) onToggleManaged() else onClick() },
                    onLongClickLabel = if (selecting) "切换选择会话" else "进入多选会话",
                    onLongClick = { if (!selecting) onEnterSelection() else onToggleManaged() },
                )
                .padding(start = 4.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                ManageCheck(checked = managedSelected)
            }
            // provider 标是列表里最省字的身份信息：一眼分清 Claude / Codex / Grok。
            // 群聊会话是个「多人房间」而不是某个 CLI，用团队图标替掉 provider 标（对齐 Web）。
            val teamChat = session.teamChat
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .then(
                        if (teamChat != null) Modifier.clip(WandShapes.sm)
                            .background(WandColors.brandSoft.copy(alpha = 0.55f))
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (teamChat != null) {
                    Icon(
                        WandIcons.agent,
                        contentDescription = null,
                        tint = WandColors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    WandProviderMark(
                        provider = session.provider,
                        variant = WandProviderMarkVariant.Tinted,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (teamChat != null) {
                        // 「群聊 · 团队名 · N 人」：一眼看出这是团队房间，点进去是 IM 页而不是普通聊天。
                        Text(
                            "群聊 · ${teamChat.teamName.ifBlank { "AI 团队" }} · ${teamChat.memberCount} 人",
                            style = MaterialTheme.typography.labelMedium,
                            color = WandColors.brand,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(WandShapes.xs)
                                .background(WandColors.brandSoft.copy(alpha = 0.5f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    SessionStatusPill(presentation = presentation)
                    Text(
                        sessionMetaLine(live, nowMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            WandIconButton(
                icon = WandIcons.more,
                contentDescription = "终端操作 $label",
                onClick = { menuOpen = true },
                variant = WandIconButtonVariant.Quiet,
                // 会话行是列表里最密的一层，操作入口收一点，别和工作区/任务的菜单抢注意力。
                tint = WandColors.textMuted.copy(alpha = 0.78f),
                iconSize = 18.dp,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = WandColors.bgElevated,
            ) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    onClick = { menuOpen = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text("移动到任务") },
                    leadingIcon = { Icon(WandIcons.folder, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text("删除终端", color = WandColors.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/**
 * 会话状态胶囊：文字 + 语义色。列表里只有一个小圆点时，
 * 「在跑 / 在等我 / 空闲」的差别要靠颜色去猜；写出来才是产品该有的信息密度。
 */
@Composable
private fun SessionStatusPill(presentation: WandStatusPresentation) {
    val color = when (presentation.tone) {
        WandStatusTone.Success -> WandColors.success
        WandStatusTone.Permission -> WandColors.permission
        WandStatusTone.Danger -> WandColors.danger
        WandStatusTone.Warning -> WandColors.warning
        WandStatusTone.Neutral -> WandColors.textMuted
    }
    // 「空闲」是绝大多数会话的常态：它只留一个安静的灰点，
    // 不写文字、不占胶囊，让真正需要看的「运行中 / 等待授权」跳出来。
    if (presentation.normalized == "idle") {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .semantics { contentDescription = "空闲" },
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(presentation.normalized, modifier = Modifier.size(6.dp))
        }
        return
    }
    Row(
        modifier = Modifier
            .clip(WandShapes.full)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            presentation.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// MARK: - 底部启动条

/**
 * 底部固定的启动条：产品的「开口」应该永远在手边。
 * 可输入多行意图，点发送后带进新建任务对话框（目录 / provider 仍走原有流程确认）。
 * 键盘回车留给换行和中文输入法；左侧「＋」直达完整表单。
 */
@Composable
internal fun HomeComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onOpenFullDialog: () -> Unit,
) {
    val trimmed = value.trim()
    var inputFocused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        // 上与列表的分界线：列表被滑动条压住时，边界要看起来是「故意切的」而不是被截断。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(WandColors.border.copy(alpha = 0.45f)),
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // edge-to-edge 下窗口不会因键盘而缩，输入条必须自己让开 IME 高度。
            .background(WandColors.bgElevated.copy(alpha = 0.94f))
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WandIconButton(
            icon = WandIcons.add,
            contentDescription = "新建任务",
            onClick = onOpenFullDialog,
            enabled = enabled,
            variant = WandIconButtonVariant.Quiet,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(WandShapes.lg)
                .background(WandColors.surface.copy(alpha = 0.92f))
                .border(
                    0.8.dp,
                    if (inputFocused) WandColors.focusRing else WandColors.border.copy(alpha = 0.7f),
                    WandShapes.lg,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                minLines = 1,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = WandColors.textPrimary),
                cursorBrush = SolidColor(WandColors.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "描述你想让 AI 做什么…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WandColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        WandIconButton(
            icon = WandIcons.send,
            contentDescription = "用这个提示词新建任务",
            onClick = {
                if (trimmed.isEmpty()) return@WandIconButton
                onSubmit(value)
            },
            enabled = enabled && trimmed.isNotEmpty(),
            variant = WandIconButtonVariant.Accent,
        )
    }
    }
}

// MARK: - 共享微件

/** 首页里的展开箭头：和卡片展开语义一致（收起时箭头转向，不硬切）。 */
@Composable
private fun TreeDisclosureCaret(
    expanded: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
) {
    val motionEnabled = !reduceMotionEnabled()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenNormal()),
        label = "homeDisclosureCaret",
    )
    Row(
        modifier = Modifier
            .clip(WandShapes.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WandColors.textMuted)
        }
        Icon(
            WandIcons.expand,
            contentDescription = contentDescription,
            tint = WandColors.textMuted,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/** 多选模式下的勾选框：底与描边跟着选中过渡，避免快速多选时整列硬闪。 */
@Composable
private fun ManageCheck(checked: Boolean) {
    val motionEnabled = !reduceMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (checked) WandColors.brand else WandColors.surfaceSoft,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "manageCheckFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (checked) WandColors.brand else WandColors.border.copy(alpha = 0.7f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "manageCheckStroke",
    )
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp)
            .clip(WandShapes.xs)
            .background(fill)
            .border(1.dp, stroke, WandShapes.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                WandIcons.statusDone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
