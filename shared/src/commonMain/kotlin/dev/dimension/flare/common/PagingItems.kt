package dev.dimension.flare.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.CombinedLoadStates
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public class PagingItems<T : Any> internal constructor(cached: PagingData<T>?) {
    private var generation = 0L
    private var collecting = false
    private var presentedRefresh = false
    private var pendingRefresh: Refresh? = null

    private val presenter =
        object : PagingDataPresenter<T>(cachedPagingData = cached) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) {
                itemSnapshotList = snapshot()
                if (event is PagingDataEvent.Refresh) presentedRefresh = true
            }
        }

    internal var itemSnapshotList: ItemSnapshotList<T> by mutableStateOf(presenter.snapshot())
        private set

    internal var loadState: CombinedLoadStates by mutableStateOf(presenter.loadStateFlow.value ?: initialLoadStates)
        private set

    init {
        presenter.addLoadStateListener { states ->
            loadState = states
            completeRefresh(states)
        }
        // A refresh can present identical states. Paging dispatches this after applying the page and its states.
        presenter.addOnPagesUpdatedListener {
            presenter.loadStateFlow.value?.let(::completeRefresh)
        }
    }

    internal suspend fun collect(pages: Flow<PagingData<T>>) {
        withContext(Dispatchers.Main) {
            try {
                pages.collectLatest { data ->
                    generation++
                    presentedRefresh = false
                    collecting = true
                    try {
                        presenter.collectFrom(data)
                    } finally {
                        collecting = false
                    }
                }
            } finally {
                pendingRefresh?.completed?.cancel()
                pendingRefresh = null
            }
        }
    }

    internal fun refresh() = presenter.refresh()

    internal fun retry() = presenter.retry()

    internal operator fun get(index: Int): T? {
        presenter[index]
        return itemSnapshotList[index]
    }

    internal suspend fun refreshSuspend(): Unit =
        withContext(Dispatchers.Main) {
            val request = pendingRefresh ?: run {
                // Static and disposed PagingData cannot start an active refresh.
                if (!collecting) {
                    refresh()
                    return@withContext
                }
                Refresh(generation).also {
                    pendingRefresh = it
                    refresh()
                }
            }
            request.completed.await()
        }

    private fun completeRefresh(states: CombinedLoadStates) {
        val request = pendingRefresh ?: return
        if (request.generation == generation) return

        val source = states.source.refresh
        // A new Paging generation can still report the previous mediator error before its source loads.
        val finished =
            presentedRefresh && source is LoadState.NotLoading && states.mediator?.refresh !is LoadState.Loading
        if (source is LoadState.Error || finished) {
            pendingRefresh = null
            request.completed.complete(Unit)
        }
    }

    private class Refresh(val generation: Long) {
        val completed = CompletableDeferred<Unit>()
    }
}

@Composable
@HiddenFromObjC
public fun <T : Any> Flow<PagingData<T>>.collectAsPagingItems(): PagingItems<T> {
    val items = remember(this) { PagingItems((this as? SharedFlow<PagingData<T>>)?.replayCache?.firstOrNull()) }
    LaunchedEffect(items) { items.collect(this@collectAsPagingItems) }
    return items
}

private val initialLoadStates =
    LoadStates(refresh = LoadState.Loading, prepend = LoadState.NotLoading(false), append = LoadState.NotLoading(false)).let {
        CombinedLoadStates(refresh = it.refresh, prepend = it.prepend, append = it.append, source = it)
    }
