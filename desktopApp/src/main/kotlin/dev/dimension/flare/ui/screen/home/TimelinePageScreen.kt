package dev.dimension.flare.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.RegisterTabCallback
import dev.dimension.flare.Res
import dev.dimension.flare.refresh
import dev.dimension.flare.ui.component.FlareScrollBar
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.status.LazyStatusVerticalStaggeredGrid
import dev.dimension.flare.ui.component.status.status
import dev.dimension.flare.ui.presenter.TimelinePagePresenter
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TimelinePageScreen(page: DeeplinkRoute.Timeline.Page) {
    val state by producePresenter(page.toString()) { remember(page) { TimelinePagePresenter(page) }.invoke() }
    val latest by rememberUpdatedState(state)
    DisposableEffect(Unit) {
        latest.setActive(true)
        onDispose { latest.setActive(false) }
    }
    val list = rememberLazyStaggeredGridState()
    RegisterTabCallback(list, onRefresh = state::refreshSync)
    CompositionLocalProvider(
        LocalTimelineAppearance provides TimelinePagePresenter.appearance(LocalTimelineAppearance.current),
    ) {
        Column(Modifier.fillMaxSize().padding(LocalWindowPadding.current)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                dev.dimension.flare.ui.component
                    .Text(text = page.title)
                SubtleButton(onClick = state::refreshSync, disabled = state.isRefreshing) {
                    Text(stringResource(Res.string.refresh))
                }
            }
            FlareScrollBar(list) {
                LazyStatusVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(1),
                    state = list,
                ) { status(state.listState) }
            }
        }
    }
}
