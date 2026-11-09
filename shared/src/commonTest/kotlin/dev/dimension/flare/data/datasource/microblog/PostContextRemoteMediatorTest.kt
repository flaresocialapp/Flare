package dev.dimension.flare.data.datasource.microblog

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.database.cache.model.DbPagingKey
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.paging.ContextPageUpdate
import dev.dimension.flare.data.datasource.microblog.paging.ContextUpdate
import dev.dimension.flare.data.datasource.microblog.paging.OffsetFromStartPagingKey
import dev.dimension.flare.data.datasource.microblog.paging.OffsetFromStartPagingSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.PostContextLoader
import dev.dimension.flare.data.datasource.microblog.paging.PostContextRemoteMediator
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageCache
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageLoader
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePageItem
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePagingMapper
import dev.dimension.flare.data.datasource.microblog.paging.TimelineRemoteMediator
import dev.dimension.flare.data.datasource.microblog.paging.toContextUpdate
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.startPlatformEventLoop
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.asTimelinePostItem
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import dev.dimension.flare.common.PagingState as UiPagingState

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
class PostContextRemoteMediatorTest : RobolectricTest() {
    private lateinit var db: CacheDatabase
    private val accountKey = MicroBlogKey("account", "example.com")
    private val mainKey = key("main")
    private val pagingKey = "context"
    private val state = PagingState<OffsetFromStartPagingKey, TimelinePageItem>(emptyList(), null, PagingConfig(20), 0)

    @BeforeTest
    fun setup() {
        startKoin { modules(module { testSingle<PlatformFormatter> { TestFormatter() } }) }
        db =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        stopKoin()
    }

    @Test
    fun appendRetryIsQueuedUntilPagingRegistersTheFailure() = assertRetryAcrossPagingHandoff(PagingRequest.Append(""), early = true)

    @Test
    fun prependRetryIsQueuedUntilPagingRegistersTheFailure() = assertRetryAcrossPagingHandoff(PagingRequest.Prepend(""), early = true)

    @Test
    fun refreshRetryIsQueuedUntilPagingRegistersTheFailure() = assertRetryAcrossPagingHandoff(PagingRequest.Refresh, early = true)

    @Test
    fun retryAfterPagingRegistersTheFailureStillLoadsReplies() = assertRetryAcrossPagingHandoff(PagingRequest.Append(""), early = false)

    @Test
    fun aFailedRetryWaitsForAnotherClick() = assertRetryAcrossPagingHandoff(PagingRequest.Append(""), early = true, failureCount = 2)

    private fun assertRetryAcrossPagingHandoff(
        failingRequest: PagingRequest,
        early: Boolean,
        failureCount: Int = 1,
    ) = runTest(timeout = 10.seconds) {
        startPlatformEventLoop()
        seed(listOf("main"))
        db.pagingTimelineDao().insertPagingKey(DbPagingKey(pagingKey))
        val errorReady = CompletableDeferred<Unit>()
        val publishError = CompletableDeferred<Unit>()
        val pagingRefresh = MutableStateFlow<LoadState?>(null)
        val requests = mutableListOf<PagingRequest>()
        var attempts = 0
        val mediator =
            PostContextRemoteMediator(
                loader(update = { request, result, initial ->
                    val update = result.toContextUpdate(mainKey, request, initial)
                    if (request == PagingRequest.Refresh) {
                        if (failingRequest is PagingRequest.Prepend) update.copy(before = null) else update.copy(after = null)
                    } else {
                        update
                    }
                }) { request ->
                    requests += request
                    if (request == failingRequest && ++attempts <= failureCount) error("Context failure $attempts")
                    when (request) {
                        PagingRequest.Refresh -> {
                            if (failingRequest is PagingRequest.Prepend) {
                                PagingResult(listOf(post("main")), previousKey = "")
                            } else {
                                PagingResult(listOf(post("main")), nextKey = "")
                            }
                        }

                        is PagingRequest.Append -> {
                            PagingResult(listOf(post("reply")))
                        }

                        is PagingRequest.Prepend -> {
                            PagingResult(listOf(post("parent")))
                        }
                    }
                },
                db,
            )
        val pager =
            Pager(
                config = offsetPagingConfig,
                remoteMediator =
                    object : RemoteMediator<OffsetFromStartPagingKey, TimelinePageItem>() {
                        override suspend fun initialize() = mediator.initialize()

                        override suspend fun load(
                            loadType: LoadType,
                            state: PagingState<OffsetFromStartPagingKey, TimelinePageItem>,
                        ): MediatorResult {
                            val result = mediator.load(loadType, state)
                            // Hold the handoff after our footer exposes the error, before Paging receives it.
                            if (result is MediatorResult.Error && !errorReady.isCompleted) {
                                errorReady.complete(Unit)
                                publishError.await()
                            }
                            return result
                        }
                    },
                pagingSourceFactory = { OffsetFromStartPagingSource(TimelineDbPageLoader(db, pagingKey, TimelineDbPageCache())) },
            )
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val states = MutableStateFlow<UiPagingState<TimelinePageItem>>(UiPagingState.Loading())
        val job =
            launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    val loading by mediator.loadStates.collectAsState()
                    val items = pager.flow.collectAsLazyPagingItems()
                    LaunchedEffect(items) {
                        mediator.retryWhenReady(snapshotFlow { items.loadState.mediator }, items::retry)
                    }
                    val ui =
                        items.toPagingState(loading) {
                            val queued = mediator.prepareRetry()
                            if (!queued || items.loadState.source.hasError) items.retry()
                        }
                    ui to items.loadState.mediator?.refresh
                }.collect {
                    states.value = it.first
                    pagingRefresh.value = it.second
                }
            }
        try {
            val failure = states.filterIsInstance<UiPagingState.Success<TimelinePageItem>>().first { it.appendState is LoadState.Error }
            errorReady.await()
            assertEquals(listOf("main"), rows())
            if (early) {
                failure.retry()
                runCurrent()
                assertEquals(1, attempts)
            }
            publishError.complete(Unit)
            if (!early) {
                pagingRefresh.first { it is LoadState.Error }
                failure.retry()
            }
            if (failureCount == 2) {
                val failedAgain =
                    states.filterIsInstance<UiPagingState.Success<TimelinePageItem>>().first {
                        (it.appendState as? LoadState.Error)?.error?.message == "Context failure 2"
                    }
                runCurrent()
                assertEquals(2, attempts)
                failedAgain.retry()
            }
            states.filterIsInstance<UiPagingState.Success<TimelinePageItem>>().first {
                it.appendState is LoadState.NotLoading && it.itemCount == 2
            }
            assertEquals(failureCount + 1, attempts)
            assertEquals(if (failingRequest is PagingRequest.Prepend) listOf("parent", "main") else listOf("main", "reply"), rows())
            assertEquals(if (failingRequest == PagingRequest.Refresh) 2 else 1, requests.count { it == PagingRequest.Refresh })
        } finally {
            job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun refreshFailureDoesNotConsumeRetryInTheActiveSideCallback() =
        runTest(timeout = 10.seconds) {
            var attempts = 0
            val failure = IllegalStateException("Refresh failed")
            lateinit var mediator: PostContextRemoteMediator
            mediator =
                PostContextRemoteMediator(
                    loader {
                        if (++attempts == 1) throw failure
                        PagingResult(listOf(post("main")))
                    },
                    db,
                    notifyError = { mediator.prepareRetry() },
                )
            val pagingStates = MutableStateFlow<LoadStates?>(null)
            val retried = CompletableDeferred<RemoteMediator.MediatorResult>()
            backgroundScope.launch {
                mediator.retryWhenReady(pagingStates) {
                    launch { retried.complete(mediator.load(LoadType.REFRESH, state)) }
                }
            }
            assertIs<RemoteMediator.MediatorResult.Error>(mediator.load(LoadType.REFRESH, state))
            pagingStates.value = LoadStates(LoadState.Error(failure), LoadState.NotLoading(true), LoadState.NotLoading(true))
            assertIs<RemoteMediator.MediatorResult.Success>(retried.await())
            assertEquals(2, attempts)
            assertFalse(mediator.prepareRetry(), "Source-only errors must use Paging's own retry")
        }

    @Test
    fun stagedRefreshKeepsLongCacheAndLoadsRepliesWithoutAnEdgeHint() =
        runTest {
            val cached = listOf("parent", "main") + (1..60).map { "old-$it" }
            seed(cached)
            val replyStarted = CompletableDeferred<Unit>()
            val replyResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val mediator =
                PostContextRemoteMediator(
                    loader(
                        update = { request, result, initial ->
                            if (request == PagingRequest.Refresh) {
                                ContextUpdate(result.data)
                            } else {
                                result.toContextUpdate(mainKey, PagingRequest.Refresh, initial)
                            }
                        },
                    ) { request ->
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(listOf(post("main", "fresh main")), nextKey = "first")
                            }

                            is PagingRequest.Append -> {
                                assertEquals("first", request.nextKey)
                                replyStarted.complete(Unit)
                                replyResponse.await()
                            }

                            is PagingRequest.Prepend -> {
                                error("Unexpected request")
                            }
                        }
                    },
                    db,
                )
            val refresh = async { mediator.load(LoadType.REFRESH, state) }
            replyStarted.await()
            assertEquals(cached, rows())
            assertEquals("fresh main", text("main"))
            assertIs<LoadState.NotLoading>(mediator.loadStates.value.refresh)
            assertIs<LoadState.Loading>(mediator.loadStates.value.append)
            replyResponse.complete(PagingResult(listOf(post("new-parent"), post("main"), post("new-reply"))))
            assertIs<RemoteMediator.MediatorResult.Success>(refresh.await())
            assertEquals(listOf("new-parent", "main", "new-reply"), rows())
            assertEquals(LoadState.NotLoading(true), mediator.loadStates.value.append)
        }

    @Test
    fun independentSidesPublishAndRetryOnlyTheFailedFirstPage() =
        runTest {
            seed(listOf("old-parent", "main", "old-reply"))
            val requests = mutableListOf<PagingRequest>()
            val beforeResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val afterResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val beforeStarted = CompletableDeferred<Unit>()
            val afterStarted = CompletableDeferred<Unit>()
            var failAfter = true
            val mediator =
                PostContextRemoteMediator(
                    loader(
                        update = { request, result, initial ->
                            if (request == PagingRequest.Refresh) {
                                ContextUpdate(result.data)
                            } else {
                                result.toContextUpdate(mainKey, request, initial)
                            }
                        },
                    ) { request ->
                        requests += request
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(listOf(post("main")), nextKey = "first-after", previousKey = "first-before")
                            }

                            is PagingRequest.Prepend -> {
                                beforeStarted.complete(Unit)
                                beforeResponse.await()
                            }

                            is PagingRequest.Append -> {
                                afterStarted.complete(Unit)
                                if (failAfter) {
                                    afterResponse.await()
                                    error("Replies unavailable")
                                }
                                PagingResult(emptyList())
                            }
                        }
                    },
                    db,
                )
            val refresh = async { mediator.load(LoadType.REFRESH, state) }
            beforeStarted.await()
            afterStarted.await()
            beforeResponse.complete(PagingResult(listOf(post("new-parent"), post("main", "updated main")), previousKey = "more-before"))
            mediator.loadStates.first { it.prepend is LoadState.NotLoading }
            assertEquals(listOf("new-parent", "main", "old-reply"), rows())
            assertIs<LoadState.Loading>(mediator.loadStates.value.append)
            afterResponse.complete(PagingResult(emptyList()))
            assertIs<RemoteMediator.MediatorResult.Error>(refresh.await())
            assertEquals("cached-after", db.pagingTimelineDao().getPagingKey(pagingKey)?.nextKey)
            assertEquals("more-before", db.pagingTimelineDao().getPagingKey(pagingKey)?.prevKey)
            assertIs<LoadState.Error>(mediator.loadStates.value.append)
            failAfter = false
            mediator.prepareRetry()
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
            assertEquals(listOf("new-parent", "main"), rows())
            assertEquals(1, requests.count { it == PagingRequest.Refresh })
            assertEquals(1, requests.count { it is PagingRequest.Prepend })
            assertEquals(2, requests.count { it == PagingRequest.Append("first-after") })
            assertNull(db.pagingTimelineDao().getPagingKey(pagingKey)?.nextKey)
            assertEquals("more-before", db.pagingTimelineDao().getPagingKey(pagingKey)?.prevKey)
        }

    @Test
    fun paginationUpdatesExistingPostsWithoutMovingOrDuplicatingThemAndClearsBothCursors() =
        runTest {
            val mediator =
                PostContextRemoteMediator(
                    loader { request ->
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(
                                    listOf(post("parent"), post("main"), post("reply")),
                                    nextKey = "after",
                                    previousKey = "before",
                                )
                            }

                            is PagingRequest.Append -> {
                                PagingResult(
                                    listOf(
                                        post("parent", "edited parent"),
                                        post("main", "edited main"),
                                        post("reply", "edited reply"),
                                        post("last"),
                                    ),
                                )
                            }

                            is PagingRequest.Prepend -> {
                                PagingResult(listOf(post("first"), post("parent", "new parent"), post("main", "new main")))
                            }
                        }
                    },
                    db,
                )
            assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
            assertEquals(listOf("parent", "main", "reply"), rows())
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.APPEND, state))
            assertEquals(listOf("parent", "main", "reply", "last"), rows())
            assertEquals("edited parent", text("parent"))
            assertEquals("edited main", text("main"))
            assertEquals("edited reply", text("reply"))
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.PREPEND, state))
            assertEquals(listOf("first", "parent", "main", "reply", "last"), rows())
            assertEquals("new main", text("main"))
            assertNull(db.pagingTimelineDao().getPagingKey(pagingKey)?.prevKey)
            assertNull(db.pagingTimelineDao().getPagingKey(pagingKey)?.nextKey)
        }

    @Test
    fun responseCanReplaceBothSidesAndUpdatePostsOutsideTheirMembership() =
        runTest {
            seed(listOf("parent", "main", "reply"))
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { _, result, _ ->
                        ContextUpdate(
                            posts = result.data,
                            before = ContextPageUpdate(emptyList(), cursor = null, replace = true),
                            after = ContextPageUpdate(listOf(key("parent")), cursor = null, replace = true),
                        )
                    }) { PagingResult(listOf(post("main"), post("parent"), post("reply", "updated removed reply"))) },
                    db,
                )
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
            assertEquals(listOf("main", "parent"), rows())
            assertEquals("updated removed reply", text("reply"))
        }

    @Test
    fun lateResponseFromPreviousRefreshCannotOverwriteNewContext() =
        runTest {
            seed(listOf("main", "cached"))
            val firstStarted = CompletableDeferred<Unit>()
            val firstResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            var calls = 0
            val mediator =
                PostContextRemoteMediator(
                    loader {
                        if (calls++ == 0) {
                            firstStarted.complete(Unit)
                            firstResponse.await()
                        } else {
                            PagingResult(listOf(post("main", "new main"), post("new reply")))
                        }
                    },
                    db,
                )
            val oldRefresh = async { mediator.load(LoadType.REFRESH, state) }
            firstStarted.await()
            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
            firstResponse.complete(PagingResult(listOf(post("main", "old main"), post("old reply"))))
            oldRefresh.await()
            assertEquals(listOf("main", "new reply"), rows())
            assertEquals("new main", text("main"))
            assertFalse(mediator.loadStates.value.append is LoadState.Loading)
        }

    @Test
    fun slowerPrependCannotRollBackPostsUpdatedByALaterAppend() =
        runTest {
            val beforeStarted = CompletableDeferred<Unit>()
            val beforeResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val mediator =
                PostContextRemoteMediator(
                    loader { request ->
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(listOf(post("main")), nextKey = "after", previousKey = "before")
                            }

                            is PagingRequest.Prepend -> {
                                beforeStarted.complete(Unit)
                                beforeResponse.await()
                            }

                            is PagingRequest.Append -> {
                                PagingResult(listOf(post("main", "new main"), post("reply")))
                            }
                        }
                    },
                    db,
                )
            mediator.load(LoadType.REFRESH, state)
            val prepend = async { mediator.load(LoadType.PREPEND, state) }
            beforeStarted.await()
            mediator.load(LoadType.APPEND, state)
            assertIs<LoadState.Loading>(mediator.loadStates.value.prepend)
            beforeResponse.complete(PagingResult(listOf(post("parent"), post("main", "old main"))))
            assertIs<RemoteMediator.MediatorResult.Success>(prepend.await())
            assertEquals(listOf("parent", "main", "reply"), rows())
            assertEquals("new main", text("main"))
        }

    @Test
    fun failedSideCanRetryWhileTheOtherSideIsStillLoading() =
        runTest {
            seed(listOf("old-parent", "main", "old-reply"))
            val beforeResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            var afterCalls = 0
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { request, result, initial ->
                        if (request == PagingRequest.Refresh) {
                            ContextUpdate(result.data)
                        } else {
                            result.toContextUpdate(mainKey, request, initial)
                        }
                    }) { request ->
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(listOf(post("main")), nextKey = "after", previousKey = "before")
                            }

                            is PagingRequest.Prepend -> {
                                beforeResponse.await()
                            }

                            is PagingRequest.Append -> {
                                if (afterCalls++ == 0) error("reply failed")
                                PagingResult(listOf(post("reply")))
                            }
                        }
                    },
                    db,
                )
            val refresh = async { mediator.load(LoadType.REFRESH, state) }
            mediator.loadStates.first { it.append is LoadState.Error }
            mediator.prepareRetry()
            mediator.loadStates.first { it.append is LoadState.NotLoading }
            assertIs<LoadState.Loading>(mediator.loadStates.value.prepend)
            assertEquals(listOf("old-parent", "main", "reply"), rows())
            beforeResponse.complete(PagingResult(listOf(post("parent"))))
            assertIs<RemoteMediator.MediatorResult.Success>(refresh.await())
            assertEquals(listOf("parent", "main", "reply"), rows())
            assertEquals(2, afterCalls)
        }

    @Test
    fun pagerPublishesTheUpdatedMainPostBeforeTheSlowRepliesFinish() =
        runTest {
            startPlatformEventLoop()
            seed(listOf("parent", "main", "cached-reply"))
            val replies = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { request, result, initial ->
                        if (request == PagingRequest.Refresh) {
                            ContextUpdate(result.data)
                        } else {
                            result.toContextUpdate(mainKey, PagingRequest.Refresh, initial)
                        }
                    }) { request ->
                        if (request == PagingRequest.Refresh) {
                            PagingResult(listOf(post("main", "fresh main")), nextKey = "first")
                        } else {
                            replies.await()
                        }
                    },
                    db,
                )
            val pageCache = TimelineDbPageCache()
            val pager =
                Pager(
                    config = offsetPagingConfig,
                    remoteMediator = mediator,
                    pagingSourceFactory = { OffsetFromStartPagingSource(TimelineDbPageLoader(db, pagingKey, pageCache)) },
                )
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val states = MutableStateFlow<UiPagingState<TimelinePageItem>>(UiPagingState.Loading())
            val job =
                launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        val loading by mediator.loadStates.collectAsState()
                        pager.flow.collectAsLazyPagingItems().toPagingState(loading)
                    }.collect { states.value = it }
                }
            try {
                val pending =
                    states.filterIsInstance<UiPagingState.Success<TimelinePageItem>>().first {
                        it
                            .peek(1)
                            ?.baseItem
                            ?.asTimelinePostItem()
                            ?.post
                            ?.content
                            ?.original
                            ?.raw == "fresh main"
                    }
                assertEquals(
                    "cached-reply",
                    pending
                        .peek(2)
                        ?.baseItem
                        ?.statusKey
                        ?.id,
                )
                assertIs<LoadState.Loading>(pending.appendState)
                replies.complete(PagingResult(listOf(post("parent"), post("main", "fresh main"), post("new-reply"))))
                states.filterIsInstance<UiPagingState.Success<TimelinePageItem>>().first {
                    it
                        .peek(2)
                        ?.baseItem
                        ?.statusKey
                        ?.id == "new-reply"
                }
            } finally {
                job.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stagedRefreshPreservesLegacyInlineParentsUntilTheParentSideSucceeds() =
        runTest {
            val grandparent = post("grandparent")
            val parent = post("parent").replyTo(grandparent)
            val main = post("main").replyTo(parent)
            val legacy =
                TimelineRemoteMediator(
                    loader { PagingResult(listOf(grandparent, parent, main)) },
                    db,
                    allowLongText = true,
                )
            assertIs<RemoteMediator.MediatorResult.Success>(legacy.load(LoadType.REFRESH, state))

            assertEquals(listOf("grandparent", "parent", "main"), visibleIds())
            val parentsStarted = CompletableDeferred<Unit>()
            val parentsResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val requests = mutableListOf<PagingRequest>()
            var parentCalls = 0
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { request, result, initial ->
                        if (request ==
                            PagingRequest.Refresh
                        ) {
                            ContextUpdate(result.data)
                        } else {
                            result.toContextUpdate(mainKey, request, initial)
                        }
                    }) { request ->
                        requests += request
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(listOf(post("main", "fresh main").replyTo(parent)), previousKey = "before", nextKey = "after")
                            }

                            is PagingRequest.Append -> {
                                PagingResult(listOf(post("reply")))
                            }

                            is PagingRequest.Prepend -> {
                                if (parentCalls++ == 0) {
                                    parentsStarted.complete(Unit)
                                    parentsResponse.await()
                                } else {
                                    PagingResult(listOf(post("parent", "fresh parent")))
                                }
                            }
                        }
                    },
                    db,
                )
            val refresh = async { mediator.load(LoadType.REFRESH, state) }
            try {
                parentsStarted.await()
                mediator.loadStates.first { it.append is LoadState.NotLoading }
                assertEquals("fresh main", text("main"))
                assertEquals(listOf("grandparent", "parent", "main", "reply"), visibleIds())
                parentsResponse.completeExceptionally(IllegalStateException("Parents unavailable"))
                assertIs<RemoteMediator.MediatorResult.Error>(refresh.await())
                assertEquals(listOf("grandparent", "parent", "main", "reply"), visibleIds())

                mediator.prepareRetry()
                assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
                assertEquals(listOf("parent", "main", "reply"), visibleIds())
                assertEquals("fresh parent", text("parent"))
                assertEquals(1, requests.count { it == PagingRequest.Refresh })
                assertEquals(1, requests.count { it is PagingRequest.Append })
                assertEquals(2, requests.count { it is PagingRequest.Prepend })
            } finally {
                refresh.cancelAndJoin()
            }
        }

    @Test
    fun legacyInlineParentMigrationDeduplicatesExistingRowsWithoutOverwritingNewerParentContent() =
        runTest {
            val grandparent = post("grandparent")
            val oldParent = post("parent", "old parent").replyTo(grandparent)
            val main =
                UiTimelineV2.TimelinePostItem(
                    post = post("main").replyTo(oldParent),
                    presentation =
                        UiTimelineV2.PostPresentation(
                            inlineParents =
                                persistentListOf(
                                    UiTimelineV2.TimelinePostItem(grandparent),
                                    UiTimelineV2.TimelinePostItem(oldParent),
                                ),
                        ),
                )
            saveToDatabase(db, listOf(TimelinePagingMapper.toDb(main, pagingKey, sortId = 0)))
            val freshParent = post("parent", "fresh parent").replyTo(grandparent)
            saveToDatabase(
                db,
                TimelinePagingMapper.toDb(
                    listOf(post("older-before"), freshParent),
                    pagingKey,
                    sortIds = listOf(-2, -1),
                ),
            )
            db.pagingTimelineDao().insertPagingKey(DbPagingKey(pagingKey, prevKey = "cached-before", nextKey = "cached-after"))
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { _, result, _ -> ContextUpdate(result.data) }) { request ->
                        assertEquals(PagingRequest.Refresh, request)
                        PagingResult(listOf(post("main", "fresh main").replyTo(freshParent)))
                    },
                    db,
                )

            assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
            val expected = listOf("older-before", "grandparent", "parent", "main")
            assertEquals(expected, rows())
            assertEquals(expected, visibleIds())
            assertEquals("fresh parent", text("parent"))
            assertEquals("fresh main", text("main"))
            val keys = db.pagingTimelineDao().getPagingKey(pagingKey)
            assertEquals("cached-before", keys?.prevKey)
            assertEquals("cached-after", keys?.nextKey)
        }

    @Test
    fun migratedInlineParentQuotesAreRemovedByTheNextParentResponse() = assertMigratedParentQuoteRemoval(quoteAddedByMain = false)

    @Test
    fun migratedInlineParentQuoteAddedByMainRefreshIsRemovedByTheParentResponse() =
        assertMigratedParentQuoteRemoval(quoteAddedByMain = true)

    private fun assertMigratedParentQuoteRemoval(quoteAddedByMain: Boolean) =
        runTest {
            val quoted = post("quoted", "quoted body")
            val parent = post("parent", "cached parent")
            val quotedParent =
                UiTimelineV2.TimelinePostItem(
                    post = parent,
                    presentation = UiTimelineV2.PostPresentation(quotes = persistentListOf(quoted)),
                )
            if (quoteAddedByMain) {
                saveToDatabase(db, listOf(TimelinePagingMapper.toDb(quoted, "$pagingKey-quoted", sortId = 0)))
            }
            val legacy =
                TimelineRemoteMediator(
                    loader { PagingResult(listOf(if (quoteAddedByMain) parent else quotedParent, post("main").replyTo(parent))) },
                    db,
                    allowLongText = true,
                )
            assertIs<RemoteMediator.MediatorResult.Success>(legacy.load(LoadType.REFRESH, state))
            val parentsStarted = CompletableDeferred<Unit>()
            val parentsResponse = CompletableDeferred<PagingResult<UiTimelineV2>>()
            val mediator =
                PostContextRemoteMediator(
                    loader(update = { request, result, initial ->
                        if (request ==
                            PagingRequest.Refresh
                        ) {
                            ContextUpdate(result.data)
                        } else {
                            result.toContextUpdate(mainKey, request, initial)
                        }
                    }) { request ->
                        when (request) {
                            PagingRequest.Refresh -> {
                                val main = post("main", "fresh main").replyTo(parent)
                                val freshMain =
                                    if (quoteAddedByMain) {
                                        UiTimelineV2.TimelinePostItem(
                                            post = main,
                                            presentation =
                                                UiTimelineV2.PostPresentation(
                                                    quotes =
                                                        persistentListOf(
                                                            parent.copy(
                                                                references =
                                                                    persistentListOf(
                                                                        UiTimelineV2.Post.Reference(quoted.statusKey, ReferenceType.Quote),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                        )
                                    } else {
                                        main
                                    }
                                PagingResult(listOf(freshMain), previousKey = "before")
                            }

                            is PagingRequest.Prepend -> {
                                parentsStarted.complete(Unit)
                                parentsResponse.await()
                            }

                            is PagingRequest.Append -> {
                                error("Unexpected request")
                            }
                        }
                    },
                    db,
                )

            suspend fun currentParent() =
                requireNotNull(
                    TimelineDbPageLoader(db, pagingKey, TimelineDbPageCache())
                        .load(0, 20)
                        .first { it.baseItem.statusKey.id == "parent" }
                        .baseItem
                        .asTimelinePostItem(),
                )

            val refresh = async { mediator.load(LoadType.REFRESH, state) }
            try {
                parentsStarted.await()
                assertEquals(listOf("quoted"), currentParent().presentation.quotes.map { it.statusKey.id })
                assertEquals("cached parent", text("parent"))
                assertEquals("fresh main", text("main"))

                parentsResponse.complete(PagingResult(listOf(post("parent", "fresh parent"))))
                assertIs<RemoteMediator.MediatorResult.Success>(refresh.await())
                assertEquals(emptyList(), currentParent().presentation.quotes)
                assertEquals("fresh parent", text("parent"))
                assertEquals("fresh main", text("main"))
            } finally {
                refresh.cancelAndJoin()
            }
        }

    @Test
    fun stagedRepliesKeepTheOriginalInlineParentPresentation() =
        runTest {
            val parent = post("parent")
            val main = post("main").replyTo(parent)
            val reply = post("reply").replyTo(main)
            val nestedReply = post("nested").replyTo(reply)
            val contextLoader =
                loader(update = { request, result, initial ->
                    val update = result.toContextUpdate(mainKey, request, initial)
                    if (request == PagingRequest.Refresh) update.copy(after = null) else update
                }) { request ->
                    when (request) {
                        PagingRequest.Refresh -> PagingResult(listOf(parent, main), nextKey = "replies")
                        is PagingRequest.Append -> PagingResult(listOf(reply, nestedReply))
                        is PagingRequest.Prepend -> error("Unexpected request")
                    }
                }
            assertPresentationMatchesLegacy(contextLoader)
        }

    @Test
    fun fullContextReplyChainsKeepTheOriginalInlineFocalPost() =
        runTest {
            val parent = post("parent")
            val main = post("main").replyTo(parent)
            val reply = post("reply").replyTo(main)
            val nestedReply = post("nested").replyTo(reply)
            val contextLoader =
                loader(update = { request, result, initial ->
                    if (request == PagingRequest.Refresh) {
                        ContextUpdate(result.data)
                    } else {
                        result.toContextUpdate(mainKey, request, initial).copy(
                            before = ContextPageUpdate(emptyList(), cursor = null, replace = true),
                        )
                    }
                }) { request ->
                    when (request) {
                        PagingRequest.Refresh -> PagingResult(listOf(main), nextKey = "context")
                        is PagingRequest.Append -> PagingResult(listOf(parent, main, reply, nestedReply))
                        is PagingRequest.Prepend -> error("Unexpected request")
                    }
                }
            assertPresentationMatchesLegacy(contextLoader)
        }

    private suspend fun assertPresentationMatchesLegacy(contextLoader: PostContextLoader) {
        val legacyMediator = TimelineRemoteMediator(contextLoader, db, allowLongText = true)
        assertIs<RemoteMediator.MediatorResult.Success>(legacyMediator.load(LoadType.REFRESH, state))
        assertIs<RemoteMediator.MediatorResult.Success>(legacyMediator.load(LoadType.APPEND, state))
        val expected =
            TimelineDbPageLoader(db, pagingKey, TimelineDbPageCache())
                .load(0, 20)
                .map { it.baseItem }
        db.pagingTimelineDao().deletePresentationReferences(pagingKey)
        db.pagingTimelineDao().delete(pagingKey)
        db.pagingTimelineDao().deletePagingKey(pagingKey)

        val mediator = PostContextRemoteMediator(contextLoader, db)
        assertIs<RemoteMediator.MediatorResult.Success>(mediator.load(LoadType.REFRESH, state))
        val actual = TimelineDbPageLoader(db, pagingKey, TimelineDbPageCache()).load(0, 20).map { it.baseItem }
        assertEquals(listOf("main", "nested"), actual.map { it.statusKey.id })
        assertEquals(expected, actual)
    }

    private fun UiTimelineV2.Post.replyTo(parent: UiTimelineV2.Post) =
        copy(
            references = persistentListOf(UiTimelineV2.Post.Reference(statusKey = parent.statusKey, type = ReferenceType.Reply)),
        )

    private fun loader(
        update: (PagingRequest, PagingResult<UiTimelineV2>, Boolean) -> ContextUpdate = { request, result, initial ->
            result.toContextUpdate(mainKey, request, initial)
        },
        response: suspend (PagingRequest) -> PagingResult<UiTimelineV2>,
    ): PostContextLoader =
        object : PostContextLoader {
            override val statusKey = mainKey
            override val accountKey = this@PostContextRemoteMediatorTest.accountKey
            override val pagingKey = this@PostContextRemoteMediatorTest.pagingKey
            override val supportPrepend = true

            override suspend fun load(
                pageSize: Int,
                request: PagingRequest,
            ) = response(request)

            override fun contextUpdate(
                request: PagingRequest,
                result: PagingResult<UiTimelineV2>,
                initial: Boolean,
            ) = update(request, result, initial)
        }

    private suspend fun seed(ids: List<String>) {
        val anchor = ids.indexOf("main")
        saveToDatabase(db, TimelinePagingMapper.toDb(ids.map { post(it) }, pagingKey, ids.indices.map { (it - anchor).toLong() }))
        db.pagingTimelineDao().insertPagingKey(DbPagingKey(pagingKey, nextKey = "cached-after", prevKey = "cached-before"))
    }

    private suspend fun rows() = db.pagingTimelineDao().getTimelinePage(pagingKey, 0, 100).map { it.statusData.statusKey.id }

    private suspend fun visibleIds(): List<String> =
        TimelineDbPageLoader(db, pagingKey, TimelineDbPageCache()).load(0, 20).flatMap {
            val item = requireNotNull(it.baseItem.asTimelinePostItem())
            item.presentation.inlineParents.map { parent -> parent.statusKey.id } + item.statusKey.id
        }

    private suspend fun text(id: String) =
        (
            db
                .statusDao()
                .get(key(id), AccountType.Specific(accountKey))
                .first()
                ?.content as UiTimelineV2.Post
        ).content.original.raw

    private fun key(id: String) = MicroBlogKey(id, "example.com")

    private fun post(
        id: String,
        text: String = id,
    ) = UiTimelineV2.Post(
        platformId = "Mastodon",
        images = persistentListOf(),
        sensitive = false,
        contentWarning = null,
        user = null,
        content = UiTranslatableText(text.toUiPlainText()),
        actions = persistentListOf(),
        poll = null,
        statusKey = key(id),
        card = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
        emojiReactions = persistentListOf(),
        sourceChannel = null,
        visibility = null,
        replyToHandle = null,
        references = persistentListOf(),
        clickEvent = ClickEvent.Noop,
        accountType = AccountType.Specific(accountKey),
    )
}
