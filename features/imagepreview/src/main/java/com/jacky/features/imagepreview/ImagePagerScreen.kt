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


@Composable
fun ImagePagerScreen(

    urls: List<String>,
    startIndex: Int = 0,
    maxScale: Float = 3f,
    onBack: (() -> Unit)? = null,
    entryStartBounds: String? = null,
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
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
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

    var rootWindowOffset by remember { mutableStateOf(IntOffset(0, 0)) }

    Box(Modifier
        .fillMaxSize()
        .onSizeChanged { containerSize = it }
        .onGloballyPositioned { coords ->
            val p = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
            rootWindowOffset = IntOffset(p.x.toInt(), p.y.toInt())
        }
        .background(MaterialTheme.colorScheme.background)) {
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
            .graphicsLayer { translationY = dragY }
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
            SharedBoundsOverlay(model = urls.getOrNull(pagerState.currentPage), rect = rect, contentScale = ContentScale.Fit)
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
