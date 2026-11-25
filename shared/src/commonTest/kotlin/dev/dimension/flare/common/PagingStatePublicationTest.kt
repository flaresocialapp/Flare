package dev.dimension.flare.common

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.RemoteMediator
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
class PagingStatePublicationTest : RobolectricTest() {
    @Test
    fun publishedItemKeysSurviveAShorterPagingGeneration() =
        withPagingStates { pages, states ->
            pages.value = page(*Array(26) { "post-$it" })
            runCurrent()
            val published = states.last()
            val itemKey = published.itemKey { it }

            pages.value = page(*Array(25) { "post-$it" })
            runCurrent()

            assertEquals(26, published.itemCount)
            assertEquals(25, states.last().itemCount)
            val currentItemKey = states.last().itemKey { it }
            assertEquals(List(25) { "post-$it" }, List(25, currentItemKey))
            assertEquals("post-25", itemKey(25))
        }

    @Test
    fun publishedContentTypesSurviveAShorterPagingGeneration() =
        withPagingStates { pages, states ->
            pages.value = page("main", "reply")
            runCurrent()
            val contentType = states.last().itemContentType { it }

            pages.value = page("main")
            runCurrent()

            assertEquals("reply", contentType(1))
        }

    @Test
    fun publishedItemsSurviveAShorterPagingGeneration() =
        withPagingStates { pages, states ->
            pages.value = page("main", "reply")
            runCurrent()
            val published = states.last()

            pages.value = page("main")
            runCurrent()

            assertEquals("reply", published[1])
            assertEquals(published.peek(1), published[1])
        }

    @Test
    fun publishedKeysTypesAndItemsStayTogetherWhenItemsAreReordered() =
        withPagingStates { pages, states ->
            pages.value = page("main", "reply")
            runCurrent()
            val published = states.last()
            val itemKey = published.itemKey { it }
            val contentType = published.itemContentType { it }

            pages.value = page("reply", "main")
            runCurrent()

            assertEquals("main", itemKey(0))
            assertEquals("main", contentType(0))
            assertEquals("main", published[0])
            assertEquals("reply", states.last()[0])
        }

    @Test
    fun publishedItemsDoNotGrowWithANewerPagingGeneration() =
        withPagingStates { pages, states ->
            val published = states.last()

            pages.value = page("main", "reply")
            runCurrent()

            assertNull(published[-1])
            assertNull(published[1])
            assertEquals("reply", states.last()[1])
        }

    @Test
    fun currentItemsTriggerPrefetchWhilePublishedPlaceholdersStayStable() =
        withPagingStates { pages, states ->
            val loadedOffsets = mutableListOf<Int>()
            val pager =
                Pager(
                    config = PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 1, enablePlaceholders = true),
                    pagingSourceFactory = {
                        object : PagingSource<Int, String>() {
                            override fun getRefreshKey(state: androidx.paging.PagingState<Int, String>): Int? = null

                            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
                                val start = params.key ?: 0
                                val end = minOf(start + params.loadSize, 6)
                                loadedOffsets += start
                                return LoadResult.Page(
                                    data = (start until end).map { "post-$it" },
                                    prevKey = null,
                                    nextKey = end.takeIf { it < 6 },
                                    itemsBefore = start,
                                    itemsAfter = 6 - end,
                                )
                            }
                        }
                    },
                )
            backgroundScope.launch {
                pager.flow.collect { pages.value = it }
            }
            runCurrent()
            val published = states.last()
            val itemKey = published.itemKey { it.removePrefix("post-").toInt() + 2 }
            val contentType = published.itemContentType { "post" }
            val placeholderKey = itemKey(2)
            val placeholderType = contentType(2)

            assertEquals(6, published.itemCount)
            assertNull(published.peek(2))
            assertEquals(published.itemKey()(2), placeholderKey)
            assertNotEquals(itemKey(0), placeholderKey)
            assertNotEquals(itemKey(3), placeholderKey)
            assertEquals(listOf(0), loadedOffsets)

            assertEquals("post-1", published[1])
            runCurrent()

            assertEquals(listOf(0, 2), loadedOffsets)
            assertEquals("post-2", states.last().peek(2))
            assertEquals(placeholderKey, itemKey(2))
            assertEquals(placeholderType, contentType(2))
            assertNull(published[5])
            runCurrent()
            assertEquals(listOf(0, 2), loadedOffsets)

            assertEquals("post-3", states.last()[3])
            runCurrent()
            assertEquals(listOf(0, 2, 4), loadedOffsets)
            assertEquals("post-5", states.last().peek(5))
        }

    @Test
    fun repliesPublishANewStateAfterTheMainPost() =
        withPagingStates { pages, states ->
            val mainOnly = states.last()

            pages.value = page("main", "reply")
            runCurrent()
            val withReplies = states.last()

            assertNotEquals(mainOnly, withReplies)
            assertEquals(1, mainOnly.itemCount)
            assertEquals(2, withReplies.itemCount)
            assertEquals("reply", withReplies.peek(1))
        }

    @Test
    fun editedPostsPublishANewStateWithoutChangingTheCount() =
        withPagingStates { pages, states ->
            val original = states.last()

            pages.value = page("edited main")
            runCurrent()
            val edited = states.last()

            assertNotEquals(original, edited)
            assertEquals("main", original.peek(0))
            assertEquals("edited main", edited.peek(0))
        }

    @Test
    fun refreshChangesPublishANewStateWithoutChangingTheItems() =
        withPagingStates { pages, states ->
            val idle = states.last()

            pages.value = page("main", refresh = LoadState.Loading)
            runCurrent()
            val refreshing = states.last()

            assertNotEquals(idle, refreshing)
            assertFalse(idle.isRefreshing)
            assertTrue(refreshing.isRefreshing)
        }

    @Test
    fun contextLoadingAndErrorsPublishWhilePagingRefreshIsStillRunning() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val pages = MutableStateFlow(page("main", "cached reply", refresh = LoadState.Loading))
            val context = MutableStateFlow(LoadStates(LoadState.NotLoading(false), LoadState.Loading, LoadState.Loading))
            val states = mutableListOf<PagingState.Success<String>>()
            var retries = 0
            val job =
                launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        val loading by context.collectAsState()
                        pages.collectAsPagingItems().toPagingState(loading) { retries++ }
                    }.collect { it.onSuccess { states += this } }
                }
            try {
                runCurrent()
                val pending = states.last()
                assertTrue(pending.isRefreshing)
                assertTrue(pending.appendState is LoadState.Loading)
                assertEquals("cached reply", pending.peek(1))

                context.value = context.value.copy(append = LoadState.Error(Exception("reply failed")))
                runCurrent()
                val failed = states.last()
                assertNotEquals(pending, failed)
                assertTrue(failed.appendState is LoadState.Error)
                assertEquals(2, failed.itemCount)
                failed.retry()
                assertEquals(1, retries)

                context.value = context.value.copy(append = LoadState.NotLoading(true))
                runCurrent()
                assertTrue(states.last().appendState is LoadState.Loading)
                assertTrue(states.last().isRefreshing)

                context.value = context.value.copy(prepend = LoadState.Error(Exception("parent failed")))
                runCurrent()
                assertTrue(states.last().appendState is LoadState.Error)
                states.last().retry()
                assertEquals(2, retries)
            } finally {
                job.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun fastRefreshCompletesWithoutSamplingTheLoadingState() =
        withPagingStates { pages, states ->
            var loads = 0
            val pager = refreshPager {
                loads++
                PagingSource.LoadResult.Page(listOf("main"), prevKey = null, nextKey = null)
            }
            val collector = backgroundScope.launch { pager.flow.collect { pages.value = it } }
            val callerScheduler = TestCoroutineScheduler()
            val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(callerScheduler))
            runCurrent()

            try {
                assertEquals(1, loads)
                val refresh = caller.async { states.last().refreshSuspend() }
                callerScheduler.runCurrent()
                runCurrent()
                assertEquals(2, loads)
                assertEquals("main", states.last().peek(0))

                callerScheduler.runCurrent()
                assertTrue(refresh.isCompleted, "Refresh must return after Paging completes, even when the caller misses Loading")
                refresh.await()
            } finally {
                caller.cancel()
                callerScheduler.runCurrent()
                collector.cancelAndJoin()
            }
        }

    @Test
    fun refreshWaitsForTheRequestedPageToBePresented() =
        withPagingStates { pages, states ->
            var loads = 0
            val allowRefreshLoad = CompletableDeferred<Unit>()
            val pager = refreshPager {
                val data = if (++loads == 1) "main" else {
                    allowRefreshLoad.await()
                    "refreshed"
                }
                PagingSource.LoadResult.Page(listOf(data), prevKey = null, nextKey = null)
            }
            backgroundScope.launch { pager.flow.collect { pages.value = it } }
            runCurrent()

            val refresh = async { withTimeout(5_000) { states.last().refreshSuspend() } }
            runCurrent()
            assertEquals(2, loads)
            advanceTimeBy(2_000)
            assertFalse(refresh.isCompleted)

            allowRefreshLoad.complete(Unit)
            runCurrent()
            assertEquals("refreshed", states.last().peek(0))
            refresh.await()
        }

    @Test
    fun refreshWaitsForRemoteMediatorEvenAfterAPreviousError() =
        withPagingStates { pages, states ->
            val remoteResults = Channel<RemoteMediator.MediatorResult>()
            var remoteRefreshes = 0
            val mediator =
                object : RemoteMediator<Int, String>() {
                    override suspend fun initialize(): InitializeAction = InitializeAction.SKIP_INITIAL_REFRESH

                    override suspend fun load(
                        loadType: LoadType,
                        state: androidx.paging.PagingState<Int, String>,
                    ): MediatorResult {
                        if (loadType != LoadType.REFRESH) return MediatorResult.Success(endOfPaginationReached = true)
                        remoteRefreshes++
                        return remoteResults.receive()
                    }
                }
            var pagesLoaded = 0
            val pager = refreshPager(mediator) {
                PagingSource.LoadResult.Page(listOf("page-${++pagesLoaded}"), prevKey = null, nextKey = null)
            }
            backgroundScope.launch { pager.flow.collect { pages.value = it } }
            runCurrent()

            val refresh = async { withTimeout(5_000) { states.last().refreshSuspend() } }
            runCurrent()
            assertEquals(1, remoteRefreshes)
            assertEquals("page-2", states.last().peek(0))
            assertFalse(refresh.isCompleted)

            remoteResults.send(RemoteMediator.MediatorResult.Error(IllegalStateException("refresh failed")))
            runCurrent()
            assertTrue(refresh.isCompleted)
            refresh.await()

            val nextRefresh = async { withTimeout(5_000) { states.last().refreshSuspend() } }
            runCurrent()
            assertEquals(2, remoteRefreshes)
            assertEquals("page-3", states.last().peek(0))
            assertFalse(nextRefresh.isCompleted, "The previous refresh error must not complete a new request")

            remoteResults.send(RemoteMediator.MediatorResult.Success(endOfPaginationReached = true))
            runCurrent()
            assertTrue(nextRefresh.isCompleted)
            nextRefresh.await()
        }

    private fun refreshPager(
        mediator: RemoteMediator<Int, String>? = null,
        load: suspend () -> PagingSource.LoadResult<Int, String>,
    ): Pager<Int, String> =
        Pager(PagingConfig(pageSize = 2, initialLoadSize = 2, enablePlaceholders = false), remoteMediator = mediator) {
            object : PagingSource<Int, String>() {
                override fun getRefreshKey(state: androidx.paging.PagingState<Int, String>): Int? = null

                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> = load()
            }
        }

    private fun withPagingStates(
        block: suspend TestScope.(MutableStateFlow<PagingData<String>>, List<PagingState.Success<String>>) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pages = MutableStateFlow(page("main"))
        val states = mutableListOf<PagingState.Success<String>>()
        val job =
            launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    pages.collectAsPagingItems().toPagingState()
                }.collect { state ->
                    state.onSuccess { states += this }
                }
            }
        try {
            runCurrent()
            block(pages, states)
        } finally {
            job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun page(
        vararg items: String,
        refresh: LoadState = LoadState.NotLoading(endOfPaginationReached = false),
    ): PagingData<String> =
        PagingData.from(
            data = items.toList(),
            sourceLoadStates =
                LoadStates(
                    refresh = refresh,
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = false),
                ),
        )
}
