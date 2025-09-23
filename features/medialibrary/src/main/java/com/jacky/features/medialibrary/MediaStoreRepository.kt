package com.jacky.features.medialibrary

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mime 类型过滤 */
enum class MimeFilter {
    Images, Videos, ImagesAndVideos
}

/** 媒体条目，兼容图片/视频 */
data class MediaAsset(
    val uri: Uri,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val dateAddedSec: Long?,
    val isVideo: Boolean
)

interface MediaStoreRepository {
    /** 分页查询媒体库，支持 mime 过滤与分页。按日期倒序。 */
    suspend fun queryPage(
        context: Context,
        filter: MimeFilter,
        page: Int,
        pageSize: Int,
        favoritesOnly: Boolean = false,
        liveOnly: Boolean = false,
    ): List<MediaAsset>

    /** 统计当前筛选条件下的总条数，用于占位与快速跳转 */
    suspend fun count(
        context: Context,
        filter: MimeFilter,
        favoritesOnly: Boolean = false,
        liveOnly: Boolean = false,
    ): Int

    companion object {
        val default: MediaStoreRepository = AndroidMediaStoreRepository()
    }
}

private class AndroidMediaStoreRepository : MediaStoreRepository {
    override suspend fun queryPage(
        context: Context,
        filter: MimeFilter,
        page: Int,
        pageSize: Int,
        favoritesOnly: Boolean,
        liveOnly: Boolean,
    ): List<MediaAsset> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver

        // liveOnly 目前仅尽力支持：强制限定为图片集合
        val effectiveFilter = if (liveOnly) MimeFilter.Images else filter

        var baseSelection: String
        var baseArgs: MutableList<String>
        val uri = filesCollection()
        when (effectiveFilter) {
            MimeFilter.Images -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                baseArgs = mutableListOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            }
            MimeFilter.Videos -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                baseArgs = mutableListOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }
            MimeFilter.ImagesAndVideos -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
                baseArgs = mutableListOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            }
        }

        // 收藏筛选：Android 11+ (API 30) 支持 IS_FAVORITE 列
        if (favoritesOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseSelection = "($baseSelection) AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1"
        }

        val selection = baseSelection
        val selectionArgs = baseArgs.toTypedArray()

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION, // null for images
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val sortOrder = MediaStore.Files.FileColumns.DATE_ADDED + " DESC"

        val list = ArrayList<MediaAsset>(pageSize)
        val offset = (page.coerceAtLeast(0)) * pageSize

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bundle = Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            }
            cr.query(uri, projection, bundle, null)
        } else {
            cr.query(uri, projection, selection, selectionArgs, "$sortOrder LIMIT $pageSize OFFSET $offset")
        }

        cursor?.use { c ->
            val idxId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val idxMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val idxW = c.getColumnIndexOrNull(MediaStore.Files.FileColumns.WIDTH)
            val idxH = c.getColumnIndexOrNull(MediaStore.Files.FileColumns.HEIGHT)
            val idxDur = c.getColumnIndexOrNull(MediaStore.Video.VideoColumns.DURATION)
            val idxDate = c.getColumnIndexOrNull(MediaStore.Files.FileColumns.DATE_ADDED)
            val idxType = c.getColumnIndexOrNull(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idxId)
                val mime = c.getStringOrNull(idxMime)
                val w = idxW?.let { if (it >= 0) c.getIntOrNull(it) else null }
                val h = idxH?.let { if (it >= 0) c.getIntOrNull(it) else null }
                val dur = idxDur?.let { if (it >= 0) c.getLongOrNull(it) else null }
                val date = idxDate?.let { if (it >= 0) c.getLongOrNull(it) else null }
                val mediaType = idxType?.let { if (it >= 0) c.getIntOrNull(it) else null }
                val isVideo = when (filter) {
                    MimeFilter.Images -> false
                    MimeFilter.Videos -> true
                    MimeFilter.ImagesAndVideos -> mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                }
                val itemUri = when {
                    isVideo -> ContentUris.withAppendedId(videosCollection(), id)
                    else -> when (filter) {
                        MimeFilter.Images -> ContentUris.withAppendedId(imagesCollection(), id)
                        MimeFilter.Videos -> ContentUris.withAppendedId(videosCollection(), id) // should not happen
                        MimeFilter.ImagesAndVideos -> if (isVideo) ContentUris.withAppendedId(videosCollection(), id) else ContentUris.withAppendedId(imagesCollection(), id)
                    }
                }
                list += MediaAsset(
                    uri = itemUri,
                    mimeType = mime,
                    width = w,
                    height = h,
                    durationMs = dur,
                    dateAddedSec = date,
                    isVideo = isVideo
                )
            }
        }
        if (cursor == null) {
            Log.w("MediaRepo", "queryPage: cursor=null filter=${effectiveFilter} favorites=${favoritesOnly} live=${liveOnly} page=${page} pageSize=${pageSize} offset=${offset}")
        } else {
            Log.d("MediaRepo", "queryPage: returned=${list.size} filter=${effectiveFilter} favorites=${favoritesOnly} live=${liveOnly} page=${page} pageSize=${pageSize} offset=${offset} sdk=${Build.VERSION.SDK_INT}")
        }

        list
    }

    override suspend fun count(
        context: Context,
        filter: MimeFilter,
        favoritesOnly: Boolean,
        liveOnly: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val effectiveFilter = if (liveOnly) MimeFilter.Images else filter
        var baseSelection: String
        var baseArgs: MutableList<String>
        when (effectiveFilter) {
            MimeFilter.Images -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                baseArgs = mutableListOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            }
            MimeFilter.Videos -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                baseArgs = mutableListOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }
            MimeFilter.ImagesAndVideos -> {
                baseSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
                baseArgs = mutableListOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                )
            }
        }
        if (favoritesOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseSelection = "($baseSelection) AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1"
        }
        val selection = baseSelection
        val selectionArgs = baseArgs.toTypedArray()
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val uri = filesCollection()
        val cursor = cr.query(uri, projection, selection, selectionArgs, null)
        val total = cursor?.use { it.count } ?: 0
        Log.d("MediaRepo", "count: total=$total filter=$effectiveFilter fav=$favoritesOnly live=$liveOnly")
        total
    }

    private fun imagesCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun videosCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun filesCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Files.getContentUri("external")
}

// Safe getters for nullable columns
private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun android.database.Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
private fun android.database.Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun android.database.Cursor.getColumnIndexOrNull(name: String): Int? = try { getColumnIndexOrThrow(name) } catch (_: Exception) { null }

