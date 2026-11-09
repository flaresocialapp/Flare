package dev.dimension.flare.data.datasource.microblog.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room3.Room
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(ExperimentalPagingApi::class)
class TimelineRemoteMediatorAppendTest : RobolectricTest() {
    private lateinit var database: CacheDatabase
    private val state = PagingState<OffsetFromStartPagingKey, TimelinePageItem>(emptyList(), null, PagingConfig(20), 0)

    @BeforeTest
    fun setup() {
        database =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun overlappingPagesKeepLoadedPostsInPlaceAndUpdateTheirContent() =
        runTest {
            var response = PagingResult<UiTimelineV2>((0..<60).map { post(it) }, nextKey = "first")
            val requests = mutableListOf<PagingRequest>()
            val mediator =
                mediator("featured") { request ->
                    requests += request
                    response
                }
            mediator.loadPage(LoadType.REFRESH)
            val originalRows = database.pagingTimelineDao().getByPagingKey(mediator.pagingKey)
            val updated = post(10, text = "Updated while loading the next page")

            response = PagingResult(listOf(updated, post(60), post(40), post(61)), nextKey = "second")
            mediator.loadPage(LoadType.APPEND)

            assertEquals((0..<62).toList(), postOrder(mediator.pagingKey))
            assertEquals(
                originalRows.map { it.sortId },
                database
                    .pagingTimelineDao()
                    .getByPagingKey(mediator.pagingKey)
                    .take(60)
                    .map { it.sortId },
            )
            assertEquals(
                updated,
                database
                    .pagingTimelineDao()
                    .getTimelinePage(mediator.pagingKey, offset = 10, limit = 1)
                    .single()
                    .statusData.content,
            )

            response = PagingResult(listOf(post(60), post(0), post(62)))
            mediator.loadPage(LoadType.APPEND)

            assertEquals((0..<63).toList(), postOrder(mediator.pagingKey))
            assertEquals(
                listOf(PagingRequest.Refresh, PagingRequest.Append("first"), PagingRequest.Append("second")),
                requests,
            )
        }

    @Test
    fun refreshCanReorderExistingPostsAndRemoveStaleOnes() =
        runTest {
            var response = PagingResult<UiTimelineV2>(listOf(post(0), post(1), post(2)), nextKey = "next")
            val mediator = mediator("featured") { response }
            mediator.loadPage(LoadType.REFRESH)

            response = PagingResult(listOf(post(1), post(0)))
            mediator.loadPage(LoadType.REFRESH)

            assertEquals(listOf(1, 0), postOrder(mediator.pagingKey))
        }

    @Test
    fun appendOnlyPreservesPositionsFromItsOwnTimeline() =
        runTest {
            val other = mediator("other") { PagingResult(listOf(post(0), post(1), post(2))) }
            other.loadPage(LoadType.REFRESH)
            val otherRows = database.pagingTimelineDao().getByPagingKey(other.pagingKey)
            var response = PagingResult<UiTimelineV2>(listOf(post(1), post(2)), nextKey = "next")
            val mediator = mediator("featured") { response }
            mediator.loadPage(LoadType.REFRESH)

            response = PagingResult(listOf(post(0)))
            mediator.loadPage(LoadType.APPEND)

            assertEquals(listOf(1, 2, 0), postOrder(mediator.pagingKey))
            assertEquals(otherRows, database.pagingTimelineDao().getByPagingKey(other.pagingKey))
        }

    @Test
    fun flatCommentPagesKeepParentAndRepliesInBackendOrder() =
        runTest {
            val root = post(0).copy(createdAt = Instant.fromEpochMilliseconds(30).toUi())
            val first =
                post(1).copy(
                    createdAt = Instant.fromEpochMilliseconds(10).toUi(),
                    references = persistentListOf(UiTimelineV2.Post.Reference(root.statusKey, ReferenceType.Reply)),
                )
            val second =
                post(2).copy(
                    createdAt = Instant.fromEpochMilliseconds(20).toUi(),
                    references = persistentListOf(UiTimelineV2.Post.Reference(first.statusKey, ReferenceType.Reply)),
                )
            val hot = mediator("plugin:forum-hot", collapseReplies = false) { PagingResult(listOf(root, first, second)) }
            val latest = mediator("plugin:forum-latest", collapseReplies = false) { PagingResult(listOf(root, second, first)) }
            hot.loadPage(LoadType.REFRESH)
            latest.loadPage(LoadType.REFRESH)
            assertEquals(listOf(0, 1, 2), postOrder(hot.pagingKey))
            assertEquals(listOf(0, 2, 1), postOrder(latest.pagingKey))
        }

    @Test
    fun emptyCommentPageRetainsOpaqueCursorAndContinuesPastPageTwentyAfterReopening() =
        runTest {
            val cursor = "/fixture/comments?offset=a+b/c=&page=21"
            val requests = mutableListOf<PagingRequest>()

            fun reopened() =
                mediator("plugin:forum-continuation", collapseReplies = false) { request ->
                    requests += request
                    when (request) {
                        PagingRequest.Refresh -> PagingResult(listOf(post(0)), nextKey = cursor)
                        PagingRequest.Append(cursor) -> PagingResult(emptyList(), nextKey = "21")
                        PagingRequest.Append("21") -> PagingResult(listOf(post(1), post(2)))
                        else -> error("Unexpected paging direction")
                    }
                }
            reopened().loadPage(LoadType.REFRESH)
            assertEquals(cursor, database.pagingTimelineDao().getNextKey("plugin:forum-continuation"))
            val empty = assertIs<RemoteMediator.MediatorResult.Success>(reopened().load(LoadType.APPEND, state))
            assertEquals(false, empty.endOfPaginationReached)
            assertEquals("21", database.pagingTimelineDao().getNextKey("plugin:forum-continuation"))
            reopened().loadPage(LoadType.APPEND)
            assertEquals(listOf(PagingRequest.Refresh, PagingRequest.Append(cursor), PagingRequest.Append("21")), requests)
            assertEquals(listOf(0, 1, 2), postOrder("plugin:forum-continuation"))
            assertEquals(null, database.pagingTimelineDao().getNextKey("plugin:forum-continuation"))
        }

    private fun mediator(
        pagingKey: String,
        collapseReplies: Boolean = true,
        response: (PagingRequest) -> PagingResult<UiTimelineV2>,
    ) = TimelineRemoteMediator(
        loader =
            object : CacheableRemoteLoader<UiTimelineV2> {
                override val pagingKey = pagingKey
                override val collapseReplyChains = collapseReplies

                override suspend fun load(
                    pageSize: Int,
                    request: PagingRequest,
                ): PagingResult<UiTimelineV2> = response(request)
            },
        database = database,
        allowLongText = false,
    )

    private suspend fun TimelineRemoteMediator.loadPage(type: LoadType) {
        assertIs<RemoteMediator.MediatorResult.Success>(load(type, state))
    }

    private suspend fun postOrder(pagingKey: String): List<Int> =
        database
            .pagingTimelineDao()
            .getTimelinePage(pagingKey, offset = 0, limit = 100)
            .map {
                it.statusData.statusKey.id
                    .toInt()
            }

    private fun post(
        id: Int,
        text: String = "Post $id",
    ) = UiTimelineV2.Post(
        platformId = "xQt",
        images = persistentListOf(),
        sensitive = false,
        contentWarning = null,
        user = null,
        content = UiTranslatableText(text.toUiPlainText()),
        actions = persistentListOf(),
        poll = null,
        statusKey = MicroBlogKey(id.toString(), "x.com"),
        card = null,
        createdAt = Instant.fromEpochMilliseconds(1).toUi(),
        references = persistentListOf(),
        clickEvent = ClickEvent.Noop,
        accountType = AccountType.Guest,
    )
}
