package com.wand.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** 供首页会话和任务列表共用；辅助功能也能直接触发刷新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WandPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction("刷新当前列表") {
                if (!isRefreshing) onRefresh()
                true
            })
        },
        content = content,
    )
}
