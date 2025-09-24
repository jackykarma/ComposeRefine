package com.jacky.features.imagepreview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * Center-anchored thumbnail carousel with snapping and scale-emphasis for the center item.
 * - Center item scales to 1.1x; spacing to its neighbors doubled.
 * - Emits [onCenteredIndexChange] when the item nearest to the viewport center changes.
 * - Clicking an item notifies [onItemClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThumbnailCarousel(
    urls: List<String>,
    selectedIndex: Int,
    onCenteredIndexChange: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 36.dp,
    baseSpacing: Dp = 1.5.dp,
    heightWidthAspect: Float = 1.6f,          // 宽:高 = 1 : heightFactor
    centerWidthScale: Float = 1.6f,      // 焦点项宽度放大倍数（仅宽度）
    centerGutterScale: Float = 3.0f      // 焦点项左右可见间距 = baseSpacing * centerGutterScale
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    // Staged animation control: shrink old, then expand new
    var expandedIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, if (urls.isEmpty()) 0 else urls.lastIndex)) }
    var shrinkingIndex by remember { mutableIntStateOf(-1) }
    var expandingIndex by remember { mutableIntStateOf(-1) }
    val animDurationMs = 250
    // 标记是否由组件内部发起的“编程滚动/动画”过程，用于抑制回调导致的二次居中
    var programmatic by remember { androidx.compose.runtime.mutableStateOf(false) }
    // 标记是否已经完成初始定位，避免在初始化过程中触发错误的回调
    var hasInitiallyPositioned by remember { androidx.compose.runtime.mutableStateOf(false) }


    // Measure viewport to compute side paddings that place item centers at viewport center
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val itemHeightPx = with(density) { itemHeight.roundToPx() }
    val itemWidthPx = (itemHeightPx * (1f / heightWidthAspect)).toInt()

    // Max footprint width seen by LazyRow: use the maximum content width (center scaled), keep padding stable
    val containerWidthPx = remember(
        itemHeightPx,
        heightWidthAspect,
        centerWidthScale
    ) {
        (itemWidthPx * centerWidthScale).toInt()
    }
    // sidePaddingPx 是“缩略图列表（LazyRow）左右两侧的内容内边距”的像素值。
    val sidePaddingPx = remember(viewportWidthPx, containerWidthPx) {
        (viewportWidthPx / 2 - containerWidthPx / 2).coerceAtLeast(0)
    }
    val sidePaddingDp = with(density) { sidePaddingPx.toDp() }

    // Snap to center using SnapFlingBehavior with center anchor
    val layoutInfoProvider = remember(listState) {
        SnapLayoutInfoProvider(listState, snapPosition = SnapPosition.Center)
    }
    val flingBehavior = rememberSnapFlingBehavior(layoutInfoProvider)

    // Keep list aligned to selected index; run animations in two phases with partial overlap:
    // - Start shrinking old focus immediately (in parallel) while scrolling
    // - After scroll finishes, expand the new focus
    LaunchedEffect(selectedIndex, sidePaddingPx) {
        // Only when we have data, not already scrolling, and padding has been measured
        if (urls.isEmpty() || listState.isScrollInProgress || sidePaddingPx <= 0) return@LaunchedEffect

        programmatic = true
        try {
            // 0) If we're already centered on the target, skip scrolling to avoid jitter
            val currentCenter = centerItemIndex(listState.layoutInfo)
            val needScroll = currentCenter != selectedIndex

            // 1) If target differs, trigger old focus shrinking BEFORE scrolling, so it runs in parallel
            if (expandedIndex != selectedIndex) {
                val old = expandedIndex
                if (old >= 0 && old != selectedIndex) {
                    // mark old as shrinking and clear expanded state
                    shrinkingIndex = old
                    expandedIndex = -1
                    // clear shrinking flag after the single-stage duration, in parallel
                    launch {
                        kotlinx.coroutines.delay(animDurationMs.toLong())
                        if (shrinkingIndex == old) shrinkingIndex = -1
                    }
                }
            }

            // 2) Scroll to target index only if needed (suspends until finished)
            if (needScroll) {
                listState.animateScrollToItem(selectedIndex)
            }

            // 3) After scroll completes, expand the new focus if still needed
            if (expandedIndex != selectedIndex) {
                expandingIndex = selectedIndex
                kotlinx.coroutines.delay(animDurationMs.toLong())
                expandedIndex = selectedIndex
                expandingIndex = -1
            }

            // 4) Mark as initially positioned after first successful scroll
            if (!hasInitiallyPositioned) {
                hasInitiallyPositioned = true
            }
        } finally {
            programmatic = false
        }
    }

    // Observe center item changes and notify (only when not scrolling to avoid oscillation)
    var centerIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(listState, selectedIndex, programmatic, hasInitiallyPositioned) {
        snapshotFlow { if (!listState.isScrollInProgress) centerItemIndex(listState.layoutInfo) else -1 }
            .distinctUntilChanged()
            .collect { idx ->
                if (idx in urls.indices) {
                    centerIndex = idx
                    // 仅在非编程滚动、已完成初始定位且与当前 selectedIndex 不同的情况下上报，避免反馈环导致二次居中
                    if (!programmatic && hasInitiallyPositioned && idx != selectedIndex) {
                        onCenteredIndexChange(idx)
                    }
                }
            }
    }

    Box(modifier.onSizeChanged { viewportWidthPx = it.width }) {
        LazyRow(
            state = listState,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(baseSpacing),
            contentPadding = PaddingValues(horizontal = sidePaddingDp),
            flingBehavior = flingBehavior
        ) {
            itemsIndexed(urls, key = { i, _ -> i }) { index, url ->
                val baseHeight = remember(itemHeight) { itemHeight }
                val baseWidthPx = with(density) { baseHeight.toPx() * (1 / heightWidthAspect) }
                val baseWidth = with(density) { baseWidthPx.toDp() }

                // Determine staged targets for this item
                val isExpanding = index == expandingIndex
                val isExpanded = index == expandedIndex
                val isShrinking = index == shrinkingIndex

                val targetScale = when {
                    isExpanding || isExpanded -> centerWidthScale
                    isShrinking -> 1f
                    else -> 1f
                }
                val targetInnerGutter = when {
                    isExpanding || isExpanded -> baseSpacing * centerGutterScale
                    isShrinking -> baseSpacing
                    else -> baseSpacing
                }

                val scaleX by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = tween(durationMillis = animDurationMs, easing = LinearEasing),
                    label = "thumb_scale_x"
                )
                val innerGutter by animateDpAsState(
                    targetValue = targetInnerGutter,
                    animationSpec = tween(durationMillis = animDurationMs, easing = LinearEasing),
                    label = "thumb_inner_gutter"
                )

                val targetWidthFocus = when {
                    isExpanding || isExpanded -> baseWidth * centerWidthScale
                    isShrinking -> baseWidth
                    else -> baseWidth
                }
                val animatedWidthFocus by animateDpAsState(
                    targetValue = targetWidthFocus,
                    animationSpec = tween(durationMillis = animDurationMs, easing = LinearEasing),
                    label = "thumb_width_focus"
                )

                // Dynamic outer width: base + staged inner gutter on focused only
                val outerWidth = animatedWidthFocus + innerGutter * 2
                Box(
                    modifier = Modifier
                        .width(outerWidth)
                        .height(baseHeight)
                        .animateItem(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(animatedWidthFocus)
                            .height(baseHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        // Image的宽高保持不变，仅在绘制阶段做scale处理
                        Box(
                            modifier = Modifier
                                .height(baseHeight)
                                .width(baseWidth)
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin.Center
                                    this.scaleX = scaleX
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url)
                                    .precision(Precision.INEXACT)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.Black.copy(alpha = 0.1f))
                                    .matchParentSize()
                                    .clickableNoIndication { onItemClick(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun centerItemIndex(info: androidx.compose.foundation.lazy.LazyListLayoutInfo): Int {
    if (info.visibleItemsInfo.isEmpty()) return -1
    val center = info.viewportStartOffset + info.viewportSize.width / 2
    var minDist = Int.MAX_VALUE
    var idx = -1
    for (item in info.visibleItemsInfo) {
        val c = item.offset + item.size / 2
        val d = kotlin.math.abs(c - center)
        if (d < minDist) {
            minDist = d; idx = item.index
        }
    }
    return idx
}

private fun distanceCloseness(distancePx: Int, itemSizePx: Int): Float {
    if (distancePx == Int.MAX_VALUE) return 0f
    val d = kotlin.math.abs(distancePx).toFloat()
    val norm = (1f - (d / (itemSizePx.toFloat())))
    return norm.coerceIn(0f, 1f)
}

@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    ) { onClick() }

