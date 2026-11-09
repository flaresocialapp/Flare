package dev.dimension.flare.ui.screen.settings

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import dev.dimension.flare.R
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.translation.PluginTranslationService
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.management.PluginManagementPresenterV1
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercise the actual renderer, native bridge, storage and shared translation cache identity together. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class PluginSettingsWebViewTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var presenter: PluginManagementPresenterV1? = null
    private var visible by mutableStateOf(true)
    private var settingsPluginId by mutableStateOf<String?>(null)

    @After
    fun cleanup() {
        if (presenter == null) return
        compose.runOnUiThread { visible = false }
        compose.waitForIdle()
        presenter?.let {
            runBlocking { it.uninstall(ID) }
            it.close()
        }
    }

    @Test
    fun offlineSettingsShareStorageAndInvalidateOnlyDeclaredTranslationDependencies() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val subsystem = koinGet<PluginSubsystemV1>()
        val manager = PluginManagementPresenterV1(subsystem, koinGet<CoroutineScope>()).also { presenter = it }
        runBlocking {
            manager.inspect(Buffer().write(packageBytes()))
            manager.confirmInstall()
        }
        val service = koinGet<PluginTranslationService>()
        val before =
            service.providers.value
                .single { it.pluginId == ID }
                .cacheKey
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance.Default,
                LocalTimelineAppearance provides TimelineAppearance.Default,
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    if (visible) {
                        val selected = settingsPluginId
                        if (selected != null) {
                            PluginSettingsScreen(pluginId = selected, onBack = { settingsPluginId = null })
                        } else {
                            PluginItem(
                                plugin =
                                    manager.state.value.items
                                        .single { it.pluginId == ID },
                                busy = false,
                                shapesIndex = 0,
                                shapesCount = 1,
                                onSettings = { settingsPluginId = it },
                                onCancelUpdate = {},
                                onUninstall = {},
                                onUpdate = {},
                            )
                        }
                    }
                }
            }
        }
        openSettings()
        var view = awaitWebView()
        awaitValue(view, "document.querySelector('#prefix')?.value", "\"[demo] \"")
        assertEquals("\"undefined\"", evaluate(view, "typeof RTCPeerConnection"))
        assertEquals(
            "\"SecurityError\"",
            evaluate(
                view,
                """
                (()=>{
                  const frame=document.createElement('iframe');document.body.append(frame);
                  try{return typeof frame.contentWindow.RTCPeerConnection}catch(error){return error.name}
                })()
                """.trimIndent(),
            ),
        )
        evaluate(view, "document.querySelector('#prefix').value='android: ';document.querySelector('#options').requestSubmit()")
        awaitValue(view, "document.querySelector('#status')?.textContent", "\"Saved. New translations will use this prefix.\"")
        assertNotEquals(
            before,
            service.providers.value
                .single { it.pluginId == ID }
                .cacheKey,
        )
        val saved =
            service.providers.value
                .single { it.pluginId == ID }
                .cacheKey

        evaluate(
            view,
            """
            import(new URL('./sdk.mjs',location.href).href).then(m=>m.connectSettings()).then(async sdk=>{
              await sdk.storage.open('state').write({set:{counter:1}});
              document.querySelector('#status').textContent='state saved';
            }).catch(error=>document.querySelector('#status').textContent=error.message);
            """.trimIndent(),
        )
        awaitValue(view, "document.querySelector('#status')?.textContent", "\"state saved\"")
        assertEquals(
            saved,
            service.providers.value
                .single { it.pluginId == ID }
                .cacheKey,
        )

        evaluate(
            view,
            """
            addEventListener('securitypolicyviolation', event=>{
              document.querySelector('#status').textContent='csp:'+event.violatedDirective;
            },{once:true});
            fetch('https://not-approved.invalid').catch(()=>{});
            """.trimIndent(),
        )
        awaitValue(view, "document.querySelector('#status')?.textContent", "\"csp:connect-src\"")
        evaluate(
            view,
            """
            import(new URL('./sdk.mjs',location.href).href).then(m=>m.connectSettings()).then(sdk=>
              sdk.http.request({url:'https://not-approved.invalid'})
            ).catch(error=>document.querySelector('#status').textContent=error.code);
            """.trimIndent(),
        )
        awaitValue(view, "document.querySelector('#status')?.textContent", "\"host.invalid-request\"")

        compose.onNodeWithContentDescription(instrumentation.targetContext.getString(R.string.navigate_back)).performClick()
        compose.waitForIdle()
        assertEquals(false, view.isAttachedToWindow)
        openSettings()
        view = awaitWebView()
        awaitValue(view, "document.querySelector('#prefix')?.value", "\"android: \"")
    }

    private fun openSettings() {
        val context = instrumentation.targetContext
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: Translation contract demo").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
    }

    private fun packageBytes(): ByteArray {
        val assets = instrumentation.context.assets
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(path: String) {
                zip.putNextEntry(ZipEntry(path))
                assets.open(path).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            add("manifest.json")
            add("plugin.js")
            add("assets/icon.png")
            assets.list("settings").orEmpty().forEach { add("settings/$it") }
        }
        return output.toByteArray()
    }

    private fun awaitWebView(): WebView {
        var result: WebView? = null
        compose.waitUntil(15_000) {
            instrumentation.runOnMainSync {
                fun find(view: View): WebView? =
                    if (view is WebView && view.isAttachedToWindow) {
                        view
                    } else {
                        (view as? ViewGroup)?.let { group ->
                            (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                        }
                    }
                result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            }
            result != null
        }
        return checkNotNull(result)
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                result = it
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "WebView did not reply" }
        return result
    }

    private fun awaitValue(
        view: WebView,
        script: String,
        expected: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last: String?
        do {
            compose.waitForIdle()
            last = evaluate(view, script)
            if (last == expected) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        val page =
            evaluate(
                view,
                """
                JSON.stringify({url:location.href,state:document.readyState,status:document.querySelector('#status')?.textContent,
                  bridge:typeof __flareNative,receive:typeof __flareSettingsReceive,
                  resources:performance.getEntriesByType('resource').map(entry=>entry.name)})
                """.trimIndent(),
            )
        assertEquals("Page: $page", expected, last)
    }

    private companion object {
        const val ID = "dev.example.translation"
    }
}
