package com.jacky.features.mediagrid

import android.app.Application
import android.content.Context
import android.util.Log

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import com.jacky.features.medialibrary.MediaAsset
import com.jacky.features.medialibrary.MediaStoreRepository
import com.jacky.features.medialibrary.MimeFilter
import kotlinx.coroutines.flow.Flow

class MediaGridViewModel(app: Application) : AndroidViewModel(app) {
    private val applicationContext: Context = app.applicationContext

    fun pagingData(
        filter: MimeFilter,
        pageSize: Int,
        favoritesOnly: Boolean,
        liveOnly: Boolean,
        initialPageKey: Int? = null,
        repository: MediaStoreRepository = MediaStoreRepository.default,
    ): Flow<PagingData<MediaAsset>> {
        val jump = (pageSize * 5).coerceAtLeast(pageSize)
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = true,
                prefetchDistance = pageSize,
                jumpThreshold = (pageSize * 2).coerceAtLeast(pageSize),
            ),
            initialKey = initialPageKey,
            pagingSourceFactory = {
                MediaPagingSource(
                    context = applicationContext,
                    repository = repository,
                    filter = filter,
                    pageSize = pageSize,
                    favoritesOnly = favoritesOnly,
                    liveOnly = liveOnly,
                )
            }
        ).flow.cachedIn(viewModelScope)
    }

    suspend fun totalCount(
        filter: MimeFilter,
        favoritesOnly: Boolean,
        liveOnly: Boolean,
        repository: MediaStoreRepository = MediaStoreRepository.default,
    ): Int = repository.count(applicationContext, filter, favoritesOnly, liveOnly)


}

private class MediaPagingSource(
    private val context: Context,
    private val repository: MediaStoreRepository,
    private val filter: MimeFilter,
    private val pageSize: Int,
    private val favoritesOnly: Boolean,
    private val liveOnly: Boolean,
) : PagingSource<Int, MediaAsset>() {

    override val jumpingSupported: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaAsset> {
        val page = params.key ?: 0
        return try {
            val sizeHint = params.loadSize
            // Fetch enough pages to satisfy sizeHint to avoid under-fetch with placeholders
            val pagesToLoad = ((sizeHint + pageSize - 1) / pageSize).coerceAtLeast(1)
            Log.d("MediaPaging", "load start: page=${page} sizeHint=${sizeHint} pagesToLoad=${pagesToLoad} jump=${jumpingSupported}")
            val collected = ArrayList<MediaAsset>(sizeHint)
            var currentPage = page
            repeat(pagesToLoad) {
                val chunk = repository.queryPage(
                    context = context,
                    filter = filter,
                    page = currentPage,
                    pageSize = pageSize,
                    favoritesOnly = favoritesOnly,
                    liveOnly = liveOnly,
                )
                collected.addAll(chunk)
                if (chunk.size < pageSize) {
                    // Reached the end
                    currentPage += 1
                    return@repeat
                }
                currentPage += 1
                if (collected.size >= sizeHint) return@repeat
            }
            val data = if (collected.size > sizeHint) collected.subList(0, sizeHint) else collected

            val offset = (page * pageSize).coerceAtLeast(0)
            val prev = if (page > 0) page - 1 else null
            val advancedPages = ((data.size + pageSize - 1) / pageSize)
            val next = if (data.isEmpty()) null else page + advancedPages

            if (params is LoadParams.Refresh) {
                val total = repository.count(context, filter, favoritesOnly, liveOnly)
                val itemsBefore = offset
                val itemsAfter = (total - offset - data.size).coerceAtLeast(0)
                Log.d("MediaPaging", "load done: page=${page} returned=${data.size} total=${total} before=${itemsBefore} after=${itemsAfter}")
                LoadResult.Page(
                    data = data,
                    prevKey = prev,
                    nextKey = next,
                    itemsBefore = itemsBefore,
                    itemsAfter = itemsAfter,
                )
            } else {
                Log.d("MediaPaging", "load done: page=${page} returned=${data.size}")
                LoadResult.Page(data = data, prevKey = prev, nextKey = next)
            }
        } catch (e: Exception) {
            Log.e("MediaPaging", "load error page=${page}: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MediaAsset>): Int? {
        val anchor = state.anchorPosition ?: return null
        // Map anchor absolute item position to page index directly to support large jumps
        val key = (anchor / pageSize).coerceAtLeast(0)
        Log.d("MediaPaging", "getRefreshKey: anchor=${anchor} key=${key}")
        return key
    }
}

