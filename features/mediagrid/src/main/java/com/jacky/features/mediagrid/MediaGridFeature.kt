package com.jacky.features.mediagrid


import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    companion object {
        private const val ROUTE = "media_grid"
    }
}

private enum class FilterOption { All, Images, Videos, Live, Favorites }


@OptIn(ExperimentalFoundationApi::class)

@Composable
private fun MediaGridScreen(
    mimeArg: String,
    pageSize: Int,
    onItemClick: (uris: List<String>, index: Int) -> Unit,
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

    val colorScheme = MaterialTheme.colorScheme
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(hasPermissions, mimeFilter, pageSize, favoritesOnly, liveOnly) {
        if (hasPermissions) {
            reloadFirstPage()
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier
        .fillMaxSize()
        .background(colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {

            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            Spacer(Modifier.height(8.dp))
            Box(Modifier
                .weight(1f)
                .fillMaxWidth()) {
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

                    items.isEmpty() -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { Text("暂无媒体") }
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
                            itemsIndexed(
                                items,
                                key = { _, asset -> asset.uri.toString() }) { index, asset ->
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
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
            .height(48.dp)
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
                ) { Icon(Icons.Filled.Close, contentDescription = null, tint = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun BoxScope.FilterFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 32.dp)
            .size(48.dp),
        shape = CircleShape
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
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 32.dp + fabSize + gap)
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
