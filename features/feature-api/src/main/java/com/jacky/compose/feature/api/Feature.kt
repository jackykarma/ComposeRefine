package com.jacky.compose.feature.api

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

interface Feature {
    val id: String
    val displayName: String
    fun register(navGraphBuilder: NavGraphBuilder, navController: NavHostController)
}

