package com.jacky.features.mediagrid

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgeDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

@Composable
private fun MediaGridScreen(
    mimeArg: String,
    pageSize: Int,
    onItemClick: (uris: List<String>, index: Int) -> Unit,
    span: Int = 3,
    spacing: Dp = 2.dp,
) {
    val context = LocalContext.current
    val mimeFilter = remember(mimeArg) {
        when (mimeArg.lowercase()) {
            "image", "images", "img" -> MimeFilter.Images
            "video", "videos", "vid" -> MimeFilter.Videos
            else -> MimeFilter.ImagesAndVideos
        }
    }

    val requiredPermissions = remember(mimeFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            when (mimeFilter) {
                MimeFilter.Images -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                MimeFilter.Videos -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                MimeFilter.ImagesAndVideos -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
        } else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    var page by remember { mutableStateOf(0) }
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
                    pageSize = pageSize
                )
                items = items + newPage
                page += 1
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(hasPermissions, mimeFilter, pageSize) {
        if (hasPermissions) {
            page = 0
            items = emptyList()
            loadNextPage()
        }
    }

    val content: @Composable () -> Unit = when {
        !hasPermissions -> {
            {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("读取媒体库需要权限")
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("去授权")
                    }
                }
            }
        }
        items.isEmpty() -> {
            { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无媒体") } }
        }
        else -> {
            {
                val uris = remember(items) { items.map { it.uri.toString() } }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(span),
                    contentPadding = PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items) { index, asset ->
                        if (index >= items.lastIndex - span * 2) {
                            // 逼近底部时触发加载
                            loadNextPage()
                        }
                        MediaThumb(
                            uri = asset.uri.toString(),
                            isVideo = asset.isVideo,
                            durationMs = asset.durationMs ?: 0L,
                            onClick = { onItemClick(uris, index) }
                        )
                    }
                }
            }
        }
    }

    content()
}

@Composable
private fun MediaThumb(
    uri: String,
    isVideo: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color.Black)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(true)
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

