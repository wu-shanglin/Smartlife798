@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.smartlife798.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val PressExpansion = 0.25f

/** Official button group provides linked press motion and accessible overflow on narrow screens. */
@Composable
fun ChoiceButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ButtonGroup(
        // alpha14 narrows maxWidth when measuring overflow without lowering minWidth.
        // Keep the outer row full-width, but give the group a loose minimum width.
        modifier = modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
        expandedRatio = PressExpansion,
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
                onCheckedChange = { if (enabled) onSelect(index) },
                enabled = enabled,
            )
        }
    }
}

/** Settings keep their subdued selection styling while sharing official neighbor press motion. */
@Composable
fun SettingsChoiceButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    ButtonGroup(
        modifier = modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
        expandedRatio = PressExpansion,
        overflowIndicator = { menu ->
            FilledTonalIconButton(onClick = { menu.show() }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多选项")
            }
        },
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = selectedIndex == index
            val icon = icons.getOrNull(index)
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    TonalToggleButton(
                        checked = isSelected,
                        onCheckedChange = { onSelect(index) },
                        interactionSource = interactionSource,
                        modifier = Modifier.weight(1f).animateWidth(interactionSource)
                            .heightIn(min = 52.dp).semantics {
                                selected = isSelected
                                role = Role.RadioButton
                            },
                        shapes = ToggleButtonDefaults.shapes(
                            shape = RoundedCornerShape(14.dp),
                            checkedShape = RoundedCornerShape(14.dp),
                        ),
                        colors = ToggleButtonDefaults.tonalToggleButtonColors(
                            containerColor = colors.surfaceContainerLow,
                            checkedContainerColor = colors.primaryContainer,
                            contentColor = colors.onSurface,
                            checkedContentColor = colors.onSurface,
                        ),
                        contentPadding = PaddingValues(10.dp),
                    ) {
                        icon?.let {
                            Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
                menuContent = { menu ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = if (icon == null) null else {
                            { Icon(icon, contentDescription = null) }
                        },
                        modifier = Modifier.semantics {
                            selected = isSelected
                            role = Role.RadioButton
                        },
                        onClick = {
                            menu.dismiss()
                            onSelect(index)
                        },
                    )
                },
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        content = content,
    )
}
