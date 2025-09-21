package com.jacky.features.imagepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

@Composable
fun ImagePagerScreen(
    urls: List<String>,
    startIndex: Int = 0,
    maxScale: Float = 3f,
) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { urls.size.coerceAtLeast(1) })
    val immersive = rememberImmersiveController(initial = false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ImmersiveSystemBarsEffect(immersive = immersive.value)
    ImmersiveBackHandler(immersive)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
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
    }
}

