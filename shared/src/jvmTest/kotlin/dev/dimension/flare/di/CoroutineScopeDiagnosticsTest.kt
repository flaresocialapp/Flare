package dev.dimension.flare.di

import dev.dimension.flare.data.repository.DebugRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CoroutineScopeDiagnosticsTest {
    @Test
    fun failedChildRecordsTheCauseAndPreservesScopeCancellation() =
        runBlocking {
            DebugRepository.clear()
            val scope = coroutineScope()
            scope
                .launch(CoroutineExceptionHandler { _, _ -> }) {
                    error("background startup failure")
                }.join()
            scope.coroutineContext[Job]!!.join()
            withTimeout(10_000) {
                DebugRepository.messages.first { messages -> messages.any { "app.scope.completed active=false" in it } }
            }

            assertFalse(scope.isActive)
            val exported = DebugRepository.printToString()
            assertContains(exported, "app.scope.created active=true")
            assertContains(exported, "app.scope.completed active=false")
            assertContains(exported, "IllegalStateException: background startup failure")
            DebugRepository.clear()
        }
}
