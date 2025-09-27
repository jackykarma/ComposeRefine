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
    // 下拉/返回相关的可调参数
    dragAlphaFactor: Float = 0.6f,            // 0..1，数值越大透明度下降更快
    maxDragScaleReduction: Float = 0.30f,     // 0..1, 0.30 = 最多缩小到 70%
    dragEasingPower: Float = 3f,              // 2=二次,3=三次, 数值越大前段越慢
    dismissThresholdDp: Dp = 32.dp            // 触发返回的下拉距离阈值（更小=更容易触发）
) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { urls.size.coerceAtLeast(1) })
    val immersive = rememberImmersiveController(initial = false)
    // 容器尺寸/当前图片信息：用于过渡与下拉返回逻辑
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var currentImageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var currentScale by remember { mutableFloatStateOf(1f) }

    // 入场共享边界过渡
    val startBoundsStr = remember(entryStartBounds) { entryStartBounds?.takeIf { it.isNotBlank() } }
    val entryStartRectPx: BoundsPx? = remember(startBoundsStr) { parseBounds(startBoundsStr) }
    var entryOverlayVisible by remember { mutableStateOf(entryStartRectPx != null) }
    val scope = rememberCoroutineScope()

    val entryProgress = remember { androidx.compose.animation.core.Animatable(0f) }

    // 退出反向过渡触发器
    var exitOverlayVisible by remember { mutableStateOf(false) }
    val exitProgress = remember { androidx.compose.animation.core.Animatable(0f) }

    // 统一的“触发退出共享边界动画”逻辑（供系统返回键与顶部返回键复用）
    val triggerExit: (() -> Unit)? = onBack?.let {
        {
            scope.launch {
                if (entryStartRectPx != null) {
                    exitOverlayVisible = true
                    immersive.value = true
                    exitProgress.snapTo(0f)
                    exitProgress.animateTo(1f, tween(260))
                    it()
                } else {
                    it()
                }
            }
        }
    }

    // 下拉拖拽退出
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { dismissThresholdDp.toPx() }
    // 处理系统返回：非沉浸态时，触发反向共享边界动画返回到网格
    if (onBack != null) {
        BackHandler(enabled = !immersive.value) {
            triggerExit?.invoke()
        }
    }

    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }



    // 控制栏透明度（返回按钮 + 底部缩略图）
    val controlsAlpha by animateFloatAsState(targetValue = if (immersive.value) 0f else 1f, label = "controls_alpha")

    // 缩略图居中同步状态
    var centeredThumbIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, urls.lastIndex)) }

    // 跟踪当前选中的缩略图索引
    var thumbnailSelectedIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, urls.lastIndex)) }
    Log.d(TAG, "ImagePagerScreen: thumbnailSelectedIndex:$thumbnailSelectedIndex")

    // 标记是否处于初始设置阶段
    var isInitialized by remember { mutableStateOf(false) }

    // 在分页状态变化时更新缩略图选中索引
    LaunchedEffect(pagerState.currentPage) {
        Log.d(TAG, "ImagePagerScreen: thumbnailSelectedIndex:$thumbnailSelectedIndex, pagerState.currentPage:${pagerState.currentPage}")
        thumbnailSelectedIndex = pagerState.currentPage
    }

    // 当 Pager 稳定在初始页后标记初始化完成
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
        // 图片分页区域
        // 调试：分页可滚动状态与滚动进度
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
                            // 触发反向共享边界退出动画
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
                            // 回弹复位
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

        // 顶部栏：铺满背景 + 返回按钮
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
                    IconButton(onClick = { triggerExit?.invoke() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 将窗口坐标的起始 bounds 转换为当前 Box 的本地坐标
        val entryStartRectLocal = remember(entryStartRectPx, rootWindowOffset) {
            entryStartRectPx?.offset(-rootWindowOffset.x, -rootWindowOffset.y)
        }

        // 计算终点矩形：优先按图片尺寸适配容器，否则回退为铺满容器
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

        // 入场共享边界覆盖层与动画
        var entryRan by remember { mutableStateOf(false) }
        LaunchedEffect(entryOverlayVisible, startBoundsStr, containerSize, currentImageSize) {
            if (entryOverlayVisible && !entryRan) {
                // 确保起点矩形与容器已就绪
                if (entryStartRectLocal == null || containerSize.width == 0 || containerSize.height == 0) return@LaunchedEffect

                // 若图片尺寸尚未就绪，短暂等待
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
            // 入场阶段使用 Crop，确保覆盖层铺满矩形（与列表缩略图视觉一致）
            SharedBoundsOverlay(model = urls.getOrNull(startIndex), rect = rect, contentScale = ContentScale.Crop)
        }

        // 退出反向覆盖层
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

        // 底部缩略图区域（铺满背景）
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



        // 同步：当 Pager 停止滚动时，令缩略图居中到当前页
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .collectLatest { (page, inProgress) ->
                    if (!inProgress && page != centeredThumbIndex) {
                        centeredThumbIndex = page
                    }
                }
        }
        // 同步：当缩略图居中索引变化（且 Pager 空闲）时，切换到该页
        // 仅在完成初始化后响应缩略图变更
        LaunchedEffect(centeredThumbIndex, isInitialized) {
            if (isInitialized && !pagerState.isScrollInProgress && centeredThumbIndex != pagerState.currentPage) {
                Log.d(TAG, "ImagePagerScreen: thumbnail triggered page change to $centeredThumbIndex")
                scope.launch { pagerState.animateScrollToPage(centeredThumbIndex) }
            }
        }
    }
}



// 工具方法
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
