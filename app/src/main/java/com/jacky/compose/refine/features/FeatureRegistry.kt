package com.jacky.compose.refine.features

import com.jacky.compose.feature.api.Feature

object FeatureRegistry {
    private val candidates = listOf(
        "com.jacky.features.imagepreview.ImagePreviewFeature"
    )

    fun discover(): List<Feature> = candidates.mapNotNull { className ->
        runCatching {
            Class.forName(className).getDeclaredConstructor().newInstance() as Feature
        }.getOrNull()
    }
}

