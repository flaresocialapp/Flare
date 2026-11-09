package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.mutableStateOf
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.home.TimelineState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TimelinePageRefreshTest : RobolectricTest() {
    @Test
    fun invalidationDuringInitialLoadWaitsForTheLatestRefreshableState() =
        runTest {
            val revision = mutableStateOf(0L)
            val list = mutableStateOf<PagingState<UiTimelineV2>>(PagingState.Loading())
            var refreshes = 0
            backgroundScope.launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    TimelinePageRefreshEffect(true, revision.value, state(list.value) { refreshes++ })
                }.collect {}
            }
            runCurrent()
            revision.value = 1
            runCurrent()
            revision.value = 2
            runCurrent()
            assertEquals(0, refreshes)
            list.value = ready()
            runCurrent()
            assertEquals(1, refreshes)
            runCurrent()
            assertEquals(1, refreshes)
        }

    @Test
    fun hiddenPageCoalescesInvalidationsUntilItIsVisibleAndReady() =
        runTest {
            val visible = mutableStateOf(false)
            val revision = mutableStateOf(0L)
            val list = mutableStateOf<PagingState<UiTimelineV2>>(ready())
            var refreshes = 0
            backgroundScope.launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    TimelinePageRefreshEffect(visible.value, revision.value, state(list.value) { refreshes++ })
                }.collect {}
            }
            runCurrent()
            revision.value = 1
            runCurrent()
            revision.value = 2
            runCurrent()
            assertEquals(0, refreshes)
            list.value = PagingState.Loading()
            visible.value = true
            runCurrent()
            assertEquals(0, refreshes)
            list.value = ready()
            runCurrent()
            assertEquals(1, refreshes)
            visible.value = false
            runCurrent()
            visible.value = true
            runCurrent()
            assertEquals(1, refreshes)
        }

    @Test
    fun invalidationDuringRefreshIsRetriedAfterTheInFlightRequestFinishes() =
        runTest {
            val revision = mutableStateOf(0L)
            val list = mutableStateOf<PagingState<UiTimelineV2>>(ready())
            val inFlight = CompletableDeferred<Unit>()
            var refreshes = 0
            backgroundScope.launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    TimelinePageRefreshEffect(
                        true,
                        revision.value,
                        state(list.value) {
                            refreshes++
                            if (refreshes == 1) {
                                list.value = ready(refreshing = true)
                                inFlight.await()
                            }
                        },
                    )
                }.collect {}
            }
            runCurrent()
            revision.value = 1
            runCurrent()
            assertEquals(1, refreshes)
            revision.value = 2
            runCurrent()
            assertEquals(1, refreshes)
            list.value = ready()
            inFlight.complete(Unit)
            runCurrent()
            assertEquals(2, refreshes)
            runCurrent()
            assertEquals(2, refreshes)
        }

    private fun ready(refreshing: Boolean = false): PagingState<UiTimelineV2> =
        PagingState.Success.ImmutableSuccess(persistentListOf(), isRefreshing = refreshing)

    private fun state(
        list: PagingState<UiTimelineV2>,
        onRefresh: suspend () -> Unit,
    ) = object : TimelineState {
        override val listState = list

        override fun refreshAsync() = error("Unexpected asynchronous refresh")

        override suspend fun refresh() {
            // Match TimelinePresenter: loading states have no refresh action.
            if (listState !is PagingState.Loading) onRefresh()
        }
    }
}
