package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.LoadState
import dev.dimension.flare.common.CacheState
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.MangaPageImageResolveReason
import dev.dimension.flare.data.datasource.microblog.datasource.MangaPagesChunk
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.repository.MangaProgressSnapshot
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.flatMapLatestUiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.map
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

@Immutable
public data class ReaderPageState(
    public val id: String,
    public val index: Int,
    public val status: PageMediaStatus,
    public val aspectRatio: Float?,
)

@Immutable
public data class ReaderChapterItem(
    public val chapter: UiMangaChapter,
    public val isCurrent: Boolean,
    public val isRead: Boolean,
    public val lastPageIndex: Int,
)

@Immutable
public sealed interface PageMediaStatus {
    @Immutable
    public data class Loading(
        public val previewUrl: String?,
        public val customHeaders: ImmutableMap<String, String>?,
        public val proxy: ProxyRef,
    ) : PageMediaStatus

    @Immutable
    public data class Ready(
        public val original: UiMedia,
        public val displayed: UiMedia,
    ) : PageMediaStatus

    @Immutable
    public data class Failed(
        public val error: Throwable,
    ) : PageMediaStatus
}

@Immutable
public sealed interface ChapterBoundary {
    @Immutable
    public data object None : ChapterBoundary

    @Immutable
    public data class Unlocked(
        public val chapter: UiMangaChapter,
        public val startFromEnd: Boolean,
    ) : ChapterBoundary

    @Immutable
    public data class Locked(
        public val chapter: UiMangaChapter,
        public val openUrl: String,
    ) : ChapterBoundary
}

public class LockedChapterException(
    public val chapter: UiMangaChapter,
    public val openUrl: String,
) : IllegalStateException("Chapter ${chapter.id} is locked")

public class MangaInvalidResponseException(
    message: String,
) : IllegalStateException(message)

internal const val MAX_CURSOR_LANDING_CHUNKS: Int = 50
private const val MAX_MANGA_TOTAL_PAGES: Int = 50_000
private const val CHUNK_PLACEHOLDER_PREFIX: String = "__chunk_placeholder_"
private const val CURSOR_APPEND_PREFETCH_THRESHOLD: Int = 10
private const val MIN_CHUNK_SIZE_HINT: Int = 20
private const val MAX_CHUNK_SIZE_HINT: Int = 200

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
public class MangaReaderPresenter(
    private val accountType: AccountType,
    private val mangaKey: MicroBlogKey,
    private val initialChapterId: String,
    private val initialPage: Int? = null,
) : PresenterBase<MangaReaderPresenter.State>() {
    private val accountService: AccountService by koinInject()
    private val progressRepository: MangaProgressRepository by koinInject()
    private val appDataStore: AppDataStore by koinInject()
    private val writeJobs = MutableStateFlow<Set<Job>>(emptySet())

    internal suspend fun awaitPendingWrites() {
        writeJobs.value.forEach { it.join() }
    }

    private val proxyRef: ProxyRef by lazy {
        ProxyRef.forAccountType(accountType)
    }

    private val serviceFlow by lazy {
        accountService.accountServiceFlow(accountType)
    }

    private val mangaDataSourceFlow by lazy {
        serviceFlow.map { service -> service.capabilities.manga }
    }

    private fun buildDetailFlow(forceRefresh: Boolean) =
        serviceFlow.flatMapLatestUiState { service ->
            val mangaDataSource =
                service.capabilities.manga
                    ?: error("Current service does not support manga data source")
            val cacheable = mangaDataSource.mangaDetail(mangaKey)
            if (!forceRefresh) {
                cacheable
                    .toUi()
                    .map { state -> state.map { it.bindMangaProxy(proxyRef) } }
            } else {
                flow {
                    var hasObservedSuccessInAttempt = false
                    combine(cacheable.data, cacheable.refreshState) { data, refresh ->
                        if (!hasObservedSuccessInAttempt && data is CacheState.Success && refresh is LoadState.NotLoading) {
                            hasObservedSuccessInAttempt = true
                        }
                        if (hasObservedSuccessInAttempt && data is CacheState.Success) {
                            UiState.Success(data.data.bindMangaProxy(proxyRef))
                        } else {
                            when {
                                refresh is LoadState.Loading -> UiState.Loading()
                                refresh is LoadState.Error -> UiState.Error(refresh.error)
                                data is CacheState.Success -> UiState.Success(data.data.bindMangaProxy(proxyRef))
                                else -> UiState.Loading()
                            }
                        }
                    }.collect { emit(it) }
                }
            }
        }

    @Immutable
    public interface State {
        public val mangaTitle: String
        public val chapterTitle: String
        public val chapterUrl: String?
        public val activeChapterId: String
        public val loadState: UiState<Unit>
        public val pages: ImmutableList<ReaderPageState>
        public val slots: ImmutableList<ReaderSlot>
        public val currentPageIndex: Int
        public val currentSlotIndex: Int
        public val direction: MangaReadingDirection
        public val doublePageSpread: Boolean
        public val spreadCoverSinglePage: Boolean
        public val previousBoundary: ChapterBoundary
        public val nextBoundary: ChapterBoundary
        public val prefetchMedia: ImmutableList<UiMedia.Image>
        public val imageTranslation: ImageTranslationPresenter.State
        public val chapterGroups: ImmutableList<UiMangaChapterGroup> get() = persistentListOf()
        public val selectedChapterGroupId: String? get() = null
        public val chapterSortDescending: Boolean get() = true
        public val chapterItems: ImmutableList<ReaderChapterItem> get() = persistentListOf()
        public val webtoonConstrainWidthOnWideScreen: Boolean get() = true
        public val volumeKeysNavigation: Boolean get() = false

        public val lockedChapter: ChapterBoundary.Locked?
            get() =
                ((loadState as? UiState.Error)?.throwable as? LockedChapterException)?.let {
                    ChapterBoundary.Locked(chapter = it.chapter, openUrl = it.openUrl)
                }

        public fun setWideViewport(isWide: Boolean)

        public fun onPageDimensionsLoaded(
            pageId: String,
            width: Float,
            height: Float,
        )

        public fun goToPage(pageIndex: Int)

        public fun goToSlot(slotIndex: Int)

        public fun stepForward()

        public fun stepBackward()

        public fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked)

        public fun retryChapter()

        public fun retryPage(pageId: String)

        public fun retryPage(
            pageId: String,
            failedUrl: String?,
        ) {
            retryPage(pageId)
        }

        public fun retryChallengeFailures() {}

        public fun setDirection(direction: MangaReadingDirection)

        public fun setDoublePageSpread(enabled: Boolean)

        public fun setSpreadCoverSinglePage(enabled: Boolean)

        public fun selectChapterGroup(groupId: String) {}

        public fun toggleChapterSort() {}

        public fun selectChapter(chapterId: String) {}

        public fun openChapter(
            chapterId: String,
            initialPage: Int?,
        ) {}

        public fun resolveChapterOpenUrl(chapter: UiMangaChapter): String? = null

        public fun setWebtoonConstrainWidthOnWideScreen(enabled: Boolean) {}

        public fun setVolumeKeysNavigation(enabled: Boolean) {}
    }

    @Composable
    override fun body(): State {
        val scope = rememberCoroutineScope()
        val writeJob =
            remember {
                SupervisorJob().also { job ->
                    writeJobs.update { it + job }
                    job.invokeOnCompletion { writeJobs.update { current -> current - job } }
                }
            }
        val writeScope = remember(scope) { CoroutineScope(scope.coroutineContext.minusKey(Job) + writeJob) }
        val writeMutex = remember { Mutex() }

        var detailAttempt by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        val detailFlow =
            remember(accountType, mangaKey, detailAttempt) {
                buildDetailFlow(forceRefresh = detailAttempt > 0)
            }
        val detailState by detailFlow.flattenUiState()
        val rawDetail = detailState.takeSuccess()
        val latestRawDetail by rememberUpdatedState(rawDetail)
        val currentDataSource by mangaDataSourceFlow.collectAsState(null)

        var resolvedMangaKey by remember(accountType, mangaKey) { mutableStateOf(mangaKey) }
        if (rawDetail != null && rawDetail.mangaKey != resolvedMangaKey) {
            resolvedMangaKey = rawDetail.mangaKey
        }
        val effectiveMangaKey = rawDetail?.mangaKey ?: resolvedMangaKey
        val latestEffectiveMangaKey by rememberUpdatedState(effectiveMangaKey)

        val settings by appDataStore.appSettingsStore.data.collectAsState(AppSettings.default)
        val progressFlow =
            remember(accountType, effectiveMangaKey) {
                progressRepository.observe(accountType, effectiveMangaKey)
            }
        val taggedProgress by remember(progressFlow, effectiveMangaKey) {
            progressFlow.map { effectiveMangaKey to it }
        }.collectAsState(initial = null)
        val loadedProgress = taggedProgress?.takeIf { it.first == effectiveMangaKey }?.second
        var seedProgress by remember(accountType, effectiveMangaKey) { mutableStateOf<MangaProgressSnapshot?>(null) }
        val effectiveObservedProgress = loadedProgress ?: seedProgress
        val latestLoadedProgress by rememberUpdatedState(effectiveObservedProgress)

        var optimisticDirectionOverride by remember(accountType, mangaKey) { mutableStateOf<MangaReadingDirection?>(null) }
        var pendingDirectionWrites by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        LaunchedEffect(loadedProgress) {
            if (loadedProgress != null && pendingDirectionWrites == 0) {
                optimisticDirectionOverride = null
            }
        }

        var optimisticChapterSortDescending by remember(accountType, mangaKey) { mutableStateOf<Boolean?>(null) }
        var pendingSortWrites by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        LaunchedEffect(loadedProgress) {
            if (loadedProgress != null && pendingSortWrites == 0) {
                optimisticChapterSortDescending = null
            }
        }

        var optimisticDoublePageSpread by remember { mutableStateOf<Boolean?>(null) }
        var optimisticSpreadCoverSinglePage by remember { mutableStateOf<Boolean?>(null) }
        var optimisticWebtoonConstrainWidthOnWideScreen by remember { mutableStateOf<Boolean?>(null) }
        var optimisticVolumeKeysNavigation by remember { mutableStateOf<Boolean?>(null) }
        var pendingSettingsWrites by remember { mutableIntStateOf(0) }
        LaunchedEffect(settings.mangaReaderConfig) {
            if (pendingSettingsWrites == 0) {
                optimisticDoublePageSpread = null
                optimisticSpreadCoverSinglePage = null
                optimisticWebtoonConstrainWidthOnWideScreen = null
                optimisticVolumeKeysNavigation = null
            }
        }

        fun clearSyncedOptimisticSettings() {
            if (pendingSettingsWrites != 0) return
            if (settings.mangaReaderConfig.doublePageSpreadOnWideScreen == optimisticDoublePageSpread) {
                optimisticDoublePageSpread = null
            }
            if (settings.mangaReaderConfig.spreadCoverSinglePage == optimisticSpreadCoverSinglePage) {
                optimisticSpreadCoverSinglePage = null
            }
            if (settings.mangaReaderConfig.webtoonConstrainWidthOnWideScreen == optimisticWebtoonConstrainWidthOnWideScreen) {
                optimisticWebtoonConstrainWidthOnWideScreen = null
            }
            if (settings.mangaReaderConfig.volumeKeysNavigation == optimisticVolumeKeysNavigation) {
                optimisticVolumeKeysNavigation = null
            }
        }

        var wideViewport by remember { mutableStateOf<Boolean?>(null) }
        var chapterTarget by remember(accountType, mangaKey) {
            mutableStateOf(
                ChapterTarget(
                    chapterId = initialChapterId,
                    attempt = 0,
                    landing = ChapterLanding.Initial(initialPage),
                ),
            )
        }
        val activeChapterId = chapterTarget.chapterId

        val chapterLocation =
            remember(rawDetail, activeChapterId) {
                rawDetail?.locateChapter(activeChapterId)
            }
        val activeChapter = chapterLocation?.chapter
        val isChapterLocked = activeChapter?.locked == true

        var selectedGroupId by remember(accountType, mangaKey) { mutableStateOf<String?>(null) }
        LaunchedEffect(activeChapterId, chapterLocation?.group?.id) {
            val activeGroupId = chapterLocation?.group?.id
            if (activeGroupId != null) {
                selectedGroupId = activeGroupId
            }
        }

        val activeSessionHolder = remember { ActiveSessionRef() }
        val session =
            if (rawDetail != null && !isChapterLocked && currentDataSource != null) {
                remember(activeChapterId, chapterTarget.attempt, currentDataSource, effectiveMangaKey) {
                    ChapterPagesSession(
                        mangaKey = effectiveMangaKey,
                        chapterId = activeChapterId,
                        attempt = chapterTarget.attempt,
                        parentContext = scope.coroutineContext,
                        proxyRef = proxyRef,
                    )
                }
            } else {
                null
            }
        activeSessionHolder.session = session

        DisposableEffect(session) {
            onDispose {
                session?.cancel()
            }
        }

        LaunchedEffect(session, currentDataSource, progressFlow) {
            val currentSession = session ?: return@LaunchedEffect
            val source = currentDataSource ?: return@LaunchedEffect
            if (!currentSession.hasStartedLoad) {
                currentSession.hasStartedLoad = true
                currentSession.loadInitial(
                    dataSource = source,
                    resolveProgress = { latestLoadedProgress ?: progressFlow.first() },
                    latestLanding = { chapterTarget.takeIf { it.attempt == currentSession.attempt }?.landing },
                    onSeedProgress = { resolved ->
                        if (seedProgress == null) {
                            seedProgress = resolved
                        }
                    },
                )
            }
        }

        val isPagesLoaded = session?.pagesLoadState is UiState.Success
        val totalPageCount = if (session != null && isPagesLoaded) session.effectiveTotalPageCount else 0
        val displayPageCount = if (session != null && isPagesLoaded) session.displayPageCount else 0
        val loadedPagesVersion = session?.loadedPagesVersion ?: 0
        val userNavTick = session?.userNavTick ?: 0
        val clampedPageIndex =
            if (session != null && isPagesLoaded && displayPageCount > 0) {
                session.currentPageIndex.coerceIn(0, displayPageCount - 1)
            } else {
                0
            }

        LaunchedEffect(session, isPagesLoaded, clampedPageIndex, currentDataSource, loadedPagesVersion, userNavTick) {
            val currentSession = session ?: return@LaunchedEffect
            if (currentSession.pagesLoadState !is UiState.Success) return@LaunchedEffect
            val source = currentDataSource ?: return@LaunchedEffect
            currentSession.syncChunkWindow(
                currentPageIndex = clampedPageIndex,
                dataSource = source,
            )
            currentSession.syncResolutionWindow(
                currentPageIndex = clampedPageIndex,
                dataSource = source,
            )
        }

        val imageTranslation = remember(accountType) { ImageTranslationPresenter(accountType) }.body()

        val effectiveDirection =
            optimisticDirectionOverride
                ?: effectiveObservedProgress?.readerModeOverride
                ?: rawDetail?.readingDirection
                ?: settings.mangaReaderConfig.defaultDirection
        val effectiveSpreadSetting =
            optimisticDoublePageSpread ?: settings.mangaReaderConfig.doublePageSpreadOnWideScreen
        val isWideAllowed = wideViewport ?: true
        val doublePageSpread =
            effectiveSpreadSetting &&
                isWideAllowed &&
                effectiveDirection != MangaReadingDirection.Webtoon
        val spreadCoverSinglePage =
            optimisticSpreadCoverSinglePage ?: settings.mangaReaderConfig.spreadCoverSinglePage
        val webtoonConstrainWidthOnWideScreen =
            optimisticWebtoonConstrainWidthOnWideScreen
                ?: settings.mangaReaderConfig.webtoonConstrainWidthOnWideScreen
        val volumeKeysNavigation =
            optimisticVolumeKeysNavigation
                ?: settings.mangaReaderConfig.volumeKeysNavigation
        val effectiveChapterSortDescending =
            optimisticChapterSortDescending
                ?: effectiveObservedProgress?.chapterSortDescending
                ?: true
        val chapterGroups = rawDetail?.chapterGroups ?: persistentListOf()
        val effectiveSelectedGroupId =
            remember(chapterGroups, selectedGroupId, chapterLocation) {
                if (chapterGroups.isEmpty()) {
                    null
                } else {
                    selectedGroupId?.takeIf { id -> chapterGroups.any { it.id == id } }
                        ?: chapterLocation?.group?.id
                        ?: chapterGroups.first().id
                }
            }
        val chapterItems =
            remember(
                chapterGroups,
                effectiveSelectedGroupId,
                effectiveChapterSortDescending,
                activeChapterId,
                effectiveObservedProgress,
            ) {
                val selectedGroup =
                    effectiveSelectedGroupId?.let { id ->
                        chapterGroups.firstOrNull { it.id == id }
                    }
                if (selectedGroup == null) {
                    persistentListOf()
                } else {
                    val orderedChapters =
                        if (effectiveChapterSortDescending) {
                            selectedGroup.chapters.asReversed()
                        } else {
                            selectedGroup.chapters
                        }
                    orderedChapters
                        .map { chapter ->
                            val saved = effectiveObservedProgress?.chapters?.get(chapter.id)
                            ReaderChapterItem(
                                chapter = chapter,
                                isCurrent = chapter.id == activeChapterId,
                                isRead = saved?.isCompleted == true,
                                lastPageIndex = saved?.lastPageIndex ?: 0,
                            )
                        }.toImmutableList()
                }
            }

        val loadedPagesMap = session?.loadedPagesByIndex ?: persistentHashMapOf()
        val failedChunkRegions = session?.failedChunkRegions.orEmpty()
        val cursorAppendError = session?.cursorAppendError
        val resolvedMediaMap = session?.resolvedMedia.orEmpty()
        val failedPagesMap = session?.failedPages.orEmpty()
        val measuredDimensionsMap = session?.measuredDimensions.orEmpty()

        val pages =
            remember(
                isPagesLoaded,
                totalPageCount,
                displayPageCount,
                loadedPagesMap,
                failedChunkRegions,
                cursorAppendError,
                resolvedMediaMap,
                failedPagesMap,
                measuredDimensionsMap,
                imageTranslation,
            ) {
                if (!isPagesLoaded || displayPageCount <= 0) {
                    persistentListOf()
                } else {
                    (0 until displayPageCount)
                        .map { index ->
                            val rawPage = loadedPagesMap[index]
                            if (rawPage == null) {
                                val chunkError =
                                    if (index >= totalPageCount) {
                                        cursorAppendError
                                    } else {
                                        failedChunkRegions.firstOrNull { index in it.range }?.error
                                    }
                                val status =
                                    if (chunkError != null) {
                                        PageMediaStatus.Failed(error = chunkError)
                                    } else {
                                        PageMediaStatus.Loading(
                                            previewUrl = null,
                                            customHeaders = null,
                                            proxy = proxyRef,
                                        )
                                    }
                                ReaderPageState(
                                    id = "$CHUNK_PLACEHOLDER_PREFIX$index",
                                    index = index,
                                    status = status,
                                    aspectRatio = null,
                                )
                            } else {
                                val resolved = resolvedMediaMap[rawPage.id]
                                val aspectRatio =
                                    resolvePageAspectRatio(
                                        rawPage = rawPage,
                                        resolvedMedia = resolved,
                                        measured = measuredDimensionsMap[rawPage.id],
                                    )
                                val status =
                                    when (rawPage) {
                                        is UiMangaPage.Ready -> {
                                            val boundOriginal =
                                                if (rawPage.media.proxy == ProxyRef.Unbound) {
                                                    rawPage.media.withProxy(proxyRef)
                                                } else {
                                                    rawPage.media
                                                }
                                            PageMediaStatus.Ready(
                                                original = boundOriginal,
                                                displayed = imageTranslation.displayed(boundOriginal),
                                            )
                                        }

                                        is UiMangaPage.Deferred -> {
                                            when {
                                                resolved != null -> {
                                                    PageMediaStatus.Ready(
                                                        original = resolved,
                                                        displayed = imageTranslation.displayed(resolved),
                                                    )
                                                }

                                                rawPage.id in failedPagesMap -> {
                                                    PageMediaStatus.Failed(error = failedPagesMap.getValue(rawPage.id))
                                                }

                                                else -> {
                                                    val boundProxy =
                                                        if (rawPage.proxy == ProxyRef.Unbound) proxyRef else rawPage.proxy
                                                    PageMediaStatus.Loading(
                                                        previewUrl = rawPage.previewUrl,
                                                        customHeaders = rawPage.customHeaders,
                                                        proxy = boundProxy,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                ReaderPageState(
                                    id = rawPage.id,
                                    index = index,
                                    status = status,
                                    aspectRatio = aspectRatio,
                                )
                            }
                        }.toImmutableList()
                }
            }

        val slots =
            remember(pages, effectiveDirection, doublePageSpread, spreadCoverSinglePage) {
                MangaSpreadLayoutSolver
                    .buildSlots(
                        pageCount = pages.size,
                        direction = effectiveDirection,
                        enabled = doublePageSpread,
                        coverSinglePage = spreadCoverSinglePage,
                        isLandscapePage = { pageIndex -> (pages.getOrNull(pageIndex)?.aspectRatio ?: 0f) > 1f },
                    ).toImmutableList()
            }

        val currentSlotIndex =
            remember(slots, clampedPageIndex) {
                MangaSpreadLayoutSolver.slotIndexForPage(slots, clampedPageIndex)
            }

        val previousBoundary =
            remember(rawDetail, chapterLocation) {
                if (rawDetail == null || chapterLocation == null) {
                    ChapterBoundary.None
                } else {
                    val prev = chapterLocation.group.chapters.getOrNull(chapterLocation.indexInGroup - 1)
                    prev.toChapterBoundary(detail = rawDetail, startFromEnd = true)
                }
            }

        val nextBoundary =
            remember(rawDetail, chapterLocation) {
                if (rawDetail == null || chapterLocation == null) {
                    ChapterBoundary.None
                } else {
                    val next = chapterLocation.group.chapters.getOrNull(chapterLocation.indexInGroup + 1)
                    next.toChapterBoundary(detail = rawDetail, startFromEnd = false)
                }
            }

        val prefetchMedia =
            remember(pages, clampedPageIndex) {
                if (pages.isEmpty()) {
                    persistentListOf()
                } else {
                    val start = clampedPageIndex + 1
                    val end = minOf(clampedPageIndex + 3, pages.lastIndex)
                    if (start > end) {
                        persistentListOf()
                    } else {
                        (start..end)
                            .mapNotNull { idx ->
                                val ready = pages[idx].status as? PageMediaStatus.Ready ?: return@mapNotNull null
                                ready.displayed as? UiMedia.Image ?: ready.original as? UiMedia.Image
                            }.toImmutableList()
                    }
                }
            }

        val loadState: UiState<Unit> =
            when (val detailUi = detailState) {
                is UiState.Loading -> {
                    UiState.Loading()
                }

                is UiState.Error -> {
                    UiState.Error(detailUi.throwable)
                }

                is UiState.Success -> {
                    if (activeChapter != null && activeChapter.locked) {
                        val openUrl = activeChapter.openUrl(detailUi.data.url).orEmpty()
                        UiState.Error(LockedChapterException(chapter = activeChapter, openUrl = openUrl))
                    } else {
                        when (val pagesUi = session?.pagesLoadState) {
                            null, is UiState.Loading -> UiState.Loading()
                            is UiState.Error -> UiState.Error(pagesUi.throwable)
                            is UiState.Success -> UiState.Success(Unit)
                        }
                    }
                }
            }

        val flushState = remember { ProgressFlushTracker() }

        fun computeLiveProgressRecord(targetSession: ChapterPagesSession? = activeSessionHolder.session): PendingProgressRecord? {
            val progressSnapshot = latestLoadedProgress ?: seedProgress ?: return null
            val currentSession = targetSession ?: return null
            if (currentSession.attempt != chapterTarget.attempt) return null
            if (currentSession.pagesLoadState !is UiState.Success) return null
            if (currentSession.unresolvedResumeGoal != null) return null
            val totalCountForProgress = currentSession.effectiveTotalPageCount
            if (totalCountForProgress <= 0) return null
            val lastRealIndex = totalCountForProgress - 1
            val pageIdx = currentSession.currentPageIndex.coerceIn(0, lastRealIndex)
            val liveDirection =
                optimisticDirectionOverride
                    ?: progressSnapshot.readerModeOverride
                    ?: latestRawDetail?.readingDirection
                    ?: settings.mangaReaderConfig.defaultDirection
            val liveSpreadSetting =
                optimisticDoublePageSpread ?: settings.mangaReaderConfig.doublePageSpreadOnWideScreen
            val liveSpread =
                liveSpreadSetting &&
                    (wideViewport ?: true) &&
                    liveDirection != MangaReadingDirection.Webtoon
            val liveCoverSingle =
                optimisticSpreadCoverSinglePage ?: settings.mangaReaderConfig.spreadCoverSinglePage
            val liveSlots =
                MangaSpreadLayoutSolver.buildSlots(
                    pageCount = totalCountForProgress,
                    direction = liveDirection,
                    enabled = liveSpread,
                    coverSinglePage = liveCoverSingle,
                    isLandscapePage = { i ->
                        val page = currentSession.loadedPagesByIndex[i] ?: return@buildSlots false
                        val ratio =
                            resolvePageAspectRatio(
                                rawPage = page,
                                resolvedMedia = currentSession.resolvedMedia[page.id],
                                measured = currentSession.measuredDimensions[page.id],
                            )
                        (ratio ?: 0f) > 1f
                    },
                )
            val slotIdx = MangaSpreadLayoutSolver.slotIndexForPage(liveSlots, pageIdx)
            val completed =
                currentSession.canCompleteChapter &&
                    liveSlots.getOrNull(slotIdx)?.contains(lastRealIndex) == true
            return PendingProgressRecord(
                mangaKey = currentSession.mangaKey,
                chapterId = currentSession.chapterId,
                pageIndex = pageIdx,
                pageCount = totalCountForProgress,
                isCompleted = completed,
            )
        }

        fun flushRecord(record: PendingProgressRecord) {
            writeScope.launch {
                try {
                    writeMutex.withLock {
                        if (flushState.lastFlushed == record) return@withLock
                        progressRepository.recordPage(
                            accountType = accountType,
                            mangaKey = record.mangaKey,
                            chapterId = record.chapterId,
                            pageIndex = record.pageIndex,
                            pageCount = record.pageCount,
                            isCompleted = record.isCompleted,
                        )
                        flushState.lastFlushed = record
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error.printStackTrace()
                }
            }
        }

        LaunchedEffect(session) {
            val currentSession = session ?: return@LaunchedEffect
            snapshotFlow { computeLiveProgressRecord(currentSession) }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(300)
                .collect { record ->
                    flushRecord(record)
                }
        }

        val flushOnExit by rememberUpdatedState { computeLiveProgressRecord()?.let(::flushRecord) }
        DisposableEffect(Unit) {
            onDispose {
                flushOnExit()
                activeSessionHolder.session?.cancel()
                writeJob.complete()
            }
        }

        fun transitionToChapter(
            targetChapterId: String,
            landing: ChapterLanding,
        ) {
            if (targetChapterId == chapterTarget.chapterId) return
            computeLiveProgressRecord()?.let(::flushRecord)
            activeSessionHolder.session?.cancel()
            latestRawDetail?.locateChapter(targetChapterId)?.group?.id?.let { groupId ->
                selectedGroupId = groupId
            }
            chapterTarget =
                ChapterTarget(
                    chapterId = targetChapterId,
                    attempt = chapterTarget.attempt + 1,
                    landing = landing,
                )
        }

        fun switchToChapter(
            targetChapterId: String,
            startFromEnd: Boolean,
        ) {
            transitionToChapter(
                targetChapterId = targetChapterId,
                landing = ChapterLanding.Boundary(startFromEnd = startFromEnd),
            )
        }

        return object : State {
            override val mangaTitle: String = rawDetail?.title.orEmpty()
            override val chapterTitle: String = activeChapter?.title ?: activeChapterId.takeIf { rawDetail != null }.orEmpty()
            override val chapterUrl: String? =
                activeChapter?.openUrl(rawDetail?.url) ?: rawDetail?.url?.trim()?.takeIf(String::isNotEmpty)
            override val activeChapterId: String = activeChapterId
            override val loadState: UiState<Unit> = loadState
            override val pages: ImmutableList<ReaderPageState> = pages
            override val slots: ImmutableList<ReaderSlot> = slots
            override val currentPageIndex: Int = clampedPageIndex
            override val currentSlotIndex: Int = currentSlotIndex
            override val direction: MangaReadingDirection = effectiveDirection
            override val doublePageSpread: Boolean = doublePageSpread
            override val spreadCoverSinglePage: Boolean = spreadCoverSinglePage
            override val previousBoundary: ChapterBoundary = previousBoundary
            override val nextBoundary: ChapterBoundary =
                if (session?.canCompleteChapter == true) {
                    nextBoundary
                } else {
                    ChapterBoundary.None
                }
            override val prefetchMedia: ImmutableList<UiMedia.Image> = prefetchMedia
            override val imageTranslation: ImageTranslationPresenter.State = imageTranslation
            override val chapterGroups: ImmutableList<UiMangaChapterGroup> = chapterGroups
            override val selectedChapterGroupId: String? = effectiveSelectedGroupId
            override val chapterSortDescending: Boolean = effectiveChapterSortDescending
            override val chapterItems: ImmutableList<ReaderChapterItem> = chapterItems
            override val webtoonConstrainWidthOnWideScreen: Boolean = webtoonConstrainWidthOnWideScreen
            override val volumeKeysNavigation: Boolean = volumeKeysNavigation

            override fun setWideViewport(isWide: Boolean) {
                wideViewport = isWide
            }

            override fun onPageDimensionsLoaded(
                pageId: String,
                width: Float,
                height: Float,
            ) {
                if (pageId.startsWith(CHUNK_PLACEHOLDER_PREFIX)) return
                if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return
                val currentSession = activeSessionHolder.session ?: return
                if (currentSession.measuredDimensions[pageId] != (width to height)) {
                    currentSession.measuredDimensions = currentSession.measuredDimensions + (pageId to (width to height))
                }
            }

            override fun goToPage(pageIndex: Int) {
                val currentSession = activeSessionHolder.session ?: return
                if (currentSession.pagesLoadState !is UiState.Success) return
                val maxIdx = currentSession.displayLastIndex
                if (maxIdx < 0) return
                Snapshot.withMutableSnapshot {
                    currentSession.onUserNavigation()
                    currentSession.currentPageIndex = pageIndex.coerceIn(0, maxIdx)
                }
            }

            override fun goToSlot(slotIndex: Int) {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val targetSlot = slots[slotIndex.coerceIn(0, slots.lastIndex)]
                Snapshot.withMutableSnapshot {
                    currentSession.onUserNavigation()
                    if (!targetSlot.contains(currentSession.currentPageIndex)) {
                        currentSession.currentPageIndex = targetSlot.primaryPageIndex
                    }
                }
            }

            override fun stepForward() {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val liveSlotIndex = MangaSpreadLayoutSolver.slotIndexForPage(slots, currentSession.currentPageIndex)
                if (liveSlotIndex < slots.lastIndex) {
                    Snapshot.withMutableSnapshot {
                        currentSession.onUserNavigation()
                        currentSession.currentPageIndex = slots[liveSlotIndex + 1].primaryPageIndex
                    }
                } else {
                    currentSession.onUserNavigation()
                    if (currentSession.canCompleteChapter && nextBoundary is ChapterBoundary.Unlocked) {
                        switchToChapter(targetChapterId = nextBoundary.chapter.id, startFromEnd = nextBoundary.startFromEnd)
                    }
                }
            }

            override fun stepBackward() {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val liveSlotIndex = MangaSpreadLayoutSolver.slotIndexForPage(slots, currentSession.currentPageIndex)
                if (liveSlotIndex > 0) {
                    Snapshot.withMutableSnapshot {
                        currentSession.onUserNavigation()
                        currentSession.currentPageIndex = slots[liveSlotIndex - 1].primaryPageIndex
                    }
                } else {
                    currentSession.onUserNavigation()
                    if (previousBoundary is ChapterBoundary.Unlocked) {
                        switchToChapter(
                            targetChapterId = previousBoundary.chapter.id,
                            startFromEnd = previousBoundary.startFromEnd,
                        )
                    }
                }
            }

            override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {
                switchToChapter(targetChapterId = boundary.chapter.id, startFromEnd = boundary.startFromEnd)
            }

            override fun retryChapter() {
                val currentSession = activeSessionHolder.session
                if (detailState is UiState.Error || isChapterLocked) {
                    detailAttempt++
                } else if (activeChapter == null && currentSession?.pagesLoadState is UiState.Error) {
                    val nextLanding = chapterTarget.landing
                    currentSession.cancel()
                    detailAttempt++
                    chapterTarget =
                        chapterTarget.copy(
                            attempt = chapterTarget.attempt + 1,
                            landing = nextLanding,
                        )
                } else {
                    val nextLanding =
                        if (currentSession?.pagesLoadState is UiState.Success) {
                            ChapterLanding.PreservePage(currentSession.currentPageIndex)
                        } else {
                            chapterTarget.landing
                        }
                    currentSession?.cancel()
                    chapterTarget =
                        chapterTarget.copy(
                            attempt = chapterTarget.attempt + 1,
                            landing = nextLanding,
                        )
                }
            }

            override fun retryPage(pageId: String) {
                retryPage(pageId = pageId, failedUrl = null)
            }

            override fun retryPage(
                pageId: String,
                failedUrl: String?,
            ) {
                val currentSession = activeSessionHolder.session ?: return
                if (currentSession.pagesLoadState !is UiState.Success) return
                val source = currentDataSource ?: return
                if (pageId.startsWith(CHUNK_PLACEHOLDER_PREFIX)) {
                    val index = pageId.removePrefix(CHUNK_PLACEHOLDER_PREFIX).toIntOrNull() ?: return
                    currentSession.retryChunkAtIndex(index = index, dataSource = source)
                    return
                }
                val deferred = currentSession.loadedPagesByIndex.values.firstOrNull { it.id == pageId } as? UiMangaPage.Deferred ?: return
                currentSession.retryPage(
                    page = deferred,
                    dataSource = source,
                    mangaKey = currentSession.mangaKey,
                    failedUrl = failedUrl,
                )
            }

            override fun retryChallengeFailures() {
                val currentSession = activeSessionHolder.session
                val exceptions =
                    buildList {
                        ((detailState as? UiState.Error)?.throwable as? SessionChallengeException)?.let(::add)
                        ((currentSession?.pagesLoadState as? UiState.Error)?.throwable as? SessionChallengeException)?.let(::add)
                        currentSession?.failedChunkRegions?.mapNotNullTo(this) { it.error as? SessionChallengeException }
                        (currentSession?.cursorAppendError as? SessionChallengeException)?.let(::add)
                        currentSession?.failedPages?.values?.mapNotNullTo(this) { it as? SessionChallengeException }
                    }
                val coordinator =
                    org.koin.mp.KoinPlatformTools
                        .defaultContext()
                        .getOrNull()
                        ?.getOrNull<SessionChallengeCoordinator>()
                if (coordinator != null) {
                    val accountKeys =
                        buildSet {
                            (accountType as? AccountType.Specific)?.accountKey?.let(::add)
                            exceptions.forEach { add(it.accountKey) }
                        }
                    exceptions.forEach { coordinator.invalidateTicket(it.challengeId) }
                    accountKeys.forEach { coordinator.invalidateAccountChallenges(it) }
                }
                when {
                    (detailState as? UiState.Error)?.throwable is SessionChallengeException -> {
                        retryChapter()
                    }

                    (currentSession?.pagesLoadState as? UiState.Error)?.throwable is SessionChallengeException -> {
                        retryChapter()
                    }

                    currentSession?.pagesLoadState is UiState.Success -> {
                        val source = currentDataSource ?: return
                        currentSession.retryChallengeFailures(dataSource = source)
                    }
                }
            }

            override fun setDirection(direction: MangaReadingDirection) {
                val targetMangaKey = latestEffectiveMangaKey
                optimisticDirectionOverride = direction
                pendingDirectionWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setReaderModeOverride(accountType, targetMangaKey, direction)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticDirectionOverride = null
                    } finally {
                        pendingDirectionWrites--
                        if (pendingDirectionWrites == 0 && latestLoadedProgress?.readerModeOverride == optimisticDirectionOverride) {
                            optimisticDirectionOverride = null
                        }
                    }
                }
            }

            override fun setDoublePageSpread(enabled: Boolean) {
                optimisticDoublePageSpread = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            doublePageSpreadOnWideScreen = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticDoublePageSpread = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun setSpreadCoverSinglePage(enabled: Boolean) {
                optimisticSpreadCoverSinglePage = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            spreadCoverSinglePage = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticSpreadCoverSinglePage = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun selectChapterGroup(groupId: String) {
                val detail = latestRawDetail
                if (detail == null || detail.chapterGroups.any { it.id == groupId }) {
                    selectedGroupId = groupId
                }
            }

            override fun toggleChapterSort() {
                val targetMangaKey = latestEffectiveMangaKey
                val nextDescending =
                    !(optimisticChapterSortDescending ?: latestLoadedProgress?.chapterSortDescending ?: true)
                optimisticChapterSortDescending = nextDescending
                pendingSortWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setChapterSortDescending(accountType, targetMangaKey, nextDescending)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticChapterSortDescending = null
                    } finally {
                        pendingSortWrites--
                        if (pendingSortWrites == 0 && latestLoadedProgress?.chapterSortDescending == optimisticChapterSortDescending) {
                            optimisticChapterSortDescending = null
                        }
                    }
                }
            }

            override fun selectChapter(chapterId: String) {
                if (chapterId == chapterTarget.chapterId) return
                val targetChapter = latestRawDetail?.locateChapter(chapterId)?.chapter ?: return
                if (targetChapter.locked) return
                transitionToChapter(
                    targetChapterId = chapterId,
                    landing = ChapterLanding.Initial(null),
                )
            }

            override fun openChapter(
                chapterId: String,
                initialPage: Int?,
            ) {
                if (chapterId == chapterTarget.chapterId) {
                    if (initialPage != null) {
                        val currentSession = activeSessionHolder.session
                        if (currentSession?.attempt == chapterTarget.attempt && currentSession.pagesLoadState is UiState.Success) {
                            goToPage(initialPage)
                        } else {
                            chapterTarget = chapterTarget.copy(landing = ChapterLanding.Initial(initialPage))
                        }
                    }
                    return
                }
                transitionToChapter(
                    targetChapterId = chapterId,
                    landing = ChapterLanding.Initial(initialPage),
                )
            }

            override fun resolveChapterOpenUrl(chapter: UiMangaChapter): String? = chapter.openUrl(latestRawDetail?.url)

            override fun setWebtoonConstrainWidthOnWideScreen(enabled: Boolean) {
                optimisticWebtoonConstrainWidthOnWideScreen = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            webtoonConstrainWidthOnWideScreen = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticWebtoonConstrainWidthOnWideScreen = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun setVolumeKeysNavigation(enabled: Boolean) {
                optimisticVolumeKeysNavigation = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            volumeKeysNavigation = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticVolumeKeysNavigation = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }
        }
    }
}

private data class ChapterTarget(
    val chapterId: String,
    val attempt: Int,
    val landing: ChapterLanding,
)

private sealed interface ChapterLanding {
    data class Initial(
        val initialPage: Int?,
    ) : ChapterLanding

    data class Boundary(
        val startFromEnd: Boolean,
    ) : ChapterLanding

    data class PreservePage(
        val pageIndex: Int,
    ) : ChapterLanding
}

private data class ChapterLocation(
    val group: UiMangaChapterGroup,
    val indexInGroup: Int,
    val chapter: UiMangaChapter,
)

private fun UiMangaDetail.locateChapter(chapterId: String): ChapterLocation? {
    for (group in chapterGroups) {
        val index = group.chapters.indexOfFirst { it.id == chapterId }
        if (index >= 0) {
            return ChapterLocation(
                group = group,
                indexInGroup = index,
                chapter = group.chapters[index],
            )
        }
    }
    return null
}

private fun UiMangaChapter?.toChapterBoundary(
    detail: UiMangaDetail,
    startFromEnd: Boolean,
): ChapterBoundary {
    if (this == null) return ChapterBoundary.None
    return if (locked) {
        ChapterBoundary.Locked(
            chapter = this,
            openUrl = openUrl(detail.url).orEmpty(),
        )
    } else {
        ChapterBoundary.Unlocked(
            chapter = this,
            startFromEnd = startFromEnd,
        )
    }
}

private class ActiveSessionRef {
    var session: ChapterPagesSession? = null
}

private class ProgressFlushTracker {
    var lastFlushed: PendingProgressRecord? = null
}

private data class PendingProgressRecord(
    val mangaKey: MicroBlogKey,
    val chapterId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val isCompleted: Boolean,
)

private data class FailedChunkRegion(
    val range: IntRange,
    val requestedOffset: Int,
    val error: Throwable,
)

private data class InFlightChunkJob(
    val targetIndex: Int,
    val job: Job,
    val isExplicitRetry: Boolean,
) {
    val isInFlight: Boolean
        get() = !job.isCompleted && !job.isCancelled
}

private sealed interface CursorChaseGoal {
    data object End : CursorChaseGoal

    data class TargetIndex(
        val target: Int,
    ) : CursorChaseGoal
}

private class ChapterPagesSession(
    val mangaKey: MicroBlogKey,
    val chapterId: String,
    val attempt: Int,
    parentContext: CoroutineContext,
    private val proxyRef: ProxyRef,
) {
    private val job = SupervisorJob(parentContext[Job])
    private val scope = CoroutineScope(parentContext.minusKey(Job) + job)
    private val resolveSemaphore = Semaphore(3)
    private val chunkSemaphore = Semaphore(2)
    private val chunkCommitMutex = Mutex()
    private val chunkJobsMutex = Mutex()
    private val resolveJobsMutex = Mutex()
    private val cursorAppendMutex = Mutex()
    private val inFlightResolveJobs = mutableMapOf<String, Job>()
    private val inFlightChunkJobs = mutableMapOf<Int, InFlightChunkJob>()
    private val pageAttempts = mutableMapOf<String, Int>()
    private val pageIndexById = mutableMapOf<String, Int>()
    private var cursorAppendJob: Job? = null

    var currentPageIndex by mutableIntStateOf(0)
    var hasStartedLoad: Boolean = false
    var pagesLoadState by mutableStateOf<UiState<Unit>>(UiState.Loading())
    var loadedPagesByIndex by mutableStateOf<PersistentMap<Int, UiMangaPage>>(persistentHashMapOf())
    var loadedPagesVersion by mutableIntStateOf(0)
    var totalCount by mutableStateOf<Int?>(null)
    var chunkSizeHint by mutableIntStateOf(MIN_CHUNK_SIZE_HINT)
    var isRangeMode by mutableStateOf(false)
    var endReached by mutableStateOf(true)
    var nextCursor by mutableStateOf<String?>(null)
    var loadedCount by mutableIntStateOf(0)
    var failedChunkRegions by mutableStateOf<List<FailedChunkRegion>>(emptyList())
    var cursorAppendError by mutableStateOf<Throwable?>(null)
    var suppressCursorAppendUntilNavigation by mutableStateOf(false)
    var unresolvedResumeGoal by mutableStateOf<CursorChaseGoal?>(null)
    var userNavTick by mutableIntStateOf(0)
    var resolvedMedia by mutableStateOf<Map<String, UiMedia>>(emptyMap())
    var failedPages by mutableStateOf<Map<String, Throwable>>(emptyMap())
    var measuredDimensions by mutableStateOf<Map<String, Pair<Float, Float>>>(emptyMap())

    val effectiveTotalPageCount: Int
        get() = totalCount ?: loadedCount

    val displayPageCount: Int
        get() = effectiveTotalPageCount + if (!isRangeMode && !endReached && cursorAppendError != null) 1 else 0

    val displayLastIndex: Int
        get() = displayPageCount - 1

    val canCompleteChapter: Boolean
        get() = pagesLoadState is UiState.Success && (totalCount != null || endReached)

    fun onUserNavigation() {
        suppressCursorAppendUntilNavigation = false
        unresolvedResumeGoal = null
        userNavTick++
    }

    suspend fun loadInitial(
        dataSource: MangaDataSource,
        resolveProgress: suspend () -> MangaProgressSnapshot,
        latestLanding: () -> ChapterLanding?,
        onSeedProgress: (MangaProgressSnapshot) -> Unit,
    ) {
        val loadJob =
            scope.launch {
                try {
                    val (firstChunk, resolvedProgress) =
                        coroutineScope {
                            val chunkDeferred =
                                async {
                                    dataSource.chapterPagesChunk(
                                        mangaKey = mangaKey,
                                        chapterId = chapterId,
                                        offset = 0,
                                    )
                                }
                            val progressDeferred = async { resolveProgress() }
                            chunkDeferred.await() to progressDeferred.await()
                        }
                    currentCoroutineContext().ensureActive()
                    val initialLanding = latestLanding() ?: return@launch
                    chunkCommitMutex.withLock {
                        applyFirstChunkLocked(firstChunk)
                    }
                    when {
                        firstChunk.totalCount != null -> {
                            val total = firstChunk.totalCount
                            val finalLanding = latestLanding() ?: initialLanding
                            val landingIndex = resolveClampedLanding(finalLanding, resolvedProgress, total)
                            Snapshot.withMutableSnapshot {
                                onSeedProgress(resolvedProgress)
                                currentPageIndex = landingIndex
                                pagesLoadState = UiState.Success(Unit)
                            }
                        }

                        !firstChunk.endReached -> {
                            val initialGoal = toCursorChaseGoal(initialLanding, resolvedProgress)
                            var chasedChunks = 0
                            while (!endReached && nextCursor != null && chasedChunks < MAX_CURSOR_LANDING_CHUNKS) {
                                val shouldChase =
                                    when (initialGoal) {
                                        CursorChaseGoal.End -> true
                                        is CursorChaseGoal.TargetIndex -> loadedCount <= initialGoal.target
                                    }
                                if (!shouldChase) break
                                currentCoroutineContext().ensureActive()
                                val cursor = nextCursor ?: break
                                val nextChunk =
                                    dataSource.chapterPagesChunk(
                                        mangaKey = mangaKey,
                                        chapterId = chapterId,
                                        cursor = cursor,
                                    )
                                currentCoroutineContext().ensureActive()
                                chunkCommitMutex.withLock {
                                    applyCursorChunkLocked(nextChunk)
                                }
                                chasedChunks++
                            }
                            val finalLanding = latestLanding() ?: return@launch
                            val finalGoal = toCursorChaseGoal(finalLanding, resolvedProgress)
                            val landingIndex = resolveClampedLanding(finalLanding, resolvedProgress, loadedCount)
                            val hitCap =
                                chasedChunks >= MAX_CURSOR_LANDING_CHUNKS &&
                                    !endReached &&
                                    when (finalGoal) {
                                        CursorChaseGoal.End -> true
                                        is CursorChaseGoal.TargetIndex -> loadedCount <= finalGoal.target
                                    }
                            Snapshot.withMutableSnapshot {
                                onSeedProgress(resolvedProgress)
                                suppressCursorAppendUntilNavigation = hitCap
                                unresolvedResumeGoal = if (hitCap) finalGoal else null
                                currentPageIndex = landingIndex
                                pagesLoadState = UiState.Success(Unit)
                            }
                        }

                        else -> {
                            val finalLanding = latestLanding() ?: initialLanding
                            val landingIndex = resolveClampedLanding(finalLanding, resolvedProgress, loadedCount)
                            Snapshot.withMutableSnapshot {
                                onSeedProgress(resolvedProgress)
                                currentPageIndex = landingIndex
                                pagesLoadState = UiState.Success(Unit)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (!job.isActive) return@launch
                    currentCoroutineContext().ensureActive()
                    pagesLoadState = UiState.Error(e)
                }
            }
        try {
            loadJob.join()
        } finally {
            loadJob.cancel()
        }
    }

    suspend fun syncChunkWindow(
        currentPageIndex: Int,
        dataSource: MangaDataSource,
    ) {
        if (!job.isActive) return
        if (isRangeMode) {
            syncRangeChunkWindow(currentPageIndex = currentPageIndex, dataSource = dataSource)
        } else {
            syncCursorAppend(currentPageIndex = currentPageIndex, dataSource = dataSource)
        }
    }

    private suspend fun syncRangeChunkWindow(
        currentPageIndex: Int,
        dataSource: MangaDataSource,
    ) {
        val total = totalCount ?: return
        if (total <= 0) return
        val clampedCurrent = currentPageIndex.coerceIn(0, total - 1)
        val windowStart = (clampedCurrent - chunkSizeHint / 2).coerceAtLeast(0)
        val windowEnd = (clampedCurrent + chunkSizeHint).coerceAtMost(total - 1)

        val jobsToStart = mutableListOf<Job>()
        val jobsToCancel = mutableListOf<Job>()
        chunkJobsMutex.withLock {
            val iterator = inFlightChunkJobs.entries.iterator()
            while (iterator.hasNext()) {
                val (_, entry) = iterator.next()
                if (!entry.isExplicitRetry && entry.targetIndex !in windowStart..windowEnd) {
                    jobsToCancel += entry.job
                    iterator.remove()
                } else if (!entry.isInFlight) {
                    iterator.remove()
                }
            }

            val loadedPages = loadedPagesByIndex
            val failedRegions = failedChunkRegions

            fun isCovered(
                index: Int,
                plannedRanges: List<IntRange>,
            ): Boolean {
                if (index in loadedPages) return true
                if (failedRegions.any { index in it.range }) return true
                if (inFlightChunkJobs.values.any { entry ->
                        entry.isInFlight && index in entry.targetIndex until (entry.targetIndex + chunkSizeHint)
                    }
                ) {
                    return true
                }
                return plannedRanges.any { index in it }
            }

            val plannedTargets = mutableListOf<Int>()
            val plannedRanges = mutableListOf<IntRange>()

            for (idx in clampedCurrent..windowEnd) {
                if (!isCovered(idx, plannedRanges)) {
                    plannedTargets += idx
                    plannedRanges += idx until (idx + chunkSizeHint)
                }
            }

            val currentIsLoadingOrFailed =
                (clampedCurrent !in loadedPages) &&
                    (
                        failedRegions.any { clampedCurrent in it.range } ||
                            inFlightChunkJobs.values.any { it.isInFlight && it.targetIndex == clampedCurrent } ||
                            clampedCurrent in plannedTargets
                    )
            if (!currentIsLoadingOrFailed) {
                var cursor = clampedCurrent - 1
                while (cursor >= windowStart) {
                    if (isCovered(cursor, plannedRanges)) {
                        cursor--
                        continue
                    }
                    val runEnd = cursor
                    var runStart = cursor
                    while (runStart - 1 >= windowStart && !isCovered(runStart - 1, plannedRanges)) {
                        runStart--
                    }
                    val targetOffset = maxOf(runStart, runEnd - chunkSizeHint + 1)
                    plannedTargets += targetOffset
                    plannedRanges += targetOffset until (targetOffset + chunkSizeHint)
                    cursor = targetOffset - 1
                }
            }

            for (targetOffset in plannedTargets) {
                createRangeChunkJobLocked(
                    targetIndex = targetOffset,
                    dataSource = dataSource,
                    isExplicitRetry = false,
                )?.let(jobsToStart::add)
            }
        }
        jobsToCancel.forEach { it.cancel() }
        jobsToStart.forEach { it.start() }
    }

    private fun createRangeChunkJobLocked(
        targetIndex: Int,
        dataSource: MangaDataSource,
        isExplicitRetry: Boolean,
    ): Job? {
        if (!job.isActive) return null
        if (inFlightChunkJobs[targetIndex]?.isInFlight == true) return null
        val hint = chunkSizeHint
        val chunkJob =
            scope.launch(start = CoroutineStart.LAZY) {
                val selfJob = currentCoroutineContext()[Job]
                try {
                    val chunk =
                        chunkSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            dataSource.chapterPagesChunk(
                                mangaKey = mangaKey,
                                chapterId = chapterId,
                                offset = targetIndex,
                                limit = hint,
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    chunkJobsMutex.withLock {
                        if (inFlightChunkJobs[targetIndex]?.job === selfJob) {
                            inFlightChunkJobs.remove(targetIndex)
                        }
                    }
                    chunkCommitMutex.withLock {
                        applyRangeChunkLocked(requestedOffset = targetIndex, chunk = chunk)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    chunkJobsMutex.withLock {
                        if (inFlightChunkJobs[targetIndex]?.job === selfJob) {
                            inFlightChunkJobs.remove(targetIndex)
                        }
                    }
                    chunkCommitMutex.withLock {
                        val total = totalCount ?: 0
                        if (total > 0 && targetIndex in 0 until total) {
                            val regionEnd = (targetIndex + hint - 1).coerceAtMost(total - 1)
                            val region = FailedChunkRegion(range = targetIndex..regionEnd, requestedOffset = targetIndex, error = e)
                            Snapshot.withMutableSnapshot {
                                failedChunkRegions = failedChunkRegions.filterNot { it.range == region.range } + region
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        chunkJobsMutex.withLock {
                            if (inFlightChunkJobs[targetIndex]?.job === selfJob) {
                                inFlightChunkJobs.remove(targetIndex)
                            }
                        }
                    }
                }
            }
        inFlightChunkJobs[targetIndex] = InFlightChunkJob(targetIndex = targetIndex, job = chunkJob, isExplicitRetry = isExplicitRetry)
        return chunkJob
    }

    private suspend fun syncCursorAppend(
        currentPageIndex: Int,
        dataSource: MangaDataSource,
    ) {
        if (isRangeMode || endReached) return
        if (suppressCursorAppendUntilNavigation) return
        if (cursorAppendError != null) return
        if (nextCursor == null || loadedCount <= 0) return
        val loadedMaxIndex = loadedCount - 1
        if (currentPageIndex < loadedMaxIndex - CURSOR_APPEND_PREFETCH_THRESHOLD) return
        launchCursorAppend(dataSource = dataSource)
    }

    private suspend fun launchCursorAppend(dataSource: MangaDataSource) {
        if (!job.isActive) return
        val jobToStart =
            chunkJobsMutex.withLock {
                if (!job.isActive || cursorAppendJob?.isActive == true) return@withLock null
                val launchedJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        val selfJob = currentCoroutineContext()[Job]
                        try {
                            cursorAppendMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                if (isRangeMode || endReached || cursorAppendError != null) return@withLock
                                val cursor = nextCursor ?: return@withLock
                                val chunk =
                                    dataSource.chapterPagesChunk(
                                        mangaKey = mangaKey,
                                        chapterId = chapterId,
                                        cursor = cursor,
                                    )
                                currentCoroutineContext().ensureActive()
                                chunkJobsMutex.withLock {
                                    if (cursorAppendJob === selfJob) {
                                        cursorAppendJob = null
                                    }
                                }
                                chunkCommitMutex.withLock {
                                    applyCursorChunkLocked(chunk)
                                }
                            }
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            currentCoroutineContext().ensureActive()
                            chunkJobsMutex.withLock {
                                if (cursorAppendJob === selfJob) {
                                    cursorAppendJob = null
                                }
                            }
                            Snapshot.withMutableSnapshot {
                                cursorAppendError = e
                            }
                        } finally {
                            withContext(NonCancellable) {
                                chunkJobsMutex.withLock {
                                    if (cursorAppendJob === selfJob) {
                                        cursorAppendJob = null
                                    }
                                }
                            }
                        }
                    }
                cursorAppendJob = launchedJob
                launchedJob
            }
        jobToStart?.start()
    }

    fun retryChunkAtIndex(
        index: Int,
        dataSource: MangaDataSource,
    ) {
        if (!job.isActive) return
        if (isRangeMode) {
            val total = totalCount ?: return
            if (index !in 0 until total) return
            scope.launch {
                val jobToStart =
                    chunkJobsMutex.withLock {
                        if (index in loadedPagesByIndex) return@withLock null
                        Snapshot.withMutableSnapshot {
                            failedChunkRegions = failedChunkRegions.filterNot { index in it.range }
                        }
                        createRangeChunkJobLocked(
                            targetIndex = index,
                            dataSource = dataSource,
                            isExplicitRetry = true,
                        )
                    }
                jobToStart?.start()
            }
        } else if (!endReached && nextCursor != null) {
            if (cursorAppendError == null || index != loadedCount) return
            Snapshot.withMutableSnapshot {
                cursorAppendError = null
            }
            scope.launch {
                launchCursorAppend(dataSource = dataSource)
            }
        }
    }

    fun retryChallengeFailures(dataSource: MangaDataSource) {
        if (!job.isActive) return
        val hasChallengeChunkFailures = failedChunkRegions.any { it.error is SessionChallengeException }
        val hasChallengeCursorFailure = cursorAppendError is SessionChallengeException
        val challengePageIds =
            failedPages.entries
                .mapNotNull { (pageId, error) -> pageId.takeIf { error is SessionChallengeException } }
                .toSet()
        if (!hasChallengeChunkFailures && !hasChallengeCursorFailure && challengePageIds.isEmpty()) return
        Snapshot.withMutableSnapshot {
            if (hasChallengeChunkFailures) {
                failedChunkRegions = failedChunkRegions.filterNot { it.error is SessionChallengeException }
            }
            if (hasChallengeCursorFailure) {
                cursorAppendError = null
            }
            if (challengePageIds.isNotEmpty()) {
                failedPages = failedPages - challengePageIds
            }
        }
        scope.launch {
            syncChunkWindow(currentPageIndex = currentPageIndex, dataSource = dataSource)
            syncResolutionWindow(currentPageIndex = currentPageIndex, dataSource = dataSource)
        }
    }

    private fun applyFirstChunkLocked(chunk: MangaPagesChunk) {
        if (chunk.offset != null && chunk.offset != 0) {
            throw MangaInvalidResponseException("Initial chunk offset must be 0, was ${chunk.offset}")
        }
        val chunkTotal = chunk.totalCount
        if (chunkTotal != null) {
            if (chunkTotal !in 1..MAX_MANGA_TOTAL_PAGES) {
                throw MangaInvalidResponseException("Invalid manga totalCount: $chunkTotal")
            }
            if (chunk.pages.isEmpty()) {
                throw MangaInvalidResponseException("Initial range chunk cannot be empty")
            }
            if (chunk.pages.size > chunkTotal) {
                throw MangaInvalidResponseException("Initial chunk size ${chunk.pages.size} exceeds totalCount $chunkTotal")
            }
        } else if (!chunk.endReached) {
            if (chunk.pages.isEmpty()) {
                throw MangaInvalidResponseException("Initial cursor chunk cannot be empty")
            }
            if (chunk.nextCursor.isNullOrBlank()) {
                throw MangaInvalidResponseException("Cursor chunk with endReached=false requires nextCursor")
            }
        }
        validateNoIntraChunkDuplicates(chunk.pages)

        val builder = loadedPagesByIndex.builder()
        chunk.pages.forEachIndexed { idx, page ->
            builder[idx] = page
            pageIndexById[page.id] = idx
        }
        val nextMap = builder.build()
        Snapshot.withMutableSnapshot {
            totalCount = chunkTotal
            isRangeMode = chunkTotal != null
            chunkSizeHint =
                if (chunkTotal != null) {
                    chunk.pages.size.coerceIn(MIN_CHUNK_SIZE_HINT, MAX_CHUNK_SIZE_HINT)
                } else {
                    MIN_CHUNK_SIZE_HINT
                }
            endReached = chunk.endReached
            nextCursor = chunk.nextCursor
            loadedCount = chunk.pages.size
            loadedPagesByIndex = nextMap
            loadedPagesVersion++
        }
    }

    private fun applyRangeChunkLocked(
        requestedOffset: Int,
        chunk: MangaPagesChunk,
    ) {
        val expectedTotal =
            totalCount ?: throw MangaInvalidResponseException("Unexpected range chunk outside range mode")
        if (chunk.pages.isEmpty()) {
            throw MangaInvalidResponseException("Range chunk returned no pages for offset $requestedOffset")
        }
        if (chunk.totalCount == null) {
            throw MangaInvalidResponseException("Range chunk flipped pagination mode to null totalCount")
        }
        if (chunk.totalCount != expectedTotal) {
            throw MangaInvalidResponseException("totalCount drifted from $expectedTotal to ${chunk.totalCount}")
        }
        val chunkOffset =
            if (requestedOffset == 0) {
                chunk.offset ?: 0
            } else {
                chunk.offset ?: throw MangaInvalidResponseException("Range chunk at offset $requestedOffset omitted offset")
            }
        if (chunkOffset < 0 || chunkOffset.toLong() + chunk.pages.size.toLong() > expectedTotal.toLong()) {
            throw MangaInvalidResponseException(
                "Range chunk [$chunkOffset, ${chunkOffset + chunk.pages.size}) overflows totalCount $expectedTotal",
            )
        }
        if (requestedOffset !in chunkOffset until (chunkOffset + chunk.pages.size)) {
            throw MangaInvalidResponseException(
                "Range chunk [$chunkOffset, ${chunkOffset + chunk.pages.size}) did not cover requested offset $requestedOffset",
            )
        }
        validateNoIntraChunkDuplicates(chunk.pages)

        val newEntries = ArrayList<Pair<Int, UiMangaPage>>(chunk.pages.size)
        chunk.pages.forEachIndexed { localIdx, incomingPage ->
            val globalIdx = chunkOffset + localIdx
            val existingAtIndex = loadedPagesByIndex[globalIdx]
            if (existingAtIndex != null) {
                if (existingAtIndex.id != incomingPage.id) {
                    throw MangaInvalidResponseException(
                        "Page id mismatch at index $globalIdx: expected '${existingAtIndex.id}', got '${incomingPage.id}'",
                    )
                }
            } else {
                val existingIndexForId = pageIndexById[incomingPage.id]
                if (existingIndexForId != null && existingIndexForId != globalIdx) {
                    throw MangaInvalidResponseException(
                        "Duplicate page id '${incomingPage.id}' at index $globalIdx (already at $existingIndexForId)",
                    )
                }
                newEntries += globalIdx to incomingPage
            }
        }

        val builder = loadedPagesByIndex.builder()
        for ((globalIdx, page) in newEntries) {
            builder[globalIdx] = page
            pageIndexById[page.id] = globalIdx
        }
        val updatedLoaded = builder.build()
        val remainingFailedRegions =
            failedChunkRegions.filter { region ->
                region.range.any { idx -> idx !in updatedLoaded }
            }
        Snapshot.withMutableSnapshot {
            loadedPagesByIndex = updatedLoaded
            failedChunkRegions = remainingFailedRegions
            loadedPagesVersion++
        }
    }

    private fun applyCursorChunkLocked(chunk: MangaPagesChunk) {
        if (isRangeMode || totalCount != null) {
            throw MangaInvalidResponseException("Unexpected cursor chunk in range mode")
        }
        if (chunk.pages.isEmpty()) {
            throw MangaInvalidResponseException("Cursor chunk cannot be empty")
        }
        if (chunk.totalCount != null) {
            throw MangaInvalidResponseException("Cursor chunk flipped pagination mode with totalCount=${chunk.totalCount}")
        }
        val currentLoaded = loadedCount
        if (chunk.offset != null && chunk.offset != currentLoaded) {
            throw MangaInvalidResponseException("Cursor chunk offset ${chunk.offset} != expected $currentLoaded")
        }
        if (!chunk.endReached && chunk.nextCursor.isNullOrBlank()) {
            throw MangaInvalidResponseException("Cursor chunk with endReached=false requires nextCursor")
        }
        if (currentLoaded.toLong() + chunk.pages.size.toLong() > MAX_MANGA_TOTAL_PAGES.toLong()) {
            throw MangaInvalidResponseException("Cursor chapter exceeded maximum $MAX_MANGA_TOTAL_PAGES pages")
        }
        validateNoIntraChunkDuplicates(chunk.pages)
        for (incomingPage in chunk.pages) {
            val existingIdx = pageIndexById[incomingPage.id]
            if (existingIdx != null) {
                throw MangaInvalidResponseException("Duplicate page id '${incomingPage.id}' in cursor chunk (already at $existingIdx)")
            }
        }

        val builder = loadedPagesByIndex.builder()
        chunk.pages.forEachIndexed { localIdx, page ->
            val globalIdx = currentLoaded + localIdx
            builder[globalIdx] = page
            pageIndexById[page.id] = globalIdx
        }
        val updatedLoaded = builder.build()
        val nextLoadedCount = currentLoaded + chunk.pages.size
        val nextEndReached = chunk.endReached
        Snapshot.withMutableSnapshot {
            loadedPagesByIndex = updatedLoaded
            loadedCount = nextLoadedCount
            nextCursor = chunk.nextCursor
            endReached = nextEndReached
            cursorAppendError = null
            val reachedResumeGoal =
                when (val goal = unresolvedResumeGoal) {
                    null -> false
                    CursorChaseGoal.End -> nextEndReached && currentPageIndex >= nextLoadedCount - 1
                    is CursorChaseGoal.TargetIndex -> nextLoadedCount > goal.target && currentPageIndex >= goal.target
                }
            if (reachedResumeGoal) {
                unresolvedResumeGoal = null
            }
            loadedPagesVersion++
        }
    }

    private fun validateNoIntraChunkDuplicates(pages: List<UiMangaPage>) {
        val seen = HashSet<String>(pages.size)
        for (page in pages) {
            if (!seen.add(page.id)) {
                throw MangaInvalidResponseException("Duplicate page id '${page.id}' within chunk")
            }
        }
    }

    private fun toCursorChaseGoal(
        landing: ChapterLanding,
        progress: MangaProgressSnapshot,
    ): CursorChaseGoal =
        when (landing) {
            is ChapterLanding.Initial -> {
                val saved = progress.chapters[chapterId]
                val target =
                    landing.initialPage
                        ?: if (saved != null && !saved.isCompleted) saved.lastPageIndex else 0
                CursorChaseGoal.TargetIndex(target.coerceAtLeast(0))
            }

            is ChapterLanding.Boundary -> {
                if (landing.startFromEnd) CursorChaseGoal.End else CursorChaseGoal.TargetIndex(0)
            }

            is ChapterLanding.PreservePage -> {
                CursorChaseGoal.TargetIndex(landing.pageIndex.coerceAtLeast(0))
            }
        }

    private fun resolveClampedLanding(
        landing: ChapterLanding,
        progress: MangaProgressSnapshot,
        pageCount: Int,
    ): Int {
        val maxIdx = (pageCount - 1).coerceAtLeast(0)
        return when (landing) {
            is ChapterLanding.Initial -> {
                val saved = progress.chapters[chapterId]
                val target =
                    landing.initialPage
                        ?: if (saved != null && !saved.isCompleted) saved.lastPageIndex else 0
                target.coerceIn(0, maxIdx)
            }

            is ChapterLanding.Boundary -> {
                if (landing.startFromEnd) maxIdx else 0
            }

            is ChapterLanding.PreservePage -> {
                landing.pageIndex.coerceIn(0, maxIdx)
            }
        }
    }

    suspend fun syncResolutionWindow(
        currentPageIndex: Int,
        dataSource: MangaDataSource,
    ) {
        val total = effectiveTotalPageCount
        if (!job.isActive || total <= 0) return
        val clampedCurrent = currentPageIndex.coerceIn(0, total - 1)
        val start = (clampedCurrent - 1).coerceAtLeast(0)
        val end = (clampedCurrent + 3).coerceAtMost(total - 1)
        val windowIds = HashSet<String>(end - start + 1)
        for (i in start..end) {
            loadedPagesByIndex[i]?.id?.let(windowIds::add)
        }

        val jobsToStart = mutableListOf<Job>()
        val jobsToCancel = mutableListOf<Job>()
        resolveJobsMutex.withLock {
            val iterator = inFlightResolveJobs.entries.iterator()
            while (iterator.hasNext()) {
                val (pageId, inFlightJob) = iterator.next()
                if (pageId !in windowIds) {
                    jobsToCancel += inFlightJob
                    iterator.remove()
                } else if (!inFlightJob.isActive) {
                    iterator.remove()
                }
            }

            val priorityOffsets = intArrayOf(0, 1, -1, 2, 3)
            for (offset in priorityOffsets) {
                val idx = clampedCurrent + offset
                if (idx !in start..end) continue
                val deferred = loadedPagesByIndex[idx] as? UiMangaPage.Deferred ?: continue
                if (deferred.id in resolvedMedia || deferred.id in failedPages || inFlightResolveJobs[deferred.id]?.isActive == true) {
                    continue
                }
                val pageAttempt = pageAttempts.getOrPut(deferred.id) { 0 }
                val reason =
                    if (pageAttempt == 0) {
                        MangaPageImageResolveReason.Initial
                    } else {
                        MangaPageImageResolveReason.ResolveRetry
                    }
                jobsToStart +=
                    createResolvePageJobLocked(
                        page = deferred,
                        dataSource = dataSource,
                        mangaKey = mangaKey,
                        attempt = pageAttempt,
                        reason = reason,
                        failedUrl = null,
                    )
            }
        }
        jobsToCancel.forEach { it.cancel() }
        jobsToStart.forEach { it.start() }
    }

    fun retryPage(
        page: UiMangaPage.Deferred,
        dataSource: MangaDataSource,
        mangaKey: MicroBlogKey,
        failedUrl: String?,
    ) {
        if (!job.isActive) return
        val normalizedFailedUrl = failedUrl?.takeIf { it.isNotBlank() }
        Snapshot.withMutableSnapshot {
            resolvedMedia = resolvedMedia - page.id
            failedPages = failedPages - page.id
        }
        scope.launch {
            var oldJob: Job? = null
            val jobToStart =
                resolveJobsMutex.withLock {
                    oldJob = inFlightResolveJobs.remove(page.id)
                    val nextAttempt = ((pageAttempts[page.id] ?: 0) + 1).coerceAtMost(100)
                    pageAttempts[page.id] = nextAttempt
                    val reason =
                        if (normalizedFailedUrl != null) {
                            MangaPageImageResolveReason.ImageLoadFailed
                        } else {
                            MangaPageImageResolveReason.ResolveRetry
                        }
                    createResolvePageJobLocked(
                        page = page,
                        dataSource = dataSource,
                        mangaKey = mangaKey,
                        attempt = nextAttempt,
                        reason = reason,
                        failedUrl = normalizedFailedUrl,
                    )
                }
            oldJob?.cancel()
            jobToStart.start()
        }
    }

    private fun createResolvePageJobLocked(
        page: UiMangaPage.Deferred,
        dataSource: MangaDataSource,
        mangaKey: MicroBlogKey,
        attempt: Int,
        reason: MangaPageImageResolveReason,
        failedUrl: String?,
    ): Job {
        val pageJob =
            scope.launch(start = CoroutineStart.LAZY) {
                val selfJob = currentCoroutineContext()[Job]
                try {
                    val media =
                        resolveSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            dataSource.resolvePageMedia(
                                mangaKey = mangaKey,
                                chapterId = chapterId,
                                pageId = page.id,
                                pageToken = page.pageToken,
                                attempt = attempt,
                                reason = reason,
                                failedUrl = failedUrl,
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    resolveJobsMutex.withLock {
                        if (inFlightResolveJobs[page.id] === selfJob) {
                            inFlightResolveJobs.remove(page.id)
                        }
                    }
                    val bound = if (media.proxy == ProxyRef.Unbound) media.withProxy(proxyRef) else media
                    Snapshot.withMutableSnapshot {
                        resolvedMedia = resolvedMedia + (page.id to bound)
                        failedPages = failedPages - page.id
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    resolveJobsMutex.withLock {
                        if (inFlightResolveJobs[page.id] === selfJob) {
                            inFlightResolveJobs.remove(page.id)
                        }
                    }
                    Snapshot.withMutableSnapshot {
                        failedPages = failedPages + (page.id to e)
                    }
                } finally {
                    withContext(NonCancellable) {
                        resolveJobsMutex.withLock {
                            if (inFlightResolveJobs[page.id] === selfJob) {
                                inFlightResolveJobs.remove(page.id)
                            }
                        }
                    }
                }
            }
        inFlightResolveJobs[page.id] = pageJob
        return pageJob
    }

    fun cancel() {
        job.cancel()
    }
}

private fun resolvePageAspectRatio(
    rawPage: UiMangaPage,
    resolvedMedia: UiMedia?,
    measured: Pair<Float, Float>?,
): Float? {
    if (measured != null && measured.first > 0f && measured.second > 0f) {
        val ratio = measured.first / measured.second
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    val mediaDims =
        resolvedMedia?.dimensionsOrNull()
            ?: (rawPage as? UiMangaPage.Ready)?.media?.dimensionsOrNull()
    if (mediaDims != null && mediaDims.first > 0f && mediaDims.second > 0f) {
        val ratio = mediaDims.first / mediaDims.second
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    if (rawPage is UiMangaPage.Deferred && rawPage.width > 0f && rawPage.height > 0f) {
        val ratio = rawPage.width / rawPage.height
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    return null
}

private fun UiMedia.dimensionsOrNull(): Pair<Float, Float>? =
    when (this) {
        is UiMedia.Image -> width to height
        is UiMedia.Gif -> width to height
        is UiMedia.Video -> width to height
        is UiMedia.Ugoira -> width to height
        is UiMedia.Audio -> null
    }
