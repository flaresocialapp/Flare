package dev.dimension.flare.data.network.vvo

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import dev.dimension.flare.common.JSON
import dev.dimension.flare.common.UploadMedia
import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.appendMedia
import dev.dimension.flare.data.network.isWeiboDirectMessageAttachment
import dev.dimension.flare.data.network.ktorClient
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.vvo.api.ConfigApi
import dev.dimension.flare.data.network.vvo.api.DirectMessageApi
import dev.dimension.flare.data.network.vvo.api.StatusApi
import dev.dimension.flare.data.network.vvo.api.TimelineApi
import dev.dimension.flare.data.network.vvo.api.UserApi
import dev.dimension.flare.data.network.vvo.api.createConfigApi
import dev.dimension.flare.data.network.vvo.api.createDirectMessageApi
import dev.dimension.flare.data.network.vvo.api.createStatusApi
import dev.dimension.flare.data.network.vvo.api.createTimelineApi
import dev.dimension.flare.data.network.vvo.api.createUserApi
import dev.dimension.flare.data.network.vvo.model.Config
import dev.dimension.flare.data.network.vvo.model.ContainerInfo
import dev.dimension.flare.data.network.vvo.model.EmojiData
import dev.dimension.flare.data.network.vvo.model.HotflowData
import dev.dimension.flare.data.network.vvo.model.StatusLiker
import dev.dimension.flare.data.network.vvo.model.UploadResponse
import dev.dimension.flare.data.network.vvo.model.VVOResponse
import dev.dimension.flare.data.platform.VVO_PLATFORM_ID
import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.data.repository.SilentCookieRenewRequest
import dev.dimension.flare.data.repository.SilentCookieRenewResult
import dev.dimension.flare.data.repository.SilentWebCookieRenewer
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.vvoHost
import dev.dimension.flare.model.vvoHostLong
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val baseUrl = "https://$vvoHost/"
private val mWeiboRootUrl = Url(baseUrl)
private val emptyDetailMessages = setOf("暂无数据", "暂无内容", "这里还没有内容", "还没有内容", "没有更多")

internal const val DEFAULT_VVO_USER_AGENT: String =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

// SUB rotates well before the ALF session window ends, so refresh proactively
// while the app is in use instead of waiting a full day.
private val chocolateRefreshInterval = 6.hours
private val silentWebViewSkipCooldown = 2.minutes
private val defaultConfigLoginRetryDelay = 500.milliseconds
private const val CONFIG_LOGIN_RETRY_LIMIT = 5
private val silentRenewAllowedHosts = setOf("weibo.cn", "weibo.com", "login.sina.com.cn")
private val silentRenewAllowedSuffixes = setOf("weibo.cn", "weibo.com")

// m.weibo.cn bounces an expired SUB through visitor/passport/sina SSO hops to re-sign it.
private const val REFRESH_REDIRECT_LIMIT = 8

private typealias VVOHttpClientFactory = (HttpClientConfig<*>.() -> Unit) -> HttpClient

private fun vvoKtorfit(
    url: String = baseUrl,
    chocolateProvider: suspend () -> String?,
    userAgentProvider: suspend () -> String?,
    httpClientFactory: VVOHttpClientFactory,
) = de.jensklingenberg.ktorfit.ktorfit {
    baseUrl(url)
    httpClient(
        httpClientFactory {
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
            install(VVOHeaderPlugin) {
                this.chocolateProvider = chocolateProvider
                this.userAgentProvider = userAgentProvider
            }
        },
    )
    converterFactories(
        VVOResponseConverterFactory(),
        ResponseConverterFactory(),
    )
}

private class VVOApis(
    chocolateProvider: suspend () -> String?,
    userAgentProvider: suspend () -> String?,
    httpClientFactory: VVOHttpClientFactory,
) {
    private val ktorfit =
        vvoKtorfit(
            chocolateProvider = chocolateProvider,
            userAgentProvider = userAgentProvider,
            httpClientFactory = httpClientFactory,
        )
    val timelineApi: TimelineApi = ktorfit.createTimelineApi()
    val userApi: UserApi = ktorfit.createUserApi()
    val configApi: ConfigApi = ktorfit.createConfigApi()
    val statusApi: StatusApi = ktorfit.createStatusApi()
    val directMessageApi: DirectMessageApi by lazy {
        vvoKtorfit(
            chocolateProvider = chocolateProvider,
            userAgentProvider = userAgentProvider,
        ) { config ->
            httpClientFactory {
                config()
                followRedirects = false
                expectSuccess = true
            }
        }.createDirectMessageApi()
    }
}

internal class VVOService private constructor(
    private val credentialFlow: Flow<VVoCredential>,
    private val accountKey: MicroBlogKey?,
    private val proxy: ProxyRef,
    private val silentCookieRenewer: SilentWebCookieRenewer?,
    private val nowEpochMillis: () -> Long,
    private val refreshCookieWhenStale: Boolean,
    private val configLoginRetryDelay: Duration,
    private val onCredentialRefreshed: suspend (VVoCredential) -> Unit,
    private val httpClientFactory: VVOHttpClientFactory,
    private val apis: VVOApis,
) : TimelineApi by apis.timelineApi,
    UserApi by apis.userApi,
    ConfigApi by apis.configApi,
    StatusApi by apis.statusApi {
    constructor(
        credentialFlow: Flow<VVoCredential>,
        accountKey: MicroBlogKey? = null,
        silentCookieRenewer: SilentWebCookieRenewer? = null,
        nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        refreshCookieWhenStale: Boolean = false,
        configLoginRetryDelay: Duration = defaultConfigLoginRetryDelay,
        onCredentialRefreshed: suspend (VVoCredential) -> Unit = {},
        proxy: ProxyRef = ProxyRef.Global,
        httpClientFactory: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { ktorClient(proxy, config = it) },
    ) : this(
        credentialFlow = credentialFlow,
        accountKey = accountKey,
        proxy = proxy,
        silentCookieRenewer = silentCookieRenewer,
        nowEpochMillis = nowEpochMillis,
        refreshCookieWhenStale = refreshCookieWhenStale,
        configLoginRetryDelay = configLoginRetryDelay,
        onCredentialRefreshed = onCredentialRefreshed,
        httpClientFactory = httpClientFactory,
        apis =
            VVOApis(
                chocolateProvider = { credentialFlow.firstOrNull()?.chocolate },
                userAgentProvider = { credentialFlow.firstOrNull()?.userAgent },
                httpClientFactory = httpClientFactory,
            ),
    )

    private val refreshMutex = Mutex()
    private var terminalExpiredCredential: VVoCredential? = null
    private var nextWebViewAttemptEpochMillis: Long = 0L
    private val webClient by lazy { httpClientFactory { followRedirects = false } }

    suspend fun directMessageApi(): DirectMessageApi {
        refreshChocolateIfNeeded()
        return apis.directMessageApi
    }

    suspend fun directMessageImageHeaders(url: String): Map<String, String> {
        require(Url(url).isWeiboDirectMessageAttachment())
        val credential = refreshChocolateIfNeeded() ?: currentCredential()
        val cookie = credential?.chocolate
        return if (cookie.isNullOrBlank()) emptyMap() else mapOf(HttpHeaders.Cookie to cookie)
    }

    suspend fun extensionAuthHeaders(url: String): Map<String, String> {
        val target = Url(url)
        require(target.protocol == URLProtocol.HTTPS && target.port == 443 && target.host in setOf("m.weibo.cn", "weibo.com")) {
            "Unsupported Weibo authentication origin"
        }
        require(target.user.isNullOrEmpty() && target.password.isNullOrEmpty()) { "URL credentials are not allowed" }
        val credential = refreshChocolateIfNeeded() ?: currentCredential()
        val cookie = credential?.chocolate?.takeIf { it.isNotBlank() } ?: error("Weibo login is required")
        val targetJarToken =
            if (target.host == "weibo.com") {
                VVoCookieJar.fromCredential(credential).cookieMapFor(target)["XSRF-TOKEN"]
            } else {
                null
            }
        val xsrfToken =
            targetJarToken
                ?: cookie
                    .split(';')
                    .mapNotNull { it.toCookieNameValueOrNull() }
                    .firstOrNull { it.first == "XSRF-TOKEN" }
                    ?.second
        return buildMap {
            put(HttpHeaders.Cookie, cookie)
            put(HttpHeaders.Referrer, "https://${target.host}/")
            put(HttpHeaders.UserAgent, effectiveUserAgent(credential))
            xsrfToken?.let { put("X-XSRF-TOKEN", it) }
        }
    }

    companion object {
        fun checkChocolates(chocolate: String): Boolean =
            chocolate
                .split(';')
                .mapNotNull { it.toCookieNameValueOrNull() }
                .toMap()
                .let(::hasValidMWeiboLoginCookies)
    }

    internal suspend fun activeCredential(): VVoCredential? = currentCredential()

    override suspend fun config(): VVOResponse<Config> {
        val proactiveCandidate = refreshProactivelyInMemoryIfStale()
        val initialCredential = proactiveCandidate ?: currentCredential()

        val initialResponse = configApi(proactiveCandidate).config()
        if (initialResponse.isLoggedInForExpectedAccount()) {
            if (proactiveCandidate != null && initialResponse.isVerifiedRenewalForExpectedAccount()) {
                onCredentialRefreshed(proactiveCandidate)
            }
            return initialResponse
        }

        val baselineCredential = initialCredential ?: return initialResponse.sanitizedForExpectedAccount()
        if (baselineCredential.chocolate.isBlank() && baselineCredential.cookies.isEmpty()) {
            return initialResponse.sanitizedForExpectedAccount()
        }

        var reusedCredential: VVoCredential? = null
        var terminalResponse: VVOResponse<Config> = initialResponse

        refreshMutex.withLock {
            val latest = currentCredential()
            if (latest != null && latest.isRefreshedAfter(baselineCredential) && !latest.hasSameSessionMaterialAs(baselineCredential)) {
                reusedCredential = latest
                return@withLock
            }

            val stageBaseline = latest ?: baselineCredential
            if (terminalExpiredCredential != null && !stageBaseline.hasSameSessionMaterialAs(terminalExpiredCredential)) {
                terminalExpiredCredential = null
            }
            if (stageBaseline.hasSameSessionMaterialAs(terminalExpiredCredential)) {
                return@withLock
            }

            var workingCredential = stageBaseline
            for (attempt in 0 until CONFIG_LOGIN_RETRY_LIMIT) {
                val now = nowEpochMillis()
                val candidate =
                    attemptStage1HttpRefresh(
                        credential = workingCredential,
                        refreshedAtEpochMillis = now,
                    ) ?: break
                if (candidate.chocolate.isBlank() || candidate.hasSameSessionMaterialAs(workingCredential)) {
                    break
                }
                workingCredential = candidate
                delayConfigLoginRetry()
                val retryResponse = configApi(candidate).config()
                terminalResponse = retryResponse
                if (retryResponse.isVerifiedRenewalForExpectedAccount()) {
                    terminalExpiredCredential = null
                    nextWebViewAttemptEpochMillis = 0L
                    onCredentialRefreshed(candidate)
                    return retryResponse
                }
            }

            val renewer = silentCookieRenewer
            val stage2Now = nowEpochMillis()
            if (renewer != null &&
                !stageBaseline.hasSameSessionMaterialAs(terminalExpiredCredential) &&
                stage2Now >= nextWebViewAttemptEpochMillis
            ) {
                val baseJar = VVoCookieJar.fromCredential(stageBaseline)
                val previousSub = baseJar.cookieMapFor(mWeiboRootUrl, stage2Now)["SUB"]
                val renewRequest =
                    SilentCookieRenewRequest(
                        accountKey = accountKey ?: MicroBlogKey(id = "unknown", host = vvoHost),
                        proxyRef = proxy,
                        startUrl = baseUrl,
                        probeUrls = VVO_COOKIE_PROBE_URLS,
                        allowedHosts = silentRenewAllowedHosts,
                        allowedHostSuffixes = silentRenewAllowedSuffixes,
                        seedCookies = baseJar.toSilentSeedCookies(stage2Now),
                        userAgent = stageBaseline.userAgent,
                        previousSubCookie = previousSub,
                    )
                val renewResult =
                    try {
                        renewer.renew(renewRequest)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        SilentCookieRenewResult.Skipped("renewer-exception")
                    }
                val completedNow = nowEpochMillis()
                when (renewResult) {
                    is SilentCookieRenewResult.Renewed -> {
                        val mergedJar = baseJar.copy()
                        mergedJar.mergeSilentSeedCookies(renewResult.cookies, completedNow)
                        val candidate =
                            mergedJar.toCredential(
                                base = stageBaseline,
                                userAgent = renewResult.userAgent ?: stageBaseline.userAgent,
                                lastRefreshEpochMillis = completedNow,
                                nowEpochMillis = completedNow,
                            )
                        if (candidate.chocolate.isNotBlank()) {
                            delayConfigLoginRetry()
                            val stage2Response = configApi(candidate).config()
                            terminalResponse = stage2Response
                            if (stage2Response.isVerifiedRenewalForExpectedAccount()) {
                                terminalExpiredCredential = null
                                nextWebViewAttemptEpochMillis = 0L
                                onCredentialRefreshed(candidate)
                                return stage2Response
                            }
                        }
                        terminalExpiredCredential = stageBaseline
                    }

                    SilentCookieRenewResult.InteractiveLoginRequired -> {
                        terminalExpiredCredential = stageBaseline
                    }

                    is SilentCookieRenewResult.Skipped -> {
                        nextWebViewAttemptEpochMillis = completedNow + silentWebViewSkipCooldown.inWholeMilliseconds
                    }
                }
            }
        }

        reusedCredential?.let { recovered ->
            delayConfigLoginRetry()
            return configApi(recovered).config().sanitizedAfterRenewalCheck()
        }

        return terminalResponse.sanitizedAfterRenewalCheck()
    }

    internal suspend fun recoverSession(
        expectedAccountKey: MicroBlogKey? = accountKey,
        failedCredential: VVoCredential? = null,
    ): Boolean {
        val baseline = failedCredential ?: currentCredential()
        val cfg =
            try {
                config()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return false
            }
        val data = cfg.data ?: return false
        if (data.login != true) return false
        if (expectedAccountKey != null && data.uid != expectedAccountKey.id) {
            return false
        }
        val after = currentCredential()
        return after != null && (baseline == null || !after.hasSameSessionMaterialAs(baseline))
    }

    suspend fun getContainerIndexRecovering(
        accountKey: MicroBlogKey,
        containerId: String,
        sinceId: String? = null,
        page: Int? = null,
    ): VVOResponse<ContainerInfo> {
        val attemptedCredential = currentCredential()
        val firstResponse =
            try {
                getContainerIndex(containerId = containerId, sinceId = sinceId, page = page)
            } catch (error: ClientRequestException) {
                if (error.response.status == HttpStatusCode.Unauthorized) {
                    if (recoverSession(accountKey, attemptedCredential)) {
                        return retryContainerIndexAfterRecovery(
                            accountKey = accountKey,
                            containerId = containerId,
                            sinceId = sinceId,
                            page = page,
                        )
                    }
                    throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
                }
                throw error
            }
        if (firstResponse.ok == -100L || firstResponse.httpCode == 401L) {
            if (recoverSession(accountKey, attemptedCredential)) {
                return retryContainerIndexAfterRecovery(
                    accountKey = accountKey,
                    containerId = containerId,
                    sinceId = sinceId,
                    page = page,
                )
            }
            throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
        }
        return firstResponse
    }

    private suspend fun retryContainerIndexAfterRecovery(
        accountKey: MicroBlogKey,
        containerId: String,
        sinceId: String?,
        page: Int?,
    ): VVOResponse<ContainerInfo> {
        val retried =
            try {
                getContainerIndex(containerId = containerId, sinceId = sinceId, page = page)
            } catch (error: ClientRequestException) {
                if (error.response.status == HttpStatusCode.Unauthorized) {
                    throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
                }
                throw error
            }
        if (retried.ok == -100L || retried.httpCode == 401L) {
            throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
        }
        return retried
    }

    private suspend inline fun <T> withLoginExpiredRecovery(
        accountKey: MicroBlogKey,
        block: () -> T,
    ): T {
        val attemptedCredential = currentCredential()
        return try {
            block()
        } catch (expired: LoginExpiredException) {
            if (recoverSession(accountKey, attemptedCredential)) {
                block()
            } else {
                throw expired
            }
        }
    }

    suspend fun getWebComments(
        id: String,
        flow: Int,
        maxId: Long?,
        accountKey: MicroBlogKey,
    ): HotflowData =
        withLoginExpiredRecovery(accountKey) {
            executeGetWebComments(id = id, flow = flow, maxId = maxId, accountKey = accountKey)
        }

    private suspend fun executeGetWebComments(
        id: String,
        flow: Int,
        maxId: Long?,
        accountKey: MicroBlogKey,
    ): HotflowData {
        val url = "https://$vvoHostLong/ajax/statuses/buildComments"
        val authHeaders = extensionAuthHeaders(url)
        val response =
            webClient.get(url) {
                authHeaders.forEach { (name, value) -> header(name, value) }
                headers[HttpHeaders.Referrer] = "https://$vvoHostLong/detail/${id.encodeURLPathPart()}"
                header("X-Requested-With", "XMLHttpRequest")
                accept(ContentType.Application.Json)
                parameter("id", id)
                parameter("is_reload", 1)
                parameter("is_show_bulletin", 2)
                parameter("is_mix", 0)
                parameter("fetch_level", 0)
                parameter("flow", flow)
                parameter("count", 20)
                maxId?.let { parameter("max_id", it) }
            }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
        }
        check(response.status.isSuccess()) { "Could not load Weibo comments (HTTP ${response.status.value})" }

        val envelope = response.bodyAsText().decodeJson<JsonObject>()
        val page = (envelope["data"] as? JsonObject) ?: envelope
        val errorCode =
            (envelope["ok"] as? JsonPrimitive)?.longOrNull?.takeUnless { it == 1L }
                ?: (page["ok"] as? JsonPrimitive)?.longOrNull?.takeUnless { it == 1L }
        if (errorCode != null) {
            val message = ((envelope["msg"] ?: page["msg"]) as? JsonPrimitive)?.contentOrNull?.trim()
            if (errorCode == -100L) throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
            if (errorCode == 0L && message in emptyDetailMessages) return HotflowData()
            error(message ?: "Could not load Weibo comments")
        }
        check(page["data"] is JsonArray) { "Weibo comments response is missing its comments array" }
        return JSON.decodeFromJsonElement<HotflowData>(page)
    }

    suspend fun getStatusLikers(
        id: String,
        page: Int,
        accountKey: MicroBlogKey,
    ): List<StatusLiker> =
        withLoginExpiredRecovery(accountKey) {
            executeGetStatusLikers(id = id, page = page, accountKey = accountKey)
        }

    private suspend fun executeGetStatusLikers(
        id: String,
        page: Int,
        accountKey: MicroBlogKey,
    ): List<StatusLiker> {
        val url = "https://$vvoHost/api/attitudes/show"
        val authHeaders = extensionAuthHeaders(url)
        val response =
            webClient.get(url) {
                authHeaders.forEach { (name, value) -> header(name, value) }
                headers[HttpHeaders.Referrer] = "https://$vvoHost/detail/${id.encodeURLPathPart()}"
                header("X-Requested-With", "XMLHttpRequest")
                header("MWeibo-Pwa", "1")
                accept(ContentType.Application.Json)
                parameter("id", id)
                parameter("page", page)
            }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
        }
        check(response.status.isSuccess()) { "Could not load Weibo likes (HTTP ${response.status.value})" }
        val body = response.bodyAsText()
        if (body.trimStart().startsWith("<") && body.isVvoNoDataHtml()) return emptyList()

        val envelope = body.decodeJson<JsonObject>()
        val code = (envelope["ok"] as? JsonPrimitive)?.longOrNull
        val message = (envelope["msg"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (code == -100L) throw LoginExpiredException(accountKey, VVO_PLATFORM_ID)
        if (code == 0L && message in emptyDetailMessages) return emptyList()
        check(code == 1L) { message ?: "Could not load Weibo likes" }
        val rows = (envelope["data"] as? JsonObject)?.get("data")
        check(rows is JsonArray) { "Weibo likes response is missing its user list" }
        return JSON.decodeFromJsonElement<List<StatusLiker>>(rows)
    }

    suspend fun getUid(screenName: String): String? {
        val response =
            httpClientFactory {
                followRedirects = false
                install(VVOHeaderPlugin) {
                    chocolateProvider = ::currentChocolate
                    userAgentProvider = { currentCredential()?.userAgent }
                }
            }.get("https://$vvoHost/n/$screenName")
        return response.headers["Location"]?.let {
            return it.split('/').last()
        }
    }

    suspend fun uploadPic(
        st: String,
        media: UploadMedia,
        xsrfToken: String = st,
        type: String = "json",
    ): UploadResponse =
        coroutineScope {
            httpClientFactory {
                install(HttpTimeout) {
                    connectTimeoutMillis = 2.minutes.inWholeMilliseconds
                    requestTimeoutMillis = 2.minutes.inWholeMilliseconds
                    socketTimeoutMillis = 2.minutes.inWholeMilliseconds
                }
                install(VVOHeaderPlugin) {
                    chocolateProvider = ::currentChocolate
                    userAgentProvider = { currentCredential()?.userAgent }
                }
            }.submitFormWithBinaryData(
                url = "https://$vvoHost/api/statuses/uploadPic",
                formData =
                    formData {
                        append("type", type)
                        appendMedia("pic", media, this@coroutineScope)
                        append("st", st)
                    },
                block = {
                    header("X-Xsrf-Token", xsrfToken)
                },
            ).bodyAsText()
                .decodeJson<UploadResponse>()
        }

    suspend fun emojis(): EmojiData =
        httpClientFactory {
            install(ContentNegotiation) {
                nullableFallbackJson(JSON)
            }
        }.get("https://flareapp.moe/emoji.json")
            .body()

    private fun VVOResponse<Config>.isLoggedInForExpectedAccount(): Boolean {
        val configData = data ?: return false
        if (configData.login != true) return false
        val expectedUid = accountKey?.id
        val actualUid = configData.uid
        return expectedUid == null || actualUid == null || actualUid == expectedUid
    }

    private fun VVOResponse<Config>.isVerifiedRenewalForExpectedAccount(): Boolean {
        val configData = data ?: return false
        if (configData.login != true) return false
        val expectedUid = accountKey?.id
        return expectedUid == null || configData.uid == expectedUid
    }

    private fun VVOResponse<Config>.sanitizedForExpectedAccount(): VVOResponse<Config> {
        val configData = data ?: return this
        if (configData.login == true && !isLoggedInForExpectedAccount()) {
            return copy(data = configData.copy(login = false, st = null))
        }
        return this
    }

    private fun VVOResponse<Config>.sanitizedAfterRenewalCheck(): VVOResponse<Config> {
        val configData = data ?: return this
        if (configData.login == true && !isVerifiedRenewalForExpectedAccount()) {
            return copy(data = configData.copy(login = false, st = null))
        }
        return this
    }

    private fun VVoCredential.hasSameSessionMaterialAs(other: VVoCredential?): Boolean =
        other != null &&
            chocolate == other.chocolate &&
            VVoCookieJar.fromCredential(this).toStoredCookies(0L) ==
            VVoCookieJar.fromCredential(other).toStoredCookies(0L)

    private suspend fun delayConfigLoginRetry() {
        if (configLoginRetryDelay > Duration.ZERO) {
            delay(configLoginRetryDelay)
        }
    }

    private fun configApi(credential: VVoCredential?): ConfigApi =
        credential
            ?.let {
                VVOApis(
                    chocolateProvider = { it.chocolate },
                    userAgentProvider = { it.userAgent },
                    httpClientFactory = httpClientFactory,
                ).configApi
            }
            ?: apis.configApi

    private suspend fun refreshChocolateIfNeeded(): VVoCredential? {
        val candidate = refreshProactivelyInMemoryIfStale() ?: return null
        if (accountKey != null) {
            val verified =
                runCatching {
                    configApi(candidate).config().isVerifiedRenewalForExpectedAccount()
                }.getOrDefault(false)
            if (!verified) return null
        }
        onCredentialRefreshed(candidate)
        return candidate
    }

    private suspend fun refreshProactivelyInMemoryIfStale(): VVoCredential? {
        if (!refreshCookieWhenStale) {
            return null
        }
        val current = currentCredential() ?: return null
        if (current.hasSameSessionMaterialAs(terminalExpiredCredential)) {
            return null
        }
        val now = nowEpochMillis()
        return if (shouldRefreshVvoCookie(current.lastCookieRefreshEpochMillis, now)) {
            runCatching {
                refreshProactivelyWhenStale(nowEpochMillis = now)
            }.getOrNull()
        } else {
            null
        }
    }

    private suspend fun refreshProactivelyWhenStale(nowEpochMillis: Long): VVoCredential? =
        refreshMutex.withLock {
            val current = currentCredential() ?: return@withLock null
            if (current.hasSameSessionMaterialAs(terminalExpiredCredential)) {
                return@withLock null
            }
            if (!shouldRefreshVvoCookie(current.lastCookieRefreshEpochMillis, nowEpochMillis)) {
                return@withLock null
            }
            val candidate = attemptStage1HttpRefresh(credential = current, refreshedAtEpochMillis = nowEpochMillis) ?: return@withLock null
            if (candidate.hasSameSessionMaterialAs(current)) {
                return@withLock null
            }
            val mWeiboCookies = VVoCookieJar.fromCredential(candidate).cookieMapFor(mWeiboRootUrl, nowEpochMillis)
            if (!hasValidMWeiboLoginCookies(mWeiboCookies)) {
                return@withLock null
            }
            candidate
        }

    private suspend fun attemptStage1HttpRefresh(
        credential: VVoCredential,
        refreshedAtEpochMillis: Long,
    ): VVoCredential? {
        if (credential.chocolate.isBlank() && credential.cookies.isEmpty()) {
            return null
        }
        val jar = VVoCookieJar.fromCredential(credential)
        // Only short-circuit on m.weibo.cn ALF if neither cross-domain SSO cookies nor Stage 2 renewer exist.
        if (silentCookieRenewer == null &&
            !jar.hasLongLivedSsoCookies(refreshedAtEpochMillis) &&
            isVvoSessionExpired(credential.chocolate, refreshedAtEpochMillis)
        ) {
            return null
        }

        val updatedJar =
            followRefreshRedirects(
                startUrl = baseUrl,
                initialJar = jar,
                userAgent = effectiveUserAgent(credential),
                nowEpochMillis = refreshedAtEpochMillis,
            )

        return updatedJar.toCredential(
            base = credential,
            userAgent = credential.userAgent,
            lastRefreshEpochMillis = refreshedAtEpochMillis,
            nowEpochMillis = refreshedAtEpochMillis,
        )
    }

    private suspend fun followRefreshRedirects(
        startUrl: String,
        initialJar: VVoCookieJar,
        userAgent: String,
        nowEpochMillis: Long,
    ): VVoCookieJar {
        val jar = initialJar.copy()
        val pendingUrls = ArrayDeque<String>()
        pendingUrls.addLast(startUrl)
        var visitorRestoreAttempted = false
        var hops = 0

        while (pendingUrls.isNotEmpty() && hops <= REFRESH_REDIRECT_LIMIT) {
            val currentUrl = pendingUrls.removeFirst()
            val parsedUrl = runCatching { Url(currentUrl) }.getOrNull() ?: continue
            hops++

            val hopCookieHeader = jar.cookieHeaderFor(parsedUrl, nowEpochMillis)
            val response =
                httpClientFactory {
                    followRedirects = false
                    install(VVOHeaderPlugin) {
                        chocolateProvider = { hopCookieHeader }
                        userAgentProvider = { userAgent }
                    }
                }.get(currentUrl)

            jar.applySetCookies(
                requestUrl = parsedUrl,
                setCookieHeaders = response.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
                nowEpochMillis = nowEpochMillis,
            )

            val location = response.headers[HttpHeaders.Location]
            if (isRefreshRedirect(response.status.value, location)) {
                val resolved = resolveRefreshRedirectUrl(current = currentUrl, location = location!!)
                if (resolved != null) {
                    pendingUrls.addFirst(resolved)
                }
                continue
            }

            if (response.status.isSuccess()) {
                val host = parsedUrl.host.lowercase()
                if (!visitorRestoreAttempted &&
                    host == "visitor.passport.weibo.cn" &&
                    parsedUrl.encodedPath.startsWith("/visitor/visitor") &&
                    parsedUrl.parameters["a"] != "restore"
                ) {
                    val visitorCookies = jar.cookieMapFor(parsedUrl, nowEpochMillis)
                    if (!visitorCookies["SRF"].isNullOrBlank()) {
                        visitorRestoreAttempted = true
                        val restoreUrl =
                            "https://visitor.passport.weibo.cn/visitor/visitor?a=restore&cb=restore_back&from=weibo&_rand=$nowEpochMillis"
                        val restoreParsedUrl = Url(restoreUrl)
                        val restoreCookieHeader = jar.cookieHeaderFor(restoreParsedUrl, nowEpochMillis)
                        val restoreResponse =
                            httpClientFactory {
                                followRedirects = false
                                install(VVOHeaderPlugin) {
                                    chocolateProvider = { restoreCookieHeader }
                                    userAgentProvider = { userAgent }
                                }
                            }.get(restoreUrl) {
                                header(HttpHeaders.Referrer, currentUrl)
                            }
                        jar.applySetCookies(
                            requestUrl = restoreParsedUrl,
                            setCookieHeaders = restoreResponse.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
                            nowEpochMillis = nowEpochMillis,
                        )
                        val payload = parseVisitorRestoreJsonp(restoreResponse.bodyAsText())
                        if (payload?.retcode == 20_000_000L && !payload.alt.isNullOrBlank()) {
                            val ssoV2Url =
                                "https://passport.weibo.com/sso/v2/login?entry=sso&source=visitor_restore&type=3" +
                                    "&alt=${payload.alt.encodeURLParameter()}&url=${baseUrl.encodeURLParameter()}"
                            pendingUrls.addFirst(ssoV2Url)
                        }
                    }
                } else if (host == "passport.weibo.com" && parsedUrl.encodedPath == "/sso/v2/login") {
                    val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                    val ssoTargets = extractTrustedSsoLocations(body = bodyText, baseUrl = currentUrl)
                    ssoTargets.asReversed().forEach { pendingUrls.addFirst(it) }
                }
            }
        }
        return jar
    }

    private fun effectiveUserAgent(credential: VVoCredential?): String =
        credential?.userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VVO_USER_AGENT

    private suspend fun currentCredential(): VVoCredential? = credentialFlow.firstOrNull()

    private suspend fun currentChocolate(): String? = currentCredential()?.chocolate
}

private fun VVoCredential.isRefreshedAfter(other: VVoCredential): Boolean {
    val lastRefresh = lastCookieRefreshEpochMillis ?: return false
    val otherLastRefresh = other.lastCookieRefreshEpochMillis
    return this != other && (otherLastRefresh == null || lastRefresh > otherLastRefresh)
}

internal fun shouldRefreshVvoCookie(
    lastRefreshEpochMillis: Long?,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): Boolean =
    lastRefreshEpochMillis == null ||
        nowEpochMillis - lastRefreshEpochMillis > chocolateRefreshInterval.inWholeMilliseconds

internal fun isRefreshRedirect(
    statusCode: Int,
    location: String?,
): Boolean = statusCode in 300..399 && !location.isNullOrBlank()

internal fun resolveRefreshRedirectUrl(
    current: String,
    location: String,
): String? =
    runCatching {
        URLBuilder()
            .takeFrom(current)
            .apply {
                parameters.clear()
                takeFrom(location)
            }.build()
    }.getOrNull()
        ?.takeIf {
            val host = it.host.lowercase()
            it.protocol == URLProtocol.HTTPS &&
                it.port == URLProtocol.HTTPS.defaultPort &&
                it.user == null &&
                it.password == null &&
                (
                    host == "weibo.cn" || host.endsWith(".weibo.cn") ||
                        host == "weibo.com" || host.endsWith(".weibo.com") ||
                        host == "login.sina.com.cn"
                )
        }?.toString()

// ALF carries the session's absolute expiry as epoch seconds.
internal fun vvoSessionExpiryEpochMillis(cookieHeader: String): Long? =
    cookieHeader
        .split(';')
        .mapNotNull { it.toCookieNameValueOrNull() }
        .firstOrNull { it.first.equals("ALF", ignoreCase = true) }
        ?.second
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { it * 1000 }

internal fun isVvoSessionExpired(
    cookieHeader: String,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): Boolean {
    val expiry = vvoSessionExpiryEpochMillis(cookieHeader) ?: return false
    return nowEpochMillis >= expiry
}

internal fun mergeVvoCookieHeader(
    currentCookieHeader: String,
    setCookieHeaders: List<String>,
): String? {
    if (setCookieHeaders.isEmpty()) {
        return null
    }

    val cookies = linkedMapOf<String, String>()
    currentCookieHeader
        .split(';')
        .mapNotNull { it.toCookieNameValueOrNull() }
        .forEach { (key, value) ->
            cookies[key] = value
        }

    var changed = false
    setCookieHeaders
        .mapNotNull { it.toSetCookiePairOrNull() }
        .forEach { (key, value) ->
            val previous = cookies.put(key, value)
            changed = previous != value || changed
        }

    if (!changed) {
        return null
    }
    return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
}

private fun String.toSetCookiePairOrNull(): Pair<String, String>? {
    val cookie = substringBefore(';').toCookieNameValueOrNull() ?: return null
    return cookie.takeUnless { isExpiredSetCookie() }
}

private fun String.isExpiredSetCookie(): Boolean =
    split(';')
        .drop(1)
        .map { it.trim().lowercase() }
        .any { attribute ->
            when {
                attribute.startsWith("max-age=") -> {
                    attribute.substringAfter("=").toLongOrNull()?.let { it <= 0 } == true
                }

                attribute.startsWith("expires=") -> {
                    parseVvoCookieExpiresEpochSeconds(attribute.substringAfter("="))
                        ?.let { it <= Clock.System.now().epochSeconds } == true
                }

                else -> {
                    false
                }
            }
        }

private class VVOHeaderConfig {
    var chocolateProvider: (suspend () -> String?)? = null
    var userAgentProvider: (suspend () -> String?)? = null
}

private val VVOHeaderPlugin =
    createClientPlugin("VVOHeaderPlugin", ::VVOHeaderConfig) {
        val chocolateProvider = pluginConfig.chocolateProvider
        val userAgentProvider = pluginConfig.userAgentProvider
        onRequest { request, _ ->
            chocolateProvider?.let { provider ->
                val chocolate = provider()
                if (!chocolate.isNullOrBlank()) {
                    request.headers.append("Cookie", chocolate)
                }
            }
            if (request.headers[HttpHeaders.Referrer] == null) {
                request.headers.append(HttpHeaders.Referrer, "https://$vvoHost/")
            }
            if (request.headers[HttpHeaders.UserAgent] == null) {
                val ua = userAgentProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VVO_USER_AGENT
                request.headers.append(HttpHeaders.UserAgent, ua)
            }
        }
    }
