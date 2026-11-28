package dev.dimension.flare.ui.screen.serviceselect

import android.net.Uri
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewState
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.CircleExclamation
import dev.dimension.flare.R
import dev.dimension.flare.common.AndroidWebViewCookieLock
import dev.dimension.flare.common.InAppNotification
import dev.dimension.flare.common.Message
import dev.dimension.flare.common.WebViewProxy
import dev.dimension.flare.data.model.ProxyConnection
import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyProtocol
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.data.network.ProxySnapshot
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeCookieValue
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.repository.SessionChallengePromptBus
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.LocalSessionChallengeLauncher
import dev.dimension.flare.ui.presenter.login.ReloginTarget
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatformTools
import java.net.URI
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import android.webkit.WebView as AndroidWebView

internal val LocalAndroidChallengeReloginHandler =
    staticCompositionLocalOf<MutableState<((ReloginTarget) -> Unit)?>?> { null }

internal sealed interface AndroidChallengeProxyDecision {
    data object Allowed : AndroidChallengeProxyDecision

    data object AccountProxyMismatch : AndroidChallengeProxyDecision
}

internal data class ChallengeProxyEndpoint(
    val mode: ProxyMode,
    val protocol: ProxyProtocol? = null,
    val host: String? = null,
    val port: Int? = null,
)

internal fun ProxyConnection.toChallengeProxyEndpoint(): ChallengeProxyEndpoint =
    ChallengeProxyEndpoint(mode = mode, protocol = protocol, host = host, port = port)

internal fun evaluateAndroidChallengeProxy(
    accountConnection: ChallengeProxyEndpoint,
    globalConnection: ChallengeProxyEndpoint,
): AndroidChallengeProxyDecision =
    if (accountConnection == globalConnection) {
        AndroidChallengeProxyDecision.Allowed
    } else {
        AndroidChallengeProxyDecision.AccountProxyMismatch
    }

internal fun evaluateAndroidChallengeProxy(
    accountConnection: ProxyConnection,
    globalConnection: ProxyConnection,
): AndroidChallengeProxyDecision =
    evaluateAndroidChallengeProxy(
        accountConnection = accountConnection.toChallengeProxyEndpoint(),
        globalConnection = globalConnection.toChallengeProxyEndpoint(),
    )

internal fun formatAndroidHostOnlySeedCookie(
    name: String,
    value: String,
): String {
    require(name.isNotBlank() && name.none { it.isISOControl() || it == ';' || it == '=' || it.isWhitespace() }) {
        "Invalid cookie name"
    }
    require(value.none { it.isISOControl() || it == ';' }) {
        "Invalid cookie value"
    }
    return "$name=$value; Path=/; Secure"
}

internal fun parseAndroidProbeCookies(
    rawCookieHeader: String?,
    probe: SessionChallengeCookieProbe,
): List<SessionChallengeCookieValue> {
    if (rawCookieHeader.isNullOrBlank()) return emptyList()
    val expectedNames = probe.cookies.mapTo(mutableSetOf()) { it.name }
    if (expectedNames.isEmpty()) return emptyList()
    return rawCookieHeader
        .split(';')
        .mapNotNull { item ->
            val eqIdx = item.indexOf('=')
            if (eqIdx <= 0) return@mapNotNull null
            val name = item.substring(0, eqIdx).trim()
            if (name !in expectedNames) return@mapNotNull null
            val value = item.substring(eqIdx + 1).trim()
            SessionChallengeCookieValue(
                sourceUrl = probe.url,
                name = name,
                value = value,
            )
        }
}

internal fun String.challengeHttpsOriginOrNull(): String? =
    runCatching {
        val uri = URI(this)
        val host = uri.host?.lowercase()
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !host.isNullOrBlank() &&
                uri.rawUserInfo == null,
        )
        val portSuffix = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        "https://$host$portSuffix"
    }.getOrNull()

internal fun Uri.isChallengeHttps(): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        !host.isNullOrBlank() &&
        userInfo == null

private data class PendingAndroidChallenge(
    val exception: SessionChallengeException,
    val onCompleted: () -> Unit,
)

@Composable
internal fun ProvideAndroidSessionChallengeHost(content: @Composable () -> Unit) {
    var activeChallenge by remember { mutableStateOf<PendingAndroidChallenge?>(null) }
    var pendingRelogin by remember { mutableStateOf<RequireReLoginException?>(null) }
    val reloginHandler = remember { mutableStateOf<((ReloginTarget) -> Unit)?>(null) }
    val launcher =
        remember {
            { exception: SessionChallengeException, onCompleted: () -> Unit ->
                pendingRelogin = null
                activeChallenge = PendingAndroidChallenge(exception = exception, onCompleted = onCompleted)
            }
        }

    LaunchedEffect(Unit) {
        SessionChallengePromptBus.prompts.collect { prompt ->
            launcher(prompt.exception) {
                prompt.complete()
            }
        }
    }

    CompositionLocalProvider(
        LocalSessionChallengeLauncher provides launcher,
        LocalAndroidChallengeReloginHandler provides reloginHandler,
    ) {
        content()
    }

    val current = activeChallenge
    if (current != null) {
        SessionChallengeWebViewDialog(
            exception = current.exception,
            onCompleted = {
                activeChallenge = null
                current.onCompleted()
            },
            onRequireRelogin = { relogin ->
                activeChallenge = null
                pendingRelogin = relogin
            },
            onDismiss = {
                activeChallenge = null
            },
        )
    }

    val relogin = pendingRelogin
    if (relogin != null) {
        FlareTheme {
            AlertDialog(
                onDismissRequest = { pendingRelogin = null },
                title = { Text(text = stringResource(R.string.session_challenge_relogin_title)) },
                text = { Text(text = stringResource(R.string.session_challenge_relogin_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingRelogin = null
                            val navigateToRelogin = reloginHandler.value
                            if (navigateToRelogin != null) {
                                navigateToRelogin(ReloginTarget(accountKey = relogin.accountKey, platformId = relogin.platformId))
                            } else {
                                KoinPlatformTools
                                    .defaultContext()
                                    .getOrNull()
                                    ?.getOrNull<InAppNotification>()
                                    ?.onError(Message.LoginExpired, relogin)
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.login_expired_relogin))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRelogin = null }) {
                        Text(text = stringResource(R.string.session_challenge_close))
                    }
                },
            )
        }
    }
}

internal data class AndroidChallengeRoute(
    val key: String,
    val isValid: () -> Boolean,
    val onInvalidated: (() -> Unit) -> () -> Unit,
)

private fun ProxySnapshot.toAndroidChallengeRoute(): AndroidChallengeRoute =
    AndroidChallengeRoute(
        key = key,
        isValid = { isValid },
        onInvalidated = { callback -> onInvalidated(callback) },
    )

private fun systemFallbackRoute(key: String): AndroidChallengeRoute =
    AndroidChallengeRoute(
        key = key,
        isValid = { true },
        onInvalidated = { { } },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionChallengeWebViewDialog(
    exception: SessionChallengeException,
    onCompleted: () -> Unit,
    onRequireRelogin: (RequireReLoginException) -> Unit,
    onDismiss: () -> Unit,
    beginSession: suspend (String) -> SessionChallengeHandle? = { challengeId ->
        KoinPlatformTools
            .defaultContext()
            .getOrNull()
            ?.getOrNull<SessionChallengeCoordinator>()
            ?.beginSession(challengeId)
    },
    prepareProxy: suspend () -> ProxySnapshot = { WebViewProxy.prepare() },
    prepareSystemNetwork: suspend () -> Unit = { WebViewProxy.prepareSystemNetwork() },
    restoreGlobalProxy: suspend () -> Unit = { WebViewProxy.prepare() },
    snapshotProxy: suspend (ProxyRef) -> ChallengeProxyEndpoint = { ref ->
        ProxyNetwork.snapshot(ref).connection.toChallengeProxyEndpoint()
    },
) {
    val latestOnCompleted by rememberUpdatedState(onCompleted)
    val latestOnRequireRelogin by rememberUpdatedState(onRequireRelogin)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestRestoreGlobalProxy by rememberUpdatedState(restoreGlobalProxy)

    val initialOrigin = remember(exception.challengeUrl) { exception.challengeUrl.challengeHttpsOriginOrNull() }
    var currentOrigin by remember(exception.challengeId) { mutableStateOf(initialOrigin.orEmpty()) }
    var sessionHandle by remember(exception.challengeId) { mutableStateOf<SessionChallengeHandle?>(null) }
    var sessionCompleted by remember(exception.challengeId) { mutableStateOf(false) }
    var route by remember(exception.challengeId) { mutableStateOf<AndroidChallengeRoute?>(null) }
    var cookiesSeeded by remember(exception.challengeId) { mutableStateOf(false) }
    var systemNetworkConfirmed by remember(exception.challengeId) { mutableStateOf(false) }
    var consentMessage by remember(exception.challengeId) { mutableStateOf<String?>(null) }
    var usedSystemNetworkFallback by remember(exception.challengeId) { mutableStateOf(false) }
    var errorText by remember(exception.challengeId) { mutableStateOf<String?>(null) }

    val expiredErrorText = stringResource(R.string.session_challenge_expired)
    val mismatchConsentText = stringResource(R.string.session_challenge_account_proxy_mismatch)
    val unsupportedProxyConsentText = stringResource(R.string.session_challenge_unsupported_proxy_consent)
    val proxyErrorText = stringResource(R.string.proxy_webview_error)

    val cancelAndDismiss = {
        if (!sessionCompleted) {
            sessionHandle?.cancel()
        }
        latestOnDismiss()
    }

    DisposableEffect(exception.challengeId) {
        val interactiveLock = AndroidWebViewCookieLock.beginInteractive()
        onDispose {
            if (!sessionCompleted) {
                sessionHandle?.close()
            }
            clearAndroidChallengeStorageAndCookies()
            interactiveLock.close()
            if (usedSystemNetworkFallback) {
                CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch {
                    runCatching { latestRestoreGlobalProxy() }
                }
            }
        }
    }

    LaunchedEffect(exception.challengeId) {
        sessionHandle = null
        route = null
        cookiesSeeded = false
        systemNetworkConfirmed = false
        consentMessage = null
        usedSystemNetworkFallback = false
        errorText = null

        val handle =
            try {
                beginSession(exception.challengeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequireReLoginException) {
                sessionCompleted = true
                latestOnRequireRelogin(e)
                return@LaunchedEffect
            } catch (_: Throwable) {
                null
            }
        if (handle == null) {
            errorText = expiredErrorText
            return@LaunchedEffect
        }
        val challengeOrigin = handle.challengeUrl.challengeHttpsOriginOrNull()
        if (challengeOrigin == null) {
            errorText = expiredErrorText
            handle.cancel()
            handle.close()
            sessionCompleted = true
            return@LaunchedEffect
        }
        if (currentOrigin.isEmpty()) {
            currentOrigin = challengeOrigin
        }
        sessionHandle = handle
    }

    val activeHandle = sessionHandle
    LaunchedEffect(activeHandle, systemNetworkConfirmed) {
        val handle = activeHandle ?: return@LaunchedEffect
        val confirmed = systemNetworkConfirmed
        ProxyNetwork.changes.collectLatest {
            if (sessionCompleted) return@collectLatest
            val challengeOrigin = handle.challengeUrl.challengeHttpsOriginOrNull()
            if (challengeOrigin == null) {
                route = null
                consentMessage = null
                errorText = expiredErrorText
                handle.cancel()
                return@collectLatest
            }
            val decision =
                try {
                    val accountConn = snapshotProxy(handle.proxyRef)
                    val globalConn = snapshotProxy(ProxyRef.Global)
                    evaluateAndroidChallengeProxy(accountConn, globalConn)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    AndroidChallengeProxyDecision.AccountProxyMismatch
                }
            if (decision != AndroidChallengeProxyDecision.Allowed) {
                if (!confirmed) {
                    route = null
                    usedSystemNetworkFallback = false
                    consentMessage = mismatchConsentText
                    errorText = null
                    return@collectLatest
                }
                val fallbackKey = "system-${handle.challengeId}"
                if (usedSystemNetworkFallback && route?.key == fallbackKey && route?.isValid?.invoke() == true && cookiesSeeded) {
                    return@collectLatest
                }
                route = null
                consentMessage = null
                errorText = null
                try {
                    if (!cookiesSeeded) {
                        seedAndroidChallengeCookies(
                            challengeOrigin = challengeOrigin,
                            seedCookies = handle.seedCookies,
                        )
                        cookiesSeeded = true
                    }
                    prepareSystemNetwork()
                    usedSystemNetworkFallback = true
                    route = systemFallbackRoute(fallbackKey)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    errorText = proxyErrorText
                }
                return@collectLatest
            }

            if (!usedSystemNetworkFallback && route?.isValid?.invoke() == true && cookiesSeeded) return@collectLatest
            route = null
            consentMessage = null
            errorText = null
            try {
                val prepared = prepareProxy()
                if (prepared.isValid) {
                    if (!cookiesSeeded) {
                        seedAndroidChallengeCookies(
                            challengeOrigin = challengeOrigin,
                            seedCookies = handle.seedCookies,
                        )
                        cookiesSeeded = true
                    }
                    usedSystemNetworkFallback = false
                    route = prepared.toAndroidChallengeRoute()
                } else {
                    errorText = proxyErrorText
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: UnsupportedOperationException) {
                if (!confirmed) {
                    route = null
                    usedSystemNetworkFallback = false
                    consentMessage = unsupportedProxyConsentText
                    errorText = null
                } else {
                    try {
                        if (!cookiesSeeded) {
                            seedAndroidChallengeCookies(
                                challengeOrigin = challengeOrigin,
                                seedCookies = handle.seedCookies,
                            )
                            cookiesSeeded = true
                        }
                        prepareSystemNetwork()
                        usedSystemNetworkFallback = true
                        route = systemFallbackRoute("system-unsupported-${handle.challengeId}")
                    } catch (innerError: CancellationException) {
                        throw innerError
                    } catch (_: Exception) {
                        errorText = proxyErrorText
                    }
                }
            } catch (_: Exception) {
                errorText = proxyErrorText
            }
        }
    }

    Dialog(
        onDismissRequest = cancelAndDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        FlareTheme {
            FlareScaffold(
                topBar = {
                    FlareTopAppBar(
                        title = {
                            Text(
                                text = currentOrigin.ifEmpty { stringResource(R.string.session_challenge_title) },
                                color =
                                    if (currentOrigin.isNotEmpty() && currentOrigin != initialOrigin) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        },
                        navigationIcon = {
                            BackButton(onBack = cancelAndDismiss)
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                ) {
                    val currentError = errorText
                    val pendingConsent = consentMessage
                    val prepared = route
                    val handle = sessionHandle
                    if (
                        usedSystemNetworkFallback &&
                        currentError == null &&
                        pendingConsent == null &&
                        handle != null &&
                        prepared != null &&
                        prepared.isValid() &&
                        cookiesSeeded
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("session_challenge_proxy_warning"),
                        ) {
                            Text(
                                text = stringResource(R.string.session_challenge_system_network_active_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            currentError != null -> {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.CircleExclamation,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Text(
                                        text = currentError,
                                        textAlign = TextAlign.Center,
                                    )
                                    Button(onClick = cancelAndDismiss) {
                                        Text(text = stringResource(R.string.session_challenge_close))
                                    }
                                }
                            }

                            pendingConsent != null && !systemNetworkConfirmed -> {
                                Column(
                                    modifier =
                                        Modifier
                                            .padding(24.dp)
                                            .testTag("session_challenge_proxy_consent"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.CircleExclamation,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Text(
                                        text = pendingConsent,
                                        textAlign = TextAlign.Center,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(onClick = cancelAndDismiss) {
                                            Text(text = stringResource(R.string.session_challenge_close))
                                        }
                                        Button(
                                            onClick = { systemNetworkConfirmed = true },
                                            modifier = Modifier.testTag("session_challenge_continue_system_network"),
                                        ) {
                                            Text(text = stringResource(R.string.session_challenge_continue_system_network))
                                        }
                                    }
                                }
                            }

                            handle != null && prepared != null && prepared.isValid() && cookiesSeeded -> {
                                key(handle.challengeId, prepared.key) {
                                    AndroidChallengeWebView(
                                        handle = handle,
                                        route = prepared,
                                        expiredErrorText = expiredErrorText,
                                        onOriginChange = { currentOrigin = it },
                                        onCompletedSuccess = {
                                            sessionCompleted = true
                                            handle.close()
                                            latestOnCompleted()
                                        },
                                        onRequireRelogin = { relogin ->
                                            sessionCompleted = true
                                            handle.close()
                                            latestOnRequireRelogin(relogin)
                                        },
                                        onError = { message ->
                                            errorText = message
                                        },
                                        onWebError = {
                                            errorText = proxyErrorText
                                        },
                                    )
                                }
                            }

                            else -> {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidChallengeWebView(
    handle: SessionChallengeHandle,
    route: AndroidChallengeRoute,
    expiredErrorText: String,
    onOriginChange: (String) -> Unit,
    onCompletedSuccess: () -> Unit,
    onRequireRelogin: (RequireReLoginException) -> Unit,
    onError: (String) -> Unit,
    onWebError: () -> Unit,
) {
    val latestOnOriginChange by rememberUpdatedState(onOriginChange)
    val latestOnCompletedSuccess by rememberUpdatedState(onCompletedSuccess)
    val latestOnRequireRelogin by rememberUpdatedState(onRequireRelogin)
    val latestOnError by rememberUpdatedState(onError)
    val latestOnWebError by rememberUpdatedState(onWebError)

    val safeInitialUrl =
        remember(handle.challengeUrl) {
            handle.challengeUrl.takeIf { it.challengeHttpsOriginOrNull() != null }
                ?: "about:blank"
        }
    val webViewState = rememberWebViewState(safeInitialUrl)
    LaunchedEffect(webViewState.lastLoadedUrl) {
        webViewState.lastLoadedUrl?.challengeHttpsOriginOrNull()?.let(latestOnOriginChange)
    }

    var unsubscribe by remember { mutableStateOf<(() -> Unit)?>(null) }
    var disposed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var webViewUserAgent by remember { mutableStateOf<String?>(null) }
    val checkTriggers = remember(handle.challengeId) { Channel<Unit>(Channel.CONFLATED) }

    DisposableEffect(handle.challengeId) {
        onDispose {
            checkTriggers.close()
        }
    }

    LaunchedEffect(handle.challengeId, route.key) {
        coroutineScope {
            val ticker =
                launch {
                    while (isActive && route.isValid() && !finished) {
                        delay(2.seconds)
                        if (webViewState.lastLoadedUrl?.challengeHttpsOriginOrNull() != null) {
                            checkTriggers.trySend(Unit)
                        }
                    }
                }
            try {
                for (trigger in checkTriggers) {
                    if (!route.isValid() || finished) break
                    val loadedOrigin = webViewState.lastLoadedUrl?.challengeHttpsOriginOrNull() ?: continue
                    loadedOrigin.let(latestOnOriginChange)
                    val manager = CookieManager.getInstance()
                    val cookies =
                        handle.probes.flatMap { probe ->
                            parseAndroidProbeCookies(
                                rawCookieHeader = manager.getCookie(probe.url),
                                probe = probe,
                            )
                        }
                    val snapshot =
                        SessionChallengeCookieSnapshot(
                            cookies = cookies,
                            userAgent = webViewUserAgent,
                        )
                    when (
                        val result =
                            try {
                                handle.check(snapshot)
                            } catch (e: CancellationException) {
                                if (!isActive) throw e
                                finished = true
                                latestOnError(expiredErrorText)
                                break
                            } catch (e: RequireReLoginException) {
                                finished = true
                                latestOnRequireRelogin(e)
                                break
                            } catch (e: Exception) {
                                finished = true
                                latestOnError(e.message?.takeIf { it.isNotBlank() } ?: expiredErrorText)
                                break
                            }
                    ) {
                        SessionChallengeCheckResult.Busy,
                        is SessionChallengeCheckResult.AwaitingCookies,
                        SessionChallengeCheckResult.Pending,
                        -> {
                            // Continue waiting for the next navigation or timer tick.
                        }

                        SessionChallengeCheckResult.Completed -> {
                            finished = true
                            latestOnCompletedSuccess()
                            break
                        }
                    }
                }
            } finally {
                ticker.cancel()
            }
        }
    }

    WebView(
        state = webViewState,
        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        modifier =
            Modifier
                .alpha(0.99f)
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
        client =
            remember(handle.challengeId, route.key) {
                object : AccompanistWebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: AndroidWebView?,
                        request: WebResourceRequest?,
                    ): Boolean = !route.isValid() || finished || (request?.isForMainFrame == true && !request.url.isChallengeHttps())

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(
                        view: AndroidWebView?,
                        url: String?,
                    ): Boolean = !route.isValid() || finished || url?.challengeHttpsOriginOrNull() == null

                    override fun onPageFinished(
                        view: AndroidWebView,
                        url: String?,
                    ) {
                        super.onPageFinished(view, url)
                        if (!route.isValid() || finished) return
                        url?.challengeHttpsOriginOrNull()?.let { origin ->
                            latestOnOriginChange(origin)
                            checkTriggers.trySend(Unit)
                        }
                    }

                    override fun onReceivedError(
                        view: AndroidWebView,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && route.isValid() && !finished) {
                            latestOnWebError()
                        }
                    }
                }
            },
        onCreated = { view ->
            view.clearCache(true)
            with(view.settings) {
                webViewUserAgent = userAgentString
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                allowContentAccess = false
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                blockNetworkLoads = !route.isValid()
            }
            unsubscribe =
                route.onInvalidated {
                    view.post {
                        if (disposed) return@post
                        view.stopLoading()
                        view.settings.blockNetworkLoads = true
                        view.loadUrl("about:blank")
                    }
                }
        },
        onDispose = { view ->
            disposed = true
            unsubscribe?.invoke()
            unsubscribe = null
            clearAndroidChallengeWebView(view)
        },
    )
}

private suspend fun seedAndroidChallengeCookies(
    challengeOrigin: String,
    seedCookies: List<dev.dimension.flare.data.repository.SessionChallengeSeedCookie>,
) {
    withContext(NonCancellable) {
        AndroidWebViewCookieLock.awaitBackgroundIdle()
        WebStorage.getInstance().deleteAllData()
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies { continuation.resume(Unit) }
        }
        seedCookies.forEach { cookie ->
            val header = formatAndroidHostOnlySeedCookie(cookie.name, cookie.value)
            suspendCancellableCoroutine { continuation ->
                manager.setCookie(challengeOrigin, header) { continuation.resume(Unit) }
            }
        }
        manager.flush()
    }
}

private fun clearAndroidChallengeStorageAndCookies() {
    runCatching {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

private fun clearAndroidChallengeWebView(webView: AndroidWebView) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearCache(true)
    webView.destroy()
}
