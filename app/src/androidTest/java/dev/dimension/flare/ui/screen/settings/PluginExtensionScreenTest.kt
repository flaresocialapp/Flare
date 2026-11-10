package dev.dimension.flare.ui.screen.settings

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.feature.plugin.extension.PluginExtensionAccountItemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionActionItemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionResultStatusV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionResultV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionRunV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionStateV1
import dev.dimension.flare.feature.plugin.management.ProvidePluginTextResolverV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Clock

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class PluginExtensionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var mode by mutableStateOf("ready")
    private var state by mutableStateOf(PluginTaskUiFixtures.state("ready"))
    private var authorizations = 0
    private var revocations = 0
    private var cancellations = 0
    private var logins = 0
    private var automatic: Triple<String, String, Boolean>? = null

    @Test
    fun authorizationRequiresConfirmationAndUnapprovedTasksStayDisabled() {
        select("unauthorized")
        show()
        compose.onNodeWithText(label(R.string.plugin_extension_run)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.plugin_extension_automatic)).assertIsNotEnabled()
        compose.onNodeWithText("林小明").assertIsDisplayed()
        access().assertIsOff().performClick()
        compose.onNodeWithText("https://m.weibo.cn", substring = true).assertIsDisplayed()
        compose.onNodeWithText(label(android.R.string.cancel)).performClick()
        assertEquals(0, authorizations)
        access().assertIsOff().performClick()
        dialogButton(R.string.plugin_extension_authorize).performClick()
        assertEquals(1, authorizations)
        access().assertIsOn()
        compose.onNodeWithText(label(R.string.plugin_extension_run)).assertIsEnabled()
    }

    @Test
    fun accountAccessSwitchRequiresConfirmationAndCancellationPreservesAccess() {
        show()
        compose.onNodeWithText(label(R.string.plugin_extension_revoke)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.plugin_extension_authorized)).assertDoesNotExist()
        access().assertIsOn().performClick()
        compose.onNodeWithText(label(R.string.plugin_extension_revoke_title)).assertIsDisplayed()
        assertEquals(0, revocations)
        compose.onNodeWithText(label(android.R.string.cancel)).performClick()
        assertEquals(0, revocations)
        access().assertIsOn().performClick()
        dialogButton(R.string.plugin_extension_revoke).performClick()
        assertEquals(1, revocations)
        access().assertIsOff()
        compose.onNodeWithText(label(R.string.plugin_extension_run)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.plugin_extension_automatic)).assertIsNotEnabled()
    }

    @Test
    fun runningTasksKeepCancellationAvailableAndDisableAutomaticChanges() {
        select("running")
        show()
        access().assertIsOn().assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.plugin_extension_automatic)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.plugin_extension_run)).assertDoesNotExist()
        compose.onNodeWithText(label(android.R.string.cancel)).assertIsEnabled().performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun automaticToggleUsesTheCorrectAccountAndTask() {
        show()
        compose
            .onNodeWithContentDescription(label(R.string.plugin_extension_automatic))
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertEquals(Triple("fixture-grant", "checkin", true), automatic)
    }

    @Test
    fun loadingDoesNotFlashTheEmptyAccountActionAndEmptyAccountsCanOpenLogin() {
        select("loading")
        show()
        compose.onNodeWithText(label(R.string.quick_menu_add_account)).assertDoesNotExist()
        compose.runOnIdle { select("empty") }
        compose.onNodeWithText(label(R.string.quick_menu_add_account)).assertIsDisplayed().performClick()
        assertEquals(1, logins)
    }

    @Test
    fun profileFailureRetainsAccountIdentityAndManualActions() {
        select("fallback")
        show()
        compose.onNodeWithText("微博").assertIsDisplayed()
        compose.onNodeWithText("100001@weibo.com").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.plugin_extension_run)).assertIsEnabled()
    }

    @Test
    fun tasksAreFilteredForEachAccountsPlatform() {
        val base = state.actions.single()
        state =
            state.copy(
                actions =
                    listOf(
                        base.copy(id = "weibo", title = UiText.Raw("微博签到"), platformIds = setOf("VVo")),
                        base.copy(id = "other", title = UiText.Raw("其他平台任务"), platformIds = setOf("Other")),
                    ),
            )
        show()
        compose.onNodeWithText("微博签到").assertIsDisplayed()
        compose.onNodeWithText("其他平台任务").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(accounts = state.accounts.map { it.copy(platformId = "Other") })
        }
        compose.onNodeWithText("其他平台任务").assertIsDisplayed()
        compose.onNodeWithText("微博签到").assertDoesNotExist()
    }

    @Test
    fun captureTaskPageStates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capturePluginTasks") == "1")
        show()
        val modes =
            listOf(
                "loading",
                "empty",
                "unauthorized",
                "authorize",
                "ready",
                "automatic",
                "running",
                "completed",
                "already-completed",
                "partial",
                "failed",
                "authentication-required",
                "user-action-required",
                "skipped",
                "interrupted",
                "error",
                "fallback",
                "manual",
                "revoke",
            )
        for (value in modes) {
            compose.runOnIdle { select(value) }
            if (value == "authorize" || value == "revoke") access().performClick()
            compose.waitForIdle()
            SystemClock.sleep(400)
            val directory = File(context.getExternalFilesDir(null), "plugin-task-access-ui").apply { mkdirs() }
            checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                File(directory, "android-$value.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun select(value: String) {
        mode = value
        state = PluginTaskUiFixtures.state(value)
    }

    private fun show() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance.Default,
                LocalTimelineAppearance provides TimelineAppearance.Default,
            ) {
                ProvidePluginTextResolverV1 {
                    FlareTheme(darkTheme = false, dynamicColor = false) {
                        key(mode) {
                            PluginExtensionContent(
                                state = state,
                                onBack = {},
                                onLogin = { logins++ },
                                onAuthorize = {
                                    assertEquals("review", it.authorizationToken)
                                    authorizations++
                                    state =
                                        state.copy(accounts = state.accounts.map { account -> account.copy(accountId = "fixture-grant") })
                                },
                                onRevoke = {
                                    assertEquals("fixture-grant", it)
                                    revocations++
                                    state =
                                        state.copy(
                                            accounts =
                                                state.accounts.map { account ->
                                                    account.copy(accountId = null, automaticActions = emptySet())
                                                },
                                        )
                                },
                                onAutomatic = { account, task, enabled ->
                                    automatic = Triple(account, task, enabled)
                                    state =
                                        PluginTaskUiFixtures.state(if (enabled) "automatic" else "ready")
                                },
                                onRun = { account, task ->
                                    assertEquals("fixture-grant", account)
                                    assertEquals("checkin", task)
                                },
                                onCancel = {
                                    assertEquals("fixture-grant", it)
                                    cancellations++
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun label(id: Int) = context.getString(id)

    private fun access() =
        compose.onNodeWithContentDescription(
            "${label(R.string.plugin_extension_authorize)}: ${state.accounts
                .single()
                .profile
                ?.handle
                ?.display ?: state.accounts.single().accountKey}",
        )

    private fun dialogButton(id: Int) = compose.onNode(hasText(label(id)) and hasAnyAncestor(isDialog()))
}

internal object PluginTaskUiFixtures {
    fun state(mode: String): PluginExtensionStateV1 {
        val key = MicroBlogKey("100001", "weibo.com")
        val account = UiAccount(key, "VVo", "微博")
        val profile =
            UiProfile(
                key = key,
                handle = UiHandle("linxiaoming", "weibo.com"),
                avatar = InstrumentationRegistry.getArguments().getString("pluginTaskAvatar").toUiImage(),
                nameInternal = "林小明".toUiPlainText(),
                platformId = "VVo",
                clickEvent = ClickEvent.Noop,
                banner = null,
                description = null,
                matrices = UiProfile.Matrices(0, 0, 0),
                mark = persistentListOf(),
                bottomContent = null,
            )
        val authorized = mode !in listOf("unauthorized", "authorize")
        val item =
            PluginExtensionAccountItemV1(
                key.toString(),
                "VVo",
                listOf("https://m.weibo.cn", "https://weibo.com"),
                "review",
                if (authorized) "fixture-grant" else null,
                if (mode in
                    listOf("automatic", "running")
                ) {
                    setOf("checkin")
                } else {
                    emptySet()
                },
                account,
                if (mode == "fallback") null else profile,
            )
        val action =
            PluginExtensionActionItemV1(
                "checkin",
                UiText.Raw("每日签到"),
                UiText.Raw("为已关注的超话签到。"),
                if (mode ==
                    "manual"
                ) {
                    null
                } else {
                    "Asia/Shanghai"
                },
            )
        val status =
            when (mode) {
                "completed" -> PluginExtensionResultStatusV1.Completed
                "already-completed" -> PluginExtensionResultStatusV1.AlreadyCompleted
                "partial" -> PluginExtensionResultStatusV1.Partial
                "failed" -> PluginExtensionResultStatusV1.Failed
                "authentication-required" -> PluginExtensionResultStatusV1.AuthenticationRequired
                "user-action-required" -> PluginExtensionResultStatusV1.UserActionRequired
                "skipped" -> PluginExtensionResultStatusV1.Skipped
                "interrupted", "running" -> PluginExtensionResultStatusV1.Interrupted
                else -> null
            }
        val runs =
            status
                ?.let {
                    listOf(
                        PluginExtensionRunV1(
                            "fixture-run",
                            "fixture-grant",
                            "checkin",
                            "2026-11-09",
                            Clock.System.now().toEpochMilliseconds() - 120_000,
                            if (mode ==
                                "running"
                            ) {
                                null
                            } else {
                                Clock.System.now().toEpochMilliseconds()
                            },
                            PluginExtensionResultV1(
                                it,
                                succeeded =
                                    if (mode ==
                                        "completed"
                                    ) {
                                        5
                                    } else if (mode ==
                                        "partial"
                                    ) {
                                        4
                                    } else {
                                        0
                                    },
                                skipped =
                                    if (mode == "skipped" ||
                                        mode == "already-completed"
                                    ) {
                                        5
                                    } else {
                                        0
                                    },
                                failed = if (mode == "failed" || mode == "partial") 1 else 0,
                            ),
                        ),
                    )
                }.orEmpty()
        return PluginExtensionStateV1(
            name = UiText.Raw("微博每日任务"),
            available = true,
            actions = listOf(action),
            accounts =
                if (mode ==
                    "empty" ||
                    mode == "loading"
                ) {
                    emptyList()
                } else {
                    listOf(item)
                },
            runs = runs,
            runningAccounts =
                if (mode ==
                    "running"
                ) {
                    setOf("fixture-grant")
                } else {
                    emptySet()
                },
            error = mode == "error",
            loading = mode == "loading",
        )
    }
}
