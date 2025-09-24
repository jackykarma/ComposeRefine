package com.jacky.compose.refine

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class ComposeRefineApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClassMb = am.memoryClass // app 可用内存（MB）
        val targetMemoryMb = (memoryClassMb * 0.5f).toInt().coerceIn(128, 1024) // 目标 50%，128MB~1GB 之间
        val memoryCacheBytes = targetMemoryMb * 1024L * 1024L

        return ImageLoader.Builder(this)
            .components {
                // 解码视频首帧
                add(VideoFrameDecoder.Factory())
            }
            // 加大内存缓存，减少列表回滚时的重复解码
            .memoryCache(
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryCacheBytes.toInt())
                    .build()
            )
            // 启用磁盘缓存（对网络/部分本地源仍有帮助，避免二次 I/O/解码）
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024L * 1024L) // 512MB
                    .build()
            )
            .respectCacheHeaders(false)
            .crossfade(false)
            .allowRgb565(true) // 降低单张位图内存，提高整体命中率
            .build()
    }
}
