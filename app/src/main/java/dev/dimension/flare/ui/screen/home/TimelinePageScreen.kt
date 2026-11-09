package dev.dimension.flare.ui.screen.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.RefreshContainer
import dev.dimension.flare.ui.component.Text
import dev.dimension.flare.ui.component.status.LazyStatusVerticalStaggeredGrid
import dev.dimension.flare.ui.component.status.status
import dev.dimension.flare.ui.presenter.TimelinePagePresenter
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.route.DeeplinkRoute
import moe.tlaster.precompose.molecule.producePresenter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelinePageScreen(
    page: DeeplinkRoute.Timeline.Page,
    onBack: () -> Unit,
) {
    val state by producePresenter(page.toString()) { remember(page) { TimelinePagePresenter(page) }.invoke() }
    val latest by rememberUpdatedState(state)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, _ ->
                latest.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
        owner.lifecycle.addObserver(observer)
        latest.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            latest.setActive(false)
        }
    }
    FlareScaffold(
        topBar = { FlareTopAppBar(title = { Text(text = page.title) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        CompositionLocalProvider(
            LocalTimelineAppearance provides TimelinePagePresenter.appearance(LocalTimelineAppearance.current),
        ) {
            RefreshContainer(
                onRefresh = state::refreshSync,
                isRefreshing = state.isRefreshing,
                indicatorPadding = padding,
                modifier = Modifier.fillMaxSize(),
                content = {
                    LazyStatusVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(1),
                        contentPadding = padding,
                        modifier = Modifier.fillMaxSize(),
                    ) { status(state.listState) }
                },
            )
        }
    }
}
