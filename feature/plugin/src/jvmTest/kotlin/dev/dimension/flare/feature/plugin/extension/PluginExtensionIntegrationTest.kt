package dev.dimension.flare.feature.plugin.extension

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.datasource.PlatformExtensionDataSource
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.storage.PluginStorageChangesV1
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
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

    private suspend fun fixture(
        script: String? = null,
        actionIds: List<String> = listOf("execute"),
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(actionIds)
        try {
            fixture.initialize(script)
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        private val actionIds: List<String>,
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
            PluginTransportResponseV1(200, mapOf("Set-Cookie" to listOf("SUB=must-not-export")), "completed".encodeToByteArray())
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
            val actionDeclarations = actionIds.joinToString(",") { """{"id":"$it","title":"$it","dailyTimeZone":"Asia/Shanghai"}""" }
            val actionMethods = actionIds.joinToString(",") { "\"$it\": perform" }
            val manifest =
                """
                {
                  "apiVersion": 2, "id": "$ID", "version": "1.0.0", "name": "Actions",
                  "platformExtension": {
                    "targets": [{ "platformId": "VVo", "authOrigins": ["https://m.weibo.cn"] }],
                    "actions": [$actionDeclarations]
                  }
                }
                """.trimIndent()
            val defaultSource =
                """
                async function perform(request, context) {
                  if (context.scope !== "extension" || context.targetPlatformId !== "VVo" || context.credentialAvailable) throw new Error("Wrong context");
                  try { await flare.credential.read(); throw new Error("Credential was exposed"); }
                  catch (error) { if (error.code !== "credential.denied") throw error; }
                  const response = await flare.account.http.request({ url: "https://m.weibo.cn/api/config", redirect: "manual" });
                  if (response.headers["set-cookie"]) throw new Error("Cookie was exposed");
                  await flare.storage.open("progress").write({ set: { [context.accountId]: 1 } });
                  return { status: response.body, succeeded: 1 };
                }
                defineFlarePlatformExtensionPlugin({ actions: { $actionMethods } });
                """.trimIndent()
            val source = script ?: defaultSource
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

        suspend fun snapshot(): PluginExtensionStateV1 =
            withTimeout(10_000) {
                service.observe(ID).first { it.available && it.accounts.isNotEmpty() }
            }

        suspend fun authorize(accountKey: String = KEY.toString()): String {
            val candidate = snapshot().accounts.single { it.accountKey == accountKey }
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

    private class Accounts : AccountService {
        val failReads = MutableStateFlow(false)
        var beforeSessionCheck: suspend () -> Unit = {}
        val values =
            MutableStateFlow(
                listOf(
                    AccountMicroblogDataSource(
                        KEY,
                        "VVo",
                        object : MicroblogDataSource by EmptyDataSource, PlatformExtensionDataSource {
                            override val extensionAuthOrigins = setOf("https://m.weibo.cn")

                            override suspend fun extensionAuthHeaders(url: String) = mapOf("Cookie" to "SUB=private-cookie")
                        },
                        "login-1",
                    ),
                ),
            )

        override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> =
            flow {
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
    }
}
