package com.jacky.features.imagepreview

import android.util.Log
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons

@Composable
fun ImagePagerScreen(
    urls: List<String>,
    startIndex: Int = 0,
    maxScale: Float = 3f,
    onBack: (() -> Unit)? = null,
) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { urls.size.coerceAtLeast(1) })
    val immersive = rememberImmersiveController(initial = false)
    val scope = rememberCoroutineScope()

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

    Box(Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        // Pager
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val model = urls.getOrNull(page)
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
                }
            )
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

