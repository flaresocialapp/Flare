package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.cachedIn
import dev.dimension.flare.common.CacheState
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.collectAsPagingItems
import dev.dimension.flare.common.isRefreshing
import dev.dimension.flare.common.refreshSuspend
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePagingMapper
import dev.dimension.flare.data.datasource.microblog.paging.toTimelinePagingSource
import dev.dimension.flare.data.datasource.microblog.pagingConfig
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.repository.MangaProgressSnapshot
import dev.dimension.flare.data.repository.STATUS_HISTORY_PAGING_KEY
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.collectAsUiState
import dev.dimension.flare.ui.model.flatMapLatestUiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.map
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

@Immutable
public data class UiMangaChapterItem(
    public val chapter: UiMangaChapter,
    public val isRead: Boolean,
    public val isCurrent: Boolean,
    public val lastPageIndex: Int,
    public val pageCount: Int,
)

@Immutable
public data class PrimaryReadTarget(
    public val chapterId: String,
    public val chapterTitle: String,
    public val initialPage: Int,
    public val isContinue: Boolean,
    public val isSingleChapterWork: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
public class MangaDetailPresenter(
    private val accountType: AccountType,
    private val mangaKey: MicroBlogKey,
) : PresenterBase<MangaDetailPresenter.State>() {
    private val accountService: AccountService by koinInject()
    private val cacheDatabase: CacheDatabase by koinInject()
    private val progressRepository: MangaProgressRepository by koinInject()
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

    private val refreshTrigger = MutableStateFlow(0)

    private fun buildDetailFlow(mangaDataSource: MangaDataSource) =
        refreshTrigger.flatMapLatestUiState { refreshCount ->
            val cacheable = mangaDataSource.mangaDetail(mangaKey)
            if (refreshCount == 0) {
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
        public val detail: UiState<UiMangaDetail>
        public val translation: MangaTranslatePresenter.State?
        public val selectedGroupId: String?
        public val chapterSortDescending: Boolean
        public val chapters: ImmutableList<UiMangaChapterItem>
        public val primaryReadTarget: PrimaryReadTarget?
        public val supportsComments: Boolean
        public val supportsRecommendations: Boolean
        public val comments: PagingState<UiTimelineV2>
        public val recommendations: PagingState<UiTimelineV2>
        public val isRefreshing: Boolean
            get() = false

        public fun refresh() {}

        public fun selectGroup(groupId: String)

        public fun toggleChapterSort()

        public fun setChapterRead(
            chapterId: String,
            isRead: Boolean,
        )
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
        DisposableEffect(Unit) {
            onDispose {
                writeJob.complete()
            }
        }

        val serviceState = key(accountType, mangaKey) { serviceFlow.collectAsUiState().value }
        val currentDataSource = serviceState.takeSuccess()?.capabilities?.manga
        val latestDataSource by rememberUpdatedState(currentDataSource)
        val detailState =
            if (currentDataSource != null) {
                key(accountType, mangaKey, currentDataSource) {
                    remember { buildDetailFlow(currentDataSource) }.flattenUiState().value
                }
            } else {
                when (val service = serviceState) {
                    is UiState.Loading -> UiState.Loading()
                    is UiState.Error -> UiState.Error(service.throwable)
                    is UiState.Success -> UiState.Error(IllegalStateException("Current service does not support manga data source"))
                }
            }
        val rawDetail = detailState.takeSuccess()

        var lastLoadedDetail by remember(accountType, mangaKey, currentDataSource) { mutableStateOf<UiMangaDetail?>(null) }
        if (rawDetail != null) {
            lastLoadedDetail = rawDetail
        }
        val effectiveRawDetail = rawDetail ?: lastLoadedDetail
        val hasLoadedDetail = lastLoadedDetail != null

        var resolvedMangaKey by remember(accountType, mangaKey, currentDataSource) { mutableStateOf(mangaKey) }
        if (effectiveRawDetail != null && effectiveRawDetail.mangaKey != resolvedMangaKey) {
            resolvedMangaKey = effectiveRawDetail.mangaKey
        }
        val effectiveMangaKey = effectiveRawDetail?.mangaKey ?: resolvedMangaKey
        val latestEffectiveMangaKey by rememberUpdatedState(effectiveMangaKey)

        val progressFlow =
            remember(accountType, effectiveMangaKey) {
                progressRepository.observe(accountType, effectiveMangaKey)
            }
        val taggedProgress by remember(progressFlow, effectiveMangaKey) {
            progressFlow.map { effectiveMangaKey to it }
        }.collectAsState(initial = null)
        val hasObservedProgressForKey = taggedProgress?.first == effectiveMangaKey
        val observedProgress = taggedProgress?.takeIf { it.first == effectiveMangaKey }?.second ?: MangaProgressSnapshot()
        var optimisticProgress by remember(accountType, effectiveMangaKey) { mutableStateOf<MangaProgressSnapshot?>(null) }
        var pendingWriteCount by remember(accountType, effectiveMangaKey) { mutableIntStateOf(0) }
        LaunchedEffect(hasObservedProgressForKey, observedProgress) {
            if (hasObservedProgressForKey && (pendingWriteCount == 0 || observedProgress.matchesIgnoringTimestamp(optimisticProgress))) {
                optimisticProgress = null
            }
        }
        val progress = optimisticProgress ?: observedProgress

        var userSelectedGroupId by remember(accountType, mangaKey) { mutableStateOf<String?>(null) }
        val selectedGroupId =
            remember(effectiveRawDetail, userSelectedGroupId, progress.lastChapterId) {
                val groups = effectiveRawDetail?.chapterGroups.orEmpty()
                if (groups.isEmpty()) {
                    null
                } else {
                    userSelectedGroupId?.takeIf { id -> groups.any { it.id == id } }
                        ?: progress.lastChapterId?.let { lastId ->
                            groups.firstOrNull { group -> group.chapters.any { it.id == lastId } }?.id
                        }
                        ?: groups.first().id
                }
            }

        val chapterSortDescending = progress.chapterSortDescending
        val chapters =
            remember(effectiveRawDetail, selectedGroupId, chapterSortDescending, progress) {
                val activeGroup =
                    selectedGroupId?.let { id ->
                        effectiveRawDetail?.chapterGroups?.firstOrNull { it.id == id }
                    }
                if (activeGroup == null) {
                    persistentListOf()
                } else {
                    val ordered =
                        if (chapterSortDescending) {
                            activeGroup.chapters.asReversed()
                        } else {
                            activeGroup.chapters
                        }
                    ordered
                        .map { chapter ->
                            val chProgress = progress.chapters[chapter.id]
                            val isCurrent = progress.lastChapterId == chapter.id
                            UiMangaChapterItem(
                                chapter = chapter,
                                isRead = chProgress?.isCompleted == true,
                                isCurrent = isCurrent,
                                lastPageIndex = chProgress?.lastPageIndex ?: if (isCurrent) progress.lastPageIndex else 0,
                                pageCount = chProgress?.pageCount ?: 0,
                            )
                        }.toImmutableList()
                }
            }

        val primaryReadTarget =
            remember(effectiveRawDetail, selectedGroupId, progress) {
                effectiveRawDetail?.let { computePrimaryReadTarget(it, selectedGroupId, progress) }
            }

        LaunchedEffect(accountType, effectiveMangaKey, effectiveRawDetail) {
            effectiveRawDetail
                ?.toHistoryFeed(
                    accountType = accountType,
                    mangaKey = effectiveMangaKey,
                )?.let { feed ->
                    saveToDatabase(
                        database = cacheDatabase,
                        items =
                            listOf(
                                TimelinePagingMapper.toDb(
                                    data = feed,
                                    pagingKey = STATUS_HISTORY_PAGING_KEY,
                                    sortId = Clock.System.now().toEpochMilliseconds(),
                                ),
                            ),
                    )
                }
        }

        val translation =
            effectiveRawDetail?.let { detail ->
                remember(detail) { MangaTranslatePresenter(detail) }.body()
            }
        val displayedDetailState =
            remember(detailState, lastLoadedDetail, translation?.detail) {
                val loaded = lastLoadedDetail
                if (loaded != null) {
                    UiState.Success(translation?.detail ?: loaded)
                } else {
                    detailState
                }
            }

        val supportsComments = currentDataSource?.supportsComments == true
        val supportsRecommendations = currentDataSource?.supportsRecommendations == true

        val comments =
            if (supportsComments && hasLoadedDetail) {
                remember(currentDataSource, effectiveMangaKey, scope) {
                    Pager(config = pagingConfig) {
                        currentDataSource.mangaComments(effectiveMangaKey).toTimelinePagingSource()
                    }.flow.cachedIn(scope)
                }.collectAsPagingItems().toPagingState()
            } else if (supportsComments && detailState is UiState.Loading) {
                remember { PagingState.Loading() }
            } else {
                remember { PagingState.Empty {} }
            }

        val recommendations =
            if (supportsRecommendations && hasLoadedDetail) {
                remember(currentDataSource, effectiveMangaKey, scope) {
                    Pager(config = pagingConfig) {
                        currentDataSource.mangaRecommendations(effectiveMangaKey).toTimelinePagingSource()
                    }.flow.cachedIn(scope)
                }.collectAsPagingItems().toPagingState()
            } else if (supportsRecommendations && detailState is UiState.Loading) {
                remember { PagingState.Loading() }
            } else {
                remember { PagingState.Empty {} }
            }

        val isRefreshing =
            (lastLoadedDetail != null && detailState is UiState.Loading) ||
                (supportsComments && comments.isRefreshing) ||
                (supportsRecommendations && recommendations.isRefreshing)

        return object : State {
            override val detail: UiState<UiMangaDetail> = displayedDetailState
            override val translation: MangaTranslatePresenter.State? = translation
            override val selectedGroupId: String? = selectedGroupId
            override val chapterSortDescending: Boolean = chapterSortDescending
            override val chapters: ImmutableList<UiMangaChapterItem> = chapters
            override val primaryReadTarget: PrimaryReadTarget? = primaryReadTarget
            override val supportsComments: Boolean = supportsComments
            override val supportsRecommendations: Boolean = supportsRecommendations
            override val comments: PagingState<UiTimelineV2> = comments
            override val recommendations: PagingState<UiTimelineV2> = recommendations
            override val isRefreshing: Boolean = isRefreshing

            override fun refresh() {
                refreshTrigger.update { it + 1 }
                if (supportsComments) {
                    scope.launch {
                        if (comments is PagingState.Error) {
                            comments.onRetry()
                        } else {
                            comments.refreshSuspend()
                        }
                    }
                }
                if (supportsRecommendations) {
                    scope.launch {
                        if (recommendations is PagingState.Error) {
                            recommendations.onRetry()
                        } else {
                            recommendations.refreshSuspend()
                        }
                    }
                }
            }

            override fun selectGroup(groupId: String) {
                userSelectedGroupId = groupId
            }

            override fun toggleChapterSort() {
                if (effectiveRawDetail == null || currentDataSource !== latestDataSource) return
                val targetMangaKey = latestEffectiveMangaKey
                val base = optimisticProgress ?: observedProgress
                val nextDescending = !base.chapterSortDescending
                optimisticProgress = base.copy(chapterSortDescending = nextDescending)
                pendingWriteCount++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setChapterSortDescending(accountType, targetMangaKey, nextDescending)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticProgress = null
                    } finally {
                        pendingWriteCount--
                        if (
                            pendingWriteCount == 0 &&
                            hasObservedProgressForKey &&
                            observedProgress.matchesIgnoringTimestamp(optimisticProgress)
                        ) {
                            optimisticProgress = null
                        }
                    }
                }
            }

            override fun setChapterRead(
                chapterId: String,
                isRead: Boolean,
            ) {
                if (effectiveRawDetail == null || currentDataSource !== latestDataSource) return
                val targetMangaKey = latestEffectiveMangaKey
                val base = optimisticProgress ?: observedProgress
                optimisticProgress = base.optimisticSetChapterRead(chapterId = chapterId, isRead = isRead)
                pendingWriteCount++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setChapterRead(accountType, targetMangaKey, chapterId, isRead)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticProgress = null
                    } finally {
                        pendingWriteCount--
                        if (
                            pendingWriteCount == 0 &&
                            hasObservedProgressForKey &&
                            observedProgress.matchesIgnoringTimestamp(optimisticProgress)
                        ) {
                            optimisticProgress = null
                        }
                    }
                }
            }
        }
    }
}

private fun MangaProgressSnapshot.matchesIgnoringTimestamp(other: MangaProgressSnapshot?): Boolean =
    other != null && copy(lastReadAt = 0L) == other.copy(lastReadAt = 0L)

internal fun UiMangaDetail.bindMangaProxy(proxy: ProxyRef): UiMangaDetail =
    copy(
        cover = cover?.let { if (it.proxy == ProxyRef.Unbound) it.copy(proxy = proxy) else it },
        author = author?.withProxy(proxy),
        content = content?.withProxy(proxy),
    )

internal fun computePrimaryReadTarget(
    detail: UiMangaDetail,
    selectedGroupId: String?,
    progress: MangaProgressSnapshot,
): PrimaryReadTarget? {
    val totalChapters = detail.chapterGroups.sumOf { it.chapters.size }
    if (totalChapters == 0) return null
    val isSingleChapterWork = totalChapters == 1

    val lastChapterId = progress.lastChapterId
    if (lastChapterId != null) {
        val groupWithLast = detail.chapterGroups.firstOrNull { group -> group.chapters.any { it.id == lastChapterId } }
        if (groupWithLast != null) {
            val lastIndex = groupWithLast.chapters.indexOfFirst { it.id == lastChapterId }
            val lastChapter = groupWithLast.chapters[lastIndex]
            val lastChapterProgress = progress.chapters[lastChapterId]
            val isLastCompleted = lastChapterProgress?.isCompleted == true
            if (!isLastCompleted && !lastChapter.locked) {
                val resumePage = (lastChapterProgress?.lastPageIndex ?: progress.lastPageIndex).coerceAtLeast(0)
                val hasProgress =
                    resumePage > 0 ||
                        progress.lastReadAt > 0L ||
                        progress.chapters.values.any { it.isCompleted || it.lastPageIndex > 0 }
                return PrimaryReadTarget(
                    chapterId = lastChapter.id,
                    chapterTitle = lastChapter.title,
                    initialPage = resumePage,
                    isContinue = hasProgress,
                    isSingleChapterWork = isSingleChapterWork,
                )
            }
            val nextUnlocked =
                groupWithLast.chapters
                    .drop(lastIndex + 1)
                    .firstOrNull { chapter -> !chapter.locked && progress.chapters[chapter.id]?.isCompleted != true }
            if (nextUnlocked != null) {
                val nextResumePage = (progress.chapters[nextUnlocked.id]?.lastPageIndex ?: 0).coerceAtLeast(0)
                return PrimaryReadTarget(
                    chapterId = nextUnlocked.id,
                    chapterTitle = nextUnlocked.title,
                    initialPage = nextResumePage,
                    isContinue = true,
                    isSingleChapterWork = isSingleChapterWork,
                )
            }
            val fallbackInGroup = groupWithLast.chapters.firstOrNull { !it.locked }
            if (fallbackInGroup != null) {
                return PrimaryReadTarget(
                    chapterId = fallbackInGroup.id,
                    chapterTitle = fallbackInGroup.title,
                    initialPage = 0,
                    isContinue = false,
                    isSingleChapterWork = isSingleChapterWork,
                )
            }
        }
    }

    val activeGroup =
        selectedGroupId?.let { id -> detail.chapterGroups.firstOrNull { it.id == id } }
            ?: detail.chapterGroups.firstOrNull()
    val firstUnlocked =
        activeGroup?.chapters?.firstOrNull { !it.locked }
            ?: detail.chapterGroups
                .asSequence()
                .flatMap { it.chapters.asSequence() }
                .firstOrNull { !it.locked }
            ?: return null
    return PrimaryReadTarget(
        chapterId = firstUnlocked.id,
        chapterTitle = firstUnlocked.title,
        initialPage = 0,
        isContinue = false,
        isSingleChapterWork = isSingleChapterWork,
    )
}

private fun MangaProgressSnapshot.optimisticSetChapterRead(
    chapterId: String,
    isRead: Boolean,
): MangaProgressSnapshot {
    val existing = chapters[chapterId]
    val pageCount = existing?.pageCount ?: 0
    val updatedLastPageIndex =
        if (isRead) {
            if (pageCount > 0) {
                maxOf(pageCount - 1, existing?.lastPageIndex ?: 0)
            } else {
                existing?.lastPageIndex ?: 0
            }
        } else {
            0
        }
    val updatedChapter =
        MangaProgressSnapshot.ChapterProgress(
            chapterId = chapterId,
            lastPageIndex = updatedLastPageIndex,
            pageCount = pageCount,
            isCompleted = isRead,
        )
    val updatedChapters = chapters + (chapterId to updatedChapter)
    return when {
        isRead && (lastChapterId == null || lastChapterId == chapterId) -> {
            copy(
                lastChapterId = chapterId,
                lastPageIndex = updatedLastPageIndex,
                lastReadAt = maxOf(lastReadAt, 1L),
                chapters = updatedChapters,
            )
        }

        !isRead && lastChapterId == chapterId -> {
            copy(
                lastPageIndex = 0,
                chapters = updatedChapters,
            )
        }

        else -> {
            copy(chapters = updatedChapters)
        }
    }
}

private fun UiMangaDetail.toHistoryFeed(
    accountType: AccountType,
    mangaKey: MicroBlogKey,
): UiTimelineV2.Feed {
    val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: "https://${mangaKey.host}/manga/${mangaKey.id}"
    val sourceName =
        author?.name?.raw?.takeIf { it.isNotBlank() }
            ?: author?.handle?.raw?.takeIf { it.isNotBlank() }
            ?: authors.firstOrNull { it.isNotBlank() }
            ?: platformId.takeIf { it.isNotBlank() }
            ?: mangaKey.host
    return UiTimelineV2.Feed(
        pluginId = pluginId,
        title = title.takeIf { it.isNotBlank() },
        description = content?.innerText?.takeIf { it.isNotBlank() },
        url = resolvedUrl,
        sourceLanguages = sourceLanguages,
        createdAt = updatedAt ?: Clock.System.now().toUi(),
        source =
            UiTimelineV2.Feed.Source(
                name = sourceName,
                icon = author?.avatar?.url,
                proxy = ProxyRef.forAccountType(accountType),
            ),
        media = cover,
        clickEvent =
            ClickEvent.Deeplink(
                DeeplinkRoute.Manga.Detail(
                    mangaKey = mangaKey,
                    accountType = accountType,
                ),
            ),
        accountType = accountType,
    )
}
