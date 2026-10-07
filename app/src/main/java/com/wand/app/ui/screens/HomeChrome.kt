package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.activityStatus
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandSegmentedTrack
import com.wand.app.ui.components.wandCardSurface
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandMorphingIcon
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.components.WandStatusPresentation
import com.wand.app.ui.components.statusColor
import com.wand.app.ui.components.WandStatusTone
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.components.WandBrandTag
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
 * 手机按 APP 参考图控制层级与密度：
 * - 一层标题栏，服务器切换收在品牌图标，消息页使用自己的标题栏；
 * - 灰白底面、克制的蓝色动作、细描边悬浮底栏；
 * - 需要动手的会话有明确的状态胶囊，而不是只有一个小圆点；
 * - 手机首页与平板展开侧栏共用底部导航：「聊天 / 通讯录 / 工作区 / 任务」直接可达，
 *   溢出菜单不重复这些入口。
 * 这里只做展示，所有状态计算走 HomePresentation 的纯函数。
 */

// MARK: - 顶部栏

@Composable
internal fun HomeTopBar(
    title: String,
    serverDisplayName: String,
    interactionEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var restoreMenuFocus by remember { mutableStateOf(false) }
    val menuFocusRequester = remember { FocusRequester() }
    LaunchedEffect(menuOpen, restoreMenuFocus) {
        if (!menuOpen && restoreMenuFocus) {
            menuFocusRequester.requestFocus()
            restoreMenuFocus = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(WandSizes.minTouchTarget).clip(WandShapes.full)
                .clickable(enabled = interactionEnabled, role = Role.Button,
                    onClickLabel = "切换服务器", onClick = onSwitchServer)
                .semantics { contentDescription = "当前服务器：${serverDisplayName.ifBlank { "未命名" }}，切换服务器" },
            contentAlignment = Alignment.Center,
        ) {
            WandBrandMark(size = 26)
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, color = WandColors.textPrimary, maxLines = 1)
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
                onClick = {
                    if (menuOpen) {
                        menuOpen = false
                        restoreMenuFocus = true
                    } else {
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
                DropdownMenuItem(
                    text = { Text("设置") },
                    leadingIcon = { Icon(WandIcons.settings, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
            }
        }
    }
}

// MARK: - 状态总览

/**
 * 首页状态行的「在跑 / 等你 / 计数」取自当前可见会话，[totalCount] 是全量数。
 * [runningOnly] 是折叠控制第三档（只列在跑的），与 [attentionOnly] 互斥。
 */
internal data class HomeActivityStats(
    val overview: HomeOverview,
    val totalCount: Int,
    val attentionOnly: Boolean,
    val runningOnly: Boolean = false,
) {
    /**
     * 筛选打开时显示「命中数 / 全量数」；否则显示全量数。
     * 「在跑」报的是真的在跑的会话数，不是可见行数：某一层被单独设成展开时可见行会更多
     * （那些行不是「在跑」），按可见行报数会读成「11 / 11 条在跑」这种自相矛盾的话。
     */
    val countLabel: String
        get() = when {
            runningOnly -> "${overview.running} / $totalCount 条在跑"
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
    attentionOnly: Boolean,
    runningOnly: Boolean = false,
): HomeActivityStats = HomeActivityStats(
    overview = finalOverview,
    totalCount = globalOverview.sessions,
    attentionOnly = attentionOnly,
    runningOnly = runningOnly,
)

/**
 * 整行是否渲染的**唯一**门（D13）：调用处直接用它，组件内不得有第二套显隐表达式。
 * 安静且没开筛选时不留空壳；筛选选中态必须留在原地，保证能点同一胶囊关闭。
 * 折叠控制不住在这一行里（它挂在小节表头的文字右边），所以这条口径保持原样。
 */
internal fun homeActivityStripVisible(showingBoard: Boolean, stats: HomeActivityStats): Boolean =
    !showingBoard && (
        stats.attentionOnly ||
            stats.overview.running > 0 || stats.overview.needsYou > 0
    )

/** 会话列表空态的两段文案（图标之外）：一行一种组合，只有一份实现。 */
internal data class HomeSessionEmptyCopy(
    val title: String,
    val subtitle: String,
)

/**
 * 有数据、但最终 `visibleGroups` 为空时的空态（§2.28）。
 * 只有「只看等你」会把列表筛空：它会整条藏掉没有待处理会话的任务与目录。
 * 「在跑」不删行（一级行永远留着），所以它没有空态，靠状态行的「0 / N 条在跑」说明。
 */
internal fun homeSessionEmptyCopy(): HomeSessionEmptyCopy = HomeSessionEmptyCopy(
    title = "没有需要处理的会话",
    subtitle = "当前没有会话等你处理。再点「已选等你」查看全部会话。",
)

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

/**
 * 列表小节表头行：左边是节名，右边挂三段式控制（[fold] 为 null 时只画标题）。
 * 总档位住在第一行表头的文字右边，「最近对话」为空时那一行就是「任务与工作区」；
 * 各分组/任务的控制同样贴在自己那一行文字的右边。
 */
@Composable
internal fun HomeSectionHeaderRow(
    title: String,
    emphasized: Boolean,
    fold: HomeFoldMode?,
    onSelectFold: (HomeFoldMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (emphasized) WandColors.textPrimary else WandColors.textSecondary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (fold != null) {
            HomeFoldControl(mode = fold, onSelect = onSelectFold)
        }
    }
}

/** 行内折叠控制的宽度：三段等宽、文案两字，贴在标题文字右边也不抢标题宽度。 */
internal val HomeFoldControlWidth = 118.dp

/** 控制整体高度（含轨道内边距）：比状态胶囊略高，但明显矮于主操作按钮。 */
internal val HomeFoldControlHeight = 32.dp

/**
 * 首页会话列表的三段式折叠控制：展开 / 收起 / 在跑。
 *
 * 形状、指示条与动效沿用全站唯一的分段控件 [WandSegmentedTrack]（指示条前缘先走、后缘晚一拍，
 * `reduceMotion` 下退化为瞬时）；选中只换颜色与字重、不换尺寸，所以换档时控件本身不位移。
 * 「在跑」是一道筛选，所以它和「等你」互斥（由调用处保证），本组件只负责选档。
 */
@Composable
internal fun HomeFoldControl(
    mode: HomeFoldMode,
    onSelect: (HomeFoldMode) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = HomeFoldControlWidth,
    enabled: Boolean = true,
) {
    WandSegmentedTrack(
        itemCount = HomeFoldMode.entries.size,
        selectedIndex = mode.ordinal,
        modifier = modifier.width(width),
        minHeight = HomeFoldControlHeight,
    ) {
        HomeFoldMode.entries.forEach { entry ->
            HomeFoldSegment(
                mode = entry,
                selected = entry == mode,
                enabled = enabled,
                onClick = { onSelect(entry) },
            )
        }
    }
}

/** 控制里的一段：选中只换颜色与字重，指示条由 [WandSegmentedTrack] 负责。 */
@Composable
private fun RowScope.HomeFoldSegment(
    mode: HomeFoldMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) WandColors.brand else WandColors.textSecondary,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenFast()),
        label = "homeFoldSegmentColor",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = HomeFoldControlHeight - 6.dp)
            .clip(WandShapes.full)
            .clickable(
                enabled = enabled && !selected,
                role = Role.Tab,
                onClickLabel = if (selected) null else mode.actionLabel,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (selected) "当前${mode.actionLabel}" else mode.actionLabel
                stateDescription = if (selected) "已选中" else "未选中"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mode.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
        )
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
    val color = tone.statusColor()
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
    employees: List<SiliconEmployee>,
    modifier: Modifier = Modifier,
    /** 该工作区本次浏览的有效档位；临时展开时不改变小节的全局设置。 */
    fold: HomeFoldMode,
    onToggleFold: (() -> Unit)? = null,
    dragging: Boolean = false,
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
    // 而不是留一张带折叠控制、点了没反应的卡片。
    val isEmpty = group.tasks.isEmpty() && group.standaloneSessions.isEmpty()
    // 在跑档只显示真有在跑、失败或待处理会话的任务（其余任务整条不显示），未分组终端同理；
    // 工作区这一行本身保留（摘要是它的真实计数）。
    val visibleTasks = foldVisibleTasks(fold, group.tasks)
    val standaloneVisible = foldVisibleSessions(fold, group.standaloneSessions)
    val visibleSessionCount = visibleTasks.sumOf { foldVisibleSessions(fold, it.sessions).size } +
        standaloneVisible.size
    // 抓起排序时卡片固定收成标题行（与档位无关），松手后按档位恢复；
    // 在跑档下这一组一条在跑、失败或待处理都没有时同样收成标题行 —— 一级行留着，行不露。
    val expanded = !dragging && foldExpandsContent(fold, visibleSessionCount + visibleTasks.size)

    WandCard(
        modifier = modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.md)
                // 有内容的目录可临时展开；空目录仍直接创建任务。
                .clickable(
                    enabled = !selecting && !dragging && (isEmpty || onToggleFold != null),
                    onClickLabel = if (isEmpty) "在 ${group.workspaceName} 新建任务"
                        else if (fold == HomeFoldMode.Expand) "恢复全局显示" else "临时展开全部内容",
                    onClick = { if (isEmpty) onNewTask() else onToggleFold?.invoke() },
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
            WandInPlaceSwap(contentKey = fold.filtersRunning, enterScale = 1f, exitScale = 1f) { running ->
                val contentFold = if (running == true) HomeFoldMode.Running else HomeFoldMode.Expand
                val visibleTasks = foldVisibleTasks(contentFold, group.tasks)
                val standaloneVisible = foldVisibleSessions(contentFold, group.standaloneSessions)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleTasks.forEachIndexed { index, task ->
                        // 任务块不再有底色，兄弟任务之间用一条发丝线分隔，边界才看得清。
                        if (index > 0) HomeHairline()
                        HomeTaskBlock(
                            task = task,
                            employees = employees,
                            nowMillis = nowMillis,
                            expanded = isTaskSessionsExpanded(
                                fold = contentFold,
                                visibleSessionCount = foldVisibleSessions(contentFold, task.sessions).size,
                                totalSessions = task.totalSessions,
                                isOnlyTask = visibleTasks.size == 1,
                            ),
                            fold = contentFold,
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
                    if (group.standaloneSessions.isNotEmpty() &&
                        (!contentFold.filtersRunning || standaloneVisible.isNotEmpty())
                    ) {
                        if (group.tasks.isNotEmpty()) HomeHairline()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${group.standaloneSessions.size} 个未分组任务",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = WandColors.textMuted,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (foldExpandsContent(contentFold, standaloneVisible.size)) {
                                standaloneVisible.forEachIndexed { index, session ->
                                    HomeSessionRow(
                                        session = session,
                                        employee = employees.firstOrNull { it.id == session.employeeId },
                                        label = listSessionLabel(session.withLiveTitle(), index),
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
    employees: List<SiliconEmployee>,
    nowMillis: Long,
    /** 内容是否显示（不可折叠的层由调用方按规则固定成展开）。 */
    expanded: Boolean,
    /** 这一层的折叠档位，控制选中态读它。 */
    fold: HomeFoldMode,
    selected: Boolean,
    selectedSessionId: String?,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManaged: () -> Unit = {},
    onToggleManagedSession: (String) -> Unit = {},
    onEnterTaskSelection: () -> Unit = {},
    onEnterSessionSelection: (String) -> Unit = {},
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
    // 在跑档只画在跑、失败和待处理的那几条；摘要仍报这一层的真实会话数（「2 个会话 · 1 运行中」）。
    val visibleSessions = foldVisibleSessions(fold, task.sessions)
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
                    visibleSessions.forEachIndexed { index, session ->
                        HomeSessionRow(
                            session = session,
                            employee = employees.firstOrNull { it.id == session.employeeId },
                            label = listSessionLabel(session.withLiveTitle(), index),
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

// MARK: - 最近对话分组

/**
 * 首页「最近对话」的一张分组卡：一级行交代「谁」和「几件事」，二级行是该员工/团队/终端下的会话。
 * 员工单条在全局展开时直接显示；收起/在跑时保留标题以便临时展开。团队与终端始终保留一级入口。
 */
@Composable
internal fun HomeGroupCard(
    group: HomeGroup,
    employees: List<SiliconEmployee>,
    /** 这一组本次浏览的有效档位。 */
    fold: HomeFoldMode,
    onToggleFold: (() -> Unit)? = null,
    nowMillis: Long,
    selectedSessionId: String?,
    selecting: Boolean = false,
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManaged: (String) -> Unit = {},
    onEnterSelection: (String) -> Unit = {},
    onOpenSession: (HomeRecentConversation) -> Unit,
    onDelete: (WorkspaceSessionSummary) -> Unit,
    onMove: (WorkspaceSessionSummary) -> Unit,
    conversationCreating: Boolean = false,
    conversationCreated: Boolean = false,
    conversationEnabled: Boolean = false,
    conversationError: String? = null,
    onCreateConversation: () -> Unit = {},
    terminalMenuOpen: Boolean = false,
    onToggleTerminalMenu: () -> Unit = {},
    onDismissTerminalMenu: () -> Unit = {},
    onSelectTerminalTarget: (WorkspaceSessionTarget) -> Unit = {},
    menuResetKey: Any? = null,
) {
    val reduceMotion = reduceMotionEnabled()
    // 在跑档只画在跑、失败和待处理的那几条；一级行（员工/团队/终端）始终留着。
    val visibleConversations = foldVisibleConversations(fold, group.conversations)
    // 临时展开前后保留同一标题行，避免单会话员工展开后触发点变成会话入口。
    val showsHeader = homeGroupShowsHeader(group) || visibleConversations.isEmpty() || onToggleFold != null
    val menuProgress by animateFloatAsState(
        targetValue = if (terminalMenuOpen) 1f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotion, WandMotion.morph()),
        label = "terminalAddMenuIcon",
    )
    val menuIconAlpha by animateFloatAsState(
        targetValue = if (!conversationCreating &&
            (terminalMenuOpen || (!conversationCreated && conversationError == null))) 1f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotion, WandMotion.tweenFast()),
        label = "terminalAddFeedback",
    )
    val conversationButton: @Composable () -> Unit = {
        if (group.kind == HomeGroupKind.Employee || group.kind.isTerminal) {
            val usesMenu = group.kind == HomeGroupKind.Pty
            val enabled = !selecting && conversationEnabled && !conversationCreating
            val tint = when {
                conversationError != null -> WandColors.danger
                conversationEnabled || conversationCreating || conversationCreated -> WandColors.brand
                else -> WandColors.textMuted.copy(alpha = 0.48f)
            }
            Box(
                modifier = Modifier.size(44.dp).clip(WandShapes.sm)
                    .semantics {
                        contentDescription = when {
                            conversationCreating -> "正在创建${group.title}的对话"
                            terminalMenuOpen -> "关闭 PTY 终端新增菜单"
                            group.kind.isTerminal -> "新建${group.title}"
                            else -> "与${group.title}新建对话"
                        }
                        role = Role.Button
                    }
                    .clickable(enabled = enabled, role = Role.Button,
                        onClick = if (usesMenu) onToggleTerminalMenu else onCreateConversation),
                contentAlignment = Alignment.Center,
            ) {
                WandStatusIconSlot(
                    indicatorColor = tint,
                    containerColor = Color.Transparent,
                    running = conversationCreating,
                    icon = when {
                        conversationError != null -> WandIcons.error
                        conversationCreated -> WandIcons.check
                        else -> WandIcons.add
                    },
                    boxSize = 28.dp,
                    iconSize = 20.dp,
                    iconAlpha = if (usesMenu) 1f - menuIconAlpha else 1f,
                )
                if (usesMenu) {
                    // 同一图标实例承载 ＋/关闭；加载/结果仍留在原来的 44dp 触控盒里。
                    WandMorphingIcon(
                        progress = menuProgress,
                        from = WandIcons.add,
                        to = WandIcons.close,
                        tint = tint,
                        modifier = Modifier.graphicsLayer { alpha = menuIconAlpha },
                    )
                    DropdownMenu(
                        expanded = terminalMenuOpen,
                        onDismissRequest = onDismissTerminalMenu,
                        containerColor = WandColors.bgElevated,
                    ) {
                        WorkspaceSessionTarget.OPTIONS.filterNot { it.isShell }.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.label) },
                                leadingIcon = { WandProviderMark(provider = target.raw,
                                    variant = WandProviderMarkVariant.Tinted) },
                                enabled = enabled,
                                onClick = {
                                    onDismissTerminalMenu()
                                    onSelectTerminalTarget(target)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // 团队与终端始终是「一级 = 分组、二级 = 会话」；员工单条在全局展开时直接显示会话。
        // 例外：在跑档下这一组一条都没露出来时，仍然画一级行（只是没有二级内容），
        // 否则「员工/工作区这些还是保留」就落空了。
        if (!showsHeader) {
            HomeGroupSessionRow(
                conversation = group.conversations.first(),
                employees = employees,
                secondary = false,
                nowMillis = nowMillis,
                selectedSessionId = selectedSessionId,
                selecting = selecting,
                selectedSessionIds = selectedSessionIds,
                onToggleManaged = onToggleManaged,
                onEnterSelection = onEnterSelection,
                onOpenSession = onOpenSession,
                onDelete = onDelete,
                onMove = onMove,
                trailingAction = conversationButton,
                menuResetKey = menuResetKey,
                modifier = if (group.kind == HomeGroupKind.Employee) Modifier.height(64.dp) else Modifier,
            )
        } else {
            HomeGroupHeader(
                group = group,
                onClick = onToggleFold?.takeIf { !selecting && group.conversations.isNotEmpty() },
                actionLabel = if (fold == HomeFoldMode.Expand) "恢复全局显示" else "临时展开全部内容",
                trailingAction = conversationButton,
            )
        }
        WandInlinePanel(visible = conversationError != null, growFrom = Alignment.Top) {
            Text(
                conversationError.orEmpty(),
                color = WandColors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        // 展开就是展开的倒放：从触发点方向上长出来 / 收回去，其他内容顺势下移，不整页重排。
        AnimatedVisibility(
            visible = showsHeader && foldExpandsContent(fold, visibleConversations.size),
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
            WandInPlaceSwap(contentKey = fold.filtersRunning, enterScale = 1f, exitScale = 1f) { running ->
                val contentFold = if (running == true) HomeFoldMode.Running else HomeFoldMode.Expand
                Column(modifier = Modifier.fillMaxWidth()) {
                    foldVisibleConversations(contentFold, group.conversations).forEach { conversation ->
                        key(conversation.session.id) {
                            HomeGroupSessionRow(
                                conversation = conversation,
                                employees = employees,
                                secondary = true,
                                nowMillis = nowMillis,
                                selectedSessionId = selectedSessionId,
                                selecting = selecting,
                                selectedSessionIds = selectedSessionIds,
                                onToggleManaged = onToggleManaged,
                                onEnterSelection = onEnterSelection,
                                onOpenSession = onOpenSession,
                                onDelete = onDelete,
                                onMove = onMove,
                                menuResetKey = menuResetKey,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一级行：头像/图标 + 名字 + 「N 个会话 · 谁在跑」+ 快捷新增。 */
@Composable
private fun HomeGroupHeader(
    group: HomeGroup,
    onClick: (() -> Unit)? = null,
    actionLabel: String = "临时展开全部内容",
    trailingAction: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .clickable(enabled = onClick != null, onClickLabel = actionLabel) { onClick?.invoke() }
            .then(if (group.kind == HomeGroupKind.Employee || group.kind.isTerminal)
                Modifier.height(64.dp) else Modifier)
            .padding(start = 4.dp,
                end = if (group.kind == HomeGroupKind.Employee || group.kind.isTerminal) 0.dp else 4.dp,
                top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (group.kind) {
                HomeGroupKind.Employee -> EmployeeAvatar(
                    group.employeeId, group.title, group.avatar,
                    size = 28.dp)
                // 群聊是个「多人房间」而不是某个 CLI，和群聊会话行用同一个团队图标。
                HomeGroupKind.Team -> Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(WandShapes.sm)
                        .background(WandColors.brandSoft.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        WandIcons.agent,
                        contentDescription = null,
                        tint = WandColors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                }
                // 无员工身份的结构化历史仍属于自己的 CLI，不冒充另一种终端入口。
                HomeGroupKind.Cli -> WandProviderMark(
                    provider = group.conversations.firstOrNull()?.session?.provider,
                    variant = WandProviderMarkVariant.Tinted,
                )
                HomeGroupKind.Pty, HomeGroupKind.BlankTerminal -> Icon(
                    WandIcons.terminal,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
            Text(
                group.title,
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summaryText(false, workspaceSummarySegments(group.conversations.size, group.counts)),
                style = MaterialTheme.typography.labelMedium,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailingAction()
    }
}

/** 分组卡里的一行会话：二级时不再重复员工名，标题说「哪个会话」。 */
@Composable
private fun HomeGroupSessionRow(
    conversation: HomeRecentConversation,
    employees: List<SiliconEmployee>,
    secondary: Boolean,
    nowMillis: Long,
    selectedSessionId: String?,
    selecting: Boolean,
    selectedSessionIds: Set<String>,
    onToggleManaged: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onOpenSession: (HomeRecentConversation) -> Unit,
    onDelete: (WorkspaceSessionSummary) -> Unit,
    onMove: (WorkspaceSessionSummary) -> Unit,
    trailingAction: @Composable () -> Unit = {},
    menuResetKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    val session = conversation.session
    HomeSessionRow(
        session = session,
        employee = employees.firstOrNull { it.id == session.employeeId },
        label = listSessionLabel(session.withLiveTitle(), 0),
        secondary = secondary,
        showEmployeeCliBadge = false,
        nowMillis = nowMillis,
        selected = session.id == selectedSessionId,
        selecting = selecting,
        managedSelected = session.id in selectedSessionIds,
        onToggleManaged = { onToggleManaged(session.id) },
        onEnterSelection = { onEnterSelection(session.id) },
        onClick = { onOpenSession(conversation) },
        onDelete = { onDelete(session) },
        onMove = { onMove(session) },
        trailingAction = trailingAction,
        menuResetKey = menuResetKey,
        modifier = modifier,
    )
}

// MARK: - 会话行

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(
    session: WorkspaceSessionSummary,
    employee: SiliconEmployee? = null,
    label: String,
    /** 二级行：已有一级行交代员工身份，这里不再重复，改为说「哪个会话」。 */
    secondary: Boolean = false,
    /** 最近对话的人物入口不显示某一个 CLI；具体会话仍可显示实际工具。 */
    showEmployeeCliBadge: Boolean = true,
    nowMillis: Long,
    selected: Boolean,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    onToggleManaged: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    trailingAction: @Composable () -> Unit = {},
    menuResetKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(selecting, menuResetKey) { menuOpen = false }
    val live = session.withLiveTitle()
    val status = live.activityStatus()
    // 权限态只在 WS 事件里，轮询摘要没有；首页要显示它必须先看实时 overlay。
    val permissionBlocked = SessionTitleStore.permissionBlockedOf(session.id) == true
    val presentation = wandStatusPresentation(if (permissionBlocked) "permission" else status)
    // 二级行不再重复一级行的头像（同一个员工/团队），只用内缩对齐一级标题，留出「从属」的感觉。
    val rowIndent = if (secondary) 32.dp else 4.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
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
                .padding(start = rowIndent, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                ManageCheck(checked = managedSelected)
            }
            // 联系人优先于底层 CLI；历史会话只依赖创建时的身份快照。
            // 群聊会话是个「多人房间」而不是某个 CLI，用团队图标替掉 provider 标（对齐 Web）。
            val teamChat = session.teamChat
            if (!secondary) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .then(
                            if (teamChat != null) Modifier.clip(WandShapes.sm)
                                .background(WandColors.brandSoft.copy(alpha = 0.55f))
                            else Modifier,
                        )
                        .logoBreathingGlow(presentation, cornerRadius = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (session.employeeId != null) {
                        EmployeeAvatar(session.employeeId,
                            employee?.name ?: session.employeeName,
                            employee?.avatar ?: session.employeeAvatar,
                            size = 28.dp, provider = session.provider.takeIf { showEmployeeCliBadge })
                    } else if (teamChat != null) {
                        Icon(
                            WandIcons.agent,
                            contentDescription = null,
                            tint = WandColors.brand,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        WandProviderMark(provider = session.provider,
                            variant = WandProviderMarkVariant.Tinted,
                        )
                    }
                }
            }
            if (secondary) {
                // 二级行只有一行：标题（是什么东西）+ 团队来源，状态由左侧 logo 呼吸灯表达，取消文字标签。
                Row(
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (session.employeeId != null) {
                        WandProviderMark(provider = session.provider,
                            variant = WandProviderMarkVariant.Tinted,
                            modifier = Modifier.logoBreathingGlow(presentation, cornerRadius = 6.dp))
                    }
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textPrimary,
                        lineHeight = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session.teamStep?.teamName?.takeIf { it.isNotBlank() }
                        ?.let { TeamSourceTag(it) }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        (employee?.name ?: session.employeeName)
                            ?.takeIf { session.employeeId != null } ?: label,
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
                        val teamSource = session.teamStep?.teamName?.takeIf { it.isNotBlank() }
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
                        // 团队派发的会话落在员工名下：补一句来源，免得看不出这是团队让他做的。
                        teamSource?.let { TeamSourceTag(it) }
                        if (session.employeeId != null) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
        trailingAction()
    }
}

/**
 * 列表页 Logo 状态呼吸灯：运行中带一圈绿色呼吸灯，其他状态带对应语义色光圈。
 * 取消列表中的文字标签，由外层光圈表达状态。
 */
@Composable
fun Modifier.logoBreathingGlow(
    presentation: WandStatusPresentation,
    cornerRadius: Dp = 8.dp,
): Modifier {
    if (presentation.normalized in listOf("idle", "exited", "stopped", "archived", "none")) {
        return this
    }
    val glowColor = presentation.tone.statusColor()
    val reduceMotion = reduceMotionEnabled()

    val alpha = if (presentation.breathing && !reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "logo-breathing")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "logo-breathing-alpha",
        )
        animatedAlpha
    } else {
        0.8f
    }

    return this.drawBehind {
        val crPx = cornerRadius.toPx()
        val cr = CornerRadius(crPx, crPx)
        // 外层柔和呼吸光晕
        drawRoundRect(
            color = glowColor.copy(alpha = alpha * 0.38f),
            topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
            size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()),
            cornerRadius = CornerRadius(crPx + 2.dp.toPx(), crPx + 2.dp.toPx()),
        )
        // 核心呼吸光圈
        drawRoundRect(
            color = glowColor.copy(alpha = alpha),
            topLeft = Offset(-1.dp.toPx(), -1.dp.toPx()),
            size = Size(size.width + 2.dp.toPx(), size.height + 2.dp.toPx()),
            cornerRadius = cr,
            style = Stroke(width = 1.8.dp.toPx()),
        )
    }
}

/** 团队派发来源的小标签：`来自 前端组`。 */
@Composable
private fun TeamSourceTag(teamName: String) {
    WandBrandTag("来自 $teamName", style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
}

/**
 * 会话状态胶囊：文字 + 语义色。列表里只有一个小圆点时，
 * 「在跑 / 在等我 / 空闲」的差别要靠颜色去猜；写出来才是产品该有的信息密度。
 */
@Composable
private fun SessionStatusPill(presentation: WandStatusPresentation) {
    val color = presentation.tone.statusColor()
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

// MARK: - 底部悬浮菜单胶囊

/** 根导航保留既有枚举与持久化模式，用户只看到明确的产品概念。 */
internal val HomeMenuPillHeight = 60.dp
internal val HomeMenuPillInset = 4.dp

internal enum class HomeMenuPillItem(val label: String, val description: String) {
    Im("聊天", "打开聊天"),
    Contacts("通讯录", "打开通讯录"),
    Chats("工作区", "打开工作区"),
    Tasks("任务", "打开任务面板");

    val icon: ImageVector
        get() = when (this) {
            Im -> WandIcons.chat
            Contacts -> WandIcons.contacts
            Chats -> WandIcons.workspace
            Tasks -> WandIcons.todo
        }
}

/** 通讯录保留现有返回来源；其余三项切换各自保存的工作路径。 */
internal fun homeMenuPillSelection(mode: HomeListMode): Int = when (mode) {
    HomeListMode.Sessions -> HomeMenuPillItem.Chats.ordinal
    HomeListMode.Tasks -> HomeMenuPillItem.Tasks.ordinal
    HomeListMode.Im -> HomeMenuPillItem.Im.ordinal
}

/** 参考 APP 的细描边悬浮胶囊；字体放大时以实际测量扩高，不压缩文字或触控区域。 */
@Composable
internal fun HomeMenuPill(
    mode: HomeListMode,
    enabled: Boolean,
    onSelect: (HomeMenuPillItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = homeMenuPillSelection(mode)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp)
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()
        .shadow(6.dp, WandShapes.full, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
        .clip(WandShapes.full)
        .background(WandColors.bgElevated.copy(alpha = 0.98f))
        .border(0.5.dp, WandColors.border, WandShapes.full)) {
        val labelWidth = ((maxWidth - HomeMenuPillInset * 2) / HomeMenuPillItem.entries.size - 8.dp).coerceAtLeast(1.dp)
        val labels = HomeMenuPillItem.entries.map {
            textMeasurer.measure(it.label, style = labelStyle, maxLines = 3,
                constraints = Constraints(maxWidth = with(density) { labelWidth.roundToPx().coerceAtLeast(1) }))
        }
        val labelLines = labels.maxOf { it.lineCount }
        val labelHeight = with(density) { labels.maxOf { it.size.height }.toDp() }
        val height = maxOf(HomeMenuPillHeight, 20.dp + 3.dp + labelHeight + 20.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = height).padding(HomeMenuPillInset)) {
            HomeMenuPillItem.entries.forEach { item ->
                HomeMenuPillButton(item, item.ordinal == selectedIndex, labelLines, height - HomeMenuPillInset * 2, enabled) { onSelect(item) }
            }
        }
    }
}

@Composable
private fun RowScope.HomeMenuPillButton(
    item: HomeMenuPillItem,
    selected: Boolean,
    labelLines: Int,
    height: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) WandColors.brandText else WandColors.textSecondary,
        animationSpec = WandMotion.respectMotion(reduceMotionEnabled().not(), WandMotion.tweenFast()),
        label = "homeNavigationColor",
    )
    Column(
        modifier = Modifier.weight(1f).heightIn(min = height)
            .clip(WandShapes.full)
            .background(if (selected) WandColors.surfaceSoft else Color.Transparent)
            .clickable(enabled = enabled, role = if (item == HomeMenuPillItem.Contacts) Role.Button else Role.Tab,
                onClickLabel = item.description, onClick = onClick)
            .semantics {
                contentDescription = if (selected) "当前${item.label}" else item.description
                if (item != HomeMenuPillItem.Contacts) this.selected = selected
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = labelLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// MARK: - 共享微件

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
