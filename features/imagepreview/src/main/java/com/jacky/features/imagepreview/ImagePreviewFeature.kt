package com.jacky.features.imagepreview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jacky.compose.feature.api.Feature
import coil.compose.AsyncImage
import androidx.navigation.NavType

class ImagePreviewFeature : Feature {
    override val id: String = ROUTE
    override val displayName: String = "图片预览"

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavHostController) {
        navGraphBuilder.composable(
            route = "$ROUTE?url={url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true })
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url")
            ImagePreviewScreen(url)
        }
    }

    companion object {
        private const val ROUTE = "image_preview"
    }
}

@Composable
private fun ImagePreviewScreen(url: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Text("无图片URL，展示默认占位图")
        } else {
            AsyncImage(model = url, contentDescription = "Image Preview")
        }
    }
}

