package dev.dimension.flare.ui.screen.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dimension.flare.R
import dev.dimension.flare.feature.plugin.management.PluginSettingsFacadeV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PluginSettingsDialog(
    pluginId: String,
    onDismiss: () -> Unit,
) {
    val session = remember(pluginId) { runCatching { PluginSettingsFacadeV1.open(pluginId) } }
    val scope = rememberCoroutineScope()
    var failure by remember(pluginId) { mutableStateOf(session.exceptionOrNull() != null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(pluginId) {
        onDispose {
            session.getOrNull()?.close()
            webView?.let(PluginSettingsWebData::detach)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
                val facade = session.getOrNull()
                if (failure || facade == null ||
                    (
                        !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                            !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
                            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    )
                ) {
                    Text(stringResource(R.string.plugin_operation_failed), Modifier.padding(16.dp))
                } else {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { context ->
                            val origin = "https://${facade.host}"
                            val entry = "$origin/${facade.entry}"
                            WebView(context).apply {
                                PluginSettingsWebData.attach(this)
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.blockNetworkLoads = true
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                WebViewCompat.addDocumentStartJavaScript(this, facade.documentStartScript, setOf("*"))
                                var started = false

                                fun local(uri: Uri): Boolean =
                                    uri.scheme == "https" && uri.host == facade.host && uri.port in setOf(-1, 443) && uri.query == null
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun shouldInterceptRequest(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): WebResourceResponse {
                                            val resource =
                                                if (request.method == "GET" && local(request.url)) {
                                                    runCatching {
                                                        facade.resource(
                                                            request.url.path
                                                                .orEmpty()
                                                                .removePrefix("/"),
                                                        )
                                                    }.getOrNull()
                                                } else {
                                                    null
                                                }
                                            return if (resource == null) {
                                                WebResourceResponse(
                                                    "text/plain",
                                                    "UTF-8",
                                                    403,
                                                    "Blocked",
                                                    emptyMap(),
                                                    ByteArrayInputStream(byteArrayOf()),
                                                )
                                            } else {
                                                WebResourceResponse(
                                                    resource.mimeType,
                                                    "UTF-8",
                                                    200,
                                                    "OK",
                                                    mapOf(
                                                        "Content-Security-Policy" to facade.contentSecurityPolicy,
                                                        "Cache-Control" to "no-store",
                                                        "X-Content-Type-Options" to "nosniff",
                                                        "X-DNS-Prefetch-Control" to "off",
                                                        "Access-Control-Allow-Origin" to "*",
                                                    ),
                                                    ByteArrayInputStream(Base64.decode(resource.base64, Base64.DEFAULT)),
                                                )
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean =
                                            !request.isForMainFrame || request.method != "GET" || !local(request.url) ||
                                                request.url.path != "/${facade.entry}"

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            error: WebResourceError,
                                        ) {
                                            if (request.isForMainFrame) {
                                                facade.close()
                                                failure = true
                                            }
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            response: WebResourceResponse,
                                        ) {
                                            if (request.isForMainFrame) {
                                                facade.close()
                                                failure = true
                                            }
                                        }

                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String?,
                                            favicon: Bitmap?,
                                        ) {
                                            if (started || url != entry) {
                                                facade.close()
                                                view.stopLoading()
                                                failure = true
                                            }
                                            started = true
                                        }
                                    }
                                WebViewCompat.addWebMessageListener(
                                    this,
                                    "__flareNative",
                                    // The sandbox has an opaque origin; authenticate the main document below.
                                    setOf("*"),
                                ) { view, message, sourceOrigin, mainFrame, reply ->
                                    val document = Uri.parse(view.url.orEmpty())
                                    if (!mainFrame || sourceOrigin.toString() != "null" ||
                                        !started || !local(document) || document.path != "/${facade.entry}" ||
                                        message.type != WebMessageCompat.TYPE_STRING
                                    ) {
                                        return@addWebMessageListener
                                    }
                                    val body = message.data ?: return@addWebMessageListener
                                    if (body.length > 5 * 1_024 * 1_024 || body.toByteArray().size > 5 * 1_024 * 1_024) {
                                        return@addWebMessageListener
                                    }
                                    scope.launch {
                                        try {
                                            reply.postMessage(facade.call(body))
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            val id = runCatching { JSONObject(body).getString("id").take(64) }.getOrNull() ?: return@launch
                                            reply.postMessage(
                                                JSONObject()
                                                    .put(
                                                        "id",
                                                        id,
                                                    ).put(
                                                        "ok",
                                                        false,
                                                    ).put(
                                                        "error",
                                                        JSONObject().put("code", "bridge.failed").put("message", "Host request failed"),
                                                    ).toString(),
                                            )
                                        }
                                    }
                                }
                                loadUrl(entry)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Keep settings browsing data separate from login WebViews, and discard it after the last page closes. */
private object PluginSettingsWebData {
    private var pages = 0

    fun attach(view: WebView) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        WebViewCompat.setProfile(view, "flare-plugin-settings")
        val profile = WebViewCompat.getProfile(view)
        if (pages == 0) profile.webStorage.deleteAllData()
        profile.cookieManager.setAcceptCookie(false)
        pages++
    }

    fun detach(view: WebView) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val profile = WebViewCompat.getProfile(view)
        view.stopLoading()
        view.destroy()
        pages--
        if (pages == 0) {
            profile.webStorage.deleteAllData()
            profile.cookieManager.removeAllCookies(null)
        }
    }
}
