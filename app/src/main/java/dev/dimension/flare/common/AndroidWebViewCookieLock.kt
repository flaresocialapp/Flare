package dev.dimension.flare.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class WebViewCookieArbitrator {
    private val stateLock = Any()
    private var interactiveCount = 0
    private var activeBackgroundJob: Job? = null
    private val preemptedJobs: MutableSet<Job> = Collections.newSetFromMap(IdentityHashMap())
    private val backgroundMutex = Mutex()

    fun beginInteractive(): AutoCloseable {
        val jobToCancel =
            synchronized(stateLock) {
                interactiveCount++
                activeBackgroundJob?.also { preemptedJobs.add(it) }
            }
        jobToCancel?.cancel()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(stateLock) {
                    interactiveCount = (interactiveCount - 1).coerceAtLeast(0)
                }
            }
        }
    }

    suspend fun awaitBackgroundIdle() {
        backgroundMutex.withLock {
            // Wait until any pre-empted background session finishes its NonCancellable cleanup.
        }
    }

    suspend fun <T> tryWithBackgroundSession(block: suspend () -> T): T? {
        synchronized(stateLock) {
            if (interactiveCount > 0) return null
        }
        return backgroundMutex.withLock {
            synchronized(stateLock) {
                if (interactiveCount > 0) return@withLock null
            }
            var sessionJob: Job? = null
            try {
                coroutineScope {
                    val myJob = coroutineContext[Job]
                    sessionJob = myJob
                    val preemptedImmediately =
                        synchronized(stateLock) {
                            if (interactiveCount > 0) {
                                true
                            } else {
                                activeBackgroundJob = myJob
                                false
                            }
                        }
                    if (preemptedImmediately) {
                        return@coroutineScope null
                    }
                    try {
                        block()
                    } finally {
                        synchronized(stateLock) {
                            if (activeBackgroundJob === myJob) {
                                activeBackgroundJob = null
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Propagate outer caller cancellation; swallow only interactive preemption.
                currentCoroutineContext().ensureActive()
                val wasPreempted =
                    synchronized(stateLock) {
                        val removed = sessionJob != null && preemptedJobs.remove(sessionJob)
                        removed || interactiveCount > 0
                    }
                if (wasPreempted) {
                    null
                } else {
                    synchronized(stateLock) {
                        sessionJob?.let { preemptedJobs.remove(it) }
                    }
                    throw cancelled
                }
            } finally {
                synchronized(stateLock) {
                    sessionJob?.let { preemptedJobs.remove(it) }
                }
            }
        }
    }
}

internal object AndroidWebViewCookieLock {
    private val arbitrator = WebViewCookieArbitrator()

    fun beginInteractive(): AutoCloseable = arbitrator.beginInteractive()

    suspend fun awaitBackgroundIdle() = arbitrator.awaitBackgroundIdle()

    suspend fun <T> tryWithBackgroundSession(block: suspend () -> T): T? = arbitrator.tryWithBackgroundSession(block)
}
