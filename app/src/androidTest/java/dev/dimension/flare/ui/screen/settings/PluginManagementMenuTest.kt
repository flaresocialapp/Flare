package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.feature.plugin.github.GitHubPluginRepositoryV1
import dev.dimension.flare.feature.plugin.management.PluginManagementItemV1
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.ui.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginManagementMenuTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var state by mutableStateOf(PluginManagementStateV1())
    private var agentEnabled by mutableStateOf(false)
    private var aiCreates = 0
    private var fileInstalls = 0
    private var installs = 0
    private var updateChecks = 0
    private var offersSettings by mutableStateOf(true)
    private var settingsOpened = 0
    private var uninstalls = 0

    @Test
    fun itemMenuShowsSettingsOnlyWhenProvidedAndClosesAfterSelection() {
        showItemMenu()
        compose.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
        itemMore().performClick()
        popupItem(R.string.settings_title).assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            offersSettings = false
        }
        itemMore().performClick()
        compose.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
        popupItem(R.string.plugin_uninstall).performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, uninstalls) }
    }

    @Test
    fun busyItemDisablesBothTheMenuAndItsOpenActions() {
        state = state.copy(busy = true)
        showItemMenu()
        itemMore().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        itemMore().performClick()
        popupItem(R.string.settings_title).assertIsEnabled()
        compose.runOnIdle { state = state.copy(busy = true) }
        popupItem(R.string.settings_title).assertIsNotEnabled()
        popupItem(R.string.plugin_uninstall).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, settingsOpened) }
    }

    @Test
    fun githubActionsAppearInThePopupAndDismissAfterSelection() {
        val repository = GitHubPluginRepositoryV1(1, "example", "plugin")
        state =
            state.copy(
                plugins =
                    listOf(
                        PluginManagementItemV1(
                            pluginId = "example.plugin",
                            platformId = "Example",
                            nameText = UiText.Raw("Example"),
                            version = "1.0.0",
                            iconPath = "",
                            sourceUrl = repository.url,
                            availableUpdate = null,
                        ),
                    ),
            )
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_install_github)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.plugin_check_updates)).assertDoesNotExist()

        add().performClick()
        popupItem(R.string.plugin_install_github).assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, installs) }

        more().performClick()
        popupItem(R.string.plugin_check_updates).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, updateChecks) }
    }

    @Test
    fun busyStateAndMissingGithubSourcesDisableTheAppropriateControls() {
        state = state.copy(busy = true)
        show()
        more().assertIsNotEnabled()
        add().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        more().assertIsEnabled().performClick()
        popupItem(R.string.plugin_check_updates).assertIsNotEnabled()
    }

    @Test
    fun addMenuGatesAiAndDismissesAfterEverySourceSelection() {
        show()
        add().performClick()
        popupItem(R.string.plugin_ai_create).assertDoesNotExist()
        popupItem(R.string.plugin_install).assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fileInstalls)
            agentEnabled = true
        }
        add().performClick()
        popupItem(R.string.plugin_ai_create).assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, aiCreates) }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Row {
                    PluginAddMenu(
                        busy = state.busy,
                        agentEnabled = agentEnabled,
                        onGitHubInstall = { installs++ },
                        onCreateWithAI = { aiCreates++ },
                        onFileInstall = { fileInstalls++ },
                    )
                    PluginManagementMenu(
                        state = state,
                        onCheckUpdates = { updateChecks++ },
                        onRebuildIndex = {},
                        onCleanup = {},
                    )
                }
            }
        }
    }

    private fun add() = compose.onNodeWithContentDescription(context.getString(R.string.plugin_add))

    private fun showItemMenu() {
        compose.setContent {
            MaterialTheme {
                PluginItemMenu(
                    name = "Example",
                    busy = state.busy,
                    onUninstall = { uninstalls++ },
                    onSettings = if (offersSettings) ({ settingsOpened++ }) else null,
                )
            }
        }
    }

    private fun itemMore() = compose.onNodeWithContentDescription("${context.getString(R.string.more)}: Example")

    private fun more() = compose.onNodeWithContentDescription(context.getString(R.string.more))

    private fun popupItem(label: Int) = compose.onNode(hasText(context.getString(label)) and hasAnyAncestor(isPopup()))
}
