package dev.dimension.flare.ui.screen.settings

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceEntryV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceStateV1
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.theme.FlareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PluginMarketplaceScreenTest {
    private val mediaQueryIntegration = androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled

    init {
        androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    @org.junit.After
    fun restoreMediaQueryIntegration() {
        androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled = mediaQueryIntegration
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry =
        PluginMarketplaceEntryV1(
            id = "dev.maxlec.zhihu",
            name = "知乎",
            description = "知乎热榜、推荐、关注动态与回答/文章详情",
            repository = "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon =
                "https://raw.githubusercontent.com/anamaxlec/flare-zhihu-plugin/557b6195db413fecc65468195c6646c668b64d64/assets/icon.png",
        )
    private var state by mutableStateOf(
        PluginManagementStateV1(marketplace = PluginMarketplaceStateV1(plugins = listOf(entry), loaded = true)),
    )
    private var installedId: String? = null
    private var openedUrl: String? = null
    private var managed = false
    private var refreshed = false
    private var openedMarketplace = false

    @Test
    fun managementKeepsItsLayoutAndOpensTheStoreFromBeforeMore() {
        show(management = true)
        compose.onNodeWithText(context.getString(R.string.settings_plugins_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace)).assertDoesNotExist()
        val store = compose.onNodeWithContentDescription(context.getString(R.string.plugin_marketplace))
        val more = compose.onNodeWithContentDescription(context.getString(R.string.more))
        org.junit.Assert.assertTrue(
            store
                .fetchSemanticsNode()
                .boundsInRoot.center.x <
                more
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x,
        )
        screenshot("management")
        store.performClick()
        compose.runOnIdle { assertEquals(true, openedMarketplace) }
    }

    @Test
    fun marketplaceDisplaysTheCatalogAndRoutesInstallAndRepositoryActions() {
        show()
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_search)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_plugins_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.more)).assertDoesNotExist()
        screenshot()
        compose
            .onNode(
                hasScrollAction() and !hasSetTextAction(),
            ).performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }
        compose.runOnIdle { assertEquals(true, refreshed) }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_repository)).assertDoesNotExist()
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: ${entry.name}").performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_repository)).performClick()
        compose.runOnIdle { assertEquals(entry.repository, openedUrl) }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).performClick()
        compose.runOnIdle { assertEquals(entry.id, installedId) }
    }

    @Test
    fun nativeSearchFiltersNamesAndDescriptionsAndRetainsTheQueryDuringRefresh() {
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_search)).performClick()
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextInput("知")
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        input.performTextReplacement("not-a-plugin")
        compose.onNodeWithText(context.getString(R.string.empty_search_results)).assertIsDisplayed()
        input.performTextReplacement("推荐")
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).performClick()
        compose.runOnIdle { assertEquals(entry.id, installedId) }
        compose.waitForIdle()
        screenshot("store-search")
        compose
            .onNode(
                hasScrollAction() and !hasSetTextAction(),
            ).performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }
        compose.runOnIdle {
            assertEquals(true, refreshed)
            state = state.copy(marketplace = state.marketplace.copy(plugins = listOf(entry.copy(description = "其他简介"))))
        }
        compose.onNodeWithText(context.getString(R.string.empty_search_results)).assertIsDisplayed()
        compose.onNodeWithText("推荐").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_search)).performClick()
        compose.onNodeWithText(entry.name).assertIsDisplayed()
    }

    @Test
    fun localDraftsOfferManagementAndARefreshFailureKeepsTheCatalog() {
        state =
            state.copy(
                authoredPlugins = listOf(AuthoredPlugin(entry.id, entry.name)),
                marketplace = state.marketplace.copy(error = "offline"),
            )
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_cached)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_manage)).performClick()
        compose.runOnIdle { assertEquals(true, managed) }
        compose.onNodeWithText(context.getString(R.string.plugin_retry)).performClick()
        compose.runOnIdle { assertEquals(true, refreshed) }
        compose.onNodeWithText(entry.name).assertIsDisplayed()
    }

    private fun show(management: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(),
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrl = uri
                        }
                    },
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    if (management) {
                        AndroidPluginManagementScreen(onBack = {}, onSettings = {}, onOpenMarketplace = { openedMarketplace = true })
                    } else {
                        PluginMarketplaceScreen(
                            state = state,
                            onBack = {},
                            onInstall = { installedId = it },
                            onManage = { managed = true },
                            onRefresh = { refreshed = true },
                            onIcon = { null },
                        )
                    }
                }
            }
        }
    }

    private fun screenshot(page: String = "store") {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.externalCacheDir, "flare-marketplace-android-$page.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
