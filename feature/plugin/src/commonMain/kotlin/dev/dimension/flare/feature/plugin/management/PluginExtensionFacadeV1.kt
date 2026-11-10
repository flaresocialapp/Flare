package dev.dimension.flare.feature.plugin.management

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionStateV1
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.settings.AccountsPresenter
import dev.dimension.flare.ui.presenter.settings.AccountsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Native account authorization and execution UI share the same state on all supported platforms. */
public class PluginExtensionFacadeV1(
    private val pluginId: String,
) : PresenterBase<PluginExtensionStateV1>() {
    private val service = koinGet<PluginSubsystemV1>().extensions
    public val state: Flow<PluginExtensionStateV1> = service.observe(pluginId)

    @Composable
    override fun body(): PluginExtensionStateV1 {
        val current by state.collectAsState(PluginExtensionStateV1(loading = true))
        val accounts =
            remember { AccountsPresenter() }
                .invoke()
                .accounts
                .takeSuccess()
                .orEmpty()
        return current.withAccountPresentation(accounts)
    }

    @Throws(Exception::class)
    public suspend fun authorize(
        accountKey: String,
        authorizationToken: String,
    ) {
        action { service.authorize(pluginId, accountKey, authorizationToken) }
    }

    @Throws(Exception::class)
    public suspend fun revoke(accountId: String) {
        action { service.revoke(pluginId, accountId) }
    }

    @Throws(Exception::class)
    public suspend fun setAutomatic(
        accountId: String,
        actionId: String,
        enabled: Boolean,
    ) {
        action { service.setAutomatic(pluginId, accountId, actionId, enabled) }
    }

    @Throws(Exception::class)
    public suspend fun run(
        accountId: String,
        actionId: String,
    ) {
        service.start(pluginId, accountId, actionId)
    }

    public fun cancel(accountId: String) {
        service.cancel(pluginId, accountId)
    }

    private suspend fun action(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            service.reportError(pluginId)
            throw error
        }
    }
}

internal fun PluginExtensionStateV1.withAccountPresentation(accounts: List<AccountsState.AccountItem>): PluginExtensionStateV1 {
    val metadata = accounts.associateBy { it.account.accountKey.toString() }
    return copy(
        accounts =
            this.accounts.map { item ->
                val account = metadata[item.accountKey]
                item.copy(account = account?.account, profile = account?.profile?.takeSuccess())
            },
    )
}
