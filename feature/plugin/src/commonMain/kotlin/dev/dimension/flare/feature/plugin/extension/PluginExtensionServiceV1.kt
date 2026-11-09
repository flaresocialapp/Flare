package dev.dimension.flare.feature.plugin.extension

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.datasource.microblog.datasource.PlatformExtensionDataSource
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginAccountHttpAccess
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.platformUuid
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.manifest.PluginManifestV1
import dev.dimension.flare.feature.plugin.manifest.toUiText
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.storage.PluginStorageChangesV1
import dev.dimension.flare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Account grants and attempt history are Host-owned, never exposed through plugin storage. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PluginExtensionServiceV1(
    private val subsystem: PluginSubsystemV1,
    private val accounts: AccountService,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
    private val runtimePool: PluginRuntimePool = subsystem.runtimePool,
) {
    private val mutex = Mutex()
    private val available = MutableStateFlow<List<AccountMicroblogDataSource>>(emptyList())
    private val records = MutableStateFlow<Map<String, StoredPluginExtensionsV1>>(emptyMap())
    private val active = MutableStateFlow<Map<String, ActiveRun>>(emptyMap())
    private val errors = MutableStateFlow<Set<String>>(emptySet())
    private val foreground = MutableStateFlow(ForegroundState(false))
    private val foregroundJob = MutableStateFlow<Job?>(null)
    private val pendingScan = MutableStateFlow(false)

    init {
        subsystem.running
            .combine(foreground) { running, visible ->
                visible.active &&
                    running.plugins.values.any { it.installed.manifest.platformExtension != null }
            }.flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(emptyList())
                } else {
                    accounts.allAccountServicesFlow().catch { error ->
                        if (error is CancellationException) throw error
                        reportAccountFailure()
                        emit(emptyList())
                    }
                }
            }.onEach { current ->
                available.value = current.filter { it.dataSource is PlatformExtensionDataSource }
                cancelInvalidRuns()
            }.launchIn(scope)
        subsystem.running
            .onEach { running ->
                mutex.withLock {
                    running.plugins.values.filter { it.installed.manifest.platformExtension != null }.forEach { plugin ->
                        try {
                            load(plugin.installed.pluginId)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            errors.update { it + plugin.installed.pluginId }
                        }
                    }
                    records.update { it.filterKeys(running.plugins::containsKey) }
                }
                cancelInvalidRuns()
            }.launchIn(scope)
    }

    fun observe(pluginId: String): Flow<PluginExtensionStateV1> =
        combine(subsystem.running, available, records, active, errors) { running, accounts, stored, jobs, failures ->
            val plugin = running.plugins[pluginId]
            val manifest = plugin?.installed?.manifest
            val extension = manifest?.platformExtension
            val data = stored[pluginId] ?: StoredPluginExtensionsV1()
            PluginExtensionStateV1(
                name =
                    manifest?.name?.toUiText(pluginId) ?: UiText.Raw(pluginId),
                available = extension != null,
                actions =
                    extension?.actions.orEmpty().map {
                        PluginExtensionActionItemV1(
                            it.id,
                            it.title.toUiText(pluginId),
                            it.description?.toUiText(pluginId),
                            it.dailyTimeZone,
                        )
                    },
                accounts =
                    accounts.mapNotNull { account ->
                        val target = extension?.targets?.singleOrNull { it.platformId == account.platformId } ?: return@mapNotNull null
                        val adapter = account.dataSource as PlatformExtensionDataSource
                        if (!adapter.extensionAuthOrigins.containsAll(target.authOrigins)) return@mapNotNull null
                        val grant = data.grants.singleOrNull { it.matches(account, requireNotNull(manifest)) }
                        PluginExtensionAccountItemV1(
                            account.accountKey.toString(),
                            account.platformId,
                            target.authOrigins.sorted(),
                            account.reviewToken(plugin),
                            grant?.id,
                            grant?.automaticActions.orEmpty(),
                        )
                    },
                runs = data.runs.filter { run -> data.grants.any { it.id == run.accountId } }.reversed(),
                runningAccounts = data.grants.filter { it.executionKey(pluginId) in jobs }.mapTo(mutableSetOf()) { it.id },
                error = pluginId in failures,
            )
        }

    suspend fun authorize(
        pluginId: String,
        accountKey: String,
        authorizationToken: String,
    ) {
        mutex.withLock {
            val plugin = plugin(pluginId)
            val account = available.value.single { it.accountKey.toString() == accountKey }
            check(authorizationToken == account.reviewToken(plugin)) { "Authorization review expired" }
            check(accounts.isSessionActive(account.accountKey, account.sessionId)) { "Account session expired" }
            val manifest = plugin.installed.manifest
            val target = requireNotNull(manifest.platformExtension).targets.single { it.platformId == account.platformId }
            check((account.dataSource as PlatformExtensionDataSource).extensionAuthOrigins.containsAll(target.authOrigins)) {
                "Unsupported authentication origin"
            }
            val data = load(pluginId)
            require(data.grants.count { it.accountKey != accountKey } < 64) { "Too many account grants" }
            val grant =
                PluginExtensionGrantV1(platformUuid(), accountKey, account.platformId, account.sessionId, manifest.extensionPermissions())
            save(pluginId, data.copy(grants = data.grants.filterNot { it.accountKey == accountKey } + grant))
            active.value[grant.executionKey(pluginId)]?.job?.cancel()
        }
    }

    suspend fun revoke(
        pluginId: String,
        accountId: String,
    ) {
        mutex.withLock {
            val data = load(pluginId)
            val job = data.grants.singleOrNull { it.id == accountId }?.let { active.value[it.executionKey(pluginId)]?.job }
            save(
                pluginId,
                data.copy(
                    grants = data.grants.filterNot { it.id == accountId },
                    runs =
                        data.runs.filterNot {
                            it.accountId ==
                                accountId
                        },
                ),
            )
            job?.cancel()
        }
    }

    suspend fun setAutomatic(
        pluginId: String,
        accountId: String,
        actionId: String,
        enabled: Boolean,
    ) {
        mutex.withLock {
            val plugin = plugin(pluginId)
            val action = requireNotNull(plugin.installed.manifest.platformExtension).actions.single { it.id == actionId }
            require(action.dailyTimeZone != null) { "This action has no daily schedule" }
            val data = load(pluginId)
            val grant = data.grants.single { it.id == accountId }
            requireCurrent(plugin, grant)
            val updated =
                grant.copy(
                    automaticActions =
                        if (enabled) {
                            grant.automaticActions + actionId
                        } else {
                            grant.automaticActions -
                                actionId
                        },
                )
            save(pluginId, data.copy(grants = data.grants.map { if (it.id == accountId) updated else it }))
        }
        if (enabled && foreground.value.active) launchDue()
    }

    fun setForeground(value: Boolean) {
        foreground.update { if (it.active == value) it else ForegroundState(value) }
        if (value) {
            launchDue()
        } else {
            foregroundJob.getAndUpdate { null }?.cancel()
            active.value.values.forEach { it.job.cancel() }
        }
    }

    fun cancel(
        pluginId: String,
        accountId: String,
    ) {
        val grant = records.value[pluginId]?.grants?.singleOrNull { it.id == accountId } ?: return
        active.value[grant.executionKey(pluginId)]?.job?.cancel()
    }

    fun reportError(pluginId: String) {
        errors.update { it + pluginId }
    }

    private fun launchDue() {
        if (!foreground.value.active ||
            subsystem.running.value.plugins.values
                .none { it.installed.manifest.platformExtension != null }
        ) {
            return
        }
        pendingScan.value = true
        startScan()
    }

    private fun startScan() {
        val previous = foregroundJob.value
        if (previous != null && !previous.isCompleted) return
        val scheduled =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (foreground.value.active && pendingScan.getAndUpdate { false }) scanDue()
                } finally {
                    foregroundJob.compareAndSet(currentCoroutineContext().job, null)
                    if (foreground.value.active && pendingScan.value) startScan()
                }
            }
        if (foregroundJob.compareAndSet(previous, scheduled)) scheduled.start() else scheduled.cancel()
    }

    private suspend fun scanDue() {
        val visibility = foreground.value
        try {
            accounts.allAccountServicesFlow().collectFirstAccounts()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            reportAccountFailure()
            return
        }
        for (plugin in subsystem.running.value.plugins.values) {
            val extension = plugin.installed.manifest.platformExtension ?: continue
            val data =
                try {
                    mutex.withLock { load(plugin.installed.pluginId) }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                } catch (_: Exception) {
                    errors.update { it + plugin.installed.pluginId }
                    continue
                }
            for (grant in data.grants) {
                for (action in extension.actions) {
                    if (!foreground.value.active) return
                    if (action.id !in grant.automaticActions) continue
                    try {
                        coroutineScope { execute(plugin.installed.pluginId, grant.id, action.id, true, ExecutionLease(plugin, visibility)) }
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        errors.update { it + plugin.installed.pluginId }
                    }
                }
            }
        }
    }

    fun start(
        pluginId: String,
        accountId: String,
        actionId: String,
    ): Job {
        val lease = executionLease(pluginId)
        return scope.launch {
            try {
                execute(pluginId, accountId, actionId, false, lease)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                reportError(pluginId)
            }
        }
    }

    private fun reportAccountFailure() {
        errors.update { existing ->
            existing +
                subsystem.running.value.plugins.values
                    .filter { it.installed.manifest.platformExtension != null }
                    .map { it.installed.pluginId }
        }
    }

    private suspend fun Flow<List<AccountMicroblogDataSource>>.collectFirstAccounts() {
        available.value = first().filter { it.dataSource is PlatformExtensionDataSource }
    }

    suspend fun run(
        pluginId: String,
        accountId: String,
        actionId: String,
        automatic: Boolean = false,
    ) {
        execute(pluginId, accountId, actionId, automatic, executionLease(pluginId))
    }

    private suspend fun execute(
        pluginId: String,
        accountId: String,
        actionId: String,
        automatic: Boolean,
        lease: ExecutionLease,
    ) {
        val execution = prepareExecution(pluginId, accountId, actionId, automatic, lease) ?: return
        val key = execution.key
        var result = PluginExtensionResultV1(PluginExtensionResultStatusV1.Interrupted)
        try {
            val adapter = execution.account.dataSource as PlatformExtensionDataSource
            val target =
                requireNotNull(execution.plugin.installed.manifest.platformExtension).targets.single {
                    it.platformId ==
                        execution.account.platformId
                }
            val access =
                object : PluginAccountHttpAccess {
                    override val origins = target.authOrigins.intersect(adapter.extensionAuthOrigins)

                    override suspend fun ensureActive() {
                        currentCoroutineContext().ensureActive()
                        ensureLease(pluginId, lease)
                        if (!foreground.value.active || subsystem.running.value.plugins[pluginId] !== execution.plugin ||
                            records.value[pluginId]?.grants?.none { it == execution.grant } != false ||
                            !accounts.isSessionActive(execution.account.accountKey, execution.grant.sessionId)
                        ) {
                            throw CancellationException("Extension authorization expired")
                        }
                        execution.plugin.session.ensureActive()
                    }

                    override suspend fun headers(url: String): Map<String, String> {
                        ensureActive()
                        return adapter.extensionAuthHeaders(url).also { ensureActive() }
                    }
                }
            result =
                runtimePool.invoke(
                    plugin = execution.plugin,
                    key = PluginRuntimeKeyV1.extension(pluginId, execution.plugin.installed.packageHash, accountId),
                    context =
                        PluginInvocationContextV1.extension(
                            pluginId,
                            execution.plugin.installed.packageHash,
                            execution.plugin.installed.manifest.platform
                                ?.id
                                .orEmpty(),
                            execution.account.platformId,
                            accountId,
                            execution.account.accountKey,
                            execution.plugin.installed.manifest.permissions.httpOrigins,
                            Locale.language,
                            access,
                        ),
                    method = "platformExtension.actions.$actionId",
                    request = PluginExtensionRequestV1(actionId, execution.attempt.businessDate, if (automatic) "automatic" else "manual"),
                    requestSerializer = PluginExtensionRequestV1.serializer(),
                    responseSerializer = PluginExtensionResultV1.serializer(),
                    timeout = PluginCallTimeoutV1.Extended,
                    validate = { it.requireValid() },
                )
            access.ensureActive()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result = PluginExtensionResultV1(PluginExtensionResultStatusV1.Failed)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    try {
                        if (subsystem.running.value.plugins[pluginId] === execution.plugin) {
                            val data = load(pluginId)
                            val pause =
                                result.status in
                                    setOf(
                                        PluginExtensionResultStatusV1.AuthenticationRequired,
                                        PluginExtensionResultStatusV1.UserActionRequired,
                                    )
                            save(
                                pluginId,
                                data.copy(
                                    runs =
                                        data.runs.map {
                                            if (it.id ==
                                                execution.attempt.id
                                            ) {
                                                it.copy(finishedAtMillis = now().toEpochMilliseconds(), result = result)
                                            } else {
                                                it
                                            }
                                        },
                                    grants =
                                        data.grants.map {
                                            if (pause &&
                                                it.id == accountId
                                            ) {
                                                it.copy(automaticActions = emptySet())
                                            } else {
                                                it
                                            }
                                        },
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        errors.update { it + pluginId }
                    } finally {
                        active.update { if (it[key] === execution.active) it - key else it }
                        execution.active.finished.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun prepareExecution(
        pluginId: String,
        accountId: String,
        actionId: String,
        automatic: Boolean,
        lease: ExecutionLease,
    ): Execution? {
        while (true) {
            var waiting: CompletableDeferred<Unit>? = null
            val execution =
                mutex.withLock {
                    ensureLease(pluginId, lease)
                    val plugin = plugin(pluginId)
                    val data = load(pluginId)
                    val grant = data.grants.single { it.id == accountId }
                    val key = grant.executionKey(pluginId)
                    active.value[key]?.let { running ->
                        if (!automatic) return null
                        waiting = running.finished
                        return@withLock null
                    }
                    val account = requireCurrent(plugin, grant)
                    ensureLease(pluginId, lease)
                    val action = requireNotNull(plugin.installed.manifest.platformExtension).actions.single { it.id == actionId }
                    val date =
                        now()
                            .toLocalDateTime(
                                action.dailyTimeZone?.let(TimeZone::of) ?: TimeZone.currentSystemDefault(),
                            ).date
                            .toString()
                    if (automatic &&
                        (actionId !in grant.automaticActions || data.attemptDates[grant.attemptKey(actionId)].orEmpty() >= date)
                    ) {
                        return null
                    }
                    val attempt = PluginExtensionRunV1(platformUuid(), accountId, actionId, date, now().toEpochMilliseconds())
                    // Persist before any remote effect; ambiguous attempts are never automatically replayed that day.
                    save(
                        pluginId,
                        data.copy(
                            runs = (data.runs + attempt).takeLast(50),
                            attemptDates =
                                data.attemptDates +
                                    (grant.attemptKey(actionId) to maxOf(date, data.attemptDates[grant.attemptKey(actionId)].orEmpty())),
                        ),
                    )
                    val running = ActiveRun(currentCoroutineContext().job)
                    active.update { it + (key to running) }
                    Execution(plugin, grant, account, attempt, key, running)
                }
            if (execution != null) return execution
            requireNotNull(waiting).await()
        }
    }

    private fun executionLease(pluginId: String) = ExecutionLease(subsystem.running.value.plugins[pluginId], foreground.value)

    private fun ensureLease(
        pluginId: String,
        lease: ExecutionLease,
    ) {
        check(lease.visibility.active) { "Plugin actions require the foreground" }
        if (foreground.value !== lease.visibility || subsystem.running.value.plugins[pluginId] !== lease.plugin) {
            throw CancellationException("Extension execution was invalidated")
        }
    }

    private suspend fun requireCurrent(
        plugin: RunningPluginV1,
        grant: PluginExtensionGrantV1,
    ): AccountMicroblogDataSource {
        check(grant.permissions == plugin.installed.manifest.extensionPermissions()) { "Extension permissions changed" }
        val account =
            available.value.singleOrNull { grant.matches(it, plugin.installed.manifest) } ?: error("Account authorization expired")
        check(accounts.isSessionActive(account.accountKey, grant.sessionId)) { "Account authorization expired" }
        return account
    }

    private fun cancelInvalidRuns() {
        active.value.forEach { (key, run) ->
            val pluginId = key.substringBefore('/')
            val plugin = subsystem.running.value.plugins[pluginId]
            val grant = records.value[pluginId]?.grants?.singleOrNull { it.executionKey(pluginId) == key }
            if (plugin == null || grant == null || available.value.none { grant.matches(it, plugin.installed.manifest) }) run.job.cancel()
        }
    }

    private fun plugin(pluginId: String): RunningPluginV1 =
        requireNotNull(subsystem.running.value.plugins[pluginId]).also {
            it.session.ensureActive()
            require(it.installed.manifest.platformExtension != null) { "Plugin has no platform extension" }
        }

    private suspend fun load(pluginId: String): StoredPluginExtensionsV1 {
        subsystem.extensionState.prepare(pluginId)
        val value = subsystem.extensionState.read(pluginId, "host", setOf("state")) { plugin(pluginId) }.values["state"]
        return (
            if (value == null ||
                value == JsonNull
            ) {
                StoredPluginExtensionsV1()
            } else {
                PluginJsonV1.decodeFromJsonElement(StoredPluginExtensionsV1.serializer(), value)
            }
        ).also { data ->
            records.update { it + (pluginId to data) }
        }
    }

    private suspend fun save(
        pluginId: String,
        data: StoredPluginExtensionsV1,
    ) {
        val oldestDate = (now() - 3.days).toLocalDateTime(TimeZone.UTC).date.toString()
        val recentAttempts = data.attemptDates.filterValues { it >= oldestDate }
        require(recentAttempts.size <= 4096) { "Too many recent extension attempts" }
        val bounded = data.copy(attemptDates = recentAttempts)
        subsystem.extensionState.write(
            pluginId,
            "host",
            PluginStorageChangesV1(
                set =
                    mapOf("state" to PluginJsonV1.encodeToJsonElement(StoredPluginExtensionsV1.serializer(), bounded)),
            ),
            null,
        ) { plugin(pluginId) }
        records.update { it + (pluginId to bounded) }
        errors.update { it - pluginId }
    }

    private class ForegroundState(
        val active: Boolean,
    )

    private data class ExecutionLease(
        val plugin: RunningPluginV1?,
        val visibility: ForegroundState,
    )

    private class ActiveRun(
        val job: Job,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class Execution(
        val plugin: RunningPluginV1,
        val grant: PluginExtensionGrantV1,
        val account: AccountMicroblogDataSource,
        val attempt: PluginExtensionRunV1,
        val key: String,
        val active: ActiveRun,
    )
}

private fun PluginExtensionGrantV1.matches(
    account: AccountMicroblogDataSource,
    manifest: PluginManifestV1,
): Boolean =
    accountKey == account.accountKey.toString() && platformId == account.platformId && sessionId == account.sessionId &&
        permissions == manifest.extensionPermissions()

private fun PluginManifestV1.extensionPermissions(): String =
    buildList {
        platformExtension?.targets?.sortedBy { it.platformId }?.forEach {
            add(it.platformId)
            addAll(it.authOrigins.sorted())
        }
        platformExtension?.actions?.sortedBy { it.id }?.forEach {
            add(it.id)
            add(it.dailyTimeZone.orEmpty())
        }
        addAll(permissions.httpOrigins.sorted())
        add(permissions.mediaNetwork.toString())
    }.let { JsonArray(it.map(::JsonPrimitive)).toString() }
        .encodeUtf8()
        .sha256()
        .hex()

private fun AccountMicroblogDataSource.reviewToken(plugin: RunningPluginV1): String =
    JsonArray(
        listOf(
            accountKey.toString(),
            sessionId,
            plugin.installed.packageHash,
            plugin.installed.manifest.extensionPermissions(),
        ).map(::JsonPrimitive),
    ).toString()
        .encodeUtf8()
        .sha256()
        .hex()

private fun PluginExtensionGrantV1.attemptKey(actionId: String): String =
    JsonArray(listOf(platformId, accountKey, actionId).map(::JsonPrimitive))
        .toString()
        .encodeUtf8()
        .sha256()
        .hex()

private fun PluginExtensionGrantV1.executionKey(pluginId: String): String =
    "$pluginId/" +
        JsonArray(listOf(platformId, accountKey).map(::JsonPrimitive))
            .toString()
            .encodeUtf8()
            .sha256()
            .hex()
