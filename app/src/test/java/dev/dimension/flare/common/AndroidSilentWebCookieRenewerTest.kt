package dev.dimension.flare.common

import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyProtocol
import dev.dimension.flare.data.repository.SilentRenewPollDecision
import dev.dimension.flare.data.repository.SilentSeedCookie
import dev.dimension.flare.data.repository.evaluateSilentRenewPollState
import dev.dimension.flare.data.repository.shouldSeedSilentRenewCookie
import dev.dimension.flare.ui.screen.serviceselect.ChallengeProxyEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSilentWebCookieRenewerTest {
    @Test
    fun arbitratorSkipsBackgroundWhenInteractiveIsActiveAndAwaitsInFlightCleanupOnPreempt(): Unit =
        runBlocking {
            val arbitrator = WebViewCookieArbitrator()
            val events = mutableListOf<String>()

            val token = arbitrator.beginInteractive()
            val whileActive =
                arbitrator.tryWithBackgroundSession {
                    events += "unexpected-bg"
                    "ran"
                }
            assertNull(whileActive)
            token.close()

            val bgStarted = CompletableDeferred<Unit>()
            val cleanupAllowFinish = CompletableDeferred<Unit>()
            val bgDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    arbitrator.tryWithBackgroundSession {
                        events += "bg-start"
                        bgStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupAllowFinish.await()
                                events += "bg-cleanup-done"
                            }
                        }
                    }
                }

            bgStarted.await()
            val preemptToken = arbitrator.beginInteractive()
            val interactiveWaiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    arbitrator.awaitBackgroundIdle()
                    events += "interactive-ready"
                }
            delay(10)
            assertEquals(listOf("bg-start"), events)

            cleanupAllowFinish.complete(Unit)
            interactiveWaiter.await()
            assertNull(bgDeferred.await())
            assertEquals(listOf("bg-start", "bg-cleanup-done", "interactive-ready"), events)
            preemptToken.close()
        }

    @Test
    fun proxyCheckSkipsSilentRenewalWhenAccountProxyDiffersFromGlobalProxy() {
        assertEquals(
            "account-proxy-mismatch",
            silentWebViewProxySkipReason(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.SYSTEM),
            ),
        )
        assertNull(
            silentWebViewProxySkipReason(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
            ),
        )
    }

    @Test
    fun formatAndroidSilentSeedCookiePreservesDomainVsHostOnlyAndHttpOnlyAttributes() {
        val domainCookie =
            formatAndroidSilentSeedCookie(
                SilentSeedCookie(
                    name = "ALC",
                    value = "v==",
                    domain = "sina.com.cn",
                    hostOnly = false,
                    path = "/",
                    secure = true,
                    httpOnly = true,
                ),
            )
        assertEquals(
            "https://sina.com.cn" to "ALC=v==; Domain=.sina.com.cn; Path=/; Secure; HttpOnly",
            domainCookie,
        )

        val hostCookie =
            formatAndroidSilentSeedCookie(
                SilentSeedCookie(
                    name = "SRF",
                    value = "srf-val",
                    domain = "visitor.passport.weibo.cn",
                    hostOnly = true,
                    path = "/",
                    secure = true,
                    httpOnly = false,
                ),
            )
        assertEquals(
            "https://visitor.passport.weibo.cn" to "SRF=srf-val; Path=/; Secure",
            hostCookie,
        )

        assertNull(
            formatAndroidSilentSeedCookie(
                SilentSeedCookie(
                    name = "BAD;NAME",
                    value = "x",
                    domain = "weibo.cn",
                    hostOnly = false,
                    path = "/",
                ),
            ),
        )
    }

    @Test
    fun pollStateDetectsNewSubCompletionAndInteractiveLoginSettleTimeout() {
        val pending =
            evaluateSilentRenewPollState(
                loadedUrl = "https://visitor.passport.weibo.cn/visitor/visitor?a=enter",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "1", "SUB" to "old-sub"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = null,
                nowMs = 1_000L,
                settleWindowMs = 2_500L,
            )
        assertEquals(SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = null), pending)

        val completedNewSub =
            evaluateSilentRenewPollState(
                loadedUrl = "https://passport.weibo.cn/sso/complete",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "1", "SUB" to "new-sub"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = null,
                nowMs = 2_000L,
                settleWindowMs = 2_500L,
            )
        assertEquals(SilentRenewPollDecision.Completed, completedNewSub)

        val enteredSignIn =
            evaluateSilentRenewPollState(
                loadedUrl = "https://passport.weibo.com/sso/signin?entry=wapsso",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "0"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = null,
                nowMs = 3_000L,
                settleWindowMs = 2_500L,
            )
        assertEquals(SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = 3_000L), enteredSignIn)

        val settledSignIn =
            evaluateSilentRenewPollState(
                loadedUrl = "https://passport.weibo.com/sso/signin?entry=wapsso",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "0"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = 3_000L,
                nowMs = 5_600L,
                settleWindowMs = 2_500L,
            )
        assertTrue(settledSignIn is SilentRenewPollDecision.InteractiveLoginRequired)

        // While still loading /sso/signin over a slow proxy, do not start the interactive-login settle clock prematurely.
        val stillLoadingSignIn =
            evaluateSilentRenewPollState(
                loadedUrl = "https://passport.weibo.com/sso/signin?entry=wapsso",
                isLoading = true,
                mWeiboCookies = mapOf("MLOGIN" to "0"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = null,
                nowMs = 6_000L,
                settleWindowMs = 2_500L,
            )
        assertEquals(SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = null), stillLoadingSignIn)

        // Settling on m.weibo.cn with MLOGIN=0 after visiting Visitor/Passport means SSO expired into guest mode -> InteractiveLoginRequired.
        val enteredGuestHome =
            evaluateSilentRenewPollState(
                loadedUrl = "https://m.weibo.cn/",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "0", "SUB" to "guest-sub"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = null,
                nowMs = 10_000L,
                settleWindowMs = 2_500L,
            )
        assertEquals(SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = 10_000L), enteredGuestHome)
        val settledGuestHome =
            evaluateSilentRenewPollState(
                loadedUrl = "https://m.weibo.cn/",
                isLoading = false,
                mWeiboCookies = mapOf("MLOGIN" to "0", "SUB" to "guest-sub"),
                previousSubCookie = "old-sub",
                visitedPassportOrVisitor = true,
                interactiveLoginEnteredAtMs = 10_000L,
                nowMs = 12_600L,
                settleWindowMs = 2_500L,
            )
        assertTrue(settledGuestHome is SilentRenewPollDecision.InteractiveLoginRequired)
    }

    @Test
    fun arbitratorReturnsNullEvenWhenInteractiveClosesBeforeBackgroundCleanupCompletes(): Unit =
        runBlocking {
            val arbitrator = WebViewCookieArbitrator()
            val bgStarted = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()

            val bgDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    arbitrator.tryWithBackgroundSession {
                        bgStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupRelease.await()
                            }
                        }
                    }
                }

            bgStarted.await()
            val interactiveToken = arbitrator.beginInteractive()
            // Interactive UI closes rapidly while background NonCancellable cleanup is still in flight.
            interactiveToken.close()
            cleanupRelease.complete(Unit)

            assertNull(bgDeferred.await())
        }

    @Test
    fun promoteMultiHostAndroidSeedsAndFilterKnownExpiredSub() {
        val raw =
            listOf(
                SilentSeedCookie("SUB", "cn-sub", "m.weibo.cn", hostOnly = true, path = "/"),
                SilentSeedCookie("SUB", "cn-sub", "passport.weibo.cn", hostOnly = true, path = "/"),
                SilentSeedCookie("SRF", "srf-1", "visitor.passport.weibo.cn", hostOnly = true, path = "/"),
            )
        val normalized = normalizeHarvestedAndroidSeedCookies(raw)
        assertEquals(
            listOf(
                SilentSeedCookie("SUB", "cn-sub", "weibo.cn", hostOnly = false, path = "/"),
                SilentSeedCookie("SRF", "srf-1", "visitor.passport.weibo.cn", hostOnly = true, path = "/"),
            ),
            normalized,
        )

        val staleCnSub = SilentSeedCookie("SUB", "dead-sub", "weibo.cn", hostOnly = false, path = "/")
        val validComSub = SilentSeedCookie("SUB", "com-sub", "weibo.com", hostOnly = false, path = "/")
        assertEquals(false, shouldSeedSilentRenewCookie(staleCnSub, previousSubCookie = "dead-sub"))
        assertEquals(true, shouldSeedSilentRenewCookie(validComSub, previousSubCookie = "dead-sub"))
    }
}
