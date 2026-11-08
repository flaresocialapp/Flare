package dev.dimension.flare.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DebugRepositoryTest {
    @Test
    fun startupDiagnosticsKeepTheCauseWithoutEnablingNetworkLogs() {
        DebugRepository.setEnabled(false)
        DebugRepository.clear()
        val cancellation = CancellationException("scope cancelled").apply { initCause(IllegalStateException("startup failure")) }

        DebugRepository.diagnostic("app.scope.completed token=private-token", cancellation)

        val exported = DebugRepository.printToString()
        assertContains(exported, "[DEBUG-compose]")
        assertContains(exported, "app.scope.completed token=[REDACTED]")
        assertContains(exported, "CancellationException: scope cancelled")
        assertContains(exported, "Caused by: java.lang.IllegalStateException: startup failure")
        assertFalse(exported.contains("private-token"))
        DebugRepository.clear()
    }

    @Test
    fun concurrentNetworkMessagesSurviveExport() =
        runBlocking {
            val marker = "concurrent-${System.nanoTime()}"
            DebugRepository.setEnabled(true)
            try {
                DebugRepository.log("$marker ready")
                withTimeout(10_000) {
                    DebugRepository.messages.first { messages -> messages.any { "$marker ready" in it } }
                }
                DebugRepository.clear()
                withTimeout(10_000) { DebugRepository.messages.first { it.isEmpty() } }

                repeat(128) { index ->
                    DebugRepository.log("$marker message=$index ${"x".repeat(3_000)}")
                }
                val complete =
                    withTimeoutOrNull(10_000) {
                        DebugRepository.messages.first { messages -> messages.count { marker in it } == 128 }
                    }

                assertNotNull(complete, "Concurrent network messages must not overwrite each other")
                val exported = DebugRepository.printToString()
                repeat(128) { index -> assertContains(exported, "$marker message=$index ") }
            } finally {
                DebugRepository.setEnabled(false)
                DebugRepository.clear()
            }
        }
}
