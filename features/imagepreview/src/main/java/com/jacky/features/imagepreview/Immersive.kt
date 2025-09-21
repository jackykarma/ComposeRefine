package com.jacky.features.imagepreview

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Simple immersive (full screen) controller.
 * - Toggle by updating [immersive] state
 * - Enters immersive automatically if [autoEnterOn] becomes true
 */
@Composable
fun rememberImmersiveController(initial: Boolean = false): MutableState<Boolean> {
    return remember { mutableStateOf(initial) }
}

@Composable
fun ImmersiveSystemBarsEffect(immersive: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(immersive) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, view)
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Optional: When immersive, handle back to exit immersive first. */
@Composable
fun ImmersiveBackHandler(immersiveState: MutableState<Boolean>) {
    BackHandler(enabled = immersiveState.value) {
        immersiveState.value = false
    }
}

