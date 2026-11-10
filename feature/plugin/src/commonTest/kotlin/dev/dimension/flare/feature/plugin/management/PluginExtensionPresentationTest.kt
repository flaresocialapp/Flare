package dev.dimension.flare.feature.plugin.management

import dev.dimension.flare.feature.plugin.extension.PluginExtensionAccountItemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionStateV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.presenter.settings.AccountsState
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginExtensionPresentationTest {
    private val alice = MicroBlogKey("alice", "weibo.com")
    private val bob = MicroBlogKey("bob", "weibo.com")
    private val grant =
        PluginExtensionAccountItemV1(alice.toString(), "VVo", listOf("https://weibo.com"), "review", "grant", setOf("checkin"))

    @Test
    fun accountPresentationMatchesTheAccountKeyWithoutChangingAuthorization() {
        val state = PluginExtensionStateV1(accounts = listOf(grant))
        val result = state.withAccountPresentation(listOf(metadata(bob, "Bob"), metadata(alice, "Alice")))

        assertEquals(1, result.accounts.size)
        assertEquals(
            alice,
            result.accounts
                .single()
                .profile
                ?.key,
        )
        assertEquals(
            "Alice",
            result.accounts
                .single()
                .profile
                ?.name
                ?.raw,
        )
        assertEquals(
            UiText.Raw("微博"),
            result.accounts
                .single()
                .account
                ?.platformDisplayNameText,
        )
        assertEquals(grant, result.accounts.single().copy(account = null, profile = null))
    }

    @Test
    fun missingOrFailedProfilesLeaveTheAccountAndItsActionsUsable() {
        val state = PluginExtensionStateV1(accounts = listOf(grant))
        val failed = metadata(alice, "Alice").copy(profile = UiState.Error(IllegalStateException("offline")))
        val result = state.withAccountPresentation(listOf(failed)).accounts.single()

        assertNull(result.profile)
        assertEquals(alice, result.account?.accountKey)
        assertEquals(grant, result.copy(account = null))
        assertEquals(state, state.withAccountPresentation(emptyList()))
    }

    private fun metadata(
        key: MicroBlogKey,
        name: String,
    ) = AccountsState.AccountItem(
        UiAccount(key, "VVo", "微博"),
        UiState.Success(
            UiProfile(
                key = key,
                handle = UiHandle(key.id, key.host),
                avatar = null,
                nameInternal = name.toUiPlainText(),
                platformId = "VVo",
                clickEvent = ClickEvent.Noop,
                banner = null,
                description = null,
                matrices = UiProfile.Matrices(0, 0, 0),
                mark = persistentListOf(),
                bottomContent = null,
            ),
        ),
    )
}
