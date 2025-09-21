package com.jacky.features.imagepreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zoomable image with:
 * - Pinch zoom (maxScale configurable)
 * - Panning with bounds
 * - Progressive loading: low-res (screen-sized) -> high-res (original)
 * - Single tap to toggle immersive via [onTap]
 * - Auto enter immersive on pinch via [onPinchStart]
 * - When zoomed and user reaches left/right edge then flings, call [requestPageChange]
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxScale: Float = 3f,
    onTap: () -> Unit,
    onPinchStart: () -> Unit,
    requestPageChange: (delta: Int) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    // Transform state (direct during gesture); we animate back only after gesture
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Track last pan velocity to decide edge-fling
    var lastPanX by remember { mutableStateOf(0f) }

    // Compute fit (min) scale to contain image in container preserving aspect
    val minScale by remember(containerSize, imageSize) {
        mutableStateOf(computeFitScale(containerSize, imageSize))
    }

    // Ensure scale is at least minScale when sizes known
    LaunchedEffect(minScale) {
        if (minScale > 0f && scale < minScale) {
            val sAnim = Animatable(scale)
            sAnim.animateTo(minScale, spring()) { scale = value }
            val oAnim = Animatable(offset, Offset.VectorConverter)
            oAnim.snapTo(Offset.Zero)
            offset = oAnim.value
        }
    }

    // Progressive loading control
    var showHighRes by remember { mutableStateOf(false) }
    var highResLoaded by remember { mutableStateOf(false) }

    // Enter high-res once user zooms in past 1.25x or after initial settle
    LaunchedEffect(scale) {
        if (scale > minScale * 1.25f) showHighRes = true
    }

    // Helpers
    fun clampOffset(target: Offset, sc: Float): Offset {
        if (containerSize == IntSize.Zero || imageSize == IntSize.Zero) return Offset.Zero
        val contentW = imageSize.width * sc
        val contentH = imageSize.height * sc
        val maxX = max(0f, (contentW - containerSize.width) / 2f)
        val maxY = max(0f, (contentH - containerSize.height) / 2f)
        return Offset(
            x = target.x.coerceIn(-maxX, maxX),
            y = target.y.coerceIn(-maxY, maxY)
        )
    }

    fun atLeftEdge(sc: Float, off: Offset): Boolean {
        if (containerSize == IntSize.Zero || imageSize == IntSize.Zero) return true
        val contentW = imageSize.width * sc
        if (contentW <= containerSize.width + 1f) return true
        val maxX = (contentW - containerSize.width) / 2f
        return off.x >= maxX - 0.5f // close to left bound (content pulled fully right)
    }

    fun atRightEdge(sc: Float, off: Offset): Boolean {
        if (containerSize == IntSize.Zero || imageSize == IntSize.Zero) return true
        val contentW = imageSize.width * sc
        if (contentW <= containerSize.width + 1f) return true
        val maxX = (contentW - containerSize.width) / 2f
        return off.x <= -maxX + 0.5f // close to right bound (content pulled fully left)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() }
                )
            }
            .pointerInput(minScale, maxScale, containerSize, imageSize) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) onPinchStart()
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    val scaleChange = if (scale == 0f) 1f else newScale / scale
                    val newOffset = (offset + pan).times(scaleChange)
                    lastPanX = pan.x
                    // update directly during gesture
                    scale = newScale
                    offset = clampOffset(newOffset, newScale)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Low-res (screen-sized) request
        val context = androidx.compose.ui.platform.LocalContext.current
        val lowReq = remember(containerSize, model) {
            ImageRequest.Builder(context)
                .data(model)
                .size(Size(containerSize.width.coerceAtLeast(1), containerSize.height.coerceAtLeast(1)))
                .precision(Precision.INEXACT)
                .scale(Scale.FIT)
                .crossfade(true)
                .build()
        }
        // High-res (original) request
        val highReq = remember(model) {
            ImageRequest.Builder(context)
                .data(model)
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .build()
        }

        // Low-res layer
        SubcomposeAsyncImage(
            model = lowReq,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val sc = max(scale, minScale)
                    scaleX = sc
                    scaleY = sc
                    translationX = offset.x
                    translationY = offset.y
                },
            onSuccess = { state ->
                val d = state.result.drawable
                imageSize = IntSize(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
            }
        )

        // High-res layer on top (only when requested), with fade in via alpha
        if (showHighRes || highResLoaded) {
            SubcomposeAsyncImage(
                model = highReq,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val sc = max(scale, minScale)
                        scaleX = sc
                        scaleY = sc
                        translationX = offset.x
                        translationY = offset.y
                        alpha = if (highResLoaded) 1f else 0f
                    },
                onSuccess = { state ->
                    highResLoaded = true
                    val d = state.result.drawable
                    imageSize = IntSize(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
                }
            )
        }
    }

    // On gesture end adjustments (bounce back / page switch)
    LaunchedEffect(scale, offset, minScale) {
        // If under min scale due to size change, animate back
        if (scale < minScale && minScale > 0f) {
            val sAnim = Animatable(scale)
            sAnim.animateTo(minScale, spring()) { scale = value }
            val oAnim = Animatable(offset, Offset.VectorConverter)
            oAnim.animateTo(Offset.Zero, spring()) { offset = value }
        } else {
            // Clamp offset if out of bounds
            val clamped = clampOffset(offset, scale)
            if (clamped != offset) {
                val oAnim = Animatable(offset, Offset.VectorConverter)
                oAnim.animateTo(clamped, spring()) { offset = value }
            }
        }
        // If at edge and user panned further (based on lastPanX), hint page switch
        if (scale > minScale * 0.999f) {
            if (lastPanX > 8f && atLeftEdge(scale, offset)) {
                requestPageChange(-1)
            } else if (lastPanX < -8f && atRightEdge(scale, offset)) {
                requestPageChange(1)
            }
        }
    }
}

private fun computeFitScale(container: IntSize, image: IntSize): Float {
    if (container == IntSize.Zero || image == IntSize.Zero) return 1f
    val sw = container.width.toFloat() / image.width.toFloat()
    val sh = container.height.toFloat() / image.height.toFloat()
    return min(sw, sh)
}

