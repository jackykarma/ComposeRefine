package com.jacky.compose.refine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jacky.compose.refine.features.FeatureRegistry
import com.jacky.compose.refine.ui.theme.ComposeRefineTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeRefineTheme {
                val navController = rememberNavController()
                val features = remember { FeatureRegistry.discover() }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            LazyColumn(modifier = Modifier.padding(padding)) {
                                items(features) { feature ->
                                    Button(onClick = {
                                        navController.navigate(feature.id)
                                    }) {
                                        Text(feature.displayName)
                                    }
                                }
                            }
                        }
                        // 遍历注册所有Feature到路由管理中
                        features.forEach { it.register(this, navController) }
                    }
                }
            }
        }
    }
}
