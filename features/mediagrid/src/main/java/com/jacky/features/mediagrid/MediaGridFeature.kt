package com.jacky.features.mediagrid

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jacky.compose.feature.api.Feature
import com.jacky.features.medialibrary.MediaAsset
import com.jacky.features.medialibrary.MediaStoreRepository
import com.jacky.features.medialibrary.MimeFilter
import kotlinx.coroutines.launch

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
            val mimetype = backStackEntry.arguments?.getString("mimetype") ?: "all"
            val pageSize = backStackEntry.arguments?.getInt("pageSize") ?: 60
            MediaGridScreen(
                mimeArg = mimetype,
                pageSize = pageSize,
                onItemClick = { uris, index ->
                    // 仅将当前已加载页的 URI 列表传给预览，避免超长参数
                    val joined = uris.joinToString(",")
                    val encoded = java.net.URLEncoder.encode(joined, Charsets.UTF_8.name())
                    navController.navigate("image_preview?url=$encoded&index=$index")
                }
            )
        }
    }

    companion object { private const val ROUTE = "media_grid" }
}

private enum class FilterOption { All, Images, Videos, Live, Favorites }


@OptIn(ExperimentalFoundationApi::class)

@Composable
private fun MediaGridScreen(
    mimeArg: String,
    pageSize: Int,
    onItemClick: (uris: List<String>, index: Int) -> Unit,
    span: Int = 3,
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
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    var page by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadNextPage() {
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                val newPage = MediaStoreRepository.default.queryPage(
                    context = context,
                    filter = mimeFilter,
                    page = page,
                    pageSize = pageSize,
                    favoritesOnly = favoritesOnly,
                    liveOnly = liveOnly,
                )
                items = items + newPage
                page += 1
            } finally {
                isLoading = false
            }
        }
    }

    fun reloadFirstPage() {
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                val firstPage = MediaStoreRepository.default.queryPage(
                    context = context,
                    filter = mimeFilter,
                    page = 0,
                    pageSize = pageSize,
                    favoritesOnly = favoritesOnly,
                    liveOnly = liveOnly,
                )
                items = firstPage
                page = 1
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(hasPermissions, mimeFilter, pageSize, favoritesOnly, liveOnly) {
        if (hasPermissions) {
            reloadFirstPage()
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when {
            !hasPermissions -> {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("读取媒体库需要权限")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("去授权")
                    }
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无媒体") }
            }
            else -> {
                val uris = remember(items) { items.map { it.uri.toString() } }
                LazyVerticalGrid(
                        columns = GridCells.Fixed(span),
                        contentPadding = PaddingValues(spacing),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalArrangement = Arrangement.spacedBy(spacing),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(items, key = { _, asset -> asset.uri.toString() }) { index, asset ->
                            if (index >= items.lastIndex - span * 2) {
                                loadNextPage()
                            }
                            MediaThumb(
                                uri = asset.uri.toString(),
                                isVideo = asset.isVideo,
                                durationMs = asset.durationMs ?: 0L,
                                onClick = { onItemClick(uris, index) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

            }
        }

        // 悬浮筛选按钮
        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 32.dp),
            onClick = { menuExpanded = true }
        ) { Text("筛选") }

        // 自定义弹出菜单：以 FAB 为参照，显示在其上方且右侧对齐
        if (menuExpanded) {
            val overlayInteraction = remember { MutableInteractionSource() }
            // 点击遮罩任意处关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(indication = null, interactionSource = overlayInteraction) { menuExpanded = false }
            ) {}

            val fabSize = 56.dp // M3 默认 FAB 尺寸
            val gap = 8.dp
            androidx.compose.material3.Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 32.dp + fabSize + gap)
            ) {
                @Composable
                fun MenuEntry(label: String, selected: Boolean, onClick: () -> Unit) {
                    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .clickable { onClick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 居中文本
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(label, textAlign = TextAlign.Center)
                        }
                        // 右侧预留等宽区域，避免文字位移
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                            if (selected) {
                                Text("\u2713", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                fun select(option: FilterOption) {
                    menuExpanded = false
                    if (currentFilter != option) currentFilter = option
                }

                Column(Modifier.width(140.dp)) {
                    MenuEntry("全部", currentFilter == FilterOption.All) { select(FilterOption.All) }
                    MenuEntry("图片", currentFilter == FilterOption.Images) { select(FilterOption.Images) }
                    MenuEntry("视频", currentFilter == FilterOption.Videos) { select(FilterOption.Videos) }
                    MenuEntry("实况", currentFilter == FilterOption.Live) { select(FilterOption.Live) }
                    MenuEntry("收藏", currentFilter == FilterOption.Favorites) { select(FilterOption.Favorites) }
                }
            }
        }
    }
}

@Composable
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
            .background(Color.Black)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(false)
                // 对视频依赖 coil-video 以解码首帧
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
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0x80000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = dur, color = Color.White, fontWeight = FontWeight.SemiBold)
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
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

