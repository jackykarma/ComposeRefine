package com.jacky.features.imagepreview

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jacky.compose.feature.api.Feature
import androidx.navigation.NavType
import com.jacky.features.medialibrary.MediaStoreRepository
import com.jacky.features.medialibrary.MimeFilter

class ImagePreviewFeature : Feature {
    override val id: String = ROUTE
    override val displayName: String = "图片预览"

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavHostController) {
        navGraphBuilder.composable(
            route = "$ROUTE?url={url}&focusUri={focusUri}&test={test}&mimetype={mimetype}&pageSize={pageSize}&startBounds={startBounds}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; nullable = true },
                navArgument("focusUri") { type = NavType.StringType; defaultValue = "" },
                navArgument("test") { type = NavType.BoolType; defaultValue = false },
                navArgument("mimetype") { type = NavType.StringType; defaultValue = "image" },
                navArgument("pageSize") { type = NavType.IntType; defaultValue = 30 },
                navArgument("startBounds") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val urlArg = backStackEntry.arguments?.getString("url")
            val focusUrl = backStackEntry.arguments?.getString("focusUri") ?: ""
            val test = backStackEntry.arguments?.getBoolean("test") ?: false
            val mimeArg = backStackEntry.arguments?.getString("mimetype")
            val pageSize = backStackEntry.arguments?.getInt("pageSize") ?: 30
            val startBounds = backStackEntry.arguments?.getString("startBounds")
            ImagePreviewScreen(urlArg, focusUrl, test, mimeArg, pageSize, startBounds, onBack = { navController.popBackStack() })
        }
    }

    companion object {
        private const val ROUTE = "image_preview"
    }
}

// 测试模式开关：默认跟随 BuildConfig.DEBUG，也可按需改为常量控制
private const val TEST_MODE_ENABLED = false

// 20 张覆盖多尺寸/比例（含 4K/8K、超长、方图、超宽等）的测试图片（picsum 固定尺寸）
private val TEST_IMAGE_URLS = listOf(
    // 控制在 <= 5000 像素范围内，提升可用性（picsum 支持任意宽高）
    "https://picsum.photos/seed/4kA/4096/2160",        // 4K 横 16:9
    "https://picsum.photos/seed/4kB/2160/4096",        // 4K 竖 9:16
    "https://picsum.photos/seed/uhdA/3840/2160",       // UHD 横 16:9
    "https://picsum.photos/seed/uhdB/2160/3840",       // UHD 竖 9:16
    "https://picsum.photos/seed/fourthreeA/4096/3072", // 4:3 横
    "https://picsum.photos/seed/fourthreeB/3072/4096", // 3:4 竖
    "https://picsum.photos/seed/square4k/4096/4096",   // 方图 4K 级
    "https://picsum.photos/seed/square2k/2048/2048",   // 方图 2K 级
    "https://picsum.photos/seed/wide21x9/4096/1754",   // 约 21:9 横
    "https://picsum.photos/seed/wide32x9/4096/1152",   // 约 32:9 横
    "https://picsum.photos/seed/wide2to1/5000/2500",   // 2:1 超宽
    "https://picsum.photos/seed/wide3to1/4500/1500",   // 3:1 超宽
    "https://picsum.photos/seed/tall2to1/2500/5000",   // 1:2 超长竖
    "https://picsum.photos/seed/tall3to1/1500/4500",   // 1:3 长截图
    "https://picsum.photos/seed/tall4k/2160/4096",     // 4K 竖
    "https://picsum.photos/seed/portraitA/3000/4500",  // 2:3 竖
    "https://picsum.photos/seed/portraitB/2400/3600",  // 2:3 竖
    "https://picsum.photos/seed/landscapeA/4500/3000", // 3:2 横
    "https://picsum.photos/seed/landscapeB/3500/2333", // ~3:2 横
    "https://picsum.photos/seed/mixedA/3500/2800"      // 5:4 横
)

@Composable
private fun ImagePreviewScreen(
    urlArg: String?,
    focusUri: String,
    forceTest: Boolean,
    mimeArg: String?,
    pageSize: Int,
    startBounds: String?,
    onBack: () -> Unit,
) {
    val urlsFromArg = remember(urlArg) {
        if (urlArg.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val decoded = java.net.URLDecoder.decode(urlArg, "UTF-8")
                decoded.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                // Fallback to direct split if decode fails
                urlArg.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }
    val context = LocalContext.current

    // Decide data source: test URLs vs MediaStore
    val useTest = (urlsFromArg.isEmpty() && (TEST_MODE_ENABLED || forceTest))

    val mimeFilter = remember(mimeArg) {
        when (mimeArg?.lowercase()) {
            "image", "images", "img" -> MimeFilter.Images
            "video", "videos", "vid" -> MimeFilter.Videos
            else -> MimeFilter.ImagesAndVideos
        }
    }

    // Runtime permissions required when reading MediaStore
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
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
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

    var mediaUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(useTest, mimeFilter, pageSize, hasPermissions) {
        if (!useTest && urlsFromArg.isEmpty() && hasPermissions) {
            val assets = MediaStoreRepository.default.queryPage(
                context = context,
                filter = mimeFilter,
                page = 0,
                pageSize = pageSize.coerceAtLeast(1)
            )
            // 当前预览仅对图片做缩放预览；视频后续可接入缩略图/播放器
            mediaUrls = assets.filter { !it.isVideo }.map { it.uri.toString() }
        }
    }

    val needMedia = urlsFromArg.isEmpty() && !useTest
    val urls = when {
        urlsFromArg.isNotEmpty() -> urlsFromArg
        useTest -> TEST_IMAGE_URLS
        else -> mediaUrls
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            needMedia && !hasPermissions -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("读取媒体库需要权限，请授权后继续")
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("去授权")
                    }
                }
            }
            urls.isEmpty() -> {
                Text("暂无可预览的媒体")
            }
            else -> {
                val focusIndex = urls.indexOf(focusUri).coerceIn(0, urls.size - 1)
                Log.d(TAG, "URLs count: ${urls.size}, requested focusIndex: $focusIndex, focusUri:$focusUri, urls:$urls")
                ImagePagerScreen(
                    urls = urls,
                    startIndex = focusIndex,
                    onBack = onBack,
                    entryStartBounds = startBounds
                )
            }
        }
    }
}

