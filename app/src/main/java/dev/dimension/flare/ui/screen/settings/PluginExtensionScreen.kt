package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dimension.flare.R
import dev.dimension.flare.feature.plugin.extension.PluginExtensionAccountItemV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionResultStatusV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionStateV1
import dev.dimension.flare.feature.plugin.management.PluginExtensionFacadeV1
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.resolveText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginExtensionScreen(
    pluginId: String,
    onBack: () -> Unit,
) {
    val facade = remember(pluginId) { PluginExtensionFacadeV1(pluginId) }
    val state by facade.state.collectAsState(PluginExtensionStateV1())
    val scope = rememberCoroutineScope()
    var review by remember { mutableStateOf<PluginExtensionAccountItemV1?>(null) }

    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }
    review?.let { account ->
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text(stringResource(R.string.plugin_extension_authorize)) },
            text = {
                Text(
                    stringResource(R.string.plugin_extension_authorize_message, account.accountKey, account.authOrigins.joinToString("\n")),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    review = null
                    action { facade.authorize(account.accountKey, account.authorizationToken) }
                }) { Text(stringResource(R.string.plugin_extension_authorize)) }
            },
            dismissButton = { TextButton(onClick = { review = null }) { Text(stringResource(android.R.string.cancel)) } },
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
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.name.resolveText(), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.plugin_extension_foreground_notice))
                    if (state.error) Text(stringResource(R.string.plugin_operation_failed), color = MaterialTheme.colorScheme.error)
                    if (state.accounts.isEmpty()) Text(stringResource(R.string.plugin_extension_no_accounts))
                }
            }
            items(state.accounts, key = { it.accountKey }) { account ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.accountKey, style = MaterialTheme.typography.titleMedium)
                        Text(account.platformId, style = MaterialTheme.typography.bodySmall)
                        val id = account.accountId
                        if (id == null) {
                            TextButton(onClick = { review = account }) { Text(stringResource(R.string.plugin_extension_authorize)) }
                        } else {
                            val running = id in state.runningAccounts
                            TextButton(
                                onClick = { action { facade.revoke(id) } },
                            ) { Text(stringResource(R.string.plugin_extension_revoke)) }
                            state.actions.forEach { operation ->
                                Text(operation.title.resolveText(), style = MaterialTheme.typography.titleSmall)
                                operation.description?.let { Text(it.resolveText()) }
                                val timeZone = operation.dailyTimeZone
                                if (timeZone != null) {
                                    val automaticLabel = stringResource(R.string.plugin_extension_automatic, timeZone)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(automaticLabel, Modifier.weight(1f))
                                        Switch(
                                            modifier = Modifier.semantics { contentDescription = automaticLabel },
                                            checked = operation.id in account.automaticActions,
                                            enabled = !running,
                                            onCheckedChange = { enabled -> action { facade.setAutomatic(id, operation.id, enabled) } },
                                        )
                                    }
                                }
                                TextButton(enabled = !running, onClick = { action { facade.run(id, operation.id) } }) {
                                    Text(stringResource(R.string.plugin_extension_run))
                                }
                            }
                            if (running) {
                                CircularProgressIndicator()
                                TextButton(onClick = { facade.cancel(id) }) { Text(stringResource(android.R.string.cancel)) }
                            }
                        }
                    }
                }
            }
            if (state.runs.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.plugin_extension_history),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            items(state.runs, key = { it.id }) { run ->
                val account = state.accounts.firstOrNull { it.accountId == run.accountId }
                val operation = state.actions.firstOrNull { it.id == run.actionId }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("${operation?.title?.resolveText().orEmpty()} · ${run.businessDate}")
                    Text(account?.accountKey.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (run.accountId in state.runningAccounts &&
                            run.finishedAtMillis == null
                        ) {
                            stringResource(R.string.plugin_extension_running)
                        } else {
                            extensionResultText(run.result.status)
                        },
                    )
                    Text(
                        stringResource(R.string.plugin_extension_counts, run.result.succeeded, run.result.skipped, run.result.failed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
