package dev.dimension.flare.feature.plugin.extension

import dev.dimension.flare.data.datasource.microblog.AccountHttpCapability
import dev.dimension.flare.data.datasource.microblog.AccountHttpRequest
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.adapter.PluginDataSourceV1
import dev.dimension.flare.feature.plugin.adapter.accountCredential
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginTransportBodyV1
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.storage.PluginStorageChangesV1
import dev.dimension.flare.feature.plugin.wire.EntityKeyV1
import dev.dimension.flare.feature.plugin.wire.LoginSuccessV1
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.feature.plugin.wire.ProfileV1
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PluginExtensionIntegrationTest {
    @Test
    fun accountLoadingWaitsForTheFirstSnapshotAndEndsOnEmptyOrFailure() =
        runBlocking {
            for (mode in listOf("accounts", "empty", "failure")) {
                fixture {
                    val gate = CompletableDeferred<Unit>()
                    accounts.beforeRead = { gate.await() }
                    if (mode == "empty") accounts.values.value = emptyList()
                    accounts.failReads.value = mode == "failure"
                    service = newService()
                    service.setForeground(true)
                    try {
                        val initial = withTimeout(10_000) { service.observe(ID).first() }
                        assertTrue(initial.loading)
                        assertTrue(initial.accounts.isEmpty())
                        gate.complete(Unit)
                        val ready = withTimeout(10_000) { service.observe(ID).first { !it.loading } }
                        assertEquals(mode == "accounts", ready.accounts.isNotEmpty())
                        assertEquals(mode == "failure", ready.error)
                    } finally {
                        gate.complete(Unit)
                    }
                }
            }
        }

    @Test
    fun authorizedAccountRunsThroughRealJavaScriptWithoutExportingCredentials() =
        runBlocking {
            fixture {
                val candidate = snapshot().accounts.single()
                assertNull(candidate.accountId)
                assertFailsWith<NoSuchElementException> { service.run(ID, "unknown", "execute") }
                assertFailsWith<IllegalStateException> { service.authorize(ID, candidate.accountKey, "stale") }
                val grant = authorize()
                service.run(ID, grant, "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertEquals(ProxyRef.forAccount(KEY), requests.single().proxy)
                assertEquals("SUB=private-cookie", requests.single().headers["Cookie"])
                assertEquals(
                    "1",
                    subsystem.storage
                        .access(ID) {}
                        .read("progress")
                        .values[grant]
                        .toString(),
                )
                service.revoke(ID, grant)
                assertNull(snapshot().accounts.single().accountId)
                assertTrue(snapshot().runs.isEmpty())
            }
        }

    @Test
    fun dailyAttemptsPersistAcrossRestartAndAllowManualRetry() =
        runBlocking {
            fixture {
                val grant = authorize()
                service.run(ID, grant, "execute")
                service.setAutomatic(ID, grant, "execute", true)
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(1, requests.size)
                service.setForeground(false)
                service = newService()
                service.setForeground(true)
                withTimeout(10_000) { service.observe(ID).first { it.runs.singleOrNull()?.finishedAtMillis != null } }
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(1, requests.size)
                service.run(ID, grant, "execute")
                assertEquals(2, requests.size)
                assertEquals("2026-11-10", snapshot().runs.first().businessDate)
            }
        }

    @Test
    fun reloginAndPermissionExpansionInvalidateGrantsAndReviews() =
        runBlocking {
            fixture {
                val candidate = snapshot().accounts.single()
                val grant = authorize()
                accounts.values.value =
                    listOf(
                        accounts.values.value
                            .single()
                            .copy(sessionId = "new-login"),
                    )
                withTimeout(10_000) { service.observe(ID).first { it.accounts.singleOrNull()?.accountId == null } }
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
                assertFailsWith<IllegalStateException> { service.authorize(ID, candidate.accountKey, candidate.authorizationToken) }
                val fresh = authorize()
                assertNotEquals(grant, fresh)
                val plugin =
                    subsystem.running.value.plugins
                        .getValue(ID)
                val manifest = plugin.installed.manifest
                val expandedPermissions = manifest.permissions.copy(httpOrigins = setOf("https://extra.example"))
                subsystem.replaceRunning(
                    ID,
                    plugin.copy(
                        installed =
                            plugin.installed.copy(
                                manifest =
                                    manifest.copy(
                                        permissions = expandedPermissions,
                                    ),
                            ),
                    ),
                )
                withTimeout(10_000) { service.observe(ID).first { it.accounts.singleOrNull()?.accountId == null } }
                assertFailsWith<IllegalStateException> { service.run(ID, fresh, "execute") }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun revocationCancelsInflightHttpAndPreventsSuccessCommit() =
        runBlocking {
            fixture {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                respond = {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<PluginTransportResponseV1>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val grant = authorize()
                val execution = async { service.run(ID, grant, "execute") }
                withTimeout(10_000) { started.await() }
                service.revoke(ID, grant)
                withTimeout(10_000) {
                    cancelled.await()
                    execution.join()
                }
                assertTrue(execution.isCancelled)
                assertTrue(snapshot().runs.isEmpty())
            }
        }

    @Test
    fun authenticationRequiredPausesAutomationAndBackgroundRejectsExecution() =
        runBlocking {
            fixture {
                val grant = authorize()
                service.setAutomatic(ID, grant, "execute", true)
                withTimeout(10_000) { service.observe(ID).first { it.runs.firstOrNull()?.finishedAtMillis != null } }
                respond = { PluginTransportResponseV1(200, emptyMap(), "authentication-required".encodeToByteArray()) }
                service.run(ID, grant, "execute")
                assertTrue(
                    snapshot()
                        .accounts
                        .single()
                        .automaticActions
                        .isEmpty(),
                )
                assertEquals(
                    PluginExtensionResultStatusV1.AuthenticationRequired,
                    snapshot()
                        .runs
                        .first()
                        .result.status,
                )
                service.setForeground(false)
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
            }
        }

    @Test
    fun trimmingHistoryDoesNotForgetDailyDeduplication() =
        runBlocking {
            fixture {
                val grant = authorize()
                repeat(52) { service.run(ID, grant, "execute") }
                assertEquals(50, snapshot().runs.size)
                service.setAutomatic(ID, grant, "execute", true)
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(52, requests.size)
            }
        }

    @Test
    fun leavingForegroundCancelsInflightWorkAndRecordsAnInterruptedAttempt() =
        runBlocking {
            fixture {
                val started = CompletableDeferred<Unit>()
                respond = {
                    started.complete(Unit)
                    CompletableDeferred<PluginTransportResponseV1>().await()
                }
                val grant = authorize()
                val execution = async { service.run(ID, grant, "execute") }
                withTimeout(10_000) { started.await() }
                service.setForeground(false)
                withTimeout(10_000) { execution.join() }
                assertTrue(execution.isCancelled)
                assertEquals(
                    PluginExtensionResultStatusV1.Interrupted,
                    service
                        .observe(ID)
                        .first { it.runs.singleOrNull()?.finishedAtMillis != null }
                        .runs
                        .single()
                        .result.status,
                )
            }
        }

    @Test
    fun requestBudgetStopsAnActionBeforeItsSixtyFifthRequest() =
        runBlocking {
            fixture(
                script =
                    """
                    defineFlarePlatformExtensionPlugin({ actions: { async execute() {
                      for (let i = 0; i < 65; i++) await flare.account.http.request({ url: "https://m.weibo.cn/api/config" });
                      return { status: "completed" };
                    } } });
                    """.trimIndent(),
            ) {
                val grant = authorize()
                service.run(ID, grant, "execute")
                assertEquals(64, requests.size)
                assertEquals(
                    PluginExtensionResultStatusV1.Failed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
            }
        }

    @Test
    fun regrantReloginAndPermissionChangesPreserveTheDailyAttempt() =
        runBlocking {
            fixture {
                respond = { PluginTransportResponseV1(200, emptyMap(), "partial".encodeToByteArray()) }
                val first = authorize()
                service.run(ID, first, "execute")
                service.revoke(ID, first)
                var grant = authorize()
                assertNotEquals(first, grant)
                service.setAutomatic(ID, grant, "execute", true)
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(1, requests.size)

                val plugin =
                    subsystem.running.value.plugins
                        .getValue(ID)
                val manifest = plugin.installed.manifest
                val expandedPermissions = manifest.permissions.copy(httpOrigins = setOf("https://extra.example"))
                subsystem.replaceRunning(
                    ID,
                    plugin.copy(
                        installed =
                            plugin.installed.copy(
                                manifest =
                                    manifest.copy(
                                        permissions = expandedPermissions,
                                    ),
                            ),
                    ),
                )
                withTimeout(10_000) { service.observe(ID).first { it.accounts.singleOrNull()?.accountId == null } }
                grant = authorize()
                service.setAutomatic(ID, grant, "execute", true)
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(1, requests.size)

                accounts.values.value =
                    listOf(
                        accounts.values.value
                            .single()
                            .copy(sessionId = "second-login"),
                    )
                withTimeout(10_000) { service.observe(ID).first { it.accounts.singleOrNull()?.accountId == null } }
                grant = authorize()
                service.setAutomatic(ID, grant, "execute", true)
                service.run(ID, grant, "execute", automatic = true)
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun enablingAnIdleAccountWhileAnotherRunsQueuesAnotherScan() =
        runBlocking {
            fixture {
                val secondKey = MicroBlogKey("456", "m.weibo.cn")
                accounts.values.value = accounts.values.value +
                    accounts.values.value
                        .single()
                        .copy(accountKey = secondKey)
                withTimeout(10_000) { service.observe(ID).first { it.accounts.size == 2 } }
                val first = authorize()
                val second = authorize(secondKey.toString())
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                respond = { request ->
                    if (request.proxy == ProxyRef.forAccount(KEY)) {
                        started.complete(Unit)
                        release.await()
                    }
                    PluginTransportResponseV1(200, emptyMap(), "completed".encodeToByteArray())
                }
                try {
                    service.setAutomatic(ID, first, "execute", true)
                    withTimeout(10_000) { started.await() }
                    service.setAutomatic(ID, second, "execute", true)
                    release.complete(Unit)
                    withTimeout(10_000) {
                        service.observe(ID).first { state ->
                            state.runs.any {
                                it.accountId == second &&
                                    it.finishedAtMillis != null
                            }
                        }
                    }
                    assertEquals(2, requests.size)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun lifecycleRetirementCancelsAnActionNamedUpdate() =
        runBlocking {
            fixture(actionIds = listOf("update")) {
                val started = CompletableDeferred<Unit>()
                respond = {
                    started.complete(Unit)
                    CompletableDeferred<PluginTransportResponseV1>().await()
                }
                val grant = authorize()
                val execution = async { service.run(ID, grant, "update") }
                withTimeout(10_000) {
                    started.await()
                    retire()
                    execution.join()
                }
                assertTrue(execution.isCancelled)
            }
        }

    @Test
    fun closingTheCallerScreenDoesNotCancelAnApplicationOwnedManualRun() =
        runBlocking {
            fixture {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                respond = {
                    started.complete(Unit)
                    release.await()
                    PluginTransportResponseV1(200, emptyMap(), "completed".encodeToByteArray())
                }
                val grant = authorize()
                val screen = CoroutineScope(Job() + Dispatchers.Default)
                val execution = CompletableDeferred<Job>()
                try {
                    screen.launch { execution.complete(service.start(ID, grant, "execute")) }.join()
                    withTimeout(10_000) { started.await() }
                    screen.coroutineContext[Job]?.cancelAndJoin()
                    assertTrue(execution.await().isActive)
                    release.complete(Unit)
                    withTimeout(10_000) { execution.await().join() }
                    assertEquals(
                        PluginExtensionResultStatusV1.Completed,
                        snapshot()
                            .runs
                            .single()
                            .result.status,
                    )
                } finally {
                    release.complete(Unit)
                    screen.cancel()
                }
            }
        }

    @Test
    fun accountReadFailuresRecoverOnTheNextForegroundEvent() =
        runBlocking {
            fixture {
                service.setForeground(false)
                accounts.failReads.value = true
                service.setForeground(true)
                withTimeout(10_000) { service.observe(ID).first { it.error && it.accounts.isEmpty() } }
                service.setForeground(false)
                accounts.failReads.value = false
                service.setForeground(true)
                withTimeout(10_000) { service.observe(ID).first { it.accounts.isNotEmpty() } }
                val grant = authorize()
                service.run(ID, grant, "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
            }
        }

    @Test
    fun fullRecentAttemptLedgerRejectsNewWorkWithoutEvictingCurrentDates() =
        runBlocking {
            fixture {
                val grant = authorize()
                val value =
                    subsystem.extensionState
                        .read(ID, "host", setOf("state")) {}
                        .values
                        .getValue("state")
                val data =
                    PluginJsonV1
                        .decodeFromJsonElement(StoredPluginExtensionsV1.serializer(), value)
                        .copy(attemptDates = (0 until 4096).associate { "account-action-$it" to "2026-11-10" })
                subsystem.extensionState.write(
                    ID,
                    "host",
                    PluginStorageChangesV1(
                        set =
                            mapOf(
                                "state" to PluginJsonV1.encodeToJsonElement(StoredPluginExtensionsV1.serializer(), data),
                            ),
                    ),
                    null,
                ) {}
                assertFailsWith<IllegalArgumentException> { service.run(ID, grant, "execute") }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun queuedManualStartsCannotCrossForegroundOrPackageGenerations() =
        runBlocking {
            for (changePackage in listOf(false, true)) {
                fixture {
                    val grant = authorize()
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    accounts.beforeSessionCheck = {
                        entered.complete(Unit)
                        release.await()
                    }
                    val holder = async { service.setAutomatic(ID, grant, "execute", false) }
                    try {
                        withTimeout(10_000) { entered.await() }
                        val queued = service.start(ID, grant, "execute")
                        if (changePackage) {
                            val plugin =
                                subsystem.running.value.plugins
                                    .getValue(ID)
                            subsystem.replaceRunning(ID, plugin.copy(generation = plugin.generation + 1))
                        } else {
                            service.setForeground(false)
                            service.setForeground(true)
                        }
                        release.complete(Unit)
                        withTimeout(10_000) {
                            holder.await()
                            queued.join()
                        }
                        assertTrue(queued.isCancelled)
                        assertTrue(requests.isEmpty())
                    } finally {
                        release.complete(Unit)
                    }
                }
            }
        }

    @Test
    fun rapidResumeWaitsForThePreviousAccountExecutionToFinishCancelling() =
        runBlocking {
            fixture(actionIds = listOf("execute", "other")) {
                val started = CompletableDeferred<Unit>()
                val cancelling = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                respond = {
                    if (requests.size == 1) {
                        started.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            withContext(NonCancellable) {
                                cancelling.complete(Unit)
                                release.await()
                            }
                        }
                    }
                    PluginTransportResponseV1(200, emptyMap(), "completed".encodeToByteArray())
                }
                val grant = authorize()
                val first = service.start(ID, grant, "execute")
                try {
                    withTimeout(10_000) { started.await() }
                    service.setAutomatic(ID, grant, "other", true)
                    service.setForeground(false)
                    withTimeout(10_000) { cancelling.await() }
                    service.setForeground(true)
                    val resumed =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            service.run(ID, grant, "other", automatic = true)
                        }
                    assertTrue(resumed.isActive)
                    release.complete(Unit)
                    withTimeout(10_000) {
                        first.join()
                        resumed.await()
                    }
                    withTimeout(10_000) {
                        service.observe(ID).first { state ->
                            state.runs.any {
                                it.actionId == "other" &&
                                    it.finishedAtMillis != null
                            }
                        }
                    }
                    assertEquals(2, requests.size)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun builtInAccountsResolveAccountOriginInTheAuthorizationReview() =
        runBlocking {
            fixture {
                val plugin =
                    subsystem.running.value.plugins
                        .getValue(ID)
                val manifest = plugin.installed.manifest
                val extension = requireNotNull(manifest.platformExtension)
                val target = extension.targets.single().copy(authOrigins = setOf(PluginAbiV1.ACCOUNT_ORIGIN))
                val installed =
                    plugin.installed.copy(
                        manifest = manifest.copy(platformExtension = extension.copy(targets = listOf(target))),
                    )
                subsystem.replaceRunning(ID, plugin.copy(installed = installed))
                assertEquals(listOf("https://m.weibo.cn"), snapshot().accounts.single().authOrigins)
                service.run(ID, authorize(), "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
            }
        }

    @Test
    fun pluginPlatformAuthenticatesAnotherPluginWithoutSharingCredentialsOrStorage() =
        runBlocking {
            fixture(providerEnabled = true, actionIds = listOf("execute", "native")) {
                val grant = authorize(PROVIDER_KEY.toString())
                service.run(ID, grant, "execute")
                val request = requests.single()
                assertEquals(ProxyRef.forAccount(PROVIDER_KEY), request.proxy)
                assertEquals("Bearer source-private-token", request.headers["Authorization"])
                assertEquals("POST", request.headers["X-Signed-Method"])
                assertEquals(
                    (request.body as PluginTransportBodyV1.Bytes).value.toByteString().base64(),
                    request.headers["X-Signed-Body"],
                )
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertTrue(
                    subsystem.storage
                        .access(PROVIDER_ID) {}
                        .read("provider-auth")
                        .values
                        .isNotEmpty(),
                )
                assertTrue(
                    subsystem.storage
                        .access(ID) {}
                        .read("provider-auth")
                        .values
                        .isEmpty(),
                )
                assertFailsWith<IllegalArgumentException> { service.run(ID, grant, "native") }
                assertFailsWith<IllegalArgumentException> { service.setAutomatic(ID, grant, "native", true) }
                val native = authorize()
                assertFailsWith<IllegalArgumentException> { service.run(ID, native, "execute") }
                service.run(ID, native, "native")
                assertEquals(2, snapshot().runs.size)
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun sourceRuntimeRetirementCancelsTheOtherPluginsInFlightRequest() =
        runBlocking {
            fixture(providerEnabled = true) {
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                respond = {
                    entered.complete(Unit)
                    try {
                        CompletableDeferred<PluginTransportResponseV1>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val grant = authorize(PROVIDER_KEY.toString())
                val job = service.start(ID, grant, "execute")
                withTimeout(10_000) { entered.await() }
                withTimeout(10_000) {
                    retireProvider()
                    cancelled.await()
                    job.join()
                }
                assertTrue(job.isCancelled)
                assertEquals(
                    PluginExtensionResultStatusV1.Interrupted,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun replacingSourceInvalidatesBothTheGrantAndPendingAuthorizationReview() =
        runBlocking {
            fixture(providerEnabled = true) {
                val candidate = snapshot().accounts.single { it.accountKey == PROVIDER_KEY.toString() }
                val grant = authorize(PROVIDER_KEY.toString())
                replaceProvider(generation = 1)
                withTimeout(10_000) {
                    service.observe(ID).first {
                        it.accounts.any { a ->
                            a.accountKey == PROVIDER_KEY.toString() &&
                                a.accountId == null
                        }
                    }
                }
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
                assertFailsWith<IllegalStateException> { service.authorize(ID, candidate.accountKey, candidate.authorizationToken) }
                val fresh = authorize(PROVIDER_KEY.toString())
                service.run(ID, fresh, "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun broadeningActionPlatformsInvalidatesExistingGrantsAndReviews() =
        runBlocking {
            fixture(providerEnabled = true) {
                val candidate = snapshot().accounts.single { it.accountKey == PROVIDER_KEY.toString() }
                val grant = authorize(PROVIDER_KEY.toString())
                val plugin =
                    subsystem.running.value.plugins
                        .getValue(ID)
                val manifest = plugin.installed.manifest
                val extension = requireNotNull(manifest.platformExtension)
                val expanded = extension.copy(actions = extension.actions.map { it.copy(platformIds = emptySet()) })
                subsystem.replaceRunning(
                    ID,
                    plugin.copy(installed = plugin.installed.copy(manifest = manifest.copy(platformExtension = expanded))),
                )
                withTimeout(10_000) {
                    service.observe(ID).first {
                        it.accounts.any { a ->
                            a.accountKey == candidate.accountKey &&
                                a.accountId == null
                        }
                    }
                }
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
                assertFailsWith<IllegalStateException> { service.authorize(ID, candidate.accountKey, candidate.authorizationToken) }
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun sourceMustExplicitlyOfferAuthenticationAndCannotChangeTransportRouting() =
        runBlocking {
            fixture(providerEnabled = true) {
                val grant = authorize(PROVIDER_KEY.toString())
                replaceProvider(
                    script = "defineFlareSocialPlugin({capabilities:{accountHttp:{authenticate(){return {Host:'attacker.example'};}}}});",
                    generation = 1,
                )
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
                val fresh = authorize(PROVIDER_KEY.toString())
                service.run(ID, fresh, "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Failed,
                    snapshot()
                        .runs
                        .last()
                        .result.status,
                )
                assertTrue(requests.isEmpty())
                replaceProvider(expose = false, generation = 2)
                withTimeout(10_000) { service.observe(ID).first { it.accounts.none { a -> a.accountKey == PROVIDER_KEY.toString() } } }
                assertFailsWith<IllegalStateException> { service.run(ID, fresh, "execute") }
            }
        }

    @Test
    fun consumerReceivesTheAccountOriginWithoutExtraUnauthenticatedHttpAccess() =
        runBlocking {
            fixture(
                providerEnabled = true,
                script =
                    """
                    defineFlarePlatformExtensionPlugin({actions:{async execute(request,context) {
                        if (context.origin !== 'https://provider.example') throw new Error('Missing target origin');
                        if (context.approvedOrigins.length) throw new Error('Unexpected ordinary HTTP permission');
                        const url = context.origin + '/checkin';
                        try { await flare.http.request({url}); throw new Error('Ordinary HTTP was granted'); }
                        catch(error) { if(error.code !== 'host.invalid-request') throw error; }
                        const response = await flare.account.http.request({url});
                        return {status:response.body};
                    },native(){return {status:'completed'};}}});
                    """.trimIndent(),
            ) {
                service.run(ID, authorize(PROVIDER_KEY.toString()), "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertEquals("$PROVIDER_ORIGIN/checkin", requests.single().url)
            }
        }

    @Test
    fun sourceAuthenticationFailurePausesAutomationWithoutDependingOnTheConsumerResult() =
        runBlocking {
            val swallowingConsumer =
                """
                defineFlarePlatformExtensionPlugin({actions:{async execute() {
                    try { await flare.account.http.request({url:'https://provider.example/checkin'}); }
                    catch(error) { if(error.code !== 'account.authentication-required') throw error; }
                    return {status:'completed'};
                },native(){return {status:'completed'};}}});
                """.trimIndent()
            for (script in listOf(null, swallowingConsumer)) {
                fixture(providerEnabled = true, script = script) {
                    replaceProvider(
                        script =
                            """
                            defineFlareSocialPlugin({capabilities:{accountHttp:{authenticate(){
                                throw flare.error({code:'AuthenticationRequired',message:{value:'source-private-token'}});
                            }}}});
                            """.trimIndent(),
                        generation = 1,
                    )
                    val grant = authorize(PROVIDER_KEY.toString())
                    service.setAutomatic(ID, grant, "execute", true)
                    val state = withTimeout(10_000) { service.observe(ID).first { it.runs.singleOrNull()?.finishedAtMillis != null } }
                    assertEquals(
                        PluginExtensionResultStatusV1.AuthenticationRequired,
                        state.runs
                            .single()
                            .result.status,
                    )
                    assertTrue(
                        state.accounts
                            .single { it.accountId == grant }
                            .automaticActions
                            .isEmpty(),
                    )
                    assertTrue(requests.isEmpty())
                }
            }
        }

    @Test
    fun sourceRequiringReloginPausesAutomationBeforeTheConsumerExecutes() =
        runBlocking {
            fixture(providerEnabled = true) {
                val grant = authorize(PROVIDER_KEY.toString())
                requireProviderRelogin()
                service.setAutomatic(ID, grant, "execute", true)
                val state = withTimeout(10_000) { service.observe(ID).first { it.runs.singleOrNull()?.finishedAtMillis != null } }
                assertEquals(
                    PluginExtensionResultStatusV1.AuthenticationRequired,
                    state.runs
                        .single()
                        .result.status,
                )
                assertTrue(
                    state.accounts
                        .single { it.accountId == grant }
                        .automaticActions
                        .isEmpty(),
                )
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun sourceAuthenticationFailureIsSanitizedAndCanPauseConsumerAutomation() =
        runBlocking {
            fixture(
                providerEnabled = true,
                script =
                    """
                    defineFlarePlatformExtensionPlugin({actions:{async execute(){
                        try { await flare.account.http.request({url:'https://provider.example/checkin'}); }
                        catch(error) {
                            if(error.code === 'account.authentication-required' &&
                               error.message === 'Account authentication requires login') {
                                return {status:'authentication-required'};
                            }
                            throw error;
                        }
                        throw new Error('Authentication was accepted');
                    },native(){return {status:'completed'};}}});
                    """.trimIndent(),
            ) {
                replaceProvider(
                    script =
                        """
                        defineFlareSocialPlugin({capabilities:{accountHttp:{authenticate(){
                            throw flare.error({code:'AuthenticationRequired',message:{value:'source-private-token'}});
                        }}}});
                        """.trimIndent(),
                    generation = 1,
                )
                val grant = authorize(PROVIDER_KEY.toString())
                service.setAutomatic(ID, grant, "execute", true)
                val state = withTimeout(10_000) { service.observe(ID).first { it.runs.singleOrNull()?.finishedAtMillis != null } }
                assertEquals(
                    PluginExtensionResultStatusV1.AuthenticationRequired,
                    state.runs
                        .single()
                        .result.status,
                )
                assertTrue(
                    state.accounts
                        .single { it.accountId == grant }
                        .automaticActions
                        .isEmpty(),
                )
                assertTrue(requests.isEmpty())
            }
        }

    @Test
    fun sourceReloginCancelsDependentWorkAndRequiresANewGrant() =
        runBlocking {
            fixture(providerEnabled = true) {
                val entered = CompletableDeferred<Unit>()
                respond = {
                    entered.complete(Unit)
                    CompletableDeferred<PluginTransportResponseV1>().await()
                }
                val candidate = snapshot().accounts.single { it.accountKey == PROVIDER_KEY.toString() }
                val grant = authorize(PROVIDER_KEY.toString())
                val job = service.start(ID, grant, "execute")
                withTimeout(10_000) { entered.await() }
                reloginProvider()
                withTimeout(10_000) { job.join() }
                assertTrue(job.isCancelled)
                assertEquals(
                    PluginExtensionResultStatusV1.Interrupted,
                    snapshot()
                        .runs
                        .single()
                        .result.status,
                )
                assertFailsWith<IllegalStateException> { service.run(ID, grant, "execute") }
                assertFailsWith<IllegalStateException> { service.authorize(ID, candidate.accountKey, candidate.authorizationToken) }
                respond = { PluginTransportResponseV1(200, emptyMap(), "completed".encodeToByteArray()) }
                val fresh = authorize(PROVIDER_KEY.toString())
                service.run(ID, fresh, "execute")
                assertEquals(
                    PluginExtensionResultStatusV1.Completed,
                    snapshot()
                        .runs
                        .first()
                        .result.status,
                )
            }
        }

    @Test
    fun sourceCredentialRefreshPreservesTheConsumerGrant() =
        runBlocking {
            fixture(providerEnabled = true) {
                replaceProvider(
                    script =
                        """
                        defineFlareSocialPlugin({capabilities:{accountHttp:{async authenticate(request) {
                            const before = await flare.credential.read();
                            await flare.credential.replace({token:'refreshed-source-token'});
                            return {Authorization:'Bearer '+before.token,'X-Provider-Secret':before.token};
                        }}}});
                        """.trimIndent(),
                    generation = 1,
                )
                val grant = authorize(PROVIDER_KEY.toString())
                service.run(ID, grant, "execute")
                service.run(ID, grant, "execute")
                assertTrue(snapshot().runs.all { it.result.status == PluginExtensionResultStatusV1.Completed })
                assertEquals("Bearer source-private-token", requests.first().headers["Authorization"])
                assertEquals("Bearer refreshed-source-token", requests.last().headers["Authorization"])
                assertEquals(grant, snapshot().accounts.single { it.accountKey == PROVIDER_KEY.toString() }.accountId)
            }
        }

    private suspend fun fixture(
        script: String? = null,
        actionIds: List<String> = listOf("execute"),
        providerEnabled: Boolean = false,
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(actionIds, providerEnabled)
        try {
            fixture.initialize(script)
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        private val actionIds: List<String>,
        private val providerEnabled: Boolean,
    ) {
        private val files = FileSystem.SYSTEM
        private val root = Files.createTempDirectory("plugin-extension").toOkioPath()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accounts = Accounts()
        val subsystem =
            PluginSubsystemV1(
                object : AppFileStore {
                    override val fileSystem = files

                    override fun directory(name: String) = root / name
                },
                object : PluginOAuthPendingStoreV1 {
                    override suspend fun save(pending: PluginOAuthPendingV1) = Unit

                    override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

                    override suspend fun consume(pending: PluginOAuthPendingV1) = false
                },
            )
        val requests = mutableListOf<PluginTransportRequestV1>()
        var respond: suspend (PluginTransportRequestV1) -> PluginTransportResponseV1 = {
            PluginTransportResponseV1(
                200,
                mapOf(
                    "Set-Cookie" to listOf("SUB=must-not-export"),
                    "X-Provider-Secret" to listOf("source-private-token"),
                ),
                "completed".encodeToByteArray(),
            )
        }
        private val pool =
            PluginRuntimePool(files, transportFactory = {
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                        synchronized(requests) { requests += request }
                        return respond(request)
                    }
                }
            }, storage = subsystem.storage)
        lateinit var service: PluginExtensionServiceV1

        fun newService() = PluginExtensionServiceV1(subsystem, accounts, scope, { Instant.parse("2026-11-09T18:00:00Z") }, pool)

        suspend fun initialize(script: String? = null) {
            if (providerEnabled) replaceProvider()
            val actionDeclarations =
                (if (providerEnabled) actionIds + "native" else actionIds).distinct().joinToString(",") {
                    val platform = if (it == "native") "VVo" else PROVIDER_PLATFORM
                    val restriction = if (providerEnabled) """, "platformIds":["$platform"]""" else ""
                    """{"id":"$it","title":"$it","dailyTimeZone":"Asia/Shanghai"$restriction}"""
                }
            val extraTarget = if (providerEnabled) """, {"platformId":"$PROVIDER_PLATFORM","authOrigins":["${'$'}accountOrigin"]}""" else ""
            val actionMethods = actionIds.joinToString(",") { "\"$it\": perform" }
            val manifest =
                """
                {
                  "apiVersion": 2, "id": "$ID", "version": "1.0.0", "name": "Actions",
                  "platformExtension": {
                    "targets": [{ "platformId": "VVo", "authOrigins": ["https://m.weibo.cn"] }$extraTarget],
                    "actions": [$actionDeclarations]
                  }
                }
                """.trimIndent()
            val defaultSource =
                """
                async function perform(request, context) {
                  if (context.scope !== "extension" || context.targetPlatformId !== "VVo" || context.credentialAvailable) {
                    throw new Error("Wrong context");
                  }
                  try { await flare.credential.read(); throw new Error("Credential was exposed"); }
                  catch (error) { if (error.code !== "credential.denied") throw error; }
                  const response = await flare.account.http.request({ url: "https://m.weibo.cn/api/config", redirect: "manual" });
                  if (response.headers["set-cookie"]) throw new Error("Cookie was exposed");
                  await flare.storage.open("progress").write({ set: { [context.accountId]: 1 } });
                  return { status: response.body, succeeded: 1 };
                }
                defineFlarePlatformExtensionPlugin({ actions: { $actionMethods } });
                """.trimIndent()
            val source = script ?: if (providerEnabled) PROVIDER_CONSUMER else defaultSource
            val archive = root / "extension.fpp"
            TestFppFactory.write(
                archive,
                listOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "plugin.js" to source.encodeToByteArray(),
                    "assets/icon.png" to TestFppFactory.validPng,
                ),
            )
            val installed = subsystem.installer.commit(subsystem.installer.inspect(archive), true, subsystem.stateStore::install)
            subsystem.replaceRunning(
                ID,
                RunningPluginV1(
                    installed,
                    subsystem.stateStore.paths
                        .packagePath(installed.packageHash)
                        .toString(),
                    "",
                ),
            )
            service = newService()
            service.setForeground(true)
            snapshot()
        }

        lateinit var provider: RunningPluginV1
        private var providerContext: ProviderContext? = null

        suspend fun replaceProvider(
            script: String = PROVIDER_SOURCE,
            generation: Long = 0,
            expose: Boolean = true,
        ) {
            if (::provider.isInitialized) pool.retire(provider)
            val capabilities =
                if (expose) {
                    """
                    "flare.datasource.account-http/v1": {
                      "operations": {"authenticate": {}}, "authOrigins": ["${'$'}accountOrigin"]
                    }
                    """.trimIndent()
                } else {
                    ""
                }
            val providerManifest =
                """
                {"apiVersion":2,"id":"$PROVIDER_ID","version":"1.0.0","name":"Provider",
                 "platform":{"id":"$PROVIDER_PLATFORM","name":"Provider","capabilities":{$capabilities}}}
                """.trimIndent()
            val archive = root / "provider.fpp"
            TestFppFactory.write(
                archive,
                TestFppFactory.validEntries(manifest = providerManifest, script = if (expose) script else "defineFlareSocialPlugin({});"),
            )
            val installed = subsystem.installer.commit(subsystem.installer.inspect(archive), true, subsystem.stateStore::install)
            provider =
                RunningPluginV1(
                    installed,
                    subsystem.stateStore.paths
                        .packagePath(installed.packageHash)
                        .toString(),
                    "",
                    generation = generation,
                )
            subsystem.replaceRunning(PROVIDER_ID, provider)
            val context =
                providerContext ?: ProviderContext(
                    PROVIDER_KEY,
                    provider.accountCredential(
                        LoginSuccessV1(
                            accountId = PROVIDER_KEY.id,
                            origin = PROVIDER_ORIGIN,
                            credential = buildJsonObject { put("token", "source-private-token") },
                            profile = ProfileV1(EntityKeyV1(PROVIDER_KEY.id, PROVIDER_KEY.host), "source@provider.example", "Source user"),
                            capabilities =
                                if (expose) {
                                    mapOf(
                                        PluginAbiV1.Capabilities.ACCOUNT_HTTP to setOf("authenticate"),
                                    )
                                } else {
                                    emptyMap()
                                },
                        ),
                    ),
                ).also { providerContext = it }
            val source = PluginDataSourceV1.authenticated(provider, pool, context, emptyMap(), null, scope)
            accounts.values.value =
                accounts.values.value.filter { it.platformId != PROVIDER_PLATFORM } +
                AccountMicroblogDataSource(PROVIDER_KEY, PROVIDER_PLATFORM, source, "source-login")
        }

        suspend fun retireProvider() = pool.retire(provider)

        suspend fun requireProviderRelogin() {
            requireNotNull(providerContext).mutateCredential(PluginAccountCredentialV1.serializer()) { it.copy(credentialRevision = null) }
        }

        suspend fun reloginProvider() {
            val context = requireNotNull(providerContext)
            context.mutateCredential(PluginAccountCredentialV1.serializer()) {
                it.copy(sessionEpoch = "new-source-login", credentialRevision = "new-source-revision")
            }
            val source = PluginDataSourceV1.authenticated(provider, pool, context, emptyMap(), null, scope)
            accounts.values.value =
                accounts.values.value.map {
                    if (it.platformId == PROVIDER_PLATFORM) it.copy(dataSource = source, sessionId = "new-source-login") else it
                }
        }

        suspend fun snapshot(): PluginExtensionStateV1 =
            withTimeout(10_000) {
                service.observe(ID).first { it.available && it.accounts.isNotEmpty() }
            }

        suspend fun authorize(accountKey: String = KEY.toString()): String {
            val candidate =
                withTimeout(10_000) {
                    service
                        .observe(ID)
                        .first { state -> state.accounts.any { it.accountKey == accountKey } }
                        .accounts
                        .single { it.accountKey == accountKey }
                }
            service.authorize(ID, candidate.accountKey, candidate.authorizationToken)
            return requireNotNull(snapshot().accounts.single { it.accountKey == accountKey }.accountId)
        }

        suspend fun retire() {
            pool.retire(
                subsystem.running.value.plugins
                    .getValue(ID),
                protectInteractions = true,
            )
        }

        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
            pool.close()
            subsystem.runtimePool.close()
            files.deleteRecursively(root)
        }
    }

    private class ProviderContext(
        override val accountKey: MicroBlogKey,
        private var value: PluginAccountCredentialV1,
    ) : PlatformDataSourceContext {
        private val mutex = Mutex()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> credential(serializer: KSerializer<T>): T = value as T

        override fun <T : Any> credentialFlow(serializer: KSerializer<T>): Flow<T> = flowOf(credential(serializer))

        override suspend fun <T : Any> updateCredential(
            serializer: KSerializer<T>,
            credential: T,
        ) {
            value =
                credential as PluginAccountCredentialV1
        }

        override suspend fun <T : Any> mutateCredential(
            serializer: KSerializer<T>,
            update: (T) -> T?,
        ): T? =
            mutex.withLock {
                update(credential(serializer))?.also { value = it as PluginAccountCredentialV1 }
            }
    }

    private class Accounts : AccountService {
        val failReads = MutableStateFlow(false)
        var beforeRead: suspend () -> Unit = {}
        var beforeSessionCheck: suspend () -> Unit = {}
        val values =
            MutableStateFlow(
                listOf(
                    AccountMicroblogDataSource(
                        KEY,
                        "VVo",
                        object : MicroblogDataSource by EmptyDataSource, AccountHttpCapability {
                            override val accountOrigin = "https://m.weibo.cn"
                            override val origins = setOf("https://m.weibo.cn")

                            override suspend fun authenticate(request: AccountHttpRequest) = mapOf("Cookie" to "SUB=private-cookie")
                        },
                        "login-1",
                    ),
                ),
            )

        override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> =
            flow {
                beforeRead()
                if (failReads.value) throw IOException("temporary account read failure")
                emitAll(values)
            }

        override suspend fun isSessionActive(
            accountKey: MicroBlogKey,
            sessionId: String,
        ): Boolean {
            beforeSessionCheck()
            return values.value.any { it.accountKey == accountKey && it.sessionId == sessionId }
        }

        override suspend fun deleteAccounts(
            platformId: String,
            ownsCredential: (String) -> Boolean,
            beforeDelete: suspend () -> Unit,
        ) = error("unused")

        override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> = error("unused")

        override suspend fun <T : Any> addAccount(
            account: UiAccount,
            credential: T,
            serializer: KSerializer<T>,
            beforeCommit: () -> Unit,
        ): Unit = error("unused")

        override fun <T : Any> credentialFlow(
            accountKey: MicroBlogKey,
            serializer: KSerializer<T>,
        ): Flow<T> = error("unused")

        override fun <T : Any> updateCredential(
            accountKey: MicroBlogKey,
            credential: T,
            serializer: KSerializer<T>,
        ): Job = error("unused")
    }

    private object EmptyDataSource : MicroblogDataSource {
        override fun homeTimeline(): RemoteLoader<UiTimelineV2> = error("unused")

        override fun userTimeline(
            userKey: MicroBlogKey,
            mediaOnly: Boolean,
        ): RemoteLoader<UiTimelineV2> = error("unused")

        override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = error("unused")

        override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = error("unused")

        override fun searchUser(query: String): RemoteLoader<UiProfile> = error("unused")

        override fun discoverUsers(): RemoteLoader<UiProfile> = error("unused")

        override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = error("unused")

        override fun discoverHashtags(): RemoteLoader<UiHashtag> = error("unused")

        override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("unused")

        override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("unused")

        override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> = error("unused")
    }

    private companion object {
        const val ID = "org.example.extension-test"
        val KEY = MicroBlogKey("123", "m.weibo.cn")
        const val PROVIDER_ID = "org.example.source-platform"
        const val PROVIDER_PLATFORM = "ExampleProvider"
        const val PROVIDER_ORIGIN = "https://provider.example"
        val PROVIDER_KEY = MicroBlogKey("source-user", "provider.example")
        val PROVIDER_SOURCE =
            """
            defineFlareSocialPlugin({capabilities:{accountHttp:{async authenticate(request, context) {
                if (context.scope !== 'account' || context.platformId !== 'ExampleProvider') throw new Error('Wrong source context');
                const credential = await flare.credential.read();
                await flare.storage.open('provider-auth').write({set:{url:request.url}});
                return {Authorization:'Bearer '+credential.token,'X-Provider-Secret':credential.token,
                        'X-Signed-Method':request.method,'X-Signed-Body':request.bodyBase64 || ''};
            }}}});
            """.trimIndent()
        val PROVIDER_CONSUMER =
            """
            defineFlarePlatformExtensionPlugin({actions:{async execute(request,context) {
                if (context.targetPlatformId !== 'ExampleProvider' || context.credentialAvailable) {
                    throw new Error('Wrong consumer context');
                }
                try { await flare.credential.read(); throw new Error('Credential exported'); }
                catch(error) { if(error.code !== 'credential.denied') throw error; }
                const response = await flare.account.http.request({
                    method:'POST',url:'https://provider.example/checkin',body:{type:'json',value:{message:'hello'}}
                });
                if(response.headers['x-provider-secret'] || response.headers['set-cookie']) throw new Error('Secret header exported');
                const own = await flare.storage.open('provider-auth').read();
                if(own.values.url) throw new Error('Source storage exported');
                return {status:response.body,succeeded:1};
            },native(){return {status:'completed'};}}});
            """.trimIndent()
    }
}
