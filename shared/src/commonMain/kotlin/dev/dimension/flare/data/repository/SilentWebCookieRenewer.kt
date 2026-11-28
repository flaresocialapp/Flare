package dev.dimension.flare.data.repository

import androidx.compose.runtime.Immutable
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.http.URLProtocol
import io.ktor.http.Url

@Immutable
public data class SilentSeedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
)

@Immutable
public data class SilentCookieRenewRequest(
    val accountKey: MicroBlogKey,
    val proxyRef: ProxyRef,
    val startUrl: String,
    val probeUrls: List<String>,
    val allowedHosts: Set<String>,
    val allowedHostSuffixes: Set<String>,
    val seedCookies: List<SilentSeedCookie>,
    val userAgent: String?,
    val previousSubCookie: String?,
    val timeoutMillis: Long = 15_000L,
)

public sealed interface SilentCookieRenewResult {
    public data class Renewed(
        val cookies: List<SilentSeedCookie>,
        val userAgent: String?,
    ) : SilentCookieRenewResult

    /** Session settled on an interactive sign-in page; SSO cookies cannot silently renew. */
    public data object InteractiveLoginRequired : SilentCookieRenewResult

    /** Platform WebView unavailable, busy with interactive UI, proxy policy blocked, or transient error. */
    public data class Skipped(
        val reason: String,
    ) : SilentCookieRenewResult
}

public sealed interface SilentRenewPollDecision {
    public data class Pending(
        val interactiveLoginEnteredAtMs: Long?,
    ) : SilentRenewPollDecision

    public data object Completed : SilentRenewPollDecision

    public data object InteractiveLoginRequired : SilentRenewPollDecision
}

public interface SilentWebCookieRenewer {
    @Throws(Exception::class)
    public suspend fun renew(request: SilentCookieRenewRequest): SilentCookieRenewResult
}

public fun isSilentRenewSsoHost(host: String?): Boolean {
    val normalized = host?.lowercase() ?: return false
    return "passport" in normalized || "sina.com.cn" in normalized
}

public fun shouldSeedSilentRenewCookie(
    cookie: SilentSeedCookie,
    previousSubCookie: String?,
): Boolean {
    if (previousSubCookie.isNullOrEmpty() || cookie.name != "SUB" || cookie.value != previousSubCookie) {
        return true
    }
    val domain =
        cookie.domain
            .trim()
            .trimStart('.')
            .lowercase()
    return !(domain == "weibo.cn" || domain.endsWith(".weibo.cn"))
}

public fun evaluateSilentRenewPollState(
    loadedUrl: String?,
    isLoading: Boolean,
    mWeiboCookies: Map<String, String>,
    previousSubCookie: String?,
    visitedPassportOrVisitor: Boolean,
    interactiveLoginEnteredAtMs: Long?,
    nowMs: Long,
    settleWindowMs: Long,
): SilentRenewPollDecision {
    val sub = mWeiboCookies["SUB"]?.trim()?.takeIf { it.isNotEmpty() }
    val mlogin = mWeiboCookies["MLOGIN"] == "1"
    val loadedHost = loadedUrl?.let { runCatching { Url(it).host.lowercase() }.getOrNull() }
    if (mlogin && sub != null && (sub != previousSubCookie || (visitedPassportOrVisitor && loadedHost == "m.weibo.cn"))) {
        return SilentRenewPollDecision.Completed
    }

    val settledUnauthenticatedTarget =
        loadedUrl != null &&
            (
                isWeiboInteractiveLoginUrl(loadedUrl) ||
                    (visitedPassportOrVisitor && loadedHost == "m.weibo.cn" && !mlogin)
            )
    if (!isLoading && settledUnauthenticatedTarget) {
        val enteredAt = interactiveLoginEnteredAtMs ?: nowMs
        return if (nowMs - enteredAt >= settleWindowMs) {
            SilentRenewPollDecision.InteractiveLoginRequired
        } else {
            SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = enteredAt)
        }
    }

    return SilentRenewPollDecision.Pending(interactiveLoginEnteredAtMs = null)
}

public fun isAllowedSilentRenewNavigationUrl(
    url: String,
    allowedHosts: Set<String>,
    allowedHostSuffixes: Set<String>,
): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    if (parsed.protocol != URLProtocol.HTTPS || parsed.port != URLProtocol.HTTPS.defaultPort) {
        return false
    }
    if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) {
        return false
    }
    val host = parsed.host.lowercase()
    if (host.isEmpty()) return false
    if (allowedHosts.any { it.lowercase() == host }) return true
    return allowedHostSuffixes.any { suffix ->
        val clean = suffix.trimStart('.').lowercase()
        clean.isNotEmpty() && (host == clean || host.endsWith(".$clean"))
    }
}

public fun isWeiboInteractiveLoginUrl(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    val host = parsed.host.lowercase()
    val path = parsed.encodedPath.lowercase()
    return when {
        host == "passport.weibo.com" && path.startsWith("/sso/signin") -> true
        host == "passport.weibo.cn" && path.startsWith("/signin/login") -> true
        host == "login.sina.com.cn" && path.startsWith("/signup/signin") -> true
        (host == "weibo.com" || host == "www.weibo.com") && path.startsWith("/newlogin") -> true
        host == "m.weibo.cn" && path.startsWith("/login") -> true
        else -> false
    }
}
