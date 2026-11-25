package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.snapshots.Snapshot
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilityProvider
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilitySet
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.MangaPageImageResolveReason
import dev.dimension.flare.data.datasource.microblog.datasource.MangaPagesChunk
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.translation.image.ImageTranslationRepository
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.shared.image.JvmImageCompressor
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MangaReaderPresenterTest {
    private val accountKey = MicroBlogKey("reader", "manga.example")
    private val accountType = AccountType.Specific(accountKey)
    private val mangaKey = MicroBlogKey("series-1", "manga.example")

    @Test
    fun coldChapterEntryLoadsDetailTokensAndGatesLockedChapter() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-1")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 1_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            var discoveredEntityToken: String? = null
            val discoveredChapterTokens = ConcurrentHashMap<String, String>()
            val chapterPageCalls = CopyOnWriteArrayList<String>()
            val resolvedPageIds = CopyOnWriteArrayList<String>()
            val inFlightCount = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            var releaseResolveGate = CompletableDeferred<Unit>()
            var threePermitsEntered = CompletableDeferred<Unit>()
            var ch3Locked = true
            val sharedDetailCache = MutableStateFlow<UiMangaDetail?>(null)

            fun currentDetail(): UiMangaDetail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = "https://manga.example/ch-2"),
                                        UiMangaChapter(
                                            "ch-3",
                                            "Chapter 3",
                                            null,
                                            null,
                                            locked = ch3Locked,
                                            url = "https://manga.example/paywall/ch-3",
                                        ),
                                    ),
                            ),
                        ),
                )

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = {
                                discoveredEntityToken = "entity-token-series-1"
                                discoveredChapterTokens["ch-1"] = "chapter-token-1"
                                discoveredChapterTokens["ch-2"] = "chapter-token-2"
                                discoveredChapterTokens["ch-3"] =
                                    if (ch3Locked) "chapter-token-3-locked" else "chapter-token-3-unlocked"
                                sharedDetailCache.value = currentDetail()
                            },
                            cacheSource = { sharedDetailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPageCalls += chapterId
                        assertEquals(
                            "entity-token-series-1",
                            discoveredEntityToken,
                            "mangaDetail must populate entityToken before chapterPages",
                        )
                        val expectedChapterToken =
                            when (chapterId) {
                                "ch-2" -> "chapter-token-2"
                                "ch-3" -> "chapter-token-3-unlocked"
                                else -> error("Unexpected chapterId $chapterId")
                            }
                        assertEquals(
                            expectedChapterToken,
                            discoveredChapterTokens[chapterId],
                            "mangaDetail must populate chapterToken before chapterPages",
                        )
                        if (chapterId == "ch-3") {
                            return readyPages(count = 2, prefix = "ch3")
                        }
                        return (0 until 8)
                            .map { idx ->
                                UiMangaPage.Deferred(
                                    id = "p-$idx",
                                    pageToken = "tok-$idx",
                                    previewUrl = "https://manga.example/preview/$idx.jpg",
                                    width = 800f,
                                    height = 1200f,
                                    customHeaders = persistentMapOf("Referer" to "https://manga.example"),
                                    proxy = ProxyRef.Unbound,
                                )
                            }.toImmutableList()
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        assertEquals("entity-token-series-1", discoveredEntityToken)
                        assertEquals("chapter-token-2", discoveredChapterTokens[chapterId])
                        val active = inFlightCount.incrementAndGet()
                        maxObservedConcurrency.accumulateAndGet(active, ::maxOf)
                        if (active >= 3) {
                            threePermitsEntered.complete(Unit)
                        }
                        try {
                            releaseResolveGate.await()
                            resolvedPageIds += pageId
                            return UiMedia.Image(
                                url = "https://manga.example/full/$pageId.jpg",
                                previewUrl = "https://manga.example/preview/$pageId.jpg",
                                description = null,
                                height = 1200f,
                                width = 800f,
                                sensitive = false,
                            )
                        } finally {
                            inFlightCount.decrementAndGet()
                        }
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val lockedJob = SupervisorJob()
            val lockedScope = CoroutineScope(dispatcher + lockedJob)
            val unlockedJob = SupervisorJob()
            val unlockedScope = CoroutineScope(dispatcher + unlockedJob)
            val lockedPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-3")
            val unlockedPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 2)
            try {
                // 1. Enter locked "ch-3" directly -> loadState shows locked gate with openUrl, chapterPages() is NEVER called.
                val lockedStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                lockedScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { lockedPresenter.body() }.collect { lockedStates.value = it }
                }
                val lockedState = awaitReaderState(lockedStates) { it.loadState is UiState.Error }
                val lockedError = assertIs<LockedChapterException>((lockedState.loadState as UiState.Error).throwable)
                assertEquals("ch-3", lockedError.chapter.id)
                assertEquals("https://manga.example/paywall/ch-3", lockedError.openUrl)
                assertEquals("https://manga.example/paywall/ch-3", lockedState.lockedChapter?.openUrl)
                assertTrue(chapterPageCalls.isEmpty(), "chapterPages() must NEVER be called for a locked chapter")

                // Unlocking ch-3 externally and calling retryChapter() refreshes mangaDetail, discovers unlocked state + fresh token, and loads pages!
                ch3Locked = false
                lockedState.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val unlockedAfterRetry =
                    awaitReaderState(lockedStates) {
                        it.loadState is UiState.Success && it.pages.size == 2
                    }
                assertEquals(null, unlockedAfterRetry.lockedChapter)
                assertEquals(listOf("ch-3"), chapterPageCalls.toList())

                lockedJob.cancelAndJoin()
                lockedPresenter.awaitPendingWrites()

                // Reset tokens & shared cache to prove cold unlocked entry ALSO loads mangaDetail first before chapterPages().
                sharedDetailCache.value = null
                chapterPageCalls.clear()
                discoveredEntityToken = null
                discoveredChapterTokens.clear()
                releaseResolveGate = CompletableDeferred()
                threePermitsEntered = CompletableDeferred()

                // 2. Enter unlocked "ch-2" directly at page 2 -> mangaDetail loads first, chapterPages() receives tokens,
                //    deferred pages within [currentPage-1 .. currentPage+3] = [1..5] resolve with max concurrency <= 3.
                val unlockedStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                unlockedScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { unlockedPresenter.body() }.collect { unlockedStates.value = it }
                }

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { threePermitsEntered.await() }
                }
                val loadingWindowState = awaitReaderState(unlockedStates) { it.pages.size == 8 }
                assertEquals(2, loadingWindowState.currentPageIndex)
                assertTrue(maxObservedConcurrency.get() in 1..3, "Expected concurrency in 1..3, got ${maxObservedConcurrency.get()}")
                // Verify outside-window pages (0, 6, 7) are Loading with bound ProxyRef.Account.
                val page0Loading = assertIs<PageMediaStatus.Loading>(loadingWindowState.pages[0].status)
                assertEquals(ProxyRef.Account(accountKey), page0Loading.proxy)

                releaseResolveGate.complete(Unit)

                val readyWindowState =
                    awaitReaderState(unlockedStates) { state ->
                        state.pages.size == 8 && (1..5).all { idx -> state.pages[idx].status is PageMediaStatus.Ready }
                    }
                assertTrue(maxObservedConcurrency.get() <= 3, "Max concurrency exceeded 3: ${maxObservedConcurrency.get()}")
                assertEquals(setOf("p-1", "p-2", "p-3", "p-4", "p-5"), resolvedPageIds.toSet())
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[0].status)
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[6].status)
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[7].status)
                // Verify prefetchMedia exposes [currentPageIndex + 1 .. currentPageIndex + 3] = [p-3, p-4, p-5] with bound proxy.
                assertEquals(
                    listOf(
                        "https://manga.example/full/p-3.jpg",
                        "https://manga.example/full/p-4.jpg",
                        "https://manga.example/full/p-5.jpg",
                    ),
                    readyWindowState.prefetchMedia.map { it.url },
                )
                assertTrue(readyWindowState.prefetchMedia.all { it.proxy == ProxyRef.Account(accountKey) })
            } finally {
                lockedJob.cancelAndJoin()
                unlockedJob.cancelAndJoin()
                lockedPresenter.awaitPendingWrites()
                unlockedPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun previousChapterTransitionLandsOnFinalPage() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-2")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 2_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "other-group",
                                title = UiText.Raw("Other"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("other-last", "Other Last", null, null, locked = false, url = null),
                                    ),
                            ),
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = "https://manga.example/ch-2"),
                                        UiMangaChapter("ch-3", "Chapter 3", null, null, locked = true, url = null),
                                    ),
                            ),
                        ),
                )
            val pagesByChapter =
                mapOf(
                    "ch-1" to readyPages(count = 5, prefix = "ch1"),
                    "ch-2" to readyPages(count = 3, prefix = "ch2"),
                )
            val mangaDataSource = FakeStaticPagesMangaDataSource(detail, pagesByChapter)
            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val singleJob = SupervisorJob()
            val singleScope = CoroutineScope(dispatcher + singleJob)
            val spreadJob = SupervisorJob()
            val spreadScope = CoroutineScope(dispatcher + spreadJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 0)
            val spreadPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 0)
            try {
                // 1. Single-page mode: on page 0 of ch-2, stepBackward() loads ch-1 at ch1Pages.lastIndex (4).
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                singleScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }
                val ch2Initial = awaitReaderState(states) { it.activeChapterId == "ch-2" && it.pages.size == 3 }
                assertEquals(0, ch2Initial.currentPageIndex)
                val prevUnlocked = assertIs<ChapterBoundary.Unlocked>(ch2Initial.previousBoundary)
                assertEquals("ch-1", prevUnlocked.chapter.id)
                assertTrue(prevUnlocked.startFromEnd)
                val nextLocked = assertIs<ChapterBoundary.Locked>(ch2Initial.nextBoundary)
                assertEquals("ch-3", nextLocked.chapter.id)
                // Falls back to detail.url since ch-3.url == null
                assertEquals("https://manga.example/series-1", nextLocked.openUrl)

                ch2Initial.stepBackward()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val ch1FromBackward = awaitReaderState(states) { it.activeChapterId == "ch-1" && it.pages.size == 5 }
                assertEquals(4, ch1FromBackward.currentPageIndex)
                assertEquals(4, ch1FromBackward.currentSlotIndex)
                // ch-1 is index 0 in group-a -> previousBoundary must be None (never crosses into other-group!).
                assertEquals(ChapterBoundary.None, ch1FromBackward.previousBoundary)
                singleJob.cancelAndJoin()
                presenter.awaitPendingWrites()

                // 2. Spread mode: openAdjacentChapter(previousBoundary as Unlocked) lands on ch1Pages.lastIndex (4) AND slots.lastIndex (2).
                val spreadStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                spreadScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { spreadPresenter.body() }.collect { spreadStates.value = it }
                }
                val spreadCh2 = awaitReaderState(spreadStates) { it.activeChapterId == "ch-2" && it.pages.size == 3 }
                spreadCh2.setWideViewport(true)
                spreadCh2.setDoublePageSpread(true)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val spreadEnabledCh2 = awaitReaderState(spreadStates) { it.doublePageSpread }
                val boundary = assertIs<ChapterBoundary.Unlocked>(spreadEnabledCh2.previousBoundary)
                spreadEnabledCh2.openAdjacentChapter(boundary)
                Snapshot.sendApplyNotifications()
                runCurrent()

                val spreadCh1 = awaitReaderState(spreadStates) { it.activeChapterId == "ch-1" && it.pages.size == 5 }
                // 5 portrait pages in RTL with spreadCoverSinglePage = true -> [Single(0), Double(2, 1, 1), Double(4, 3, 3)]
                assertEquals(3, spreadCh1.slots.size)
                assertEquals(4, spreadCh1.currentPageIndex)
                assertEquals(2, spreadCh1.currentSlotIndex)
                assertTrue(spreadCh1.slots[spreadCh1.currentSlotIndex].contains(4))

                // Discovering landscape dimensions on page 3 splits the spread without losing currentPageIndex = 4!
                spreadCh1.onPageDimensionsLoaded("ch1-3", width = 1600f, height = 1000f)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val splitSpreadCh1 = awaitReaderState(spreadStates) { it.slots.size == 4 }
                assertEquals(4, splitSpreadCh1.currentPageIndex)
                assertEquals(3, splitSpreadCh1.currentSlotIndex)
                assertEquals(ReaderSlot.Single(4), splitSpreadCh1.slots[splitSpreadCh1.currentSlotIndex])
            } finally {
                singleJob.cancelAndJoin()
                spreadJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                spreadPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun debouncesProgressFlushesOnSwitchAndDisposeCancelsOldChapterJobsAndRetries() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-3")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(dispatcher)
                    .build()
            var now = 10_000L
            var failNextWrite = false
            var failedWrites = 0
            val progressRepository =
                MangaProgressRepository(cacheDatabase) {
                    if (failNextWrite) {
                        failNextWrite = false
                        failedWrites++
                        throw IOException("Transient progress write failure")
                    }
                    now++
                }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val ch1PageResolutionCancelled = CompletableDeferred<Unit>()
            val ch1PageResolutionStarted = CompletableDeferred<Unit>()
            var failCh2ChapterOnce = true
            var failCh2Page0Once = true

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> {
                        val cache = MutableStateFlow<UiMangaDetail?>(null)
                        return Cacheable(
                            fetchSource = { cache.value = detail },
                            cacheSource = { cache.filterNotNull() },
                        )
                    }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> =
                        when (chapterId) {
                            "ch-1" -> {
                                persistentListOf(
                                    UiMangaPage.Ready("ch1-0", UiMedia.Image("https://manga.example/ch1-0.jpg")),
                                    UiMangaPage.Ready("ch1-1", UiMedia.Image("https://manga.example/ch1-1.jpg")),
                                    UiMangaPage.Ready("ch1-2", UiMedia.Image("https://manga.example/ch1-2.jpg")),
                                    UiMangaPage.Deferred(
                                        id = "ch1-3",
                                        pageToken = "slow-tok",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                )
                            }

                            "ch-2" -> {
                                if (failCh2ChapterOnce) {
                                    failCh2ChapterOnce = false
                                    error("Transient chapter list failure")
                                }
                                persistentListOf(
                                    UiMangaPage.Deferred(
                                        id = "ch2-0",
                                        pageToken = "flaky-tok",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                    UiMangaPage.Ready("ch2-1", UiMedia.Image("https://manga.example/ch2-1.jpg")),
                                    UiMangaPage.Ready("ch2-2", UiMedia.Image("https://manga.example/ch2-2.jpg")),
                                )
                            }

                            else -> {
                                error("Unexpected chapter $chapterId")
                            }
                        }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        if (chapterId == "ch-1" && pageId == "ch1-3") {
                            ch1PageResolutionStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } catch (e: CancellationException) {
                                ch1PageResolutionCancelled.complete(Unit)
                                throw e
                            }
                        }
                        if (chapterId == "ch-2" && pageId == "ch2-0") {
                            if (failCh2Page0Once) {
                                failCh2Page0Once = false
                                error("Transient CDN error")
                            }
                            return UiMedia.Image("https://manga.example/ch2-0.jpg")
                        }
                        error("Unexpected resolvePageMedia($chapterId, $pageId)")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val writeErrors = mutableListOf<Throwable>()
            val readerScope = CoroutineScope(dispatcher + readerJob + CoroutineExceptionHandler { _, error -> writeErrors.add(error) })
            var activePresenter: MangaReaderPresenter? = null
            try {
                appDataStore.appSettingsStore.updateData {
                    it.copy(mangaReaderConfig = AppSettings.MangaReaderConfig(defaultDirection = MangaReadingDirection.Rtl))
                }
                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val observedSnapshots = CopyOnWriteArrayList<dev.dimension.flare.data.repository.MangaProgressSnapshot>()
                readerScope.launch {
                    progressRepository.observe(accountType, mangaKey).collect { observedSnapshots += it }
                }

                val initial = awaitReaderState(states) { it.activeChapterId == "ch-1" && it.pages.size == 4 }
                // detail.readingDirection (Ltr) overrides settings.defaultDirection (Rtl) when readerModeOverride == null
                assertEquals(MangaReadingDirection.Ltr, initial.direction)
                runCurrent()
                assertTrue(ch1PageResolutionStarted.isCompleted)

                // Rapidly scrub pages 1 -> 2 within < 300ms: neither page 1 nor page 2 may be persisted before 300ms elapses.
                val writesBeforeScrub = now
                initial.goToPage(1)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(150)
                runCurrent()
                states.value!!.goToPage(2)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(250)
                runCurrent()
                assertEquals(writesBeforeScrub, now, "Debounce must suppress writes while < 300ms has elapsed since last page change")
                assertTrue(observedSnapshots.none { it.lastPageIndex == 1 || it.lastPageIndex == 2 })

                // Complete the 300ms debounce window after goToPage(2): writes exactly once at pageIndex = 2 (skipping page 1).
                advanceTimeBy(60)
                runCurrent()
                assertEquals(writesBeforeScrub + 1, now)
                assertTrue(observedSnapshots.none { it.lastPageIndex == 1 })
                val debouncedProgress = observedSnapshots.last()
                assertEquals("ch-1", debouncedProgress.lastChapterId)
                assertEquals(2, debouncedProgress.lastPageIndex)
                assertFalse(debouncedProgress.chapters["ch-1"]!!.isCompleted)

                // Enable double-page spread with coverSinglePage = false -> slots are [Double(0, 1, 0), Double(2, 3, 2)].
                // Jumping to page 2 (primaryPageIndex of the final Double(2, 3) slot) includes pages.lastIndex (3),
                // so stepForward() into ch-2 BEFORE 300ms must:
                // 1) flush ch-1 immediately with isCompleted = true (even though currentPageIndex == 2 < lastIndex == 3!)
                // 2) cancel in-flight resolvePageMedia("ch-1", "ch1-3")!
                states.value!!.setWideViewport(true)
                states.value!!.setDoublePageSpread(true)
                states.value!!.setSpreadCoverSinglePage(false)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val spreadCh1 = awaitReaderState(states) { it.doublePageSpread && !it.spreadCoverSinglePage && it.slots.size == 2 }
                assertEquals(1, spreadCh1.currentSlotIndex)
                assertTrue(spreadCh1.slots[spreadCh1.currentSlotIndex].contains(3))
                spreadCh1.stepForward()
                Snapshot.sendApplyNotifications()
                runCurrent()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { ch1PageResolutionCancelled.await() }
                }
                val flushedCh1 =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-1"]?.isCompleted == true }
                        }
                    }
                assertEquals(2, flushedCh1.chapters["ch-1"]?.lastPageIndex)

                // ch-2 chapterPages() fails once -> retryChapter() recovers ch-2!
                val ch2ChapterFailed =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" && it.loadState is UiState.Error
                    }
                ch2ChapterFailed.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()

                // ch-2 page 0 fails once -> retryPage("ch2-0") recovers it to Ready!
                val ch2Failed =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" &&
                            it.pages.firstOrNull()?.status is PageMediaStatus.Failed
                    }
                ch2Failed.retryPage("ch2-0")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val ch2Recovered =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" &&
                            it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }

                ch2Recovered.setDirection(MangaReadingDirection.Webtoon)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val webtoon = awaitReaderState(states) { it.direction == MangaReadingDirection.Webtoon }
                failNextWrite = true
                webtoon.goToPage(1)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(301)
                runCurrent()
                assertEquals(1, failedWrites)

                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                assertTrue(writeErrors.isEmpty())

                val finalProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first {
                                it.lastChapterId == "ch-2" &&
                                    it.lastPageIndex == 1 &&
                                    it.readerModeOverride == MangaReadingDirection.Webtoon
                            }
                        }
                    }
                assertEquals(1, finalProgress.chapters["ch-2"]?.lastPageIndex)
                assertFalse(finalProgress.chapters["ch-2"]!!.isCompleted)
            } finally {
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun coldEntryWithNullInitialPageAwaitsIoProgressWithoutClobberingSavedPage() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-4")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 50_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Rtl,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )
            // Warm in-memory detail + instant chapterPages so detail & pages resolve BEFORE PlatformDispatchers.IO Room query!
            val warmCache = MutableStateFlow<UiMangaDetail?>(detail)
            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { warmCache.value = detail },
                            cacheSource = { warmCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = readyPages(count = 8, prefix = "warm")

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            var activePresenter: MangaReaderPresenter? = null
            try {
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-2",
                    pageIndex = 5,
                    pageCount = 8,
                    isCompleted = false,
                )
                progressRepository.setReaderModeOverride(accountType, mangaKey, MangaReadingDirection.Webtoon)

                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = null)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                val successPageIndices = CopyOnWriteArrayList<Int>()
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state ->
                        if (state.loadState is UiState.Success) {
                            successPageIndices += state.currentPageIndex
                        }
                        states.value = state
                    }
                }

                val loaded = awaitReaderState(states) { it.loadState is UiState.Success && it.pages.size == 8 }
                assertEquals(5, loaded.currentPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, loaded.direction)
                assertTrue(
                    successPageIndices.all { it == 5 },
                    "Success state must never emit page 0 when resuming saved page 5: $successPageIndices",
                )

                // Dispose reader immediately and verify saved progress in SQLite remains at pageIndex = 5 (never overwritten with 0).
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()

                val persisted =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first()
                        }
                    }
                assertEquals("ch-2", persisted.lastChapterId)
                assertEquals(5, persisted.lastPageIndex)
                assertEquals(5, persisted.chapters["ch-2"]?.lastPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, persisted.readerModeOverride)
            } finally {
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun retryDeferredReadyPageMintsFreshUrlAndBackgroundDetailRefreshPreservesActiveSession() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-5")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 75_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Rtl,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )

            var failInitialDetailOnce = true
            val detailFetchCount = AtomicInteger(0)
            val chapterPagesCallCount = AtomicInteger(0)
            val deferredResolveCount = AtomicInteger(0)
            var backgroundRefreshEntered: CompletableDeferred<Unit>? = null
            var releaseBackgroundRefresh: CompletableDeferred<Unit>? = null
            var latestDetailCacheable: Cacheable<UiMangaDetail>? = null
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = {
                                val call = detailFetchCount.incrementAndGet()
                                if (failInitialDetailOnce) {
                                    failInitialDetailOnce = false
                                    error("Initial detail network failure")
                                }
                                if (call >= 3) {
                                    backgroundRefreshEntered?.complete(Unit)
                                    releaseBackgroundRefresh?.await()
                                }
                                detailCache.value = detail
                            },
                            cacheSource = { detailCache.filterNotNull() },
                        ).also { latestDetailCacheable = it }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPagesCallCount.incrementAndGet()
                        return persistentListOf(
                            UiMangaPage.Deferred(
                                id = "def-0",
                                pageToken = "signed-tok",
                                previewUrl = null,
                                width = 800f,
                                height = 1200f,
                                customHeaders = null,
                                proxy = ProxyRef.Global,
                            ),
                            UiMangaPage.Ready("p-1", UiMedia.Image("https://manga.example/p-1.jpg")),
                            UiMangaPage.Ready("p-2", UiMedia.Image("https://manga.example/p-2.jpg")),
                        )
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        val attempt = deferredResolveCount.incrementAndGet()
                        return UiMedia.Image("https://manga.example/cdn-v$attempt/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            var activePresenter: MangaReaderPresenter? = null
            try {
                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                val observedLoadStates = CopyOnWriteArrayList<UiState<Unit>>()
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state ->
                        observedLoadStates += state.loadState
                        states.value = state
                    }
                }

                // 1. Initial detail fetch fails -> retryChapter() sets detailAttempt = 1 (forceRefresh = true) and recovers!
                val initialError = awaitReaderState(states) { it.loadState is UiState.Error }
                initialError.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val firstReady =
                    awaitReaderState(states) { state ->
                        state.loadState is UiState.Success &&
                            state.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }
                val v1Media = assertIs<PageMediaStatus.Ready>(firstReady.pages[0].status).original as UiMedia.Image
                assertEquals("https://manga.example/cdn-v1/def-0.jpg", v1Media.url)
                assertEquals(1, deferredResolveCount.get())
                assertEquals(1, chapterPagesCallCount.get())

                // 2. retryPage("def-0") on an already-Ready Deferred page evicts resolvedMedia and mints a fresh v2 URL!
                firstReady.retryPage("def-0")
                Snapshot.sendApplyNotifications()
                runCurrent()

                val secondReady =
                    awaitReaderState(states) { state ->
                        val ready = state.pages.firstOrNull()?.status as? PageMediaStatus.Ready
                        (ready?.original as? UiMedia.Image)?.url == "https://manga.example/cdn-v2/def-0.jpg"
                    }
                assertEquals(2, deferredResolveCount.get())

                // 3. Advance to page 2, then trigger a background detail refresh (refreshState = LoadState.Loading) while detailAttempt > 0:
                //    active ChapterPagesSession and currentPageIndex must stay mounted without resetting!
                secondReady.goToPage(2)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val onPage2 = awaitReaderState(states) { it.currentPageIndex == 2 }
                observedLoadStates.clear()

                val refreshEntered = CompletableDeferred<Unit>()
                val releaseRefresh = CompletableDeferred<Unit>()
                backgroundRefreshEntered = refreshEntered
                releaseBackgroundRefresh = releaseRefresh
                val activeCacheable = assertNotNull(latestDetailCacheable)
                activeCacheable.refresh()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { refreshEntered.await() }
                }
                Snapshot.sendApplyNotifications()
                runCurrent()

                val duringBackgroundRefresh = assertNotNull(states.value)
                assertIs<UiState.Success<Unit>>(duringBackgroundRefresh.loadState)
                assertEquals(2, duringBackgroundRefresh.currentPageIndex)
                assertEquals(1, chapterPagesCallCount.get(), "Background detail refresh must not tear down ChapterPagesSession")
                assertTrue(
                    observedLoadStates.all { it is UiState.Success },
                    "loadState must remain Success across background detail refresh, saw $observedLoadStates",
                )

                releaseRefresh.complete(Unit)
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(2, states.value!!.currentPageIndex)
                assertEquals(1, chapterPagesCallCount.get())
            } finally {
                releaseBackgroundRefresh?.complete(Unit)
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun canonicalMangaKeyAlignsReaderProgressChapterPagesAndDeferredMediaWithEntityToken() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-canonical-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            var now = 20_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val slugKey = MicroBlogKey("slug-solo-1", "manga.example")
            val canonicalKey = MicroBlogKey("canonical-solo-1", "manga.example")
            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                    ),
                            ),
                        ),
                ).copy(mangaKey = canonicalKey)

            val entityTokensByKey = ConcurrentHashMap<MicroBlogKey, String>()
            val chapterTokensByKey = ConcurrentHashMap<Pair<MicroBlogKey, String>, String>()
            val detailKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val chapterPagesKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val resolvePageKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> {
                        detailKeys += mangaKey
                        return Cacheable(
                            fetchSource = {
                                listOf(mangaKey, canonicalKey).forEach { key ->
                                    entityTokensByKey[key] = "entity-tok-canonical"
                                    chapterTokensByKey[key to "ch-1"] = "chapter-tok-1"
                                }
                                detailCache.value = detail
                            },
                            cacheSource = { detailCache.filterNotNull() },
                        )
                    }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPagesKeys += mangaKey
                        assertEquals("entity-tok-canonical", entityTokensByKey[mangaKey])
                        assertEquals("chapter-tok-1", chapterTokensByKey[mangaKey to chapterId])
                        return (0 until 5)
                            .map { idx ->
                                UiMangaPage.Deferred(
                                    id = "cp-$idx",
                                    pageToken = "pt-$idx",
                                    previewUrl = null,
                                    width = 800f,
                                    height = 1200f,
                                    customHeaders = null,
                                    proxy = ProxyRef.Unbound,
                                )
                            }.toImmutableList()
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        resolvePageKeys += mangaKey
                        assertEquals("entity-tok-canonical", entityTokensByKey[mangaKey])
                        assertEquals("chapter-tok-1", chapterTokensByKey[mangaKey to chapterId])
                        return UiMedia.Image("https://manga.example/canonical/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            // Seed existing progress under canonicalKey so cold entry via slugKey (initialPage = null) must resume page 2!
            progressRepository.recordPage(
                accountType = accountType,
                mangaKey = canonicalKey,
                chapterId = "ch-1",
                pageIndex = 2,
                pageCount = 5,
                isCompleted = false,
            )

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val detailJob = SupervisorJob()
            val detailScope = CoroutineScope(dispatcher + detailJob)
            val readerPresenter = MangaReaderPresenter(accountType, slugKey, initialChapterId = "ch-1", initialPage = null)
            val detailPresenter = MangaDetailPresenter(accountType, slugKey)
            try {
                val detailStates = MutableStateFlow<MangaDetailPresenter.State?>(null)
                detailScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { detailPresenter.body() }.collect { detailStates.value = it }
                }

                val readerStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { readerPresenter.body() }.collect { readerStates.value = it }
                }

                val ready =
                    awaitReaderState(readerStates) { state ->
                        state.loadState is UiState.Success &&
                            state.pages.size == 5 &&
                            state.pages[2].status is PageMediaStatus.Ready
                    }
                assertEquals(2, ready.currentPageIndex, "Cold entry via slugKey must resume saved page from canonicalKey")
                assertEquals(listOf(canonicalKey), chapterPagesKeys.toList())
                assertTrue(resolvePageKeys.isNotEmpty() && resolvePageKeys.all { it == canonicalKey })

                // Advance to page 3 and override direction -> MangaDetailPresenter(slugKey) must observe the updated canonical progress!
                ready.goToPage(3)
                ready.setDirection(MangaReadingDirection.Webtoon)
                Snapshot.sendApplyNotifications()
                advanceTimeBy(350)
                runCurrent()

                val canonicalProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, canonicalKey).first {
                                it.lastPageIndex == 3 && it.readerModeOverride == MangaReadingDirection.Webtoon
                            }
                        }
                    }
                val updatedDetailState =
                    awaitReaderState(detailStates) { state ->
                        state.detail is UiState.Success && state.primaryReadTarget?.initialPage == 3
                    }
                assertEquals(3, updatedDetailState.primaryReadTarget?.initialPage)
                assertEquals(3, canonicalProgress.lastPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, canonicalProgress.readerModeOverride)

                // Exiting before the debounce must flush the live page under the canonical key.
                ready.goToPage(4)
                readerJob.cancelAndJoin()
                readerPresenter.awaitPendingWrites()
                val onExit = progressRepository.observe(accountType, canonicalKey).first()
                assertEquals(4, onExit.lastPageIndex)
                assertTrue(onExit.chapters.getValue("ch-1").isCompleted)
                assertEquals(null, progressRepository.observe(accountType, slugKey).first().lastChapterId)
            } finally {
                readerJob.cancelAndJoin()
                detailJob.cancelAndJoin()
                readerPresenter.awaitPendingWrites()
                detailPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun inReaderChapterDirectorySupportsGroupSwitchSortToggleAndOpenChapterReuse() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-directory-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            var now = 30_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val ch1 = UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1")
            val ch2 = UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = "https://manga.example/ch-2")
            val ch3Locked = UiMangaChapter("ch-3", "Chapter 3", null, null, locked = true, url = "https://manga.example/paywall/ch-3")
            val ex1 = UiMangaChapter("ex-1", "Extra 1", null, null, locked = false, url = null)
            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-main",
                                title = UiText.Raw("Main"),
                                chapters = persistentListOf(ch1, ch2, ch3Locked),
                            ),
                            UiMangaChapterGroup(
                                id = "group-extra",
                                title = UiText.Raw("Extra"),
                                chapters = persistentListOf(ex1),
                            ),
                        ),
                )
            val pagesByChapter =
                mapOf(
                    "ch-1" to readyPages(count = 4, prefix = "ch1"),
                    "ch-2" to readyPages(count = 3, prefix = "ch2"),
                    "ex-1" to readyPages(count = 3, prefix = "ex1"),
                )
            val chapterPageLoads = CopyOnWriteArrayList<String>()
            val extraPagesRequested = CompletableDeferred<Unit>()
            val releaseExtraPages = CompletableDeferred<Unit>()
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)
            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPageLoads += chapterId
                        if (chapterId == "ex-1") {
                            extraPagesRequested.complete(Unit)
                            releaseExtraPages.await()
                        }
                        return pagesByChapter.getValue(chapterId)
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                // 1. Initial state at ch-1: selectedChapterGroupId == "group-main", descending chapterItems, ascending boundaries.
                val initial = awaitReaderState(states) { it.loadState is UiState.Success && it.pages.size == 4 }
                assertEquals(listOf("group-main", "group-extra"), initial.chapterGroups.map { it.id })
                assertEquals("group-main", initial.selectedChapterGroupId)
                assertTrue(initial.chapterSortDescending)
                assertTrue(initial.webtoonConstrainWidthOnWideScreen)
                assertFalse(initial.volumeKeysNavigation)
                assertEquals(
                    listOf(
                        ReaderChapterItem(chapter = ch3Locked, isCurrent = false, isRead = false, lastPageIndex = 0),
                        ReaderChapterItem(chapter = ch2, isCurrent = false, isRead = false, lastPageIndex = 0),
                        ReaderChapterItem(chapter = ch1, isCurrent = true, isRead = false, lastPageIndex = 0),
                    ),
                    initial.chapterItems,
                )
                assertEquals(ChapterBoundary.None, initial.previousBoundary)
                val nextUnlocked = assertIs<ChapterBoundary.Unlocked>(initial.nextBoundary)
                assertEquals("ch-2", nextUnlocked.chapter.id)
                assertFalse(nextUnlocked.startFromEnd)
                assertEquals("https://manga.example/paywall/ch-3", initial.resolveChapterOpenUrl(ch3Locked))
                assertEquals("https://manga.example/series-1", initial.resolveChapterOpenUrl(ex1))

                // Locked or already-active chapters are ignored by selectChapter.
                initial.selectChapter("ch-3")
                initial.selectChapter("ch-1")
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals("ch-1", states.value!!.activeChapterId)
                assertEquals(listOf("ch-1"), chapterPageLoads.toList())

                // 2. toggleChapterSort() flips to ascending and persists in MangaProgressRepository while keeping boundaries ascending.
                states.value!!.toggleChapterSort()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val ascendingState = awaitReaderState(states) { !it.chapterSortDescending }
                assertEquals(listOf("ch-1", "ch-2", "ch-3"), ascendingState.chapterItems.map { it.chapter.id })
                assertEquals(ChapterBoundary.None, ascendingState.previousBoundary)
                assertEquals("ch-2", assertIs<ChapterBoundary.Unlocked>(ascendingState.nextBoundary).chapter.id)
                val sortedProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { !it.chapterSortDescending }
                        }
                    }
                assertFalse(sortedProgress.chapterSortDescending)

                // 3. selectChapterGroup("group-extra") previews ex-1 while activeChapterId remains "ch-1".
                ascendingState.goToPage(2)
                ascendingState.selectChapterGroup("group-extra")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val extraGroupState = awaitReaderState(states) { it.selectedChapterGroupId == "group-extra" && it.currentPageIndex == 2 }
                assertEquals("ch-1", extraGroupState.activeChapterId)
                assertEquals(
                    listOf(ReaderChapterItem(chapter = ex1, isCurrent = false, isRead = false, lastPageIndex = 0)),
                    extraGroupState.chapterItems,
                )

                // 4. selectChapter("ch-2") flushes ch-1 progress immediately, switches activeChapterId, and syncs group back to "group-main".
                extraGroupState.selectChapter("ch-2")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val flushedCh1 =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-1"]?.lastPageIndex == 2 }
                        }
                    }
                assertFalse(flushedCh1.chapters.getValue("ch-1").isCompleted)
                val ch2State =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" &&
                            it.loadState is UiState.Success &&
                            it.pages.size == 3 &&
                            it.chapterItems.firstOrNull()?.lastPageIndex == 2
                    }
                assertEquals("group-main", ch2State.selectedChapterGroupId)
                assertEquals(
                    listOf(
                        ReaderChapterItem(chapter = ch1, isCurrent = false, isRead = false, lastPageIndex = 2),
                        ReaderChapterItem(chapter = ch2, isCurrent = true, isRead = false, lastPageIndex = 0),
                        ReaderChapterItem(chapter = ch3Locked, isCurrent = false, isRead = false, lastPageIndex = 0),
                    ),
                    ch2State.chapterItems,
                )

                // Move to final page of ch-2 so switching via openChapter flushes ch-2 as completed (isRead = true).
                ch2State.goToPage(2)
                Snapshot.sendApplyNotifications()
                runCurrent()

                states.value!!.openChapter("ex-1", initialPage = 1)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val loadingExtra = awaitReaderState(states) { it.activeChapterId == "ex-1" && it.loadState is UiState.Loading }
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { extraPagesRequested.await() }
                }
                assertEquals(listOf("ch-1", "ch-2", "ex-1"), chapterPageLoads.toList())
                loadingExtra.openChapter("ex-1", initialPage = 2)
                loadingExtra.openChapter("ex-1", initialPage = null)
                releaseExtraPages.complete(Unit)
                // Landing on the last page schedules an asynchronous progress write.
                val loadedExtra =
                    awaitReaderState(states) {
                        it.activeChapterId == "ex-1" &&
                            it.loadState is UiState.Success &&
                            it.chapterItems.singleOrNull()?.isRead == true
                    }
                assertEquals(2, loadedExtra.currentPageIndex)
                assertEquals("group-extra", loadedExtra.selectedChapterGroupId)
                assertEquals(
                    listOf(ReaderChapterItem(chapter = ex1, isCurrent = true, isRead = true, lastPageIndex = 2)),
                    loadedExtra.chapterItems,
                )
                assertEquals(listOf("ch-1", "ch-2", "ex-1"), chapterPageLoads.toList())

                loadedExtra.openChapter("ex-1", initialPage = 0)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val ex1AtPage0 = awaitReaderState(states) { it.activeChapterId == "ex-1" && it.currentPageIndex == 0 }
                assertEquals(listOf("ch-1", "ch-2", "ex-1"), chapterPageLoads.toList())

                // Switching group preview back to "group-main" reflects ch-2 flushed as read (isRead = true, lastPageIndex = 2).
                ex1AtPage0.selectChapterGroup("group-main")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val mainGroupPreview =
                    awaitReaderState(states) {
                        it.selectedChapterGroupId == "group-main" &&
                            it.chapterItems.getOrNull(1)?.isRead == true
                    }
                assertEquals(
                    ReaderChapterItem(chapter = ch2, isCurrent = false, isRead = true, lastPageIndex = 2),
                    mainGroupPreview.chapterItems[1],
                )

                // 6. setWebtoonConstrainWidthOnWideScreen(false) and setVolumeKeysNavigation(true) update optimistically and persist.
                mainGroupPreview.setWebtoonConstrainWidthOnWideScreen(false)
                mainGroupPreview.setVolumeKeysNavigation(true)
                Snapshot.sendApplyNotifications()
                val optimisticSettingsState =
                    awaitReaderState(states) {
                        !it.webtoonConstrainWidthOnWideScreen && it.volumeKeysNavigation
                    }
                assertFalse(optimisticSettingsState.webtoonConstrainWidthOnWideScreen)
                assertTrue(optimisticSettingsState.volumeKeysNavigation)

                val persistedConfig =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            appDataStore.appSettingsStore.data
                                .first {
                                    !it.mangaReaderConfig.webtoonConstrainWidthOnWideScreen &&
                                        it.mangaReaderConfig.volumeKeysNavigation
                                }.mangaReaderConfig
                        }
                    }
                assertFalse(persistedConfig.webtoonConstrainWidthOnWideScreen)
                assertTrue(persistedConfig.volumeKeysNavigation)
            } finally {
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun pageImageRetryTracksAttemptsReasonsFailedUrlAndResetsOnChapterSwitchAndRetryChapter() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-retry-meta-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 40_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            data class ResolveCall(
                val chapterId: String,
                val pageId: String,
                val attempt: Int,
                val reason: MangaPageImageResolveReason,
                val failedUrl: String?,
            )

            val recordedCalls = CopyOnWriteArrayList<ResolveCall>()
            var failCh1InitialOnce = true
            var gatedImageRetryDeferred: CompletableDeferred<Unit>? = null
            var gatedImageRetryStarted: CompletableDeferred<Unit>? = null
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-main",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> =
                        when (chapterId) {
                            "ch-1" -> {
                                persistentListOf(
                                    UiMangaPage.Deferred(
                                        id = "p-0",
                                        pageToken = "pt-ch1-0",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                    UiMangaPage.Ready(
                                        id = "p-static",
                                        media = UiMedia.Image("https://manga.example/static-1.jpg"),
                                    ),
                                )
                            }

                            "ch-2" -> {
                                persistentListOf(
                                    UiMangaPage.Deferred(
                                        id = "p-0",
                                        pageToken = "pt-ch2-0",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                )
                            }

                            else -> {
                                error("Unexpected chapter $chapterId")
                            }
                        }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("4-arg overload should not be invoked when 7-arg overload is implemented")

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                        attempt: Int,
                        reason: MangaPageImageResolveReason,
                        failedUrl: String?,
                    ): UiMedia {
                        recordedCalls += ResolveCall(chapterId, pageId, attempt, reason, failedUrl)
                        if (chapterId == "ch-1" && pageId == "p-0") {
                            if (attempt == 0 && failCh1InitialOnce) {
                                failCh1InitialOnce = false
                                error("Initial page resolve failure")
                            }
                            if (reason == MangaPageImageResolveReason.ImageLoadFailed && attempt == 2) {
                                gatedImageRetryStarted?.complete(Unit)
                                gatedImageRetryDeferred?.await()
                                return UiMedia.Image("https://hath.example/v2/1.jpg")
                            }
                            return UiMedia.Image("https://hath.example/v1/1.jpg")
                        }
                        return UiMedia.Image("https://hath.example/$chapterId/$pageId-v$attempt.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                // 1. Initial resolution fails -> attempt = 0, reason = Initial, failedUrl = null
                val failedInitial =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-1" && it.pages.firstOrNull()?.status is PageMediaStatus.Failed
                    }
                assertEquals(
                    listOf(ResolveCall("ch-1", "p-0", attempt = 0, reason = MangaPageImageResolveReason.Initial, failedUrl = null)),
                    recordedCalls.toList(),
                )

                // 2. Retry after PageMediaStatus.Failed -> attempt = 1, reason = ResolveRetry, failedUrl = null
                failedInitial.retryPage("p-0")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val firstReady =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-1" && it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }
                assertEquals(
                    "https://hath.example/v1/1.jpg",
                    (assertIs<PageMediaStatus.Ready>(firstReady.pages[0].status).original as UiMedia.Image).url,
                )
                assertEquals(
                    ResolveCall("ch-1", "p-0", attempt = 1, reason = MangaPageImageResolveReason.ResolveRetry, failedUrl = null),
                    recordedCalls.last(),
                )

                // 3. Retry after image load failure on Deferred Ready page -> clears resolvedMedia (Loading),
                //    then calls attempt = 2, reason = ImageLoadFailed, failedUrl = "https://hath.example/v1/1.jpg"
                val gate = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                gatedImageRetryDeferred = gate
                gatedImageRetryStarted = started
                firstReady.retryPage("p-0", failedUrl = "https://hath.example/v1/1.jpg")
                Snapshot.sendApplyNotifications()
                runCurrent()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { started.await() }
                }
                val loadingDuringReResolve =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-1" && it.pages.firstOrNull()?.status is PageMediaStatus.Loading
                    }
                assertIs<PageMediaStatus.Loading>(loadingDuringReResolve.pages[0].status)
                gate.complete(Unit)
                val secondReady =
                    awaitReaderState(states) { state ->
                        val ready = state.pages.firstOrNull()?.status as? PageMediaStatus.Ready
                        (ready?.original as? UiMedia.Image)?.url == "https://hath.example/v2/1.jpg"
                    }
                assertEquals(
                    ResolveCall(
                        chapterId = "ch-1",
                        pageId = "p-0",
                        attempt = 2,
                        reason = MangaPageImageResolveReason.ImageLoadFailed,
                        failedUrl = "https://hath.example/v1/1.jpg",
                    ),
                    recordedCalls.last(),
                )

                // 4. Retrying static UiMangaPage.Ready does NOT call resolvePageMedia
                val callsBeforeStaticRetry = recordedCalls.size
                secondReady.retryPage("p-static", failedUrl = "https://manga.example/static-1.jpg")
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(callsBeforeStaticRetry, recordedCalls.size)

                // 5. Clamping: repeated retries cap attempt at 100
                var latestReady = secondReady
                for (expectedAttempt in 3..102) {
                    val prevCalls = recordedCalls.size
                    latestReady.retryPage("p-0", failedUrl = "https://hath.example/v2/1.jpg")
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    latestReady =
                        awaitReaderState(states) {
                            recordedCalls.size > prevCalls && it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                        }
                    assertEquals(expectedAttempt.coerceAtMost(100), recordedCalls.last().attempt)
                }

                // 6. retryChapter() clears pageAttempts -> p-0 resets back to attempt = 0, Initial, failedUrl = null
                val callsBeforeRetryChapter = recordedCalls.size
                latestReady.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val afterRetryChapter =
                    awaitReaderState(states) {
                        recordedCalls.size > callsBeforeRetryChapter &&
                            it.activeChapterId == "ch-1" &&
                            it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }
                assertEquals(
                    ResolveCall("ch-1", "p-0", attempt = 0, reason = MangaPageImageResolveReason.Initial, failedUrl = null),
                    recordedCalls.last(),
                )

                // 7. Switching to ch-2 resets pageAttempts -> p-0 in ch-2 also starts at attempt = 0, Initial
                val nextChapterBoundary = assertIs<ChapterBoundary.Unlocked>(afterRetryChapter.nextBoundary)
                afterRetryChapter.openAdjacentChapter(nextChapterBoundary)
                Snapshot.sendApplyNotifications()
                runCurrent()
                awaitReaderState(states) {
                    it.activeChapterId == "ch-2" && it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                }
                assertEquals(
                    ResolveCall("ch-2", "p-0", attempt = 0, reason = MangaPageImageResolveReason.Initial, failedUrl = null),
                    recordedCalls.last(),
                )
            } finally {
                gatedImageRetryDeferred?.complete(Unit)
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rangePagingInitialLoadJumpRestoreAndBoundaryEndFetchOnlyTargetWindow() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-range-jump-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 55_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            data class ChunkRequest(
                val chapterId: String,
                val offset: Int,
                val limit: Int?,
                val cursor: String?,
            )

            val totalPages = 840
            val chunkSize = 40
            val chunkRequests = CopyOnWriteArrayList<ChunkRequest>()
            val resolvedPageIds = CopyOnWriteArrayList<String>()
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            fun buildDeferredBlock(
                chapterId: String,
                blockStart: Int,
                count: Int,
            ): ImmutableList<UiMangaPage> =
                (blockStart until (blockStart + count))
                    .map { idx ->
                        UiMangaPage.Deferred(
                            id = "$chapterId-p-$idx",
                            pageToken = "tok-$chapterId-$idx",
                            previewUrl = null,
                            width = 800f,
                            height = 1200f,
                            customHeaders = null,
                            proxy = ProxyRef.Unbound,
                        )
                    }.toImmutableList()

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        chunkRequests += ChunkRequest(chapterId, offset, limit, cursor)
                        val alignedOffset = (offset / chunkSize) * chunkSize
                        val count = minOf(chunkSize, totalPages - alignedOffset)
                        return MangaPagesChunk(
                            pages = buildDeferredBlock(chapterId, alignedOffset, count),
                            offset = alignedOffset,
                            totalCount = totalPages,
                            nextCursor = null,
                            endReached = true,
                        )
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        assertFalse(pageId.startsWith("__chunk_placeholder_"), "Must never resolve placeholder $pageId")
                        resolvedPageIds += pageId
                        return UiMedia.Image("https://manga.example/$chapterId/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val job1 = SupervisorJob()
            val scope1 = CoroutineScope(dispatcher + job1)
            val job2 = SupervisorJob()
            val scope2 = CoroutineScope(dispatcher + job2)
            val presenter1 = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = null)
            val presenter2 = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 0)
            try {
                // Seed saved progress at page 500 of 840 in ch-1.
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-1",
                    pageIndex = 500,
                    pageCount = totalPages,
                    isCompleted = false,
                )

                val states1 = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope1.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter1.body() }.collect { states1.value = it }
                }

                val restoredState =
                    awaitReaderState(states1) { state ->
                        state.loadState is UiState.Success &&
                            state.pages.size == totalPages &&
                            state.pages[500].status is PageMediaStatus.Ready
                    }
                assertEquals(500, restoredState.currentPageIndex)
                assertEquals("ch-1-p-500", restoredState.pages[500].id)
                assertEquals("ch-1-p-480", restoredState.pages[480].id)
                assertEquals("ch-1-p-519", restoredState.pages[519].id)
                // Unloaded pages remain placeholders with Loading status and bound proxy.
                val placeholder100 = restoredState.pages[100]
                assertEquals("__chunk_placeholder_100", placeholder100.id)
                assertEquals(100, placeholder100.index)
                assertNull(placeholder100.aspectRatio)
                val loadingPlaceholder = assertIs<PageMediaStatus.Loading>(placeholder100.status)
                assertEquals(ProxyRef.Account(accountKey), loadingPlaceholder.proxy)
                assertNull(loadingPlaceholder.previewUrl)
                // Zero intermediate chunks in 40..479 were requested.
                assertTrue(
                    chunkRequests.none { it.chapterId == "ch-1" && it.offset in 40..479 },
                    "Intermediate chunks 40..479 must not be fetched on jump restore: $chunkRequests",
                )
                assertTrue(chunkRequests.any { it.chapterId == "ch-1" && it.offset == 0 && it.limit == null })
                assertTrue(chunkRequests.any { it.chapterId == "ch-1" && it.offset in 480..519 && it.limit == 40 })
                assertTrue(resolvedPageIds.none { it.startsWith("__chunk_placeholder_") })

                job1.cancelAndJoin()
                presenter1.awaitPendingWrites()
                chunkRequests.clear()

                // Opening ch-2 at page 0 fetches ONLY offset 0 and immediately exposes 840 pages.
                val states2 = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope2.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter2.body() }.collect { states2.value = it }
                }
                val ch2AtStart =
                    awaitReaderState(states2) { state ->
                        state.activeChapterId == "ch-2" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == totalPages &&
                            state.pages[0].status is PageMediaStatus.Ready
                    }
                assertEquals(ChunkRequest("ch-2", offset = 0, limit = null, cursor = null), chunkRequests.first())
                assertTrue(
                    chunkRequests.all { it.chapterId == "ch-2" && it.offset in 0..40 },
                    "Initial ch-2 load at page 0 must only touch [0..40] window: $chunkRequests",
                )
                assertEquals("__chunk_placeholder_500", ch2AtStart.pages[500].id)

                // Stepping backward from page 0 of ch-2 enters ch-1 at Boundary(startFromEnd = true) -> lands directly at 839!
                chunkRequests.clear()
                ch2AtStart.stepBackward()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val ch1AtEnd =
                    awaitReaderState(states2) { state ->
                        state.activeChapterId == "ch-1" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == totalPages &&
                            state.pages[839].status is PageMediaStatus.Ready
                    }
                assertEquals(839, ch1AtEnd.currentPageIndex)
                assertTrue(
                    chunkRequests.none { it.chapterId == "ch-1" && it.offset in 40..799 },
                    "End boundary jump must not fetch intermediate chunks: $chunkRequests",
                )
            } finally {
                job1.cancelAndJoin()
                job2.cancelAndJoin()
                presenter1.awaitPendingWrites()
                presenter2.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rangePagingScrubCancelsOutOfWindowJobsAndRespectsSemaphore2NearToFar() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-scrub-semaphore-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 56_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val totalPages = 200
            val initialChunkSize = 20 // chunkSizeHint = 20 -> window [current - 10, current + 20]
            val returnedChunkSize = 10 // returning 10 pages per range request forces multiple chunks in window
            val gatesByOffset = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
            val startedByOffset = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
            val cancelledByOffset = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
            val cancelledOffsets = CopyOnWriteArrayList<Int>()
            val requestedOffsets = CopyOnWriteArrayList<Int>()
            val inFlightCount = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        requestedOffsets += offset
                        if (offset == 0) {
                            return MangaPagesChunk(
                                pages =
                                    (0 until initialChunkSize)
                                        .map { idx ->
                                            UiMangaPage.Ready("p-$idx", UiMedia.Image("https://manga.example/$idx.jpg"))
                                        }.toImmutableList(),
                                offset = 0,
                                totalCount = totalPages,
                                endReached = true,
                            )
                        }
                        val active = inFlightCount.incrementAndGet()
                        maxObservedConcurrency.accumulateAndGet(active, ::maxOf)
                        startedByOffset[offset]?.complete(Unit)
                        try {
                            gatesByOffset[offset]?.await()
                            val count = minOf(returnedChunkSize, totalPages - offset)
                            return MangaPagesChunk(
                                pages =
                                    (offset until (offset + count))
                                        .map { idx ->
                                            UiMangaPage.Ready("p-$idx", UiMedia.Image("https://manga.example/$idx.jpg"))
                                        }.toImmutableList(),
                                offset = offset,
                                totalCount = totalPages,
                                endReached = true,
                            )
                        } catch (e: CancellationException) {
                            cancelledOffsets += offset
                            cancelledByOffset[offset]?.complete(Unit)
                            throw e
                        } finally {
                            inFlightCount.decrementAndGet()
                        }
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val atStart = awaitReaderState(states) { it.loadState is UiState.Success && it.pages.size == totalPages }

                // 1. Scrub to page 60 (window 50..80), hold 60 and 80 on gates, then rapidly scrub to 150 (window 140..170):
                //    in-flight requests outside [140..170] must be cancelled immediately.
                val start60 = CompletableDeferred<Unit>()
                val gate60 = CompletableDeferred<Unit>()
                val cancelled60 = CompletableDeferred<Unit>()
                startedByOffset[60] = start60
                gatesByOffset[60] = gate60
                cancelledByOffset[60] = cancelled60
                val gate80 = CompletableDeferred<Unit>()
                gatesByOffset[80] = gate80

                atStart.goToPage(60)
                Snapshot.sendApplyNotifications()
                runCurrent()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { start60.await() }
                }

                // Rapidly scrub to 150 before releasing 60/80 -> old window jobs cancel, 150 loads!
                val gate150 = CompletableDeferred<Unit>()
                val start150 = CompletableDeferred<Unit>()
                gatesByOffset[150] = gate150
                startedByOffset[150] = start150

                states.value!!.goToPage(150)
                Snapshot.sendApplyNotifications()
                runCurrent()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { cancelled60.await() }
                }
                assertTrue(60 in cancelledOffsets, "Out-of-window chunk job at 60 must be cancelled on scrub to 150")
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { start150.await() }
                }
                gate150.complete(Unit)
                val settled150 =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 150 &&
                            (140..170).all { idx -> state.pages[idx].status is PageMediaStatus.Ready }
                    }
                assertTrue(
                    maxObservedConcurrency.get() in 1..2,
                    "Chunk concurrency must be bounded by Semaphore(2), got ${maxObservedConcurrency.get()}",
                )

                // 2. Verify near-to-far order (current -> forward -> backward) and Semaphore(2) with 3 pending chunks:
                //    at page 100 (window 90..120), 100 returns 100..109, leaving 110 (forward), 120 (forward), and 90 (backward).
                requestedOffsets.clear()
                maxObservedConcurrency.set(0)
                val gate110 = CompletableDeferred<Unit>()
                val gate120 = CompletableDeferred<Unit>()
                val start110 = CompletableDeferred<Unit>()
                val start120 = CompletableDeferred<Unit>()
                gatesByOffset[110] = gate110
                gatesByOffset[120] = gate120
                startedByOffset[110] = start110
                startedByOffset[120] = start120

                settled150.goToPage(100)
                Snapshot.sendApplyNotifications()
                runCurrent()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        start110.await()
                        start120.await()
                    }
                }
                assertEquals(2, maxObservedConcurrency.get(), "Expected Semaphore(2) to run 110 and 120 concurrently")
                assertFalse(90 in requestedOffsets, "Backward chunk 90 must not overtake forward chunks 110 & 120")

                gate110.complete(Unit)
                gate120.complete(Unit)
                awaitReaderState(states) { state ->
                    state.currentPageIndex == 100 &&
                        (90..120).all { idx -> state.pages[idx].status is PageMediaStatus.Ready }
                }
                val idx100 = requestedOffsets.indexOf(100)
                val idx110 = requestedOffsets.indexOf(110)
                val idx120 = requestedOffsets.indexOf(120)
                val idx90 = requestedOffsets.indexOf(90)
                assertTrue(
                    idx100 in 0 until idx110 && idx110 < idx90 && idx120 < idx90,
                    "Expected current -> forward -> backward order, got $requestedOffsets",
                )
                assertTrue(maxObservedConcurrency.get() <= 2, "Chunk concurrency exceeded Semaphore(2): ${maxObservedConcurrency.get()}")
            } finally {
                gatesByOffset.values.forEach { it.complete(Unit) }
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rangeChunkFailureMarksOnlyUnloadedIndicesAndPlaceholderRetryRecoversChunkInPlace() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-chunk-retry-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 57_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val totalPages = 840
            val chunkSize = 40
            var failBlock12Once = true
            val chunkRequests = CopyOnWriteArrayList<Int>()
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters = persistentListOf(UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null)),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        chunkRequests += offset
                        val aligned = (offset / chunkSize) * chunkSize
                        if (aligned == 480 && failBlock12Once) {
                            failBlock12Once = false
                            throw IOException("Chunk 480 failed")
                        }
                        val count = minOf(chunkSize, totalPages - aligned)
                        return MangaPagesChunk(
                            pages =
                                (aligned until (aligned + count))
                                    .map { idx ->
                                        UiMangaPage.Deferred(
                                            id = "p-$idx",
                                            pageToken = "pt-$idx",
                                            previewUrl = null,
                                            width = 800f,
                                            height = 1200f,
                                            customHeaders = null,
                                            proxy = ProxyRef.Global,
                                        )
                                    }.toImmutableList(),
                            offset = aligned,
                            totalCount = totalPages,
                            endReached = true,
                        )
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        assertFalse(pageId.startsWith("__chunk_placeholder_"))
                        return UiMedia.Image("https://manga.example/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val initial =
                    awaitReaderState(states) {
                        it.loadState is UiState.Success &&
                            it.pages.size == totalPages &&
                            it.pages[0].status is PageMediaStatus.Ready
                    }
                initial.goToPage(500)
                Snapshot.sendApplyNotifications()
                runCurrent()

                // Chunk 480..519 failed -> unloaded page 500 displays Failed(IOException), while already-loaded 0..39 & 520..559 stay intact!
                val chunkFailed =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 500 &&
                            state.pages[500].id == "__chunk_placeholder_500" &&
                            state.pages[500].status is PageMediaStatus.Failed &&
                            state.pages[520].id == "p-520"
                    }
                assertIs<UiState.Success<Unit>>(chunkFailed.loadState)
                assertIs<PageMediaStatus.Ready>(chunkFailed.pages[0].status)
                assertIs<PageMediaStatus.Loading>(chunkFailed.pages[520].status)
                val failedError = assertIs<PageMediaStatus.Failed>(chunkFailed.pages[500].status).error
                assertIs<IOException>(failedError)

                // Retrying placeholder 500 fetches ONLY that chunk and replaces in place!
                val requestsBeforeRetry = chunkRequests.size
                chunkFailed.retryPage("__chunk_placeholder_500")
                Snapshot.sendApplyNotifications()
                runCurrent()

                val recovered =
                    awaitReaderState(states) { state ->
                        state.pages[500].id == "p-500" && state.pages[500].status is PageMediaStatus.Ready
                    }
                assertEquals(listOf(500), chunkRequests.drop(requestsBeforeRetry))
                assertEquals("p-480", recovered.pages[480].id)
                assertEquals("p-519", recovered.pages[519].id)
            } finally {
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rangeChunkValidatesOverlapPreservesResolvedStateAndRejectsDriftAndDuplicates() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-overlap-drift-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 58_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val totalPages = 300
            val chunkSize = 20
            val resolveAttemptsForP10 = CopyOnWriteArrayList<Int>()
            var retryChapterFixed = false
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters = persistentListOf(UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null)),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            fun pageList(
                indices: IntRange,
                idForIndex: (Int) -> String = { "p-$it" },
            ): ImmutableList<UiMangaPage> =
                indices
                    .map { idx ->
                        if (idx == 10) {
                            UiMangaPage.Deferred(
                                id = idForIndex(idx),
                                pageToken = "tok-10",
                                previewUrl = null,
                                width = 800f,
                                height = 1200f,
                                customHeaders = null,
                                proxy = ProxyRef.Global,
                            )
                        } else {
                            UiMangaPage.Ready(id = idForIndex(idx), media = UiMedia.Image("https://manga.example/${idForIndex(idx)}.jpg"))
                        }
                    }.toImmutableList()

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        if (retryChapterFixed) {
                            val start = (offset / chunkSize) * chunkSize
                            val end = minOf(totalPages - 1, start + chunkSize - 1)
                            return MangaPagesChunk(
                                pages = pageList(start..end),
                                offset = start,
                                totalCount = totalPages,
                                endReached = true,
                            )
                        }
                        return when (offset) {
                            0 -> {
                                MangaPagesChunk(pages = pageList(0..19), offset = 0, totalCount = totalPages, endReached = true)
                            }

                            in 20..39 -> {
                                MangaPagesChunk(pages = pageList(20..39), offset = 20, totalCount = totalPages, endReached = true)
                            }

                            in 40..59 -> {
                                // Overlapping [10..49] with identical IDs on 10..39 -> must preserve p-10 resolved media & attempts!
                                MangaPagesChunk(pages = pageList(10..49), offset = 10, totalCount = totalPages, endReached = true)
                            }

                            in 60..79 -> {
                                MangaPagesChunk(pages = pageList(50..69), offset = 50, totalCount = totalPages, endReached = true)
                            }

                            in 75..99 -> {
                                // Overlaps index 100 with conflicting ID!
                                MangaPagesChunk(
                                    pages =
                                        pageList(85..104) { idx ->
                                            if (idx == 100) "conflict-100" else "p-$idx"
                                        },
                                    offset = 85,
                                    totalCount = totalPages,
                                    endReached = true,
                                )
                            }

                            in 140..179 -> {
                                // Cross-index duplicate page ID ("p-10" already exists at index 10)!
                                MangaPagesChunk(
                                    pages =
                                        pageList(140..159) { idx ->
                                            if (idx == 150) "p-10" else "p-$idx"
                                        },
                                    offset = 140,
                                    totalCount = totalPages,
                                    endReached = true,
                                )
                            }

                            in 200..239 -> {
                                // totalCount drift (350 != 300)!
                                MangaPagesChunk(pages = pageList(200..219), offset = 200, totalCount = 350, endReached = true)
                            }

                            in 250..289 -> {
                                // Mode flip (totalCount = null)!
                                MangaPagesChunk(
                                    pages = pageList(250..269),
                                    offset = 250,
                                    totalCount = null,
                                    endReached = false,
                                    nextCursor = "bad",
                                )
                            }

                            else -> {
                                val start = (offset / chunkSize) * chunkSize
                                val end = minOf(totalPages - 1, start + chunkSize - 1)
                                MangaPagesChunk(pages = pageList(start..end), offset = start, totalCount = totalPages, endReached = true)
                            }
                        }
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                        attempt: Int,
                        reason: MangaPageImageResolveReason,
                        failedUrl: String?,
                    ): UiMedia {
                        if (pageId == "p-10") {
                            resolveAttemptsForP10 += attempt
                        }
                        return UiMedia.Image("https://cdn.example/$pageId-v$attempt.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 10)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                // Resolve p-10 once (attempt 0), then retry once (attempt 1) so v1 URL & attempt=1 are recorded.
                val initialP10 =
                    awaitReaderState(states) { state ->
                        state.loadState is UiState.Success && state.pages[10].status is PageMediaStatus.Ready
                    }
                initialP10.retryPage("p-10", failedUrl = "https://cdn.example/p-10-v0.jpg")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val retriedP10 =
                    awaitReaderState(states) { state ->
                        val ready = state.pages[10].status as? PageMediaStatus.Ready
                        (ready?.original as? UiMedia.Image)?.url == "https://cdn.example/p-10-v1.jpg"
                    }
                assertEquals(listOf(0, 1), resolveAttemptsForP10)

                // A. Jump to 45 -> overlapping chunk [10..49] loads 40..49 and keeps p-10 resolved media + pageAttempts!
                retriedP10.goToPage(45)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val overlapped =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 45 && state.pages[45].id == "p-45"
                    }
                val preservedP10 = assertIs<PageMediaStatus.Ready>(overlapped.pages[10].status)
                assertEquals("https://cdn.example/p-10-v1.jpg", (preservedP10.original as UiMedia.Image).url)
                assertEquals(listOf(0, 1), resolveAttemptsForP10)
                overlapped.retryPage("p-10", failedUrl = "https://cdn.example/p-10-v1.jpg")
                Snapshot.sendApplyNotifications()
                runCurrent()
                awaitReaderState(states) { state ->
                    val ready = state.pages[10].status as? PageMediaStatus.Ready
                    (ready?.original as? UiMedia.Image)?.url == "https://cdn.example/p-10-v2.jpg"
                }
                assertEquals(listOf(0, 1, 2), resolveAttemptsForP10)

                // B. Load 100..129 first, then jump to 85 where [85..104] conflicts on index 100 -> IllegalStateException without corrupting 100..119!
                states.value!!.goToPage(110)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val loaded100 = awaitReaderState(states) { it.pages[100].id == "p-100" }

                loaded100.goToPage(85)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val mismatchFailed =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 85 && state.pages[85].status is PageMediaStatus.Failed
                    }
                assertIs<IllegalStateException>(assertIs<PageMediaStatus.Failed>(mismatchFailed.pages[85].status).error)
                assertEquals("p-100", mismatchFailed.pages[100].id)
                assertIs<PageMediaStatus.Ready>(mismatchFailed.pages[100].status)

                // C. Cross-index duplicate page ID ("p-10" at index 150) -> rejected with IllegalStateException, p-10 stays at 10!
                mismatchFailed.goToPage(150)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val dupFailed =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 150 && state.pages[150].status is PageMediaStatus.Failed
                    }
                assertIs<IllegalStateException>(assertIs<PageMediaStatus.Failed>(dupFailed.pages[150].status).error)
                assertEquals("p-10", dupFailed.pages[10].id)
                assertEquals("__chunk_placeholder_150", dupFailed.pages[150].id)

                // D. totalCount drift (350 != 300) and mode flip (totalCount == null) -> rejected with IllegalStateException!
                dupFailed.goToPage(210)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val driftFailed =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 210 && state.pages[210].status is PageMediaStatus.Failed
                    }
                assertIs<IllegalStateException>(assertIs<PageMediaStatus.Failed>(driftFailed.pages[210].status).error)
                assertEquals(300, driftFailed.pages.size)

                driftFailed.goToPage(260)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val flipFailed =
                    awaitReaderState(states) { state ->
                        state.currentPageIndex == 260 && state.pages[260].status is PageMediaStatus.Failed
                    }
                assertIs<IllegalStateException>(assertIs<PageMediaStatus.Failed>(flipFailed.pages[260].status).error)

                // retryChapter() rebuilds clean state preserving page 260!
                retryChapterFixed = true
                flipFailed.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val rebuilt =
                    awaitReaderState(states) { state ->
                        state.loadState is UiState.Success &&
                            state.currentPageIndex == 260 &&
                            state.pages[260].id == "p-260" &&
                            state.pages[260].status is PageMediaStatus.Ready
                    }
                assertEquals(300, rebuilt.pages.size)
            } finally {
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cursorModeAppendsSequentiallyEnforcesOffsetsChasesUpTo50AndGuardsIsCompleted() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-cursor-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            var now = 59_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            data class CursorCall(
                val chapterId: String,
                val offset: Int,
                val cursor: String?,
            )

            val calls = CopyOnWriteArrayList<CursorCall>()
            var failCur2WithBadOffsetOnce = true
            val cur1Started = CompletableDeferred<Unit>()
            val cur1Gate = CompletableDeferred<Unit>()
            val chaseCancelStarted = CompletableDeferred<Unit>()
            val chaseCancelGate = CompletableDeferred<Unit>()
            val chaseCancelled = CompletableDeferred<Unit>()
            val chSeq = UiMangaChapter("ch-seq", "Seq", null, null, locked = false, url = null)
            val chChaseOk = UiMangaChapter("ch-chase-ok", "Chase OK", null, null, locked = false, url = null)
            val chChaseCap = UiMangaChapter("ch-chase-cap", "Chase Cap", null, null, locked = false, url = null)
            val chChaseCancel = UiMangaChapter("ch-chase-cancel", "Chase Cancel", null, null, locked = false, url = null)
            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters = persistentListOf(chSeq, chChaseOk, chChaseCap, chChaseCancel),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            fun pages(
                prefix: String,
                range: IntRange,
            ): ImmutableList<UiMangaPage> =
                range
                    .map { idx ->
                        UiMangaPage.Ready("$prefix-$idx", UiMedia.Image("https://manga.example/$prefix-$idx.jpg"))
                    }.toImmutableList()

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        calls += CursorCall(chapterId, offset, cursor)
                        if (cursor != null) {
                            assertEquals(0, offset, "Cursor continuation must never pass positive offset")
                        }
                        return when (chapterId) {
                            "ch-seq" -> {
                                when (cursor) {
                                    null -> {
                                        MangaPagesChunk(
                                            pages = pages("seq", 0..14),
                                            offset = 0,
                                            totalCount = null,
                                            nextCursor = "cur-1",
                                            endReached = false,
                                        )
                                    }

                                    "cur-1" -> {
                                        cur1Started.complete(Unit)
                                        cur1Gate.await()
                                        MangaPagesChunk(
                                            pages = pages("seq", 15..29),
                                            offset = null,
                                            totalCount = null,
                                            nextCursor = "cur-2",
                                            endReached = false,
                                        )
                                    }

                                    "cur-2" -> {
                                        if (failCur2WithBadOffsetOnce) {
                                            failCur2WithBadOffsetOnce = false
                                            MangaPagesChunk(
                                                pages = pages("seq", 30..34),
                                                offset = 29,
                                                totalCount = null,
                                                nextCursor = null,
                                                endReached = true,
                                            )
                                        } else {
                                            MangaPagesChunk(
                                                pages = pages("seq", 30..34),
                                                offset = 30,
                                                totalCount = null,
                                                nextCursor = null,
                                                endReached = true,
                                            )
                                        }
                                    }

                                    else -> {
                                        error("Unexpected cursor $cursor")
                                    }
                                }
                            }

                            "ch-chase-ok" -> {
                                val chunkIdx = cursor?.removePrefix("ok-")?.toInt() ?: 0
                                val start = chunkIdx * 5
                                val end = start + 4
                                val isLast = chunkIdx == 3
                                MangaPagesChunk(
                                    pages = pages("ok", start..end),
                                    offset = if (chunkIdx == 0) 0 else null,
                                    totalCount = null,
                                    nextCursor = if (isLast) null else "ok-${chunkIdx + 1}",
                                    endReached = isLast,
                                )
                            }

                            "ch-chase-cap" -> {
                                val chunkIdx = cursor?.removePrefix("cap-")?.toInt() ?: 0
                                val start = chunkIdx * 2
                                val end = start + 1
                                val isLast = chunkIdx == 59 // 60 chunks total (0..119)
                                MangaPagesChunk(
                                    pages = pages("cap", start..end),
                                    offset = if (chunkIdx == 0) 0 else null,
                                    totalCount = null,
                                    nextCursor = if (isLast) null else "cap-${chunkIdx + 1}",
                                    endReached = isLast,
                                )
                            }

                            "ch-chase-cancel" -> {
                                if (cursor == null) {
                                    MangaPagesChunk(
                                        pages = pages("can", 0..4),
                                        offset = 0,
                                        totalCount = null,
                                        nextCursor = "can-1",
                                        endReached = false,
                                    )
                                } else {
                                    chaseCancelStarted.complete(Unit)
                                    try {
                                        chaseCancelGate.await()
                                    } catch (e: CancellationException) {
                                        chaseCancelled.complete(Unit)
                                        throw e
                                    }
                                    MangaPagesChunk(
                                        pages = pages("can", 5..9),
                                        offset = null,
                                        totalCount = null,
                                        nextCursor = null,
                                        endReached = true,
                                    )
                                }
                            }

                            else -> {
                                error("Unexpected chapter $chapterId")
                            }
                        }
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val job1 = SupervisorJob()
            val scope1 = CoroutineScope(dispatcher + job1)
            val job2 = SupervisorJob()
            val scope2 = CoroutineScope(dispatcher + job2)
            val job3 = SupervisorJob()
            val scope3 = CoroutineScope(dispatcher + job3)

            val presenterSeq = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-seq", initialPage = 0)
            val presenterCap = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-chase-cap", initialPage = 500)
            val presenterCancel = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-chase-cancel", initialPage = 50)
            try {
                // 1. Sequential append, omitted offset, invalid offset rejection, and isCompleted guard while endReached == false.
                val seqStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope1.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenterSeq.body() }.collect { seqStates.value = it }
                }
                val seqInitial = awaitReaderState(seqStates) { it.loadState is UiState.Success && it.pages.size == 15 }
                assertEquals(listOf(CursorCall("ch-seq", 0, null)), calls.toList())
                assertEquals(
                    ChapterBoundary.None,
                    seqInitial.nextBoundary,
                    "nextBoundary must stay None while cursor chapter still has unloaded pages",
                )
                seqInitial.stepForward()
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals("ch-seq", seqStates.value!!.activeChapterId)
                seqInitial.goToPage(0)
                Snapshot.sendApplyNotifications()
                runCurrent()

                // Scrolling to index 4 (= 14 - 10) triggers single-flight cur-1; scrolling further (5, 6) while cur-1 is in flight must NOT duplicate cur-1!
                seqInitial.goToPage(4)
                Snapshot.sendApplyNotifications()
                runCurrent()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { cur1Started.await() }
                }
                seqStates.value!!.goToPage(5)
                Snapshot.sendApplyNotifications()
                runCurrent()
                seqStates.value!!.goToPage(6)
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(1, calls.count { it.chapterId == "ch-seq" && it.cursor == "cur-1" })
                cur1Gate.complete(Unit)

                val afterCur1 = awaitReaderState(seqStates) { it.pages.size == 30 }
                assertEquals("seq-15", afterCur1.pages[15].id)
                assertEquals("seq-29", afterCur1.pages[29].id)
                assertEquals(1, calls.count { it.chapterId == "ch-seq" && it.cursor == "cur-1" })

                // Staying below loadedMaxIndex - 10 (= 19) does not trigger cur-2; moving to 29 triggers cur-2 failure (offset 29 != 30)
                // while keeping isCompleted == false while endReached == false.
                afterCur1.goToPage(18)
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(0, calls.count { it.chapterId == "ch-seq" && it.cursor == "cur-2" })

                // Calling retryPage("__chunk_placeholder_<loadedCount>") while cursorAppendError == null must NOT launch an unsolicited append.
                afterCur1.retryPage("__chunk_placeholder_30")
                Snapshot.sendApplyNotifications()
                testScheduler.advanceUntilIdle()
                runCurrent()
                assertEquals(0, calls.count { it.chapterId == "ch-seq" && it.cursor == "cur-2" })

                afterCur1.goToPage(29)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val cur2Failed =
                    awaitReaderState(seqStates) { state ->
                        state.pages.any { it.status is PageMediaStatus.Failed }
                    }
                advanceTimeBy(350)
                runCurrent()
                val incompleteProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-seq"]?.lastPageIndex == 29 }
                        }
                    }
                assertFalse(
                    incompleteProgress.chapters.getValue("ch-seq").isCompleted,
                    "isCompleted must remain false while endReached == false even at loadedMaxIndex",
                )
                assertEquals(30, cur2Failed.pages.count { !it.id.startsWith("__chunk_placeholder_") })
                assertIs<IllegalStateException>(assertIs<PageMediaStatus.Failed>(cur2Failed.pages[30].status).error)

                // Calling retryPage("__chunk_placeholder_0") when cursorAppendError != null and loadedCount > 0 must NOT retry/clear cursor append.
                cur2Failed.retryPage("__chunk_placeholder_0")
                Snapshot.sendApplyNotifications()
                testScheduler.advanceUntilIdle()
                runCurrent()
                assertEquals(1, calls.count { it.chapterId == "ch-seq" && it.cursor == "cur-2" })
                assertIs<PageMediaStatus.Failed>(seqStates.value!!.pages[30].status)

                // Retry cur-2 -> succeeds with explicit offset = 30 and endReached = true -> nextBoundary unlocks and reaching page 34 marks isCompleted = true!
                cur2Failed.retryPage("__chunk_placeholder_30")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val afterCur2 = awaitReaderState(seqStates) { it.pages.size == 35 && it.pages[34].id == "seq-34" }
                assertEquals("ch-chase-ok", assertIs<ChapterBoundary.Unlocked>(afterCur2.nextBoundary).chapter.id)
                afterCur2.goToPage(34)
                Snapshot.sendApplyNotifications()
                advanceTimeBy(350)
                runCurrent()
                val completedProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-seq"]?.isCompleted == true }
                        }
                    }
                assertEquals(34, completedProgress.chapters.getValue("ch-seq").lastPageIndex)

                // Boundary(startFromEnd = true) into ch-seq? Actually test Boundary(startFromEnd = true) from ch-chase-cap back into ch-chase-ok!
                // First test ch-chase-ok via openChapter + stepBackward from ch-chase-cap? Wait: ch-chase-cap has 60 chunks, let's test ch-chase-ok via openAdjacentChapter/stepBackward!
                calls.clear()
                afterCur2.openChapter("ch-chase-cap", initialPage = 0)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val capAt0 = awaitReaderState(seqStates) { it.activeChapterId == "ch-chase-cap" && it.loadState is UiState.Success }
                capAt0.stepBackward() // Boundary(startFromEnd = true) -> ch-chase-ok (4 chunks, 20 pages, endReached = true)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val chasedEndOk =
                    awaitReaderState(seqStates) { state ->
                        state.activeChapterId == "ch-chase-ok" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == 20
                    }
                assertEquals(19, chasedEndOk.currentPageIndex)
                job1.cancelAndJoin()
                presenterSeq.awaitPendingWrites()

                // 2. Deep jump exceeding MAX_CURSOR_LANDING_CHUNKS (50): chases 50 cursor chunks (+ initial = 51 calls),
                //    clamps to loaded end (101), stops chasing until user scrolls, then continues lazily!
                calls.clear()
                val capStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope2.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenterCap.body() }.collect { capStates.value = it }
                }
                val cappedState =
                    awaitReaderState(capStates) { state ->
                        state.activeChapterId == "ch-chase-cap" && state.loadState is UiState.Success
                    }
                assertEquals(MAX_CURSOR_LANDING_CHUNKS + 1, calls.count { it.chapterId == "ch-chase-cap" })
                assertEquals(102, cappedState.pages.size)
                assertEquals(101, cappedState.currentPageIndex)

                // Scrolling after capped landing resumes lazy cursor append!
                cappedState.goToPage(101)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val resumedLazy = awaitReaderState(capStates) { it.pages.size > 102 }
                assertTrue(resumedLazy.pages.size >= 104)
                job2.cancelAndJoin()
                presenterCap.awaitPendingWrites()

                // 2b. Opening and closing a 120-page cursor chapter when resume caps at 50 chunks (102 pages)
                //     must preserve the prior 116/120 progress until the user explicitly navigates.
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-chase-cap",
                    pageIndex = 115,
                    pageCount = 120,
                    isCompleted = false,
                )
                val preserveScopeJob = SupervisorJob()
                val preserveScope = CoroutineScope(dispatcher + preserveScopeJob)
                val preservePresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-chase-cap")
                val preserveStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                preserveScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { preservePresenter.body() }.collect { preserveStates.value = it }
                }
                val cappedResume =
                    awaitReaderState(preserveStates) { state ->
                        state.activeChapterId == "ch-chase-cap" && state.loadState is UiState.Success
                    }
                assertEquals(102, cappedResume.pages.size)
                assertEquals(101, cappedResume.currentPageIndex)
                assertEquals(ChapterBoundary.None, cappedResume.nextBoundary)
                advanceTimeBy(400)
                runCurrent()
                preserveScopeJob.cancelAndJoin()
                preservePresenter.awaitPendingWrites()
                val preservedAfterClose = progressRepository.observe(accountType, mangaKey).first().chapters.getValue("ch-chase-cap")
                assertEquals(115, preservedAfterClose.lastPageIndex)
                assertEquals(120, preservedAfterClose.pageCount)

                // Now user explicitly navigates after capped resume -> progress updates to the user's chosen page.
                val navJob = SupervisorJob()
                val navScope = CoroutineScope(dispatcher + navJob)
                val navPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-chase-cap")
                val navStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                navScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { navPresenter.body() }.collect { navStates.value = it }
                }
                val navCapped =
                    awaitReaderState(navStates) { state ->
                        state.activeChapterId == "ch-chase-cap" && state.loadState is UiState.Success
                    }
                navCapped.goToPage(80)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(350)
                runCurrent()
                navJob.cancelAndJoin()
                navPresenter.awaitPendingWrites()
                val updatedAfterNav = progressRepository.observe(accountType, mangaKey).first().chapters.getValue("ch-chase-cap")
                assertEquals(80, updatedAfterNav.lastPageIndex)

                // 3. Switching chapter mid-chase cancels the active chase coroutine cleanly.
                val cancelStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope3.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenterCancel.body() }.collect { cancelStates.value = it }
                }
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { chaseCancelStarted.await() }
                }
                val loadingChaseState = assertNotNull(cancelStates.value)
                assertIs<UiState.Loading<Unit>>(loadingChaseState.loadState)
                loadingChaseState.openChapter("ch-chase-ok", initialPage = 0)
                Snapshot.sendApplyNotifications()
                runCurrent()
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { chaseCancelled.await() }
                }
                val switchedOk =
                    awaitReaderState(cancelStates) { state ->
                        state.activeChapterId == "ch-chase-ok" && state.loadState is UiState.Success
                    }
                assertEquals(0, switchedOk.currentPageIndex)
            } finally {
                cur1Gate.complete(Unit)
                chaseCancelGate.complete(Unit)
                job1.cancelAndJoin()
                job2.cancelAndJoin()
                job3.cancelAndJoin()
                presenterSeq.awaitPendingWrites()
                presenterCap.awaitPendingWrites()
                presenterCancel.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sessionChallengePrefetchDoesNotAutoOpenAndRetryChallengeFailuresInvalidatesSiblingsAndRequeuesAll() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-challenge-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 1_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val otherAccountKey = MicroBlogKey("other", "manga.example")
            val activeTickets = ConcurrentHashMap<String, MicroBlogKey>()
            val invalidatedTickets = CopyOnWriteArrayList<String>()
            val invalidatedAccounts = CopyOnWriteArrayList<MicroBlogKey>()
            val coordinator =
                object : SessionChallengeCoordinator {
                    override suspend fun beginSession(challengeId: String): SessionChallengeHandle? = null

                    override fun invalidateTicket(challengeId: String) {
                        invalidatedTickets += challengeId
                        activeTickets.remove(challengeId)
                    }

                    override fun invalidateAccountChallenges(accountKey: MicroBlogKey) {
                        invalidatedAccounts += accountKey
                        activeTickets.entries.removeIf { it.value == accountKey }
                    }

                    override fun invalidateSiblingTickets(
                        accountKey: MicroBlogKey,
                        excludeChallengeId: String,
                    ) {
                        activeTickets.entries.removeIf { it.value == accountKey && it.key != excludeChallengeId }
                    }
                }
            activeTickets["other-account-ticket"] = otherAccountKey

            fun deferredPages(
                prefix: String,
                range: IntRange,
            ): ImmutableList<UiMangaPage> =
                range
                    .map { idx ->
                        UiMangaPage.Deferred(
                            id = "$prefix-$idx",
                            pageToken = "token-$prefix-$idx",
                            previewUrl = null,
                            width = 800f,
                            height = 1200f,
                            customHeaders = null,
                            proxy = ProxyRef.Unbound,
                        )
                    }.toImmutableList()

            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "main",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-range", "Range", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-init", "Init", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-cursor", "Cursor", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)
            var blockWithChallenge = true
            var blockInitialWithChallenge = true
            var blockCursorWithChallenge = true
            val chunkRequests = CopyOnWriteArrayList<String>()
            val resolveRequests = CopyOnWriteArrayList<String>()

            fun issueChallenge(id: String): SessionChallengeException {
                activeTickets[id] = accountKey
                return SessionChallengeException(
                    challengeId = id,
                    accountKey = accountKey,
                    platformId = "ExampleManga",
                    challengeUrl = "https://manga.example/challenge/$id",
                    displayMessage = "Verify $id",
                )
            }

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { detailCache.value = detail },
                            cacheSource = { detailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = error("chapterPagesChunk must be used")

                    override suspend fun chapterPagesChunk(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        offset: Int,
                        limit: Int?,
                        cursor: String?,
                    ): MangaPagesChunk {
                        chunkRequests += "$chapterId:$offset:$cursor"
                        return when (chapterId) {
                            "ch-range" -> {
                                if (offset == 0) {
                                    MangaPagesChunk(
                                        pages = deferredPages("r", 0..19),
                                        offset = 0,
                                        totalCount = 40,
                                        nextCursor = null,
                                        endReached = true,
                                    )
                                } else {
                                    if (blockWithChallenge) {
                                        throw issueChallenge("ticket-chunk-$offset")
                                    }
                                    MangaPagesChunk(
                                        pages = deferredPages("r", 20..39),
                                        offset = 20,
                                        totalCount = 40,
                                        nextCursor = null,
                                        endReached = true,
                                    )
                                }
                            }

                            "ch-init" -> {
                                if (blockInitialWithChallenge) {
                                    throw issueChallenge("ticket-init")
                                }
                                MangaPagesChunk(
                                    pages = readyPages(3, "init"),
                                    offset = 0,
                                    totalCount = 3,
                                    nextCursor = null,
                                    endReached = true,
                                )
                            }

                            "ch-cursor" -> {
                                if (cursor == null) {
                                    MangaPagesChunk(
                                        pages = readyPages(15, "cur0"),
                                        offset = 0,
                                        totalCount = null,
                                        nextCursor = "cur-1",
                                        endReached = false,
                                    )
                                } else {
                                    if (blockCursorWithChallenge) {
                                        throw issueChallenge("ticket-cursor")
                                    }
                                    MangaPagesChunk(
                                        pages = readyPages(5, "cur1"),
                                        offset = 15,
                                        totalCount = null,
                                        nextCursor = null,
                                        endReached = true,
                                    )
                                }
                            }

                            else -> {
                                error("Unexpected $chapterId")
                            }
                        }
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia =
                        resolvePageMedia(
                            mangaKey = mangaKey,
                            chapterId = chapterId,
                            pageId = pageId,
                            pageToken = pageToken,
                            attempt = 0,
                            reason = MangaPageImageResolveReason.Initial,
                            failedUrl = null,
                        )

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                        attempt: Int,
                        reason: MangaPageImageResolveReason,
                        failedUrl: String?,
                    ): UiMedia {
                        resolveRequests += pageId
                        if (pageId == "r-14") {
                            throw IOException("Network failure")
                        }
                        if (blockWithChallenge) {
                            throw issueChallenge("ticket-page-$pageId")
                        }
                        return UiMedia.Image(
                            url = "https://manga.example/$pageId.jpg",
                            previewUrl = "https://manga.example/$pageId-preview.jpg",
                            description = null,
                            height = 1200f,
                            width = 800f,
                            sensitive = false,
                        )
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                        testSingle<SessionChallengeCoordinator> { coordinator }
                    },
                )
            }

            val job = SupervisorJob()
            val scope = CoroutineScope(dispatcher + job)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-range", initialPage = 10)
            try {
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                scope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                // 1. Concurrent -1..+3 resolution around page 10 (pages 9..13) AND range chunk 20..39 fail with SessionChallengeException.
                //    Background prefetch must NOT auto-open any challenge; non-challenge page failures stay unaffected.
                val blocked =
                    awaitReaderState(states) { state ->
                        state.activeChapterId == "ch-range" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == 40 &&
                            (9..13).all { idx ->
                                (state.pages[idx].status as? PageMediaStatus.Failed)?.error is SessionChallengeException
                            } &&
                            (state.pages[20].status as? PageMediaStatus.Failed)?.error is SessionChallengeException
                    }
                assertTrue(activeTickets.containsKey("ticket-page-r-10"))
                assertTrue(activeTickets.containsKey("ticket-page-r-11"))
                assertTrue(activeTickets.containsKey("ticket-chunk-20"))

                // Also mark page 14 with a non-challenge IOException to confirm retryChallengeFailures preserves non-challenge errors.
                blocked.goToPage(13)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val withNonChallengeError =
                    awaitReaderState(states) { state ->
                        (state.pages[14].status as? PageMediaStatus.Failed)?.error is IOException
                    }

                // Navigate back to page 10, clear challenge block, and invoke retryChallengeFailures().
                withNonChallengeError.goToPage(10)
                Snapshot.sendApplyNotifications()
                runCurrent()
                blockWithChallenge = false
                resolveRequests.clear()
                chunkRequests.clear()
                states.value!!.retryChallengeFailures()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val recovered =
                    awaitReaderState(states) { state ->
                        (9..13).all { idx -> state.pages[idx].status is PageMediaStatus.Ready } &&
                            state.pages[20].id == "r-20"
                    }
                assertTrue(
                    invalidatedTickets.containsAll(
                        listOf(
                            "ticket-page-r-9",
                            "ticket-page-r-10",
                            "ticket-page-r-11",
                            "ticket-page-r-12",
                            "ticket-page-r-13",
                            "ticket-chunk-20",
                        ),
                    ),
                )
                assertTrue(accountKey in invalidatedAccounts)
                assertFalse(activeTickets.values.contains(accountKey))
                assertTrue(activeTickets.containsKey("other-account-ticket"), "Unrelated account tickets must remain intact")
                assertIs<IOException>(assertIs<PageMediaStatus.Failed>(recovered.pages[14].status).error)

                // 2. Chapter initial load failing with SessionChallengeException recovers via retryChallengeFailures().
                recovered.openChapter("ch-init", initialPage = 0)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val initBlocked =
                    awaitReaderState(states) { state ->
                        state.activeChapterId == "ch-init" &&
                            (state.loadState as? UiState.Error)?.throwable is SessionChallengeException
                    }
                blockInitialWithChallenge = false
                initBlocked.retryChallengeFailures()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val initRecovered =
                    awaitReaderState(states) { state ->
                        state.activeChapterId == "ch-init" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == 3
                    }

                // 3. Cursor append failing with SessionChallengeException recovers via retryChallengeFailures().
                initRecovered.openChapter("ch-cursor", initialPage = 14)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val cursorBlocked =
                    awaitReaderState(states) { state ->
                        state.activeChapterId == "ch-cursor" &&
                            state.loadState is UiState.Success &&
                            state.pages.size == 16 &&
                            (state.pages[15].status as? PageMediaStatus.Failed)?.error is SessionChallengeException
                    }
                blockCursorWithChallenge = false
                cursorBlocked.retryChallengeFailures()
                Snapshot.sendApplyNotifications()
                runCurrent()
                awaitReaderState(states) { state ->
                    state.activeChapterId == "ch-cursor" &&
                        state.loadState is UiState.Success &&
                        state.pages.size == 20
                }
            } finally {
                job.cancelAndJoin()
                presenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    private fun readerDetail(
        readingDirection: MangaReadingDirection? = MangaReadingDirection.Rtl,
        chapterGroups: ImmutableList<UiMangaChapterGroup>,
    ): UiMangaDetail =
        UiMangaDetail(
            pluginId = "dev.example.manga",
            platformId = "ExampleManga",
            mangaKey = mangaKey,
            accountType = accountType,
            title = "Sample Manga",
            altTitles = persistentListOf(),
            cover = null,
            author = null,
            authors = persistentListOf(),
            artists = persistentListOf(),
            status = UiMangaStatus.Ongoing,
            content = "Synopsis".toUiPlainText(),
            url = "https://manga.example/series-1",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
            readingDirection = readingDirection,
            tagGroups = persistentListOf(),
            chapterGroups = chapterGroups,
            isBookmarked = false,
            bookmarkAction = ClickEvent.Noop,
            actions = persistentListOf(),
            sourceLanguages = persistentListOf(),
        )

    private fun readyPages(
        count: Int,
        prefix: String,
    ): ImmutableList<UiMangaPage> =
        (0 until count)
            .map { idx ->
                UiMangaPage.Ready(
                    id = "$prefix-$idx",
                    media =
                        UiMedia.Image(
                            url = "https://manga.example/$prefix-$idx.jpg",
                            previewUrl = "https://manga.example/$prefix-$idx.jpg",
                            description = null,
                            height = 1200f,
                            width = 800f,
                            sensitive = false,
                        ),
                )
            }.toImmutableList()

    private suspend fun <T : Any> awaitReaderState(
        states: MutableStateFlow<T?>,
        predicate: (T) -> Boolean,
    ): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                states.filterNotNull().first(predicate)
            }
        }
}

private class FakeStaticPagesMangaDataSource(
    private val detail: UiMangaDetail,
    private val pagesByChapter: Map<String, ImmutableList<UiMangaPage>>,
) : MangaDataSource {
    private val cache = MutableStateFlow<UiMangaDetail?>(null)

    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
        Cacheable(
            fetchSource = { cache.value = detail },
            cacheSource = { cache.filterNotNull() },
        )

    override suspend fun chapterPages(
        mangaKey: MicroBlogKey,
        chapterId: String,
    ): ImmutableList<UiMangaPage> = pagesByChapter.getValue(chapterId)

    override suspend fun resolvePageMedia(
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageId: String,
        pageToken: String,
    ): UiMedia = error("Unused")
}

private class FakeReaderAccountService(
    private val accountKey: MicroBlogKey,
    private val mangaDataSource: MangaDataSource,
) : AccountService {
    override suspend fun deleteAccounts(
        platformId: String,
        ownsCredential: (String) -> Boolean,
        beforeDelete: suspend () -> Unit,
    ) = Unit

    override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> =
        flowOf(
            object : MicroblogDataSource, DataSourceCapabilityProvider {
                override val authenticatedAccountKey: MicroBlogKey = accountKey
                override val capabilitySet: DataSourceCapabilitySet = DataSourceCapabilitySet(manga = mangaDataSource)

                override fun homeTimeline(): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun userTimeline(
                    userKey: MicroBlogKey,
                    mediaOnly: Boolean,
                ): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun searchUser(query: String): RemoteLoader<UiProfile> = error("Unused")

                override fun discoverUsers(): RemoteLoader<UiProfile> = error("Unused")

                override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun discoverHashtags(): RemoteLoader<UiHashtag> = error("Unused")

                override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

                override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

                override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> = persistentListOf()
            },
        )

    override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> = flowOf(emptyList())

    override suspend fun <T : Any> addAccount(
        account: UiAccount,
        credential: T,
        serializer: KSerializer<T>,
        beforeCommit: () -> Unit,
    ) = Unit

    override fun <T : Any> credentialFlow(
        accountKey: MicroBlogKey,
        serializer: KSerializer<T>,
    ): Flow<T> = error("Unused")

    override fun <T : Any> updateCredential(
        accountKey: MicroBlogKey,
        credential: T,
        serializer: KSerializer<T>,
    ): Job = Job()
}
