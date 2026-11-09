package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.isLoading
import dev.dimension.flare.common.isRefreshing
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.home.TimelinePresenterOptions
import dev.dimension.flare.ui.presenter.home.TimelineState
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
public class TimelinePagePresenter(
    private val page: DeeplinkRoute.Timeline.Page,
) : PresenterBase<TimelinePagePresenter.State>() {
    private val accounts: AccountRepository by koinInject()
    private val active = MutableStateFlow(true)
    private val sources by lazy {
        val exists =
            (page.accountType as? AccountType.Specific)?.let { account ->
                accounts.getFlow(account.accountKey).map { it.takeSuccess() != null }.distinctUntilChanged()
            } ?: flowOf(true)
        val services =
            (page.accountType as? AccountType.GuestHost)?.let { guest ->
                accounts.platformRegistry.platforms
                    .map { platforms -> platforms.firstOrNull { it.platformId == page.platformId } }
                    .distinctUntilChanged()
                    .map { platform -> platform?.guestDataSource(guest.host, Locale.language) ?: UnavailableMicroblogDataSource }
            } ?: accountServiceFlow(page.accountType, accounts)
        services
            .combine(exists) { service, present ->
                service.capabilities.timelinePage?.takeIf {
                    present && it.pluginId == page.pluginId && it.packageHash == page.packageHash
                }
            }.distinctUntilChanged()
    }
    private val timeline by lazy {
        TimelinePresenter(
            loader =
                sources.map { source ->
                    source?.timelinePage(page.pageId, page.parameters) ?: object : RemoteLoader<UiTimelineV2> {
                        override suspend fun load(
                            pageSize: Int,
                            request: PagingRequest,
                        ): PagingResult<UiTimelineV2> = error("Timeline page is unavailable for this account or plugin version")
                    }
                },
            options = TimelinePresenterOptions(allowLongTextTranslationDisplay = { true }),
        )
    }

    public companion object {
        public fun appearance(base: TimelineAppearance): TimelineAppearance = base.copy(lineLimit = Int.MAX_VALUE)
    }

    public interface State : TimelineItemPresenter.State {
        public fun setActive(active: Boolean)
    }

    @Composable
    override fun body(): State {
        val state = timeline.body()
        val scope = rememberCoroutineScope()
        val visible by active.collectAsState()
        val revision by remember { sources.flatMapLatest { it?.revision ?: flowOf(0L) } }.collectAsState(0L)
        TimelinePageRefreshEffect(visible, revision, state)
        return object : State {
            override val listState = state.listState
            override val isRefreshing = state.listState.isRefreshing

            override fun setActive(active: Boolean) {
                this@TimelinePagePresenter.active.value = active
            }

            override fun refreshSync() {
                scope.launch { state.refresh() }
            }

            override suspend fun refreshSuspend() {
                state.refresh()
            }
        }
    }
}

@Composable
internal fun TimelinePageRefreshEffect(
    visible: Boolean,
    revision: Long,
    state: TimelineState,
) {
    var refreshedRevision by remember { mutableLongStateOf(0L) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(visible, revision) {
        if (visible && revision != refreshedRevision) {
            // A loading state cannot refresh; keep invalidation pending until its read finishes.
            snapshotFlow { currentState.listState }.first { !it.isLoading && !it.isRefreshing }
            currentState.refresh()
            refreshedRevision = revision
        }
    }
}
