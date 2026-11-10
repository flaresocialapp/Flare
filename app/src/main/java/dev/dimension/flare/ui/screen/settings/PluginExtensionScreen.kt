package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.CircleExclamation
import compose.icons.fontawesomeicons.solid.CircleInfo
import compose.icons.fontawesomeicons.solid.UserPlus
import dev.dimension.flare.R
import dev.dimension.flare.feature.plugin.extension.PluginExtensionAccountItemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionResultStatusV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionStateV1
import dev.dimension.flare.feature.plugin.management.PluginExtensionFacadeV1
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.TabIcon
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.component.status.ListEmptyView
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import dev.dimension.flare.ui.theme.segmentedShapes2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.tlaster.precompose.molecule.producePresenter

@Composable
internal fun PluginExtensionScreen(
    pluginId: String,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val facade = remember(pluginId) { PluginExtensionFacadeV1(pluginId) }
    val state by producePresenter { facade.invoke() }
    val scope = rememberCoroutineScope()

    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }
    PluginExtensionContent(
        state = state,
        onBack = onBack,
        onLogin = onLogin,
        onAuthorize = { account -> action { facade.authorize(account.accountKey, account.authorizationToken) } },
        onRevoke = { action { facade.revoke(it) } },
        onAutomatic = { account, operation, enabled -> action { facade.setAutomatic(account, operation, enabled) } },
        onRun = { account, operation -> action { facade.run(account, operation) } },
        onCancel = facade::cancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PluginExtensionContent(
    state: PluginExtensionStateV1,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onAuthorize: (PluginExtensionAccountItemV1) -> Unit,
    onRevoke: (String) -> Unit,
    onAutomatic: (String, String, Boolean) -> Unit,
    onRun: (String, String) -> Unit,
    onCancel: (String) -> Unit,
) {
    var authorization by remember { mutableStateOf<PluginExtensionAccountItemV1?>(null) }
    var revocation by remember { mutableStateOf<PluginExtensionAccountItemV1?>(null) }
    authorization?.let { account ->
        AlertDialog(
            onDismissRequest = { authorization = null },
            title = { Text(stringResource(R.string.plugin_extension_authorize_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.plugin_extension_authorize_message,
                        account.profile?.handle?.display ?: account.accountKey,
                        account.authOrigins.joinToString("\n"),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    authorization = null
                    onAuthorize(account)
                }) { Text(stringResource(R.string.plugin_extension_authorize)) }
            },
            dismissButton = { TextButton(onClick = { authorization = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    revocation?.let { account ->
        AlertDialog(
            onDismissRequest = { revocation = null },
            title = { Text(stringResource(R.string.plugin_extension_revoke_title)) },
            text = {
                Text(
                    stringResource(R.string.plugin_extension_revoke_message, account.profile?.handle?.display ?: account.accountKey),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    revocation = null
                    account.accountId?.let(onRevoke)
                }) {
                    Text(stringResource(R.string.plugin_extension_revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { revocation = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    FlareScaffold(
        topBar = {
            FlareTopAppBar(
                title = { Text(stringResource(R.string.plugin_extension_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = screenHorizontalPadding),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.name.resolveText(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.plugin_extension_foreground_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.error) Text(stringResource(R.string.plugin_operation_failed), color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.loading) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            } else if (state.accounts.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ListEmptyView(text = stringResource(R.string.plugin_extension_no_accounts), icon = FontAwesomeIcons.Solid.UserPlus)
                        TextButton(onClick = onLogin) { Text(stringResource(R.string.quick_menu_add_account)) }
                    }
                }
            }
            items(state.accounts, key = { it.accountKey }) { account ->
                val id = account.accountId
                val busy = id in state.runningAccounts
                val actions = state.actions.filter { it.platformIds.isEmpty() || account.platformId in it.platformIds }
                val rowCount = 1 + actions.sumOf { if (it.dailyTimeZone == null) 1 else 2 }
                val platform = account.account?.platformDisplayNameText?.resolveText() ?: account.platformId
                val controls: @Composable () -> Unit = {
                    val access = "${stringResource(
                        R.string.plugin_extension_authorize,
                    )}: ${account.profile?.handle?.display ?: account.accountKey}"
                    Switch(
                        checked = id != null,
                        onCheckedChange = { enabled -> if (enabled) authorization = account else revocation = account },
                        modifier = Modifier.semantics { contentDescription = access },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    val profile = account.profile
                    if (profile != null) {
                        AccountItem(
                            userState = UiState.Success(profile),
                            shapes = ListItemDefaults.segmentedShapes2(0, rowCount),
                            onClick = { if (id == null) authorization = account else revocation = account },
                            toLogin = onLogin,
                            supportingContent = { Text(platform, maxLines = 1) },
                            trailingContent = { controls() },
                        )
                    } else {
                        SegmentedListItem(
                            onClick = { if (id == null) authorization = account else revocation = account },
                            shapes = ListItemDefaults.segmentedShapes2(0, rowCount),
                            content = { Text(platform) },
                            supportingContent = { Text(account.accountKey) },
                            leadingContent = {
                                TabIcon(
                                    icon = account.account?.platformIcon ?: UiIcon.World.asType(),
                                    contentDescription = null,
                                    size = 40.dp,
                                )
                            },
                            trailingContent = controls,
                        )
                    }
                    var row = 1
                    actions.forEach { operation ->
                        val running =
                            busy && state.runs.any { it.accountId == id && it.actionId == operation.id && it.finishedAtMillis == null }
                        ListItem(
                            modifier = Modifier.clip(ListItemDefaults.segmentedShapes2(row++, rowCount).shape),
                            content = { Text(operation.title.resolveText()) },
                            supportingContent = operation.description?.let { description -> { Text(description.resolveText()) } },
                            trailingContent = {
                                if (running && id != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.plugin_extension_running), style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { onCancel(id) }) { Text(stringResource(android.R.string.cancel)) }
                                    }
                                } else {
                                    TextButton(enabled = id != null && !busy, onClick = {
                                        id?.let { onRun(it, operation.id) }
                                    }) { Text(stringResource(R.string.plugin_extension_run)) }
                                }
                            },
                        )
                        operation.dailyTimeZone?.let { zone ->
                            val automatic = stringResource(R.string.plugin_extension_automatic)
                            SegmentedListItem(
                                enabled = id != null && !busy,
                                onClick = {
                                    if (id != null &&
                                        !busy
                                    ) {
                                        onAutomatic(id, operation.id, operation.id !in account.automaticActions)
                                    }
                                },
                                shapes = ListItemDefaults.segmentedShapes2(row++, rowCount),
                                content = { Text(automatic) },
                                supportingContent = { Text(stringResource(R.string.plugin_extension_automatic_description, zone)) },
                                trailingContent = {
                                    Switch(
                                        checked = operation.id in account.automaticActions,
                                        enabled = id != null && !busy,
                                        onCheckedChange = { enabled -> id?.let { onAutomatic(it, operation.id, enabled) } },
                                        modifier = Modifier.semantics { contentDescription = automatic },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (state.accounts.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.plugin_extension_history),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.runs.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.plugin_extension_no_history),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(state.runs, key = { _, it -> it.id }) { index, run ->
                val account = state.accounts.firstOrNull { it.accountId == run.accountId }
                val operation = state.actions.firstOrNull { it.id == run.actionId }
                val running = run.accountId in state.runningAccounts && run.finishedAtMillis == null
                val status = run.result.status
                ListItem(
                    modifier = Modifier.clip(ListItemDefaults.segmentedShapes2(index, state.runs.size).shape),
                    content = { Text(operation?.title?.resolveText() ?: run.actionId) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (running) stringResource(R.string.plugin_extension_running) else extensionResultText(status))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    account?.profile?.name?.innerText ?: account?.accountKey.orEmpty(),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                DateTimeText(data = run.startedAt, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                stringResource(
                                    R.string.plugin_extension_counts,
                                    run.result.succeeded,
                                    run.result.skipped,
                                    run.result.failed,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    leadingContent = {
                        if (running) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            FAIcon(
                                when (status) {
                                    PluginExtensionResultStatusV1.Completed,
                                    PluginExtensionResultStatusV1.AlreadyCompleted,
                                    -> FontAwesomeIcons.Solid.Check

                                    PluginExtensionResultStatusV1.Skipped -> FontAwesomeIcons.Solid.CircleInfo

                                    else -> FontAwesomeIcons.Solid.CircleExclamation
                                },
                                contentDescription = null,
                                tint =
                                    if (status ==
                                        PluginExtensionResultStatusV1.Failed
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun extensionResultText(status: PluginExtensionResultStatusV1): String =
    stringResource(
        when (status) {
            PluginExtensionResultStatusV1.Completed -> R.string.plugin_extension_completed
            PluginExtensionResultStatusV1.AlreadyCompleted -> R.string.plugin_extension_already_completed
            PluginExtensionResultStatusV1.Partial -> R.string.plugin_extension_partial
            PluginExtensionResultStatusV1.Failed -> R.string.plugin_extension_failed
            PluginExtensionResultStatusV1.AuthenticationRequired -> R.string.plugin_extension_login_required
            PluginExtensionResultStatusV1.UserActionRequired -> R.string.plugin_extension_user_action_required
            PluginExtensionResultStatusV1.Skipped -> R.string.plugin_extension_skipped
            PluginExtensionResultStatusV1.Interrupted -> R.string.plugin_extension_interrupted
        },
    )
