package com.jacky.features.imagepreview

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.draw.clipToBounds

import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.ContentScale


import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

import androidx.compose.material.icons.Icons


/**
 * 预览大图分页组件（覆盖在媒体网格列表之上）。
 *
 * 功能概述：
 * - 入场共享边界：从列表缩略图的 bounds 动画到预览初始位置；入场期间隐藏上下菜单；背景由 0->1 渐显。
 * - 交互预览：支持左右分页预览；图片可缩放/拖拽（放大态时禁用左右分页以避免冲突）。
 * - 下拉返回（非放大态）：垂直拖拽时图片随距离轻微缩小，背景按距离逐步变透明；松手后若超过阈值则触发反向共享边界动画缩回列表项，否则回弹复位。
 * - 退出共享边界：反向动画使用 ContentScale.Crop，确保回到列表缩图时 1:1 贴合无留边；退出过程中背景 1->0 渐隐。
 *
 * 参数说明：
 * @param urls                需要预览的图片 URL 列表
 * @param startIndex          初始展示的页索引
 * @param maxScale            单张图片的最大缩放倍数（例如 3f）
 * @param onBack              退出回调；在反向动画结束后调用，用于关闭 overlay
 * @param entryStartBounds    入场起点的窗口坐标 bounds（字符串编码），用于共享边界过渡
 * @param dragAlphaFactor     下拉时背景透明度衰减系数（0..1，越大越快变透明）
 * @param maxDragScaleReduction 下拉时图片最大缩放收缩比例（0..1，0.30 = 最多缩到 70%）
 * @param dragEasingPower     下拉缓动幂次（2=二次，3=三次，数值越大前半段越慢、越温和）
 * @param dismissThresholdDp  触发返回的垂直拖拽距离阈值（dp，越小越容易触发）
 */

@Composable
fun ImagePagerScreen(

    urls: List<String>,
    startIndex: Int = 0,
    maxScale: Float = 3f,
    onBack: (() -> Unit)? = null,
    entryStartBounds: String? = null,
    // Drag behavior tuning
    dragAlphaFactor: Float = 0.6f,            // 0..1, larger -> alpha下降更快
    maxDragScaleReduction: Float = 0.30f,     // 0..1, 0.30 = 最多缩小到 70%
    dragEasingPower: Float = 3f,              // 2=二次,3=三次, 数值越大前段越慢
    dismissThresholdDp: Dp = 32.dp            // 触发返回的下拉距离阈值（更小=更容易触发）
) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { urls.size.coerceAtLeast(1) })
    val immersive = rememberImmersiveController(initial = false)
    // Container and current image info for transitions/dismiss logic
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var currentImageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var currentScale by remember { mutableFloatStateOf(1f) }

    // Entry shared-bounds transition
    val startBoundsStr = remember(entryStartBounds) { entryStartBounds?.takeIf { it.isNotBlank() } }
    val entryStartRectPx: BoundsPx? = remember(startBoundsStr) { parseBounds(startBoundsStr) }
    var entryOverlayVisible by remember { mutableStateOf(entryStartRectPx != null) }
    val scope = rememberCoroutineScope()

    val entryProgress = remember { androidx.compose.animation.core.Animatable(0f) }

    // Exit reverse transition trigger
    var exitOverlayVisible by remember { mutableStateOf(false) }
    val exitProgress = remember { androidx.compose.animation.core.Animatable(0f) }

    // Drag-to-dismiss
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { dismissThresholdDp.toPx() }
    // Handle normal back: when not immersive, perform reverse animation to grid (if possible)
    if (onBack != null) {
        BackHandler(enabled = !immersive.value) {
            scope.launch {
                if (entryStartRectPx != null) {
                    exitOverlayVisible = true
                    immersive.value = true
                    exitProgress.snapTo(0f)
                    exitProgress.animateTo(1f, tween(260))
                    onBack()
                } else {
                    onBack()
                }
            }
        }
    }

    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }



    ImmersiveSystemBarsEffect(immersive = immersive.value)
    ImmersiveBackHandler(immersive)

    // Controls alpha (back + thumbnails)
    val controlsAlpha by animateFloatAsState(targetValue = if (immersive.value) 0f else 1f, label = "controls_alpha")

    // Thumbnail center sync state
    var centeredThumbIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, urls.lastIndex)) }

    // Track the current selected index for thumbnail carousel
    var thumbnailSelectedIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, urls.lastIndex)) }
    Log.d(TAG, "ImagePagerScreen: thumbnailSelectedIndex:$thumbnailSelectedIndex")

    // Track if we're in the initial setup phase
    var isInitialized by remember { mutableStateOf(false) }

    // Update thumbnail selected index when pager state changes
    LaunchedEffect(pagerState.currentPage) {
        Log.d(TAG, "ImagePagerScreen: thumbnailSelectedIndex:$thumbnailSelectedIndex, pagerState.currentPage:${pagerState.currentPage}")
        thumbnailSelectedIndex = pagerState.currentPage
    }

    // Mark as initialized after the pager has settled on the correct initial page
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage == startIndex) {
            isInitialized = true
            Log.d(TAG, "ImagePagerScreen: initialized at page ${pagerState.currentPage}")
        }
    }

    // 当图片未放大（scale<=1f）时允许 Pager 滑动以实现“边滑边预览”；放大时禁用 Pager 滑动
    var allowPagerScroll by remember { mutableStateOf(true) }

    // 下拉交互相关的实时系数：
    // 1) dragFraction：基于垂直拖拽距离与阈值（像素）计算的 0..1 进度；仅在 dragging=true 时生效
    // 2) easedDrag：对 dragFraction 施加幂次缓动（dragEasingPower），使前半段更温和、后半段更明显
    // 3) baseAlpha：页面背景的基础透明度——入场阶段 0->1、退出阶段 1->0、普通态为 1
    // 4) bgAlpha：叠加“下拉导致的额外透明度”后得到的最终背景透明度；dragAlphaFactor 决定透明度下降速度
    // 5) dragScale：根据 easedDrag 将图片按比例缩小；maxDragScaleReduction 控制最大缩放收缩比例
    val dragFraction = if (dragging) (kotlin.math.abs(dragY) / dismissThresholdPx).coerceIn(0f, 1f) else 0f
    val easedDrag = powf(dragFraction, dragEasingPower)
    val baseAlpha = when {
        exitOverlayVisible -> 1f - exitProgress.value
        entryOverlayVisible -> entryProgress.value
        else -> 1f
    }
    val bgAlpha = (baseAlpha * (1f - dragAlphaFactor * easedDrag)).coerceIn(0f, 1f)
    val dragScale = 1f - maxDragScaleReduction * easedDrag

    var rootWindowOffset by remember { mutableStateOf(IntOffset(0, 0)) }

    Box(Modifier
        .fillMaxSize()
        .onSizeChanged { containerSize = it }
        .onGloballyPositioned { coords ->
            val p = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
            //
            // 拖拽手势规则（仅在当前图片未放大/未旋转等复杂变换时启用下拉返回）：
            // - 仅统计“纵向占优”的拖拽（|dy| >= |dx|），避免与左右分页冲突
            // - dismissThresholdPx 来自 dismissThresholdDp（dp -> px），用于“触发返回”的距离判断
            // - 过程内实时更新 dragY；松手时根据 |dragY| 与阈值比较来决定“回弹”还是“触发返回”

            rootWindowOffset = IntOffset(p.x.toInt(), p.y.toInt())
        }
        .background(MaterialTheme.colorScheme.background.copy(alpha = bgAlpha))) {
        // Pager
        // Debug: pager allow and scrolling progress
        LaunchedEffect(allowPagerScroll) {
            Log.d(TAG, "ImagePagerScreen: allowPagerScroll=$allowPagerScroll")
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { Triple(pagerState.currentPage, pagerState.currentPageOffsetFraction, pagerState.isScrollInProgress) }
                .collectLatest { (page, offset, inProgress) ->
                    Log.d(TAG, "ImagePagerScreen: pager page=$page, offset=$offset, scrolling=$inProgress")
                }
        }

        HorizontalPager(state = pagerState, userScrollEnabled = allowPagerScroll, modifier = Modifier
            .fillMaxSize()
            .alpha(if (entryOverlayVisible || exitOverlayVisible) 0f else 1f)
            .graphicsLayer {
            // 下拉手势检测：
            // - onDragStart：仅在 currentScale<=1f（未放大）时进入“下拉模式”，并暂时开启沉浸以免系统栏闪动
            // - onDrag：仅处理纵向占优的拖拽，累加 dragY；消费事件以避免传递给分页
            // - onDragEnd：|dragY| > dismissThresholdPx 时触发退出动画，否则回弹到原位

                translationY = dragY
                scaleX = dragScale
                scaleY = dragScale
            }
            .pointerInput(currentScale, dismissThresholdPx) {
                detectDragGestures(
                    onDragStart = {
                        if (currentScale <= 1f) {
                            dragging = true
                            immersive.value = true
                        }
                    },
                    onDragEnd = {
                        val absY = kotlin.math.abs(dragY)
                        if (dragging && absY > dismissThresholdPx) {
                            // Trigger exit reverse animation
                            scope.launch {
                                if (onBack != null) {
                                    if (entryStartRectPx != null) {
                                        exitOverlayVisible = true
                                        immersive.value = true
                                        exitProgress.snapTo(0f)
                                        exitProgress.animateTo(1f, tween(260))
                                        onBack()
                                    } else {
                                        onBack()
                                    }
                                }
                            }
                        } else {
                            // Restore
                            scope.launch { Animatable(dragY).animateTo(0f) { dragY = value } }
                            immersive.value = false
                        }
                        dragging = false
                    },
                ) { change, dragAmount ->
                    if (!dragging && currentScale > 1f) return@detectDragGestures
                    if (kotlin.math.abs(dragAmount.y) >= kotlin.math.abs(dragAmount.x)) {
                        dragY += dragAmount.y
                        change.consume()
                    }
                }
            }
        ) { page ->

            val model = urls.getOrNull(page)
            Box(Modifier.fillMaxSize().clipToBounds()) {
                ZoomableImage(
                    model = model,
                    contentDescription = "Image $page",
                    modifier = Modifier.fillMaxSize(),
                    maxScale = maxScale,
                    onTap = { immersive.value = !immersive.value },
                    onPinchStart = { immersive.value = true },
                    requestPageChange = { delta ->
                        val target = (pagerState.currentPage + delta).coerceIn(0, pagerState.pageCount - 1)
                        if (target != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(target) }
                        }
                    },
                    onAllowPagerScrollChange = { allow -> allowPagerScroll = allow },
                    onLowResLoaded = { size -> if (page == pagerState.currentPage) currentImageSize = size },
                    onScaleChanged = { s -> if (page == pagerState.currentPage) currentScale = s }
                )
            }
        }

        // Top bar: full-width background + back button
        if (onBack != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(controlsAlpha)
            ) {
                Box(
                    modifier = Modifier
                        .statusBarsPadding()

                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Convert start bounds from window to local coordinates of this Box
        val entryStartRectLocal = remember(entryStartRectPx, rootWindowOffset) {
            entryStartRectPx?.offset(-rootWindowOffset.x, -rootWindowOffset.y)
        }

        // Compute end rect: prefer fit-to-container if image size known; otherwise fallback to full container
        val entryEndIsFallback = remember(containerSize, currentImageSize) {
            containerSize.width > 0 && containerSize.height > 0 && (currentImageSize.width == 0 || currentImageSize.height == 0)
        }
        val entryEndRectPx = remember(containerSize, currentImageSize) {
            if (containerSize.width > 0 && containerSize.height > 0) {
                if (!entryEndIsFallback) {
                    fitRectPx(containerSize, currentImageSize)
                } else {
                    BoundsPx(0, 0, containerSize.width, containerSize.height)
                }
            } else null
        }

        // Entry shared bounds overlay and animation
        var entryRan by remember { mutableStateOf(false) }
        LaunchedEffect(entryOverlayVisible, startBoundsStr, containerSize, currentImageSize) {
            if (entryOverlayVisible && !entryRan) {
                // Ensure start rect and container ready
                if (entryStartRectLocal == null || containerSize.width == 0 || containerSize.height == 0) return@LaunchedEffect

                // If measured size is not yet available, wait a short timeout
                if (currentImageSize.width == 0 || currentImageSize.height == 0) {
                    withTimeoutOrNull(250) {
                        snapshotFlow { currentImageSize }
                            .first { it.width > 0 && it.height > 0 }
                    }
                }

                val endOk = entryEndRectPx != null
                if (endOk) {
                    immersive.value = true
                    entryProgress.snapTo(0f)
                    entryRan = true
                    entryProgress.animateTo(1f, tween(280))
                    entryOverlayVisible = false
                    immersive.value = false
                }
            }
        }

        if (entryOverlayVisible && entryStartRectLocal != null) {
            val rect = if (entryEndRectPx != null) lerpRect(entryStartRectLocal, entryEndRectPx, entryProgress.value) else entryStartRectLocal
            // Use Crop during entry to ensure the overlay always fills the rect (matches grid item visual)
            SharedBoundsOverlay(model = urls.getOrNull(startIndex), rect = rect, contentScale = ContentScale.Crop)
        }

        // Exit reverse overlay
        val exitStartRectPx = remember(containerSize, currentImageSize) {
            if (containerSize.width > 0 && containerSize.height > 0) {
                if (currentImageSize.width > 0 && currentImageSize.height > 0) {
                    fitRectPx(containerSize, currentImageSize)
                } else {
                    BoundsPx(0, 0, containerSize.width, containerSize.height)
                }
            } else null
        }
        if (exitOverlayVisible && entryStartRectLocal != null && exitStartRectPx != null) {
            val rect = lerpRect(exitStartRectPx, entryStartRectLocal, exitProgress.value)
            SharedBoundsOverlay(model = urls.getOrNull(pagerState.currentPage), rect = rect, contentScale = ContentScale.Crop)
        }

        // Bottom thumbnails with full-width background
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier

                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(64.dp)
                .alpha(controlsAlpha)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                ThumbnailCarousel(
                    urls = urls,
                    selectedIndex = thumbnailSelectedIndex,
                    onCenteredIndexChange = { idx ->
                        Log.d(TAG, "ImagePagerScreen: onCenteredIndexChange called with idx=$idx, isInitialized=$isInitialized")
                        centeredThumbIndex = idx
                    },
                    onItemClick = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                )
            }
        }



        // Sync: when pager settles, center thumbnails
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .collectLatest { (page, inProgress) ->
                    if (!inProgress && page != centeredThumbIndex) {
                        centeredThumbIndex = page
                    }
                }
        }
        // Sync: when thumbnails center changes (and pager idle), page to it
        // Only respond to thumbnail changes after initial setup is complete
        LaunchedEffect(centeredThumbIndex, isInitialized) {
            if (isInitialized && !pagerState.isScrollInProgress && centeredThumbIndex != pagerState.currentPage) {
                Log.d(TAG, "ImagePagerScreen: thumbnail triggered page change to $centeredThumbIndex")
                scope.launch { pagerState.animateScrollToPage(centeredThumbIndex) }
            }
        }
    }
}



// Helpers
private data class BoundsPx(val l: Int, val t: Int, val w: Int, val h: Int)

private fun parseBounds(s: String?): BoundsPx? {
    if (s.isNullOrBlank()) return null
    return try {
        val parts = s.split(',').map { it.trim() }
        if (parts.size >= 4) BoundsPx(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt()) else null
    } catch (_: Throwable) { null }
}

private fun fitRectPx(container: IntSize, image: IntSize): BoundsPx {
    val cw = container.width.toFloat().coerceAtLeast(1f)
    val ch = container.height.toFloat().coerceAtLeast(1f)
    val iw = image.width.toFloat().coerceAtLeast(1f)
    val ih = image.height.toFloat().coerceAtLeast(1f)
    val scale = kotlin.math.min(cw / iw, ch / ih)
    val w = (iw * scale).toInt()
    val h = (ih * scale).toInt()
    val l = ((cw - w) / 2f).toInt()
    val t = ((ch - h) / 2f).toInt()
    return BoundsPx(l, t, w, h)
}

private fun BoundsPx.offset(dx: Int, dy: Int) = BoundsPx(l + dx, t + dy, w, h)

private fun lerpRect(a: BoundsPx, b: BoundsPx, p: Float): BoundsPx {
    val t = p.coerceIn(0f, 1f)
    fun lerpInt(x: Int, y: Int): Int = (x + (y - x) * t).toInt()
    return BoundsPx(
        l = lerpInt(a.l, b.l),
        t = lerpInt(a.t, b.t),
        w = lerpInt(a.w, b.w),
        h = lerpInt(a.h, b.h)
    )
}

@Composable
private fun BoxScope.SharedBoundsOverlay(model: String?, rect: BoundsPx, contentScale: ContentScale = ContentScale.Fit) {
    val density = LocalDensity.current
    val wDp = with(density) { rect.w.toDp() }
    val hDp = with(density) { rect.h.toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val req = remember(model, rect) {
            coil.request.ImageRequest.Builder(context)
                .data(model)

                .size(coil.size.Size(rect.w, rect.h))
                .precision(coil.size.Precision.INEXACT)
                .scale(coil.size.Scale.FILL)
                .build()
        }
        coil.compose.AsyncImage(
            model = req,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .offset { IntOffset(rect.l, rect.t) }
                .size(wDp, hDp)
        )
    }
}


private fun powf(x: Float, p: Float): Float = Math.pow(x.toDouble(), p.toDouble()).toFloat()
