package com.xnotes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.core.workspace.NoteTabs
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Independent workspace bar. It depends on tab metadata and actions, not on the page toolbar,
 * canvas, or Editor. A future customizable toolbar host can mount or replace this component.
 */
@Composable
fun NoteTabBar(
    tabs: NoteTabs,
    activeUri: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    floatingSurface: Boolean = false,
) {
    val palette = LocalPalette.current
    // All tabs are equal-width, so the indicator can be positioned arithmetically.
    // Avoid feeding onGloballyPositioned measurements into Compose state each animation frame.
    val highlight = palette.selectionBackground.toComposeColor()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val visibleSlots = visibleTabSlots(maxWidth.value, tabs.tabs.size)
        val tabWidth = maxWidth / visibleSlots
        val animatedWidth by animateDpAsState(tabWidth, tween(240), label = "tabWidth")
        val activeIndex = tabs.tabs.indexOfFirst { it.uri == activeUri }
        val density = LocalDensity.current
        val (highlightXDp, highlightWidthDp) = tabHighlightBounds(animatedWidth.value, activeIndex)
        val targetX = with(density) { highlightXDp.dp.toPx() }
        val targetWidth = with(density) { highlightWidthDp.dp.toPx() }
        val highlightMotion = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)
        val highlightX by animateFloatAsState(targetX, highlightMotion, label = "tabHighlightX")
        val highlightWidth by animateFloatAsState(targetWidth, highlightMotion, label = "tabHighlightWidth")
    Row(
        Modifier.fillMaxWidth()
            .background((if (floatingSurface) palette.panel else palette.bg).toComposeColor())
            .horizontalScroll(rememberScrollState())
            .height(42.dp)
            .drawBehind {
                if (activeIndex >= 0 && highlightWidth > 0f) {
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        highlight,
                        topLeft = Offset(highlightX, inset),
                        size = Size(highlightWidth, size.height - inset * 2),
                        cornerRadius = CornerRadius((size.height - inset * 2) / 2),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEach { tab ->
            key(tab.uri) {
            val tabTint by animateColorAsState(
                targetValue = if (tab.uri == activeUri) palette.selectionForeground.toComposeColor()
                    else palette.textDim.toComposeColor(),
                animationSpec = tween(300),
                label = "tabTint",
            )
            Row(
                Modifier.width(animatedWidth)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).height(40.dp)
                        .clickable(
                            interactionSource = remember(tab.uri) { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab.uri) },
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isCanvas = tab.uri.substringBefore('?').endsWith(".xcanvas", ignoreCase = true) ||
                        tab.title.endsWith(".xcanvas", ignoreCase = true)
                    Icon(
                        imageVector = if (isCanvas) Icons.Outlined.Gesture else Icons.AutoMirrored.Outlined.Article,
                        contentDescription = if (isCanvas) "Canvas" else "Note",
                        tint = tabTint,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        tab.title.replace(Regex("(?i)\\.(xnote|xcanvas)$"), ""),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = tabTint,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
                TextButton(onClick = { onClose(tab.uri) }, modifier = Modifier.width(36.dp)) {
                    Text("×", fontSize = 16.sp, color = tabTint)
                }
            }
            }
        }
    }
    }
}

/** Equal-width tabs until five fit, but never below 144 dp per visible tab. */
internal fun visibleTabSlots(widthDp: Float, tabCount: Int): Int =
    minOf(tabCount.coerceAtLeast(1), 5, (widthDp / 144f).toInt().coerceAtLeast(1))

/** Stable indicator geometry independent of animation frames and removed-tab measurements. */
internal fun tabHighlightBounds(tabWidthDp: Float, activeIndex: Int): Pair<Float, Float> =
    (activeIndex.coerceAtLeast(0) * tabWidthDp + 2f) to (tabWidthDp - 4f).coerceAtLeast(0f)
