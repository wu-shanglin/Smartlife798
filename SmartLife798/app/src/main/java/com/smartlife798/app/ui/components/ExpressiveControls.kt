@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.smartlife798.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Official button group provides linked press motion and accessible overflow on narrow screens. */
@Composable
fun ChoiceButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(
        // alpha14 narrows maxWidth when measuring overflow without lowering minWidth.
        // Keep the outer row full-width, but give the group a loose minimum width.
        modifier = modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
        overflowIndicator = { menu ->
            FilledTonalIconButton(onClick = { menu.show() }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多选项")
            }
        },
    ) {
        labels.forEachIndexed { index, label ->
            toggleableItem(
                checked = selectedIndex == index,
                label = label,
                onCheckedChange = { onSelect(index) },
            )
        }
    }
}

/** The same gesture state drives pull distance, release and the Expressive loading morph. */
@Composable
fun ExpressiveRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}
