@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.smartlife798.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Keep official press motion only where the smallest neighbor can retain its full label. */
@Composable
fun ChoiceButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = AdaptiveChoiceGroup(labels, selectedIndex, onSelect, modifier, enabled, settings = false)

@Composable
fun SettingsChoiceButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) = AdaptiveChoiceGroup(labels, selectedIndex, onSelect, modifier, true, settings = true, icons)

@Composable
private fun AdaptiveChoiceGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    settings: Boolean,
    icons: List<ImageVector?> = emptyList(),
) {
    if (labels.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val style = if (settings) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        else MaterialTheme.typography.labelLarge
    val padding = if (settings) PaddingValues(10.dp) else ToggleButtonDefaults.ContentPadding
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // TextMeasurer caches shaping. Do not subcompose a second, invisible set of
    // Material buttons just to discover their intrinsic widths on every measure.
    val widths = labels.mapIndexed { index, label ->
        val text = measurer.measure(label, style, softWrap = false, maxLines = 1).size.width
        with(density) {
            maxOf(48.dp.roundToPx(), text +
                (padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) +
                    padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)).roundToPx() +
                if (icons.getOrNull(index) != null) 26.dp.roundToPx() else 0)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spacing = with(density) { ButtonGroupDefaults.HorizontalArrangement.spacing.roundToPx() }
        val available = with(density) { maxWidth.roundToPx() }
        val slot = ((available - spacing * (labels.size - 1)) / labels.size).coerceAtLeast(0)
        val minimum = widths.maxOrNull() ?: 0
        val equalWidths = slot >= minimum
        val ratio = if (equalWidths) safePressExpansion(slot, minimum) else 0f
        // alpha14 remembers its measure policy without expandedRatio as a key.
        // Recreate only when size/font changes select a different layout policy.
        key(equalWidths, ratio) {
            ButtonGroup(
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start),
                expandedRatio = ratio,
                verticalAlignment = Alignment.CenterVertically,
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
                            val buttonModifier = (if (equalWidths) Modifier.weight(1f) else Modifier)
                                .animateWidth(interactionSource)
                                .heightIn(min = if (settings) 52.dp else 48.dp)
                                .semantics { selected = isSelected; role = Role.RadioButton }
                            val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                                icon?.let {
                                    Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
                                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                }
                                Text(label, style = style, textAlign = TextAlign.Center,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            if (settings) {
                                TonalToggleButton(
                                    checked = isSelected,
                                    onCheckedChange = { onSelect(index) },
                                    enabled = enabled,
                                    interactionSource = interactionSource,
                                    modifier = buttonModifier,
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
                                    contentPadding = padding,
                                    content = content,
                                )
                            } else {
                                ToggleButton(checked = isSelected,
                                    onCheckedChange = { onSelect(index) }, enabled = enabled,
                                    interactionSource = interactionSource, modifier = buttonModifier,
                                    contentPadding = padding, content = content)
                            }
                        },
                        menuContent = { menu ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                enabled = enabled,
                                leadingIcon = if (icon == null) null else {
                                    { Icon(icon, contentDescription = null) }
                                },
                                modifier = Modifier.semantics { selected = isSelected; role = Role.RadioButton },
                                onClick = { menu.dismiss(); onSelect(index) },
                            )
                        },
                    )
                }
            }
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
