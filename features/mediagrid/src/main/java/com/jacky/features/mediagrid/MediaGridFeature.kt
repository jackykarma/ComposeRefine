package com.jacky.features.mediagrid

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

import androidx.compose.foundation.BorderStroke


import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest

import com.jacky.compose.feature.api.Feature
import com.jacky.features.medialibrary.MimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.jacky.features.imagepreview.ImagePagerScreen
import androidx.compose.runtime.mutableStateMapOf


import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaGridFeature
 * - 职责：展示媒体缩略图网格与快速滚动；承载预览 Overlay 并与其进行共享边界与滚动联动。
 * - 关键状态：
 *   • parentGridState：主网格滚动状态，用于联动/恢复。
 *   • gridInitialIndex/Offset：进入预览时记录的主列表位置，用于返回入场项时恢复，避免“跳”。
 *   • previewUrls/previewFocusUri：当前预览窗口与入场项。
 *   • previewStartBounds：入场共享边界起点；也作为无法命中目标时的回退出口。
 *   • thumbBoundsByUri：实时维护的 uri->bounds 映射，供退出目标使用。
 *   • previewExitTargetBounds：传给预览页的退出目标。
 *   • blankUri：被占位为空白的目标缩略图（退出或下拉期间）。
 * - 联动策略：
 *   • onPageChanged：回到“入场项”时立刻静默恢复主列表到初始位置；切到其他项时仅在不可见时滚动到目标。
 *   • onExitBlankingChange：下拉开始立刻置空目标格子；未退出时在结束时恢复。
 *   • onExitStart：退出动画开始时置空格子；若目标为入场项，兜底恢复主列表位置。
 */

class MediaGridFeature : Feature {
    override val id: String = ROUTE
    override val displayName: String = "媒体缩略图"

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavHostController) {
        navGraphBuilder.composable(
            route = "$ROUTE?mimetype={mimetype}&pageSize={pageSize}",
            arguments = listOf(
                navArgument("mimetype") { type = NavType.StringType; defaultValue = "all" },
                navArgument("pageSize") { type = NavType.IntType; defaultValue = 60 },
            )
        ) { backStackEntry ->
            var blankUri by remember { mutableStateOf<String?>(null) }

            val mimetype = backStackEntry.arguments?.getString("mimetype") ?: "all"
            val pageSize = backStackEntry.arguments?.getInt("pageSize") ?: 60
            // Obtain context once in composable scope; capture into lambda
            val ctx = LocalContext.current
            var gridInitialIndex by remember { mutableIntStateOf(0) }
            var gridInitialOffset by remember { mutableIntStateOf(0) }

            var previewWindowStartAbs by remember { mutableIntStateOf(0) }
            val parentGridState = rememberLazyGridState()
            val parentScope = rememberCoroutineScope()



            // Overlay preview state kept in the same route to ensure grid stays rendered behind
            var previewVisible by remember { mutableStateOf(false) }
            var previewUrls by remember { mutableStateOf<List<String>>(emptyList()) }
            var previewFocusUri by remember { mutableStateOf("") }
            var previewStartBounds by remember { mutableStateOf<String?>(null) }
            // 预览退出目标：由网格中“当前目标项”的窗口 bounds 提供
            val thumbBoundsByUri = remember { mutableStateMapOf<String, String>() }
            var previewCurrentUrl by remember { mutableStateOf<String?>(null) }
            var previewExitTargetBounds by remember { mutableStateOf<String?>(null) }

            Box(Modifier.fillMaxSize()) {
                MediaGridScreen(
                    mimeArg = mimetype,
                    pageSize = pageSize,
                    onItemClick = { uris, focusUri, startBounds, windowStartAbs ->
                        // Prefetch likely-needed bitmaps to improve first-entry visuals
                        // 1) Prefetch overlay (start bounds) size to match entry overlay request
                        startBounds?.let { s ->
                            val size = try {
                                val p = s.split(',')
                                Pair(p[2].toInt(), p[3].toInt())
                            } catch (_: Throwable) { null }
                            size?.let { (w, h) ->
                                val req = coil.request.ImageRequest.Builder(ctx)
                                    .data(focusUri)
                                    .size(coil.size.Size(w, h))
                                    .precision(coil.size.Precision.INEXACT)
                                    .scale(coil.size.Scale.FILL)
                                    .build()
                                coil.Coil.imageLoader(ctx).enqueue(req)
                            }
                        }
                        // 2) Prefetch preview container-ish size (screen size) for low-res Fit layer
                        run {
                            val dm = ctx.resources.displayMetrics
                            val req = coil.request.ImageRequest.Builder(ctx)
                                .data(focusUri)
                                .size(coil.size.Size(dm.widthPixels, dm.heightPixels))
                                .precision(coil.size.Precision.INEXACT)
                                .scale(coil.size.Scale.FIT)
                                .build()
                            // 记录预览窗口在数据集中的起始绝对索引，便于预览页切换时把网格滚动到目标项
                            previewWindowStartAbs = windowStartAbs

                            coil.Coil.imageLoader(ctx).enqueue(req)
                        }

                        // 记录打开预览时的网格初始滚动位置，便于必要时恢复
                        gridInitialIndex = parentGridState.firstVisibleItemIndex
                        gridInitialOffset = parentGridState.firstVisibleItemScrollOffset

                        // Open in overlay
                        previewUrls = uris
                        previewFocusUri = focusUri
                        previewStartBounds = startBounds
                        previewVisible = true
                    }
                        ,
                        onItemBounds = { uri, bounds ->
                            // 每当某个可见缩略图的窗口坐标发生变化（onGloballyPositioned）时上报
                            // 1) 更新 uri->bounds 的实时映射，供预览页作为 exitTargetBounds 使用
                            // 2) 若该 uri 即为当前预览页 url，则立刻刷新 previewExitTargetBounds，避免返回目标滞后
                            thumbBoundsByUri[uri] = bounds
                            if (previewCurrentUrl == uri) previewExitTargetBounds = bounds
                        },
                        gridState = parentGridState,
                        blankUri = blankUri
                )

                if (previewVisible) {
                    val startIndex = remember(previewUrls, previewFocusUri) {
                        previewUrls.indexOf(previewFocusUri).coerceIn(0, previewUrls.lastIndex)
                    }
                    ImagePagerScreen(
                        urls = previewUrls,
                        startIndex = startIndex,
                        onBack = { blankUri = null; previewVisible = false },
                        entryStartBounds = previewStartBounds,
                        exitTargetBounds = previewExitTargetBounds,
                        onPageChanged = { _, url ->
                            previewCurrentUrl = url
                            val isEntry = (url == previewFocusUri)
                            if (isEntry) {
                                // 返回到入场项：立即无动画恢复主列表到进入时的位置，避免下拉时出现“先滚后再滚回”的跳变
                                val curIndex = parentGridState.firstVisibleItemIndex
                                val curOffset = parentGridState.firstVisibleItemScrollOffset
                                if (curIndex != gridInitialIndex || curOffset != gridInitialOffset) {
                                    parentScope.launch { parentGridState.scrollToItem(gridInitialIndex, gridInitialOffset) }
                                }
                                // 优先用实时可见 bounds，否则回退到入场起点 bounds（即使 A 暂不可见也不强制滚动）
                                previewExitTargetBounds = thumbBoundsByUri[url] ?: previewStartBounds
                            } else {
                                // 返回到非入场项：如不在可见范围才滚动，确保有可用的 exit bounds
                                val pageIndex = previewUrls.indexOf(url).coerceAtLeast(0)
                                val targetAbs = (previewWindowStartAbs + pageIndex).coerceAtLeast(0)
                                val visible = parentGridState.layoutInfo.visibleItemsInfo.any { it.index == targetAbs }
                                if (!visible) {
                                    parentScope.launch { parentGridState.animateScrollToItem(targetAbs) }
                                }
                                previewExitTargetBounds = if (url.isNotEmpty()) thumbBoundsByUri[url] else null
                            }
                        },
                        onExitStart = { url ->
                            blankUri = url
                            // 若是返回到入场项，则在退出动画开始时恢复网格到初始可见位置，避免最终位置偏移
                            if (url == previewFocusUri) {
                                parentScope.launch { parentGridState.scrollToItem(gridInitialIndex, gridInitialOffset) }
                            }
                        },
                        onExitBlankingChange = { url, active -> blankUri = if (active) url else null }
                    )
                }
            }
        }
    }

    companion object {
        private const val ROUTE = "media_grid"
    }
}

private enum class FilterOption { All, Images, Videos, Live, Favorites }


@OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)

/**
 * 媒体网格主界面（支持预览 Overlay 的承载与联动）。
 *
 * 设计要点：
 * - 共享边界目标（bounds）实时维护：
 *   • onItemBounds 在每个可见缩略图 onGloballyPositioned 时上报窗口坐标；
 *   • 使用 thumbBoundsByUri[uri] 存储；当预览当前页 url 变更时，若命中则更新 previewExitTargetBounds。
 * - 预览 Overlay 的打开：
 *   • onItemClick 组装一段以点击项为中心的 urls 窗口，并记录 windowStartAbs；
 *   • 记录 gridInitialIndex/Offset 以便返回到入场项时恢复列表位置；
 *   • 记录 previewStartBounds 用作入场与回退目标。
 * - 页切换联动：
 *   • 若切到“入场项”（url==previewFocusUri），不滚动主列表，并立即将主列表静默恢复到进入预览时的位置；
 *     退出动画目标优先用当前可见 bounds，否则回退到 previewStartBounds；
 *   • 若切到“非入场项”，仅在该项不在可见区时才滚动到目标 index，减少不必要滚动，同时保证有可用的目标 bounds。
 * - 下拉空白占位：
 *   • onExitBlankingChange(url, true/false) 来自预览页，在下拉开始/取消时置空/恢复缩略图格子；
 *   • onExitStart(url) 在退出动画开始时置空格子，并在返回入场项时兜底恢复列表位置。
 *
 * 参数：
 * @param mimeArg       媒体类型过滤（all/images/videos/...）
 * @param pageSize      分页加载大小
 * @param onItemClick   打开预览 Overlay 的回调（传入 urls 窗口、焦点 url、起点 bounds、窗口起始绝对 index）
 * @param onItemBounds  可见缩略图的窗口坐标上报回调（用于实时维护共享边界目标）
 * @param gridState     父层传入的 LazyGridState（便于父层进行联动滚动/恢复）
 * @param blankUri      若非空，则该 uri 对应的格子渲染为空白占位（用于退出共享边界对接）
 * @param span          每行列数
 * @param spacing       格子间距
 */

@Composable
private fun MediaGridScreen(
    mimeArg: String,
    pageSize: Int,
    onItemClick: (uris: List<String>, focusUrl: String, startBounds: String?, windowStartAbs: Int) -> Unit,
    onItemBounds: ((uri: String, bounds: String) -> Unit)? = null,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    blankUri: String? = null,
    span: Int = 4,
    spacing: Dp = 2.dp,
) {
    val context = LocalContext.current

    var currentFilter by remember(mimeArg) {
        mutableStateOf(
            when (mimeArg.lowercase()) {
                "image", "images", "img" -> FilterOption.Images
                "video", "videos", "vid" -> FilterOption.Videos
                else -> FilterOption.All
            }
        )
    }

    fun toRepoFilter(option: FilterOption): Triple<MimeFilter, Boolean, Boolean> = when (option) {
        FilterOption.All -> Triple(MimeFilter.ImagesAndVideos, false, false)
        FilterOption.Images -> Triple(MimeFilter.Images, false, false)
        FilterOption.Videos -> Triple(MimeFilter.Videos, false, false)
        FilterOption.Favorites -> Triple(MimeFilter.ImagesAndVideos, true, false)
        FilterOption.Live -> Triple(MimeFilter.Images, false, true) // 尽力匹配实况，优先图片集合
    }

    val (mimeFilter, favoritesOnly, liveOnly) = remember(currentFilter) { toRepoFilter(currentFilter) }

    val requiredPermissions = remember(currentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            when (currentFilter) {
                FilterOption.Images, FilterOption.Live -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                FilterOption.Videos -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                FilterOption.All, FilterOption.Favorites -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var hasPermissions by remember(currentFilter) {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(currentFilter, hasPermissions) {
        Log.d(
            "MediaGrid",
            "filter=${currentFilter} hasPerm=${hasPermissions} mime=${mimeFilter} fav=${favoritesOnly} live=${liveOnly}"
        )
    }

    // ViewModel + Paging3
    val viewModel: MediaGridViewModel = viewModel()

    // Total count for absolute mapping (jump / thumb position)
    var totalCount by rememberSaveable(mimeFilter, favoritesOnly, liveOnly) { mutableIntStateOf(0) }
    LaunchedEffect(mimeFilter, favoritesOnly, liveOnly, hasPermissions) {
        if (hasPermissions) {
            totalCount = viewModel.totalCount(
                filter = mimeFilter,
                favoritesOnly = favoritesOnly,
                liveOnly = liveOnly
            )
        } else totalCount = 0
    }

    // Drag target (absolute index); we'll continuously scroll to it (throttled)
    var dragTargetIndex by remember { mutableIntStateOf(-1) }

    // gridState 由父层传入

    val pagingFlow = remember(mimeFilter, pageSize, favoritesOnly, liveOnly, hasPermissions) {
        if (hasPermissions) viewModel
            .pagingData(
                filter = mimeFilter,
                pageSize = pageSize,
                favoritesOnly = favoritesOnly,
                liveOnly = liveOnly
            ) else null
    }
    val lazyPagingItems = pagingFlow?.collectAsLazyPagingItems()

    // Continuous scroll to target while dragging (throttled)
    LaunchedEffect(totalCount, pageSize) {
        snapshotFlow { dragTargetIndex }
            .filter { it >= 0 }
            .distinctUntilChanged()
            .sample(8)
            .collectLatest { targetIndex ->
                gridState.scrollToItem(targetIndex)
            }
    }

    val colorScheme = MaterialTheme.colorScheme
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    !hasPermissions -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("读取媒体库需要权限")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                                Text("去授权")
                            }
                        }
                    }

                    else -> {
                        val lpi = lazyPagingItems
                        if (lpi == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "暂无媒体"
                                )
                            }
                        } else {
                            // 始终渲染 LazyVerticalGrid，以便 Paging 接收到视口提示而开始加载
                            Box(Modifier.fillMaxSize()) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(span),
                                    contentPadding = PaddingValues(spacing),
                                    horizontalArrangement = Arrangement.spacedBy(spacing),
                                    verticalArrangement = Arrangement.spacedBy(spacing),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        count = lpi.itemCount,
                                        key = { index ->
                                            lpi.peek(index)?.uri?.toString() ?: index.toString()
                                        }
                                    ) { index ->
                                        var thumbBoundsStr by remember { mutableStateOf<String?>(null) }

                                        val asset = lpi[index]
                                        Box(
                                            modifier = Modifier
                                                .aspectRatio(1f)
                                                .animateItem(
                                                    fadeInSpec = tween(1000),
                                                    fadeOutSpec = tween(1000),
                                                    placementSpec = tween(durationMillis = 400)
                                                )
                                        ) {
                                            if (asset != null) {
                                                val centerUri = asset.uri.toString()
                                                if (blankUri != null && centerUri == blankUri) {
                                                    // 退出动画期间：将目标格子置为空白，占位等待共享边界回收
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.03f))
                                                    )
                                                } else {
                                                    MediaThumb(
                                                        uri = centerUri,
                                                        isVideo = asset.isVideo,
                                                        durationMs = asset.durationMs ?: 0L,
                                                        onClick = {
                                                            val window = 60
                                                            val start = maxOf(0, index - window / 2)
                                                            val end = minOf(lpi.itemCount - 1, index + window / 2)

                                                            // Calculate the target index as offset from window start
                                                            val targetOffsetInWindow = index - start

                                                            // Build URI list (only include loaded items)
                                                            val uris = (start..end).mapNotNull { i ->
                                                                lpi.peek(i)?.uri?.toString()
                                                            }

                                                            // Use the offset directly, clamped to available range
                                                            val adjustedIndex = targetOffsetInWindow.coerceIn(0, uris.size - 1)

                                                            android.util.Log.d(
                                                                "MediaGrid",
                                                                "Clicked index: $index, window: $start-$end, offset: $targetOffsetInWindow, final: $adjustedIndex, uris: ${uris.size}"
                                                            )
                                                            onItemClick(uris, centerUri, thumbBoundsStr, start)
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .onGloballyPositioned { coords ->
                                                                val pos = coords.localToWindow(Offset.Zero)
                                                                val size = coords.size
                                                                val s = "${pos.x.toInt()},${pos.y.toInt()},${size.width},${size.height}"
                                                                thumbBoundsStr = s
                                                                onItemBounds?.invoke(centerUri, s)
                                                            }
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.03f))
                                                )
                                            }
                                        }
                                    }
                                }

                                    // Prefetch preview-sized bitmaps for currently visible items to warm Coil cache
                                    val prefetchedSet = remember { hashSetOf<String>() }
                                    LaunchedEffect(gridState, lpi) {
                                        snapshotFlow {
                                            gridState.layoutInfo.visibleItemsInfo.map { it.index }
                                        }.collectLatest { indices ->
                                            val dm = context.resources.displayMetrics
                                            indices.forEach { i ->
                                                val uri = lpi.peek(i)?.uri?.toString()
                                                if (uri != null && prefetchedSet.add(uri)) {
                                                    val req = ImageRequest.Builder(context)
                                                        .data(uri)
                                                        .size(coil.size.Size(dm.widthPixels, dm.heightPixels))
                                                        .precision(coil.size.Precision.INEXACT)
                                                        .scale(coil.size.Scale.FIT)
                                                        .build()
                                                    coil.Coil.imageLoader(context).enqueue(req)
                                                }
                                            }
                                        }
                                    }


                                // 覆盖层：根据 loadState 展示加载/错误/空态
                                when (val refresh = lpi.loadState.refresh) {
                                    is LoadState.Loading -> {
                                        Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }

                                    is LoadState.Error -> {
                                        Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    "加载失败：" + (refresh.error.message
                                                        ?: "未知错误")
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Button(onClick = { lpi.retry() }) { Text("重试") }
                                            }
                                        }
                                    }

                                    is LoadState.NotLoading -> {
                                        if (lpi.itemCount == 0) {
                                            Box(
                                                Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) { Text("暂无媒体") }
                                        }
                                    }
                                }

                                // Fast scroller overlay（使用 totalCount 绝对映射；拖动中节流跳页，松手保证最终对齐）
                                if (totalCount > 0) {
                                    FastScroller(
                                        gridState = gridState,
                                        totalCount = totalCount,
                                        columns = span,
                                        onDragTargetChanged = { targetIndex ->
                                            dragTargetIndex = targetIndex
                                        },
                                        labelForIndex = { idx ->
                                            val count = lpi.itemCount
                                            if (idx in 0 until count) {
                                                lpi.peek(idx)?.dateAddedSec?.let { sec ->
                                                    formatDateFromSeconds(
                                                        sec
                                                    )
                                                }
                                            } else null
                                        }
                                    )
                                }
                                // 调试用
                                /*val refreshState = lpi.loadState.refresh
                                val appendState = lpi.loadState.append
                                val msg = "count=${lpi.itemCount} refresh=${refreshState::class.simpleName} append=${appendState::class.simpleName}"
                                Surface(
                                    tonalElevation = 2.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).alpha(0.85f)
                                ) {
                                    Text(msg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                }*/
                            }
                        }
                    }

                }
            }
        }

        FilterFab(onClick = { menuExpanded = true })

        FilterMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            current = currentFilter,
            onSelect = { option ->
                menuExpanded = false
                if (currentFilter != option) currentFilter = option
            }
        )

    }
}

@Composable
/**
 * 单个媒体缩略图。
 * - 负责展示图片/视频封面与时长角标；
 * - 点击后通过 onClick 交由父层处理（父层会进行预览 overlay 的打开与共享边界起点的上报）。
 *
 * @param uri        媒体唯一标识（用于加载与共享边界映射）
 * @param isVideo    是否为视频，视频时显示时长角标
 * @param durationMs 视频时长（毫秒）
 * @param onClick    点击回调
 * @param modifier   外部修饰符
 */

private fun MediaThumb(
    uri: String,
    isVideo: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.LightGray)
            .clickable { onClick() }
    ) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .crossfade(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (isVideo) {
            val dur = formatDuration(durationMs)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                val cs = MaterialTheme.colorScheme
                val bg = if (isSystemInDarkTheme()) cs.surface else Color.White
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bg,
                    contentColor = cs.onSurface,
                    tonalElevation = 0.dp,
                    border = BorderStroke(0.75.dp, cs.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = dur,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}


@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSystemInDarkTheme()) colors.surface else Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .height(40.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(TextStyle(color = colors.onSurface)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke(query); focusManager.clearFocus() }),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
            ) { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (query.isEmpty() && !focused) {
                        Text("输入时间、地点、文件名...", color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
            }
            if (query.isNotEmpty() || focused) {
                IconButton(
                    onClick = { onQueryChange(""); focusManager.clearFocus() },
                    modifier = Modifier.semantics { contentDescription = "清空" }
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.FilterFab(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container = if (isSystemInDarkTheme()) cs.surface else Color.White
    val content = cs.onSurface
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = 32.dp)
            .size(48.dp)
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.4f), CircleShape),
        shape = CircleShape,
        containerColor = container,
        contentColor = content
    ) {
        Icon(Icons.Filled.FilterList, contentDescription = "筛选")
    }
}

@Composable
private fun BoxScope.FilterMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    current: FilterOption,
    onSelect: (FilterOption) -> Unit,
) {
    // 轻量遮罩，仅在展开时出现
    if (expanded) {
        val overlayInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = overlayInteraction
                ) { onDismissRequest() }
        ) {}
    }

    val fabSize = 56.dp
    val gap = 8.dp
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(220)) { it / 2 },
        exit = fadeOut(animationSpec = tween(140)) + slideOutVertically(animationSpec = tween(180)) { it / 2 }
    ) {
        // 在一个填充父级的 Box 中对齐，避免 align 在非 Box 父级失效
        Box(Modifier.fillMaxSize()) {
            val cs2 = MaterialTheme.colorScheme
            val cardBg = if (isSystemInDarkTheme()) cs2.surface else Color.White
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 32.dp, bottom = 32.dp + fabSize + gap)
                    .border(1.dp, cs2.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            ) {
                Column(Modifier.width(140.dp)) {
                    FilterMenuEntry(
                        "全部",
                        current == FilterOption.All
                    ) { onSelect(FilterOption.All) }
                    FilterMenuEntry(
                        "图片",
                        current == FilterOption.Images
                    ) { onSelect(FilterOption.Images) }
                    FilterMenuEntry(
                        "视频",
                        current == FilterOption.Videos
                    ) { onSelect(FilterOption.Videos) }
                    FilterMenuEntry(
                        "实况",
                        current == FilterOption.Live
                    ) { onSelect(FilterOption.Live) }
                    FilterMenuEntry("收藏", current == FilterOption.Favorites) {
                        onSelect(
                            FilterOption.Favorites
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterMenuEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(label, textAlign = TextAlign.Center)
        }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}


@Composable
private fun BoxScope.FastScroller(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    totalCount: Int,
    columns: Int,
    onDragTargetChanged: (targetIndex: Int) -> Unit,
    labelForIndex: (Int) -> String? = { null },
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current

    var show by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf(0f) } // 0..1
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(300),
        label = "fast_scroller_alpha"
    )

    val thumbHeight = 56.dp
    val thumbWidth = 36.dp
    val thumbHeightPx = with(density) { thumbHeight.roundToPx() }

    fun totalRows(count: Int, cols: Int): Int = maxOf(1, (count + cols - 1) / cols)

    // Show when list is scrolling or on first data arrival; auto-hide after idle
    val isScrolling = gridState.isScrollInProgress
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling) {
            show = true
        } else if (!dragging) {
            delay(1500)
            show = false
        }
    }
    LaunchedEffect(totalCount) {
        if (totalCount > 0) {
            show = true
            delay(1200)
            if (!dragging && !gridState.isScrollInProgress) show = false
        }
    }

    // Sync thumb position with list scroll when not dragging (absolute index with placeholders)
    LaunchedEffect(gridState, totalCount, columns, trackHeight) {
        if (totalCount <= 0) return@LaunchedEffect
        snapshotFlow {
            val firstIdx = gridState.firstVisibleItemIndex
            val firstOffset = gridState.firstVisibleItemScrollOffset
            val firstItemH = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
            Triple(firstIdx, firstOffset, firstItemH)
        }.collectLatest { (firstIdx, firstOffset, firstItemH) ->
            if (!dragging && trackHeight > 0) {
                val rows = totalRows(totalCount, columns)
                // With placeholders enabled, firstVisibleItemIndex is absolute within total dataset
                val absoluteFirst = firstIdx
                val firstRowAbs = absoluteFirst / maxOf(1, columns)
                val rowH = if (firstItemH > 0) firstItemH else 1
                val rowFrac = (firstOffset / rowH.toFloat()).coerceIn(0f, 1f)
                progress = if (rows <= 1) 0f else (firstRowAbs + rowFrac) / (rows - 1).toFloat()
            }
        }
    }

    // Invisible full-height track as gesture area
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(end = 8.dp)
            .width(thumbWidth)
            .onGloballyPositioned { trackHeight = it.size.height }
            .pointerInput(totalCount, trackHeight, columns) {
                detectDragGestures(
                    onDragStart = {
                        show = true; dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                    },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    val available = (trackHeight - thumbHeightPx).coerceAtLeast(1)
                    val yTop =
                        (change.position.y - thumbHeightPx / 2f).toInt().coerceIn(0, available)
                    progress = yTop / available.toFloat()
                    val rows = totalRows(totalCount, columns)
                    val rowTarget = (progress * (rows - 1).coerceAtLeast(0)).toInt()
                    val targetIndex =
                        (rowTarget * columns).coerceIn(0, (totalCount - 1).coerceAtLeast(0))
                    onDragTargetChanged(targetIndex)
                }
            }
            .alpha(alpha)
    ) {
        // Draggable thumb (white background + up/down icon)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = with(density) {
                    val available = (trackHeight - thumbHeightPx).coerceAtLeast(0)
                    (available * progress).toInt().toDp()
                })
                .size(width = thumbWidth, height = thumbHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
        ) {
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    if (totalCount > 0) {
        var bubbleHeight by remember { mutableIntStateOf(0) }
        val infoGap = 12.dp
        val currentIndex = if (dragging) {
            val rows = totalRows(totalCount, columns)
            val rowTarget = (progress * (rows - 1).coerceAtLeast(0)).toInt()
            (rowTarget * columns).coerceIn(0, (totalCount - 1).coerceAtLeast(0))
        } else gridState.firstVisibleItemIndex

        // Keep last non-empty label to avoid flicker/missing text while target item not yet loaded
        var lastNonEmpty by remember { mutableStateOf("") }
        val computed = labelForIndex(currentIndex) ?: ""
        LaunchedEffect(computed, dragging) {
            if (computed.isNotEmpty()) lastNonEmpty = computed
        }
        val text = if (computed.isNotEmpty()) computed else lastNonEmpty

        if (dragging && text.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSystemInDarkTheme()) colors.surface else Color.White,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp + thumbWidth + infoGap)
                    .onGloballyPositioned { bubbleHeight = it.size.height }
                    .offset(y = with(density) {
                        val available = (trackHeight - thumbHeightPx).coerceAtLeast(0)
                        val top = (available * progress).toInt()
                        val centered = top + ((thumbHeightPx - bubbleHeight) / 2f)
                        centered.toInt().coerceIn(0, (trackHeight - bubbleHeight).coerceAtLeast(0))
                            .toDp()
                    })
                    .alpha(alpha)
            ) {
                Text(
                    text = text,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun formatDateFromSeconds(sec: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sdf.format(Date(sec * 1000))
    } catch (_: Throwable) {
        ""
    }
}


