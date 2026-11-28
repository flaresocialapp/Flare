package dev.dimension.flare.ui.screen.serviceselect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowLeft
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.presenter.login.LoginCookieSnapshot
import dev.dimension.flare.ui.presenter.login.LoginCookieValue
import dev.dimension.flare.ui.presenter.login.LoginEffect
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebContent
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewState
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun WebViewLoginScreen(
    request: LoginEffect.OpenWebCookieLogin,
    callback: suspend (LoginCookieSnapshot) -> Boolean,
    onBack: () -> Unit,
) {
    val initialOrigin = remember(request.url) { request.url.httpsOriginOrNull() }
    val state =
        remember(request.url) {
            WebViewState(WebContent.Url("about:blank")).apply {
                webSettings.desktopWebSettings.incognito = true
            }
        }
    val interceptor =
        remember {
            object : RequestInterceptor {
                override fun onInterceptUrlRequest(
                    request: WebRequest,
                    navigator: io.github.kdroidfilter.webview.web.WebViewNavigator,
                ): WebRequestInterceptResult =
                    if (!request.isForMainFrame || request.url.httpsOriginOrNull() != null) {
                        WebRequestInterceptResult.Allow
                    } else {
                        WebRequestInterceptResult.Reject
                    }
            }
        }
    val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
    val disposeWebView: () -> Unit = { state.webView?.nativeWebView?.clearAllCookies() }
    val currentOrigin = state.lastLoadedUrl?.httpsOriginOrNull() ?: initialOrigin.orEmpty()
    var cachedUserAgent by remember(request.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.webView, request.url) {
        cachedUserAgent = null
        val nativeWebView = state.webView?.nativeWebView ?: return@LaunchedEffect
        while (!nativeWebView.isReady()) {
            delay(50)
        }
        nativeWebView.clearAllCookies()
        navigator.loadUrl(request.url)
        cachedUserAgent = readDesktopUserAgent(nativeWebView)
    }
    LaunchedEffect(request) {
        while (true) {
            delay(2.seconds)
            val webView = state.webView?.nativeWebView ?: continue
            val userAgent =
                cachedUserAgent ?: if (webView.isReady()) {
                    readDesktopUserAgent(webView)?.also { cachedUserAgent = it }
                } else {
                    null
                }
            val rawHeader =
                listOfNotNull(state.lastLoadedUrl, request.url)
                    .distinct()
                    .flatMap { webView.getCookiesForUrl(it) }
                    .plus(webView.getCookies())
                    .distinctBy { listOf(it.domain, it.path, it.name) }
                    .joinToString("; ") { "${it.name}=${it.value}" }
                    .takeIf { it.isNotBlank() }
            val values =
                request.probes
                    .flatMap { probe ->
                        val expected = probe.names.toSet()
                        webView
                            .getCookiesForUrl(probe.url)
                            .filter { probe.captureAll || it.name in expected }
                            .map { cookie ->
                                LoginCookieValue(
                                    sourceUrl = probe.url,
                                    name = cookie.name,
                                    value = cookie.value,
                                    attributes =
                                        dev.dimension.flare.ui.presenter.login.LoginCookieAttributes(
                                            domain = cookie.domain,
                                            path = cookie.path,
                                            expiresAt = cookie.expiresDateMs,
                                            session = cookie.isSessionOnly,
                                            secure = cookie.isSecure,
                                            httpOnly = cookie.isHttpOnly,
                                            sameSite = cookie.sameSite?.name?.lowercase(),
                                        ),
                                )
                            }
                    }
            if (callback(LoginCookieSnapshot(values = values, rawHeader = rawHeader, userAgent = userAgent))) {
                onBack.invoke()
                break
            }
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(LocalWindowPadding.current),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = screenHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtleButton(onClick = onBack, iconOnly = true) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.ArrowLeft,
                    contentDescription = null,
                )
            }
            Text(
                text = currentOrigin,
                style = FluentTheme.typography.subtitle,
                color =
                    if (currentOrigin.isNotEmpty() && currentOrigin != initialOrigin) {
                        FluentTheme.colors.system.critical
                    } else {
                        FluentTheme.colors.text.text.primary
                    },
            )
        }
        WebView(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            navigator = navigator,
            onDispose = disposeWebView,
        )
    }
}

private suspend fun readDesktopUserAgent(webView: WryWebViewPanel): String? {
    if (!webView.isReady()) return null
    val raw =
        withTimeoutOrNull(1.seconds) {
            suspendCancellableCoroutine<String?> { continuation ->
                try {
                    webView.evaluateJavaScript("navigator.userAgent") { result ->
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                } catch (_: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    return raw?.let(::parseDesktopJavaScriptString)
}

internal fun parseDesktopJavaScriptString(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
    val decoded =
        if (trimmed.startsWith('"')) {
            runCatching { Json.decodeFromString<String>(trimmed) }.getOrNull() ?: return null
        } else {
            trimmed
        }
    return decoded.takeIf { it.isNotBlank() }
}

private fun String.httpsOriginOrNull(): String? =
    runCatching {
        val uri = URI(this)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.isNotBlank() && uri.rawUserInfo == null)
        "https://${uri.host}${if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"}"
    }.getOrNull()
