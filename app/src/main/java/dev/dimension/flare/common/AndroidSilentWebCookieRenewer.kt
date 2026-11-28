package dev.dimension.flare.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.data.repository.SilentCookieRenewRequest
import dev.dimension.flare.data.repository.SilentCookieRenewResult
import dev.dimension.flare.data.repository.SilentRenewPollDecision
import dev.dimension.flare.data.repository.SilentSeedCookie
import dev.dimension.flare.data.repository.SilentWebCookieRenewer
import dev.dimension.flare.data.repository.evaluateSilentRenewPollState
import dev.dimension.flare.data.repository.isAllowedSilentRenewNavigationUrl
import dev.dimension.flare.data.repository.isSilentRenewSsoHost
import dev.dimension.flare.data.repository.shouldSeedSilentRenewCookie
import dev.dimension.flare.ui.presenter.login.LoginCookieProbe
import dev.dimension.flare.ui.presenter.login.parseRawCookieHeaderForProbe
import dev.dimension.flare.ui.screen.serviceselect.AndroidChallengeProxyDecision
import dev.dimension.flare.ui.screen.serviceselect.ChallengeProxyEndpoint
import dev.dimension.flare.ui.screen.serviceselect.evaluateAndroidChallengeProxy
import dev.dimension.flare.ui.screen.serviceselect.toChallengeProxyEndpoint
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.coroutines.resume

private const val DEFAULT_SETTLE_WINDOW_MS = 2_500L
private const val DEFAULT_POLL_INTERVAL_MS = 750L

internal fun silentWebViewProxySkipReason(
    accountConnection: ChallengeProxyEndpoint,
    globalConnection: ChallengeProxyEndpoint,
): String? =
    when (evaluateAndroidChallengeProxy(accountConnection = accountConnection, globalConnection = globalConnection)) {
        AndroidChallengeProxyDecision.Allowed -> null
        AndroidChallengeProxyDecision.AccountProxyMismatch -> "account-proxy-mismatch"
    }

internal fun formatAndroidSilentSeedCookie(cookie: SilentSeedCookie): Pair<String, String>? {
    val name = cookie.name.trim()
    val domain =
        cookie.domain
            .trim()
            .trimStart('.')
            .lowercase()
    val path = cookie.path.trim().ifEmpty { "/" }
    if (name.isEmpty() || domain.isEmpty() || !path.startsWith('/')) return null
    if (name.any { it.isISOControl() || it == ';' || it == '=' || it.isWhitespace() }) return null
    if (cookie.value.any { it.isISOControl() || it == ';' }) return null
    if (domain.any { it.isISOControl() || it == ';' || it == '/' || it.isWhitespace() }) return null
    if (path.any { it.isISOControl() || it == ';' }) return null

    val header =
        buildString {
            append(name)
            append('=')
            append(cookie.value)
            if (!cookie.hostOnly) {
                append("; Domain=.")
                append(domain)
            }
            append("; Path=")
            append(path)
            if (cookie.secure) {
                append("; Secure")
            }
            if (cookie.httpOnly) {
                append("; HttpOnly")
            }
        }
    return "https://$domain" to header
}

internal fun normalizeHarvestedAndroidSeedCookies(rawCookies: List<SilentSeedCookie>): List<SilentSeedCookie> {
    data class ExactKey(
        val name: String,
        val domain: String,
        val path: String,
    )

    data class SharedRootKey(
        val name: String,
        val value: String,
        val path: String,
        val rootDomain: String,
    )

    val lastIndexByExact = HashMap<ExactKey, Int>()
    rawCookies.forEachIndexed { index, cookie ->
        val normalizedDomain =
            cookie.domain
                .trim()
                .trimStart('.')
                .lowercase()
        lastIndexByExact[ExactKey(cookie.name.trim(), normalizedDomain, cookie.path)] = index
    }
    val deduped =
        rawCookies.filterIndexed { index, cookie ->
            val normalizedDomain =
                cookie.domain
                    .trim()
                    .trimStart('.')
                    .lowercase()
            lastIndexByExact[ExactKey(cookie.name.trim(), normalizedDomain, cookie.path)] == index
        }

    val trustedRoots = listOf("weibo.cn", "weibo.com", "sina.com.cn")
    val hostSetByRootKey = HashMap<SharedRootKey, MutableSet<String>>()
    for (cookie in deduped) {
        if (!cookie.hostOnly) continue
        val domain =
            cookie.domain
                .trim()
                .trimStart('.')
                .lowercase()
        val root = trustedRoots.firstOrNull { domain == it || domain.endsWith(".$it") } ?: continue
        hostSetByRootKey
            .getOrPut(SharedRootKey(cookie.name.trim(), cookie.value, cookie.path, root)) { LinkedHashSet() }
            .add(domain)
    }

    val emittedRoots = HashSet<SharedRootKey>()
    val result = ArrayList<SilentSeedCookie>(deduped.size)
    for (cookie in deduped) {
        if (cookie.hostOnly) {
            val domain =
                cookie.domain
                    .trim()
                    .trimStart('.')
                    .lowercase()
            val root = trustedRoots.firstOrNull { domain == it || domain.endsWith(".$it") }
            if (root != null) {
                val key = SharedRootKey(cookie.name.trim(), cookie.value, cookie.path, root)
                if ((hostSetByRootKey[key]?.size ?: 0) >= 2) {
                    if (emittedRoots.add(key)) {
                        result += cookie.copy(domain = root, hostOnly = false)
                    }
                    continue
                }
            }
        }
        result += cookie
    }
    return result
}

@Single
internal fun provideAndroidSilentWebCookieRenewer(
    @Provided context: Context,
): SilentWebCookieRenewer = AndroidSilentWebCookieRenewer(context = context)

internal class AndroidSilentWebCookieRenewer(
    private val context: Context,
    internal val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    internal val settleWindowMs: Long = DEFAULT_SETTLE_WINDOW_MS,
) : SilentWebCookieRenewer {
    override suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult {
        if (!isAllowedSilentRenewNavigationUrl(request.startUrl, request.allowedHosts, request.allowedHostSuffixes)) {
            return SilentCookieRenewResult.Skipped("invalid-start-url")
        }

        val sessionResult =
            AndroidWebViewCookieLock.tryWithBackgroundSession {
                executeProtectedRenewal(request)
            }
        return sessionResult ?: SilentCookieRenewResult.Skipped("interactive-webview-active")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun executeProtectedRenewal(request: SilentCookieRenewRequest): SilentCookieRenewResult {
        val accountEndpoint =
            try {
                ProxyNetwork.snapshot(request.proxyRef).connection.toChallengeProxyEndpoint()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return SilentCookieRenewResult.Skipped("proxy-snapshot-failed")
            }
        val globalEndpoint =
            try {
                ProxyNetwork.snapshot(ProxyRef.Global).connection.toChallengeProxyEndpoint()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return SilentCookieRenewResult.Skipped("proxy-snapshot-failed")
            }
        silentWebViewProxySkipReason(accountEndpoint, globalEndpoint)?.let { reason ->
            return SilentCookieRenewResult.Skipped(reason)
        }

        val proxySnapshot =
            try {
                WebViewProxy.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (_: UnsupportedOperationException) {
                return SilentCookieRenewResult.Skipped("proxy-override-unsupported")
            } catch (_: Throwable) {
                return SilentCookieRenewResult.Skipped("proxy-prepare-failed")
            }
        if (!proxySnapshot.isValid) {
            return SilentCookieRenewResult.Skipped("proxy-invalid")
        }

        return withContext(Dispatchers.Main) {
            var webView: WebView? = null
            var unsubscribeProxy: (() -> Unit)? = null
            var disposed = false
            try {
                clearCookieAndWebStorageSuspend()
                seedCookiesSuspend(request.seedCookies, request.previousSubCookie)

                val triggers = Channel<Unit>(Channel.CONFLATED)
                var currentUrl: String? = null
                var pageLoading = true
                var visitedPassportOrVisitor = false
                var resolvedUserAgent: String? = null

                val createdView =
                    WebView(context.applicationContext).apply {
                        clearCache(true)
                        with(settings) {
                            if (!request.userAgent.isNullOrBlank()) {
                                userAgentString = request.userAgent
                            }
                            resolvedUserAgent = userAgentString
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            javaScriptCanOpenWindowsAutomatically = false
                            allowContentAccess = false
                            allowFileAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            blockNetworkLoads = !proxySnapshot.isValid
                        }
                        layout(0, 0, 390, 844)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    if (!proxySnapshot.isValid) return
                                    pageLoading = true
                                    currentUrl = url
                                    if (isSilentRenewSsoHost(url?.let { runCatching { Url(it).host }.getOrNull() })) {
                                        visitedPassportOrVisitor = true
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    navRequest: WebResourceRequest?,
                                ): Boolean {
                                    if (!proxySnapshot.isValid) return true
                                    val targetUri = navRequest?.url ?: return true
                                    if (navRequest.isForMainFrame) {
                                        val allowed =
                                            isAllowedSilentRenewNavigationUrl(
                                                url = targetUri.toString(),
                                                allowedHosts = request.allowedHosts,
                                                allowedHostSuffixes = request.allowedHostSuffixes,
                                            )
                                        if (allowed && isSilentRenewSsoHost(targetUri.host)) {
                                            visitedPassportOrVisitor = true
                                        }
                                        return !allowed
                                    }
                                    if (!targetUri.isStrictHttps()) return true
                                    if (isSilentRenewSsoHost(targetUri.host)) {
                                        visitedPassportOrVisitor = true
                                    }
                                    return false
                                }

                                @Deprecated("Deprecated in Android")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?,
                                ): Boolean {
                                    if (!proxySnapshot.isValid || url == null) return true
                                    val allowed =
                                        isAllowedSilentRenewNavigationUrl(
                                            url = url,
                                            allowedHosts = request.allowedHosts,
                                            allowedHostSuffixes = request.allowedHostSuffixes,
                                        )
                                    if (allowed && isSilentRenewSsoHost(runCatching { Url(url).host }.getOrNull())) {
                                        visitedPassportOrVisitor = true
                                    }
                                    return !allowed
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    if (!proxySnapshot.isValid) return
                                    pageLoading = false
                                    currentUrl = url
                                    if (isSilentRenewSsoHost(url?.let { runCatching { Url(it).host }.getOrNull() })) {
                                        visitedPassportOrVisitor = true
                                    }
                                    triggers.trySend(Unit)
                                }
                            }
                    }
                webView = createdView
                unsubscribeProxy =
                    proxySnapshot.onInvalidated {
                        createdView.post {
                            if (disposed) return@post
                            createdView.stopLoading()
                            createdView.settings.blockNetworkLoads = true
                            createdView.loadUrl("about:blank")
                            triggers.trySend(Unit)
                        }
                    }

                createdView.loadUrl(request.startUrl)

                val timedResult =
                    withTimeoutOrNull(request.timeoutMillis) {
                        coroutineScope {
                            val ticker =
                                launch {
                                    while (isActive && proxySnapshot.isValid) {
                                        delay(pollIntervalMs)
                                        triggers.trySend(Unit)
                                    }
                                }
                            try {
                                var interactiveEnteredAtMs: Long? = null
                                val manager = CookieManager.getInstance()
                                for (unit in triggers) {
                                    if (!proxySnapshot.isValid) {
                                        return@coroutineScope SilentCookieRenewResult.Skipped("proxy-invalidated")
                                    }
                                    val mWeiboProbe = LoginCookieProbe(url = "https://m.weibo.cn/", captureAll = true)
                                    val mWeiboCookies =
                                        parseRawCookieHeaderForProbe(
                                            rawHeader = manager.getCookie(mWeiboProbe.url),
                                            probe = mWeiboProbe,
                                        ).let { pairs ->
                                            val latest = LinkedHashMap<String, String>()
                                            for (pair in pairs) {
                                                latest[pair.name] = pair.value
                                            }
                                            latest
                                        }
                                    val nowMs = System.currentTimeMillis()
                                    when (
                                        val decision =
                                            evaluateSilentRenewPollState(
                                                loadedUrl = currentUrl ?: createdView.url,
                                                isLoading = pageLoading,
                                                mWeiboCookies = mWeiboCookies,
                                                previousSubCookie = request.previousSubCookie,
                                                visitedPassportOrVisitor = visitedPassportOrVisitor,
                                                interactiveLoginEnteredAtMs = interactiveEnteredAtMs,
                                                nowMs = nowMs,
                                                settleWindowMs = settleWindowMs,
                                            )
                                    ) {
                                        SilentRenewPollDecision.Completed -> {
                                            val harvested = harvestProbedCookies(manager, request.probeUrls)
                                            return@coroutineScope SilentCookieRenewResult.Renewed(
                                                cookies = harvested,
                                                userAgent = resolvedUserAgent,
                                            )
                                        }

                                        SilentRenewPollDecision.InteractiveLoginRequired -> {
                                            return@coroutineScope SilentCookieRenewResult.InteractiveLoginRequired
                                        }

                                        is SilentRenewPollDecision.Pending -> {
                                            interactiveEnteredAtMs = decision.interactiveLoginEnteredAtMs
                                        }
                                    }
                                }
                                SilentCookieRenewResult.Skipped("poll-channel-closed")
                            } finally {
                                ticker.cancel()
                            }
                        }
                    }

                timedResult ?: SilentCookieRenewResult.Skipped("timeout")
            } finally {
                withContext(NonCancellable) {
                    disposed = true
                    unsubscribeProxy?.invoke()
                    webView?.let { view ->
                        runCatching {
                            view.stopLoading()
                            view.clearHistory()
                            view.clearCache(true)
                            view.destroy()
                        }
                    }
                    runCatching { clearCookieAndWebStorageSuspend() }
                }
            }
        }
    }

    private fun harvestProbedCookies(
        manager: CookieManager,
        probeUrls: List<String>,
    ): List<SilentSeedCookie> =
        normalizeHarvestedAndroidSeedCookies(
            probeUrls.flatMap { probeUrl ->
                val host =
                    runCatching { Url(probeUrl).host.lowercase() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@flatMap emptyList()
                val probe = LoginCookieProbe(url = probeUrl, captureAll = true)
                parseRawCookieHeaderForProbe(manager.getCookie(probeUrl), probe).map { item ->
                    SilentSeedCookie(
                        name = item.name,
                        value = item.value,
                        domain = host,
                        hostOnly = true,
                        path = "/",
                        secure = true,
                    )
                }
            },
        )

    private suspend fun seedCookiesSuspend(
        seedCookies: List<SilentSeedCookie>,
        previousSubCookie: String?,
    ) {
        val manager = CookieManager.getInstance()
        for (cookie in seedCookies) {
            if (!shouldSeedSilentRenewCookie(cookie, previousSubCookie)) continue
            val (originUrl, header) = formatAndroidSilentSeedCookie(cookie) ?: continue
            suspendCancellableCoroutine { continuation ->
                manager.setCookie(originUrl, header) { continuation.resume(Unit) }
            }
        }
        manager.flush()
    }

    private suspend fun clearCookieAndWebStorageSuspend() {
        WebStorage.getInstance().deleteAllData()
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies { continuation.resume(Unit) }
        }
        manager.flush()
    }
}

private fun Uri.isStrictHttps(): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        !host.isNullOrBlank() &&
        userInfo == null &&
        (port == -1 || port == 443)
