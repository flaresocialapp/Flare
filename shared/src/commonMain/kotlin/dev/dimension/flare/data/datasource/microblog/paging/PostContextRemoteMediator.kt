package dev.dimension.flare.data.datasource.microblog.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.LoadType
import androidx.paging.PagingState
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.connect
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.database.cache.model.DbPagingKey
import dev.dimension.flare.data.database.cache.model.DbPagingTimeline
import dev.dimension.flare.data.database.cache.model.DbStatus
import dev.dimension.flare.data.database.cache.model.DbTimelineItemPresentationType
import dev.dimension.flare.data.repository.DebugRepository
import dev.dimension.flare.data.translation.NoopPreTranslationService
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asTimelinePostItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalPagingApi::class)
internal class PostContextRemoteMediator(
    private val loader: PostContextLoader,
    private val database: CacheDatabase,
    notifyError: (Throwable) -> Unit = {},
    preTranslationService: PreTranslationService = NoopPreTranslationService,
) : TimelineRemoteMediator(loader, database, true, notifyError, preTranslationService) {
    private val mutableLoadStates = MutableStateFlow(LoadStates(LoadState.Loading, LoadState.Loading, LoadState.Loading))
    val loadStates = mutableLoadStates.asStateFlow()
    private val writes = Mutex()
    private val failures = mutableMapOf<PendingRequest, Throwable>()
    private val retryRequested = MutableStateFlow(false)
    private val retryWhileLoading = MutableStateFlow<(() -> Unit)?>(null)
    private var generation = 0L
    private var sequence = 0L
    private val postVersions = mutableMapOf<String, Long>()
    private val sideVersions = mutableMapOf<Boolean, Long>()
    private val accountType = AccountType.Specific(loader.accountKey)
    private val anchorId = DbStatus.createId(accountType, loader.statusKey)

    private data class PendingRequest(
        val request: PagingRequest,
        val initial: Boolean,
    )

    fun prepareRetry(): Boolean {
        if (!loadStates.value.hasError) return false
        retryRequested.value = true
        retryWhileLoading.value?.invoke()
        return true
    }

    suspend fun retryWhenReady(
        pagingStates: Flow<LoadStates?>,
        retryPaging: () -> Unit,
    ) {
        // Keep early clicks queued until Paging has registered the failure.
        combine(retryRequested, pagingStates, loadStates) { requested, paging, context ->
            requested && context.hasError && paging?.hasError == true
        }.distinctUntilChanged().collect { ready ->
            if (ready) retryPaging()
        }
    }

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun doLoad(
        loadType: LoadType,
        state: PagingState<OffsetFromStartPagingKey, TimelinePageItem>,
    ): MediatorResult {
        val retry = retryRequested.compareAndSet(expect = true, update = false)
        val (epoch, failedRequests) =
            writes.withLock {
                val pending =
                    failures.keys
                        .toList()
                        .takeIf { retry && it.none { it.request == PagingRequest.Refresh } }
                        .orEmpty()
                if (loadType == LoadType.REFRESH && pending.isEmpty()) {
                    generation++
                    failures.clear()
                    mutableLoadStates.value = LoadStates(LoadState.Loading, LoadState.Loading, LoadState.Loading)
                } else if (pending.isNotEmpty()) {
                    mutableLoadStates.update {
                        fun retrying(state: LoadState) = if (state is LoadState.Error) LoadState.Loading else state
                        it.copy(refresh = retrying(it.refresh), prepend = retrying(it.prepend), append = retrying(it.append))
                    }
                }
                generation to pending
            }
        var retryAction: (() -> Unit)? = null
        try {
            coroutineScope {
                // Paging cannot retry its REFRESH until it finishes; retry failed sides in the active load scope.
                retryAction = {
                    launch {
                        val pending =
                            writes.withLock {
                                val failed = failures.keys.filter { it.request != PagingRequest.Refresh }
                                if (epoch != generation || failed.isEmpty() || !retryRequested.compareAndSet(true, false)) {
                                    return@withLock emptyList()
                                }
                                failed.forEach { failures.remove(it) }
                                failed
                            }
                        pending.map { async { fetch(it, state.config.pageSize, epoch) } }.awaitAll()
                    }
                }
                retryWhileLoading.value = retryAction
                if (failedRequests.isNotEmpty()) {
                    coroutineScope { failedRequests.map { async { fetch(it, state.config.pageSize, epoch) } }.awaitAll() }
                } else if (loadType == LoadType.REFRESH) {
                    val result = fetch(PendingRequest(PagingRequest.Refresh, initial = true), state.config.pageSize, epoch)
                    if (result != null) {
                        val update = loader.contextUpdate(PagingRequest.Refresh, result, initial = true)
                        val pending =
                            buildList {
                                if (update.before == null) result.previousKey?.let { add(PendingRequest(PagingRequest.Prepend(it), true)) }
                                if (update.after == null) result.nextKey?.let { add(PendingRequest(PagingRequest.Append(it), true)) }
                            }
                        // Fetch the first context even when cached rows keep Paging away from either edge.
                        coroutineScope { pending.map { async { fetch(it, state.config.pageSize, epoch) } }.awaitAll() }
                    }
                } else {
                    val keys = database.pagingTimelineDao().getPagingKey(pagingKey)
                    val request =
                        when (loadType) {
                            LoadType.PREPEND -> keys?.prevKey?.let(PagingRequest::Prepend)
                            LoadType.APPEND -> keys?.nextKey?.let(PagingRequest::Append)
                            LoadType.REFRESH -> error("Handled above")
                        }
                    if (request != null) fetch(PendingRequest(request, initial = false), state.config.pageSize, epoch)
                }
            }
        } finally {
            retryWhileLoading.compareAndSet(retryAction, null)
        }
        return writes.withLock {
            if (epoch != generation) return@withLock MediatorResult.Success(endOfPaginationReached = false)
            val error = failures.values.firstOrNull()
            val keys = database.pagingTimelineDao().getPagingKey(pagingKey)
            // A combined endpoint may have been responsible for both initially pending sides.
            if (loadType == LoadType.REFRESH || failedRequests.isNotEmpty()) {
                mutableLoadStates.update { states ->
                    fun finish(
                        value: LoadState,
                        end: Boolean,
                    ): LoadState = if (value is LoadState.Loading) error?.let(LoadState::Error) ?: LoadState.NotLoading(end) else value
                    states.copy(
                        refresh = finish(states.refresh, false),
                        prepend = finish(states.prepend, keys?.prevKey == null),
                        append = finish(states.append, keys?.nextKey == null),
                    )
                }
            }
            error?.let { MediatorResult.Error(it) } ?: MediatorResult.Success(
                endOfPaginationReached =
                    when (loadType) {
                        LoadType.REFRESH -> false
                        LoadType.PREPEND -> keys?.prevKey == null
                        LoadType.APPEND -> keys?.nextKey == null
                    },
            )
        }
    }

    private suspend fun fetch(
        pending: PendingRequest,
        pageSize: Int,
        epoch: Long,
    ): PagingResult<UiTimelineV2>? {
        val revision =
            writes.withLock {
                if (epoch != generation) return null
                failures.remove(pending)
                setRequestState(pending.request, LoadState.Loading)
                ++sequence
            }
        return try {
            val result = timeline(pageSize, pending.request)
            currentCoroutineContext().ensureActive()
            val update = loader.contextUpdate(pending.request, result, pending.initial)
            writes.withLock {
                currentCoroutineContext().ensureActive()
                if (epoch != generation) return null
                val applied = applyUpdate(update, revision)
                failures.remove(pending)
                mutableLoadStates.update { states ->
                    states.copy(
                        refresh = if (pending.request == PagingRequest.Refresh) LoadState.NotLoading(false) else states.refresh,
                        prepend = applied.before?.let { LoadState.NotLoading(it.cursor == null) } ?: states.prepend,
                        append = applied.after?.let { LoadState.NotLoading(it.cursor == null) } ?: states.append,
                    )
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writes.withLock {
                val sideVersion =
                    when (pending.request) {
                        is PagingRequest.Prepend -> sideVersions[true]
                        is PagingRequest.Append -> sideVersions[false]
                        PagingRequest.Refresh -> null
                    }
                if (epoch == generation && revision >= (sideVersion ?: Long.MIN_VALUE)) {
                    failures[pending] = e
                    setRequestState(pending.request, LoadState.Error(e))
                    onError(e)
                    DebugRepository.error(e)
                }
            }
            null
        }
    }

    private fun setRequestState(
        request: PagingRequest,
        value: LoadState,
    ) {
        mutableLoadStates.update {
            when (request) {
                PagingRequest.Refresh -> it.copy(refresh = value)
                is PagingRequest.Prepend -> it.copy(prepend = value)
                is PagingRequest.Append -> it.copy(append = value)
            }
        }
    }

    private suspend fun applyUpdate(
        response: ContextUpdate,
        revision: Long,
    ): ContextUpdate {
        val update =
            response.copy(
                before = response.before.takeIf { revision >= (sideVersions[true] ?: Long.MIN_VALUE) },
                after = response.after.takeIf { revision >= (sideVersions[false] ?: Long.MIN_VALUE) },
            )
        val savedRows =
            database.connect {
                val dao = database.pagingTimelineDao()
                // ponytail: Read row IDs per update; use range queries if very large threads make this expensive.
                val cached = dao.getByPagingKey(pagingKey)
                val cachedIds = cached.map { it.statusId }.toSet()
                val existing = migrateInlineParents(cached, update, revision)
                val promotedIds = existing.filterNot { it.statusId in cachedIds }.map { it.statusId }
                val existingById = existing.associateBy { it.statusId }
                val anchorSortId = existingById[anchorId]?.sortId ?: 0L
                require(anchorId in existingById || update.posts.any { it.statusKey == loader.statusKey }) {
                    "Post context does not contain ${loader.statusKey}"
                }
                val incoming = update.posts.associateBy { DbStatus.createId(accountType, it.statusKey) }
                val positions = existing.associate { it.statusId to it.sortId }.toMutableMap()
                positions[anchorId] = anchorSortId
                val stale = mutableSetOf<String>()
                val retained = mutableSetOf<String>()
                var keys = dao.getPagingKey(pagingKey) ?: DbPagingKey(pagingKey)

                fun applySide(
                    page: ContextPageUpdate?,
                    before: Boolean,
                ) {
                    if (page == null) return
                    val oldSide = existing.filter { if (before) it.sortId < anchorSortId else it.sortId > anchorSortId }
                    val ids =
                        page.keys
                            .map { DbStatus.createId(accountType, it) }
                            .filter { it != anchorId }
                            .distinct()
                    require(ids.all { it in incoming || it in existingById }) { "Context page references an unknown post" }
                    val oldIds = oldSide.map { it.statusId }
                    val ordered =
                        if (page.replace) {
                            ids
                        } else {
                            // Existing posts returned again update in place, including posts from the other side.
                            val additions = ids.filterNot { it in existingById }
                            if (before) additions + oldIds else oldIds + additions
                        }
                    if (page.replace) stale += oldIds.filterNot { it in ordered }
                    require(ordered.none { it in retained }) { "A post cannot belong to both context sides" }
                    retained += ordered
                    ordered.forEachIndexed { index, id ->
                        positions[id] = if (before) anchorSortId - ordered.size + index else anchorSortId + index + 1
                    }
                    keys = if (before) keys.copy(prevKey = page.cursor) else keys.copy(nextKey = page.cursor)
                }
                applySide(update.before, true)
                applySide(update.after, false)
                stale.removeAll(retained)
                val posts = incoming.filter { (id, _) -> revision >= (postVersions[id] ?: Long.MIN_VALUE) }
                val mapped = TimelinePagingMapper.toDb(posts.values.toList(), pagingKey, posts.keys.map { positions[it] ?: anchorSortId })
                val moved =
                    existing
                        .filter { it.statusId !in stale && it.statusId !in posts && positions[it.statusId] != it.sortId }
                        .map { it.copy(sortId = positions.getValue(it.statusId)) }
                if (moved.isNotEmpty()) dao.updateExisting(moved)
                saveToDatabase(database, mapped)
                val promoted = promotedIds.filterNot { it in posts }
                if (promoted.isNotEmpty()) {
                    // The focal save may itself change a promoted parent's quote/repost references.
                    val references = promoted.chunked(500).flatMap { dao.getPageStatusReferences(it) }.groupBy { it.statusId }
                    val corrected =
                        promoted.chunked(500).flatMap { dao.getByPagingKeyAndStatusIds(pagingKey, it) }.mapNotNull { row ->
                            val signature = TimelinePagingMapper.semanticReferenceSignature(references[row.statusId].orEmpty())
                            if (row.semanticReferenceSignature == signature) null else row.copy(semanticReferenceSignature = signature)
                        }
                    if (corrected.isNotEmpty()) dao.updateExisting(corrected)
                }
                val removed = stale + (posts.keys - positions.keys)
                if (removed.isNotEmpty()) {
                    val removedRows = (existing + mapped.map { it.timeline }).filter { it.statusId in removed }.distinctBy { it._id }
                    dao.deletePresentationReferences(pagingKey, removedRows.map { it._id })
                    dao.delete(removedRows)
                }
                dao.insertPagingKey(keys)
                mapped
            }
        savedRows.forEach { postVersions[it.timeline.statusId] = revision }
        if (update.before != null) sideVersions[true] = revision
        if (update.after != null) sideVersions[false] = revision
        enqueuePreTranslation(savedRows)
        return update
    }

    private suspend fun migrateInlineParents(
        existing: List<DbPagingTimeline>,
        update: ContextUpdate,
        revision: Long,
    ): List<DbPagingTimeline> {
        if (update.before != null || revision < (postVersions[anchorId] ?: Long.MIN_VALUE)) return existing
        val incoming = update.posts.firstOrNull { it.statusKey == loader.statusKey }?.asTimelinePostItem() ?: return existing
        if (incoming.presentation.inlineParents.isNotEmpty()) return existing
        val anchor = existing.firstOrNull { it.statusId == anchorId } ?: return existing
        val byId = existing.associateBy { it.statusId }
        val dao = database.pagingTimelineDao()
        val parentIds =
            dao
                .getPagePresentationReferences(pagingKey, listOf(anchor._id))
                .filter { it.presentationType == DbTimelineItemPresentationType.InlineParent }
                .sortedBy { it.referenceOrder }
                .map { it.referenceStatusId }
                .distinct()
                .filter { it != anchorId && (byId[it]?.sortId ?: Long.MIN_VALUE) < anchor.sortId }
        if (parentIds.isEmpty()) return existing
        val validIds =
            parentIds
                .chunked(500)
                .flatMap { database.statusDao().getVersions(it) }
                .map { it.id }
                .toSet()
        val inlineIds = parentIds.filter { it in validIds }
        if (inlineIds.isEmpty()) return existing
        val references = inlineIds.chunked(500).flatMap { dao.getPageStatusReferences(it) }.groupBy { it.statusId }
        val beforeIds =
            existing
                .filter { it.sortId < anchor.sortId }
                .sortedBy { it.sortId }
                .map { it.statusId }
                .filterNot { it in inlineIds } +
                inlineIds
        // Preserve legacy inline ancestors as preceding rows until the independent parent request succeeds.
        val before =
            beforeIds.mapIndexed { index, id ->
                val sortId = anchor.sortId - beforeIds.size + index
                byId[id]?.copy(sortId = sortId) ?: DbPagingTimeline(
                    pagingKey = pagingKey,
                    statusId = id,
                    sortId = sortId,
                    semanticReferenceSignature = TimelinePagingMapper.semanticReferenceSignature(references[id].orEmpty()),
                )
            }
        val inserted = before.filter { it.statusId !in byId }
        val moved = before.filter { byId[it.statusId]?.let { old -> old.sortId != it.sortId } == true }
        if (inserted.isNotEmpty()) dao.insertNew(inserted)
        if (moved.isNotEmpty()) dao.updateExisting(moved)
        return (existing.filterNot { it.statusId in beforeIds } + before).sortedBy { it.sortId }
    }
}
