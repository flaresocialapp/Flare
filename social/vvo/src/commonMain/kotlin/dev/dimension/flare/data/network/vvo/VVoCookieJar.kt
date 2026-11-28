package dev.dimension.flare.data.network.vvo

import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.platform.VVoStoredCookie
import dev.dimension.flare.data.repository.SilentSeedCookie
import dev.dimension.flare.model.vvoHost
import dev.dimension.flare.ui.presenter.login.LoginCookieSnapshot
import dev.dimension.flare.ui.presenter.login.LoginCookieValue
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.time.Clock

internal val VVO_COOKIE_PROBE_URLS: List<String> =
    listOf(
        "https://m.weibo.cn/",
        "https://passport.weibo.cn/",
        "https://visitor.passport.weibo.cn/",
        "https://weibo.com/",
        "https://passport.weibo.com/",
        "https://visitor.passport.weibo.com/",
        "https://login.sina.com.cn/",
    )

internal val VVO_TRUSTED_ROOT_DOMAINS: Set<String> =
    setOf(
        "weibo.cn",
        "weibo.com",
        "sina.com.cn",
    )

private val mWeiboRootUrl = Url("https://$vvoHost/")

internal fun isTrustedVvoCookieDomain(domain: String): Boolean {
    val normalized = domain.trim().trimStart('.').lowercase()
    if (normalized.isEmpty()) return false
    return VVO_TRUSTED_ROOT_DOMAINS.any { root ->
        normalized == root || normalized.endsWith(".$root")
    }
}

internal fun hasValidMWeiboLoginCookies(cookies: Map<String, String>): Boolean =
    cookies["MLOGIN"] == "1" && !cookies["SUB"]?.trim().isNullOrEmpty()

internal class VVoCookieJar(
    cookies: List<VVoStoredCookie> = emptyList(),
) {
    private val entries = mutableListOf<VVoStoredCookie>()

    init {
        cookies.forEach { cookie ->
            val normalizedDomain = cookie.domain.trim().trimStart('.').lowercase()
            val normalizedName = cookie.name.trim()
            if (normalizedName.isNotEmpty() && isTrustedVvoCookieDomain(normalizedDomain)) {
                upsertExact(
                    cookie.copy(
                        name = normalizedName,
                        domain = normalizedDomain,
                        path = normalizeCookiePath(cookie.path),
                    ),
                )
            }
        }
    }

    fun copy(): VVoCookieJar = VVoCookieJar(entries.toList())

    fun isEmpty(): Boolean = entries.isEmpty()

    fun cookieMapFor(
        url: Url,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Map<String, String> {
        val matched = matchingCookies(url, nowEpochMillis)
        if (matched.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (cookie in matched) {
            if (!result.containsKey(cookie.name)) {
                result[cookie.name] = cookie.value
            }
        }
        return result
    }

    fun cookieHeaderFor(
        url: Url,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): String? {
        val map = cookieMapFor(url, nowEpochMillis)
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    fun applySetCookies(
        requestUrl: Url,
        setCookieHeaders: List<String>,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        if (setCookieHeaders.isEmpty()) return false
        val requestHost = requestUrl.host.lowercase()
        if (!isTrustedVvoCookieDomain(requestHost)) return false

        var changed = false
        for (rawHeader in setCookieHeaders) {
            val parsed = parseSetCookieHeader(rawHeader, requestUrl, nowEpochMillis) ?: continue
            if (parsed.expired) {
                val removed =
                    entries.removeAll { existing ->
                        existing.name == parsed.cookie.name &&
                            existing.path == parsed.cookie.path &&
                            (
                                (existing.domain == parsed.cookie.domain && existing.hostOnly == parsed.cookie.hostOnly) ||
                                    (
                                        !parsed.cookie.hostOnly &&
                                            existing.hostOnly &&
                                            domainMatches(existing.domain, parsed.cookie.domain, hostOnly = false)
                                    )
                            )
                    }
                changed = removed || changed
            } else {
                changed = upsertFromNetwork(parsed.cookie) || changed
            }
        }
        return changed
    }

    fun hasLongLivedSsoCookies(nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        entries.any { cookie ->
            if (cookie.value.isBlank()) return@any false
            if (cookie.expiresAtEpochMillis != null && cookie.expiresAtEpochMillis <= nowEpochMillis) {
                return@any false
            }
            when (cookie.name.uppercase()) {
                "ALC", "SRF", "SSO", "SUS", "SUP" -> true
                "SUB", "ALF" ->
                    cookie.domain == "weibo.com" ||
                        cookie.domain.endsWith(".weibo.com") ||
                        cookie.domain == "sina.com.cn" ||
                        cookie.domain.endsWith(".sina.com.cn")
                else -> false
            }
        }

    fun toStoredCookies(nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds()): List<VVoStoredCookie> =
        entries.filter { cookie ->
            cookie.expiresAtEpochMillis == null || cookie.expiresAtEpochMillis > nowEpochMillis
        }

    fun toSilentSeedCookies(nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds()): List<SilentSeedCookie> =
        toStoredCookies(nowEpochMillis).map { cookie ->
            SilentSeedCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                hostOnly = cookie.hostOnly,
                path = cookie.path,
                expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
            )
        }

    fun mergeSilentSeedCookies(
        seedCookies: List<SilentSeedCookie>,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        var changed = false
        val preExistingHostOnlyKeys =
            entries
                .filter { it.hostOnly }
                .mapTo(HashSet()) { CookieSlotKey(it.name, it.domain, it.path) }
        val incomingDomainKeys =
            seedCookies
                .filter { !it.hostOnly }
                .mapTo(HashSet()) {
                    CookieSlotKey(
                        name = it.name.trim(),
                        domain =
                            it.domain
                                .trim()
                                .trimStart('.')
                                .lowercase(),
                        path = normalizeCookiePath(it.path),
                    )
                }
        for (seed in seedCookies) {
            val cleanName = seed.name.trim()
            val normalizedDomain =
                seed.domain
                    .trim()
                    .trimStart('.')
                    .lowercase()
            if (cleanName.isEmpty() || !isTrustedVvoCookieDomain(normalizedDomain)) continue
            val expiresAt = seed.expiresAtEpochMillis
            if (expiresAt != null && expiresAt <= nowEpochMillis) continue
            val normalizedPath = normalizeCookiePath(seed.path)
            if (!seed.hostOnly) {
                val removed =
                    entries.removeAll { existing ->
                        existing.name == cleanName &&
                            existing.path == normalizedPath &&
                            existing.hostOnly &&
                            CookieSlotKey(existing.name, existing.domain, existing.path) in preExistingHostOnlyKeys &&
                            domainMatches(existing.domain, normalizedDomain, hostOnly = false)
                    }
                changed = removed || changed
            } else {
                val removed =
                    entries.removeAll { existing ->
                        existing.name == cleanName &&
                            existing.path == normalizedPath &&
                            !existing.hostOnly &&
                            domainMatches(normalizedDomain, existing.domain, hostOnly = false) &&
                            CookieSlotKey(cleanName, existing.domain, normalizedPath) !in incomingDomainKeys
                    }
                changed = removed || changed
            }
            val stored =
                VVoStoredCookie(
                    name = cleanName,
                    value = seed.value,
                    domain = normalizedDomain,
                    hostOnly = seed.hostOnly,
                    path = normalizedPath,
                    expiresAtEpochMillis = seed.expiresAtEpochMillis,
                    secure = seed.secure,
                    httpOnly = seed.httpOnly,
                )
            changed = upsertExact(stored) || changed
        }
        return changed
    }

    fun toCredential(
        base: VVoCredential? = null,
        userAgent: String? = base?.userAgent,
        lastRefreshEpochMillis: Long? = base?.lastCookieRefreshEpochMillis,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): VVoCredential {
        val stored = toStoredCookies(nowEpochMillis)
        val mWeiboChocolate = cookieHeaderFor(mWeiboRootUrl, nowEpochMillis) ?: base?.chocolate.orEmpty()
        return VVoCredential(
            chocolate = mWeiboChocolate,
            lastCookieRefreshEpochMillis = lastRefreshEpochMillis,
            cookies = stored,
            userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun matchingCookies(
        url: Url,
        nowEpochMillis: Long,
    ): List<VVoStoredCookie> {
        val host = url.host.lowercase()
        val path = url.encodedPath.ifEmpty { "/" }
        val isHttps = url.protocol == URLProtocol.HTTPS
        return entries
            .filter { cookie ->
                (cookie.expiresAtEpochMillis == null || cookie.expiresAtEpochMillis > nowEpochMillis) &&
                    (!cookie.secure || isHttps) &&
                    domainMatches(host, cookie.domain, cookie.hostOnly) &&
                    pathMatches(path, cookie.path)
            }.sortedWith(
                compareByDescending<VVoStoredCookie> { it.path.length }
                    .thenByDescending { it.hostOnly }
                    .thenByDescending { it.domain.length },
            )
    }

    private fun upsertExact(cookie: VVoStoredCookie): Boolean {
        val index =
            entries.indexOfFirst {
                it.name == cookie.name &&
                    it.domain == cookie.domain &&
                    it.hostOnly == cookie.hostOnly &&
                    it.path == cookie.path
            }
        return if (index >= 0) {
            val previous = entries[index]
            entries[index] = cookie
            previous != cookie
        } else {
            entries += cookie
            true
        }
    }

    private fun upsertFromNetwork(cookie: VVoStoredCookie): Boolean {
        if (!cookie.hostOnly) {
            val removedShadowingHosts =
                entries.removeAll { existing ->
                    existing.name == cookie.name &&
                        existing.path == cookie.path &&
                        existing.hostOnly &&
                        domainMatches(existing.domain, cookie.domain, hostOnly = false)
                }
            return upsertExact(cookie) || removedShadowingHosts
        }
        return upsertExact(cookie)
    }

    companion object {
        fun fromCredential(credential: VVoCredential): VVoCookieJar =
            if (credential.cookies.isNotEmpty()) {
                VVoCookieJar(credential.cookies)
            } else {
                fromRawCookieHeader(
                    rawCookieHeader = credential.chocolate,
                    domain = "weibo.cn",
                    hostOnly = false,
                )
            }

        fun fromRawCookieHeader(
            rawCookieHeader: String,
            domain: String,
            hostOnly: Boolean,
        ): VVoCookieJar {
            if (rawCookieHeader.isBlank() || !isTrustedVvoCookieDomain(domain)) {
                return VVoCookieJar()
            }
            val normalizedDomain = domain.trim().trimStart('.').lowercase()
            val cookies =
                rawCookieHeader
                    .split(';')
                    .mapNotNull { segment -> segment.toCookieNameValueOrNull() }
                    .map { (name, value) ->
                        VVoStoredCookie(
                            name = name,
                            value = value,
                            domain = normalizedDomain,
                            hostOnly = hostOnly,
                            path = "/",
                            secure = true,
                        )
                    }
            return VVoCookieJar(cookies)
        }

        fun fromLoginSnapshot(
            snapshot: LoginCookieSnapshot,
            nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
        ): VVoCookieJar {
            if (snapshot.values.isEmpty()) {
                return fromRawCookieHeader(
                    rawCookieHeader = snapshot.rawHeader.orEmpty(),
                    domain = "weibo.cn",
                    hostOnly = false,
                )
            }
            val dedupedValues = deduplicateAttributeLessSnapshotValues(snapshot.values)
            val parsedWithMeta =
                dedupedValues.mapNotNull { value ->
                    val cookie = value.toStoredCookieOrNull(nowEpochMillis) ?: return@mapNotNull null
                    val inferredFromSourceHost = value.attributes?.domain.isNullOrBlank()
                    cookie to inferredFromSourceHost
                }
            return VVoCookieJar(promoteSharedRootDomainCookies(parsedWithMeta))
        }
    }
}

private data class CookieSlotKey(
    val name: String,
    val domain: String,
    val path: String,
)

private fun deduplicateAttributeLessSnapshotValues(values: List<LoginCookieValue>): List<LoginCookieValue> {
    val lastAttrLessIndexByKey = HashMap<Pair<String, String>, Int>()
    values.forEachIndexed { index, item ->
        if (item.attributes == null) {
            lastAttrLessIndexByKey[item.sourceUrl to item.name.trim()] = index
        }
    }
    return values.filterIndexed { index, item ->
        item.attributes != null || lastAttrLessIndexByKey[item.sourceUrl to item.name.trim()] == index
    }
}

private fun promoteSharedRootDomainCookies(items: List<Pair<VVoStoredCookie, Boolean>>): List<VVoStoredCookie> {
    data class SharedGroupKey(
        val name: String,
        val value: String,
        val path: String,
        val rootDomain: String,
    )

    val hostCounts = HashMap<SharedGroupKey, MutableSet<String>>()
    for ((cookie, inferredFromSourceHost) in items) {
        if (!inferredFromSourceHost || !cookie.hostOnly) continue
        val root = trustedRootDomainOf(cookie.domain) ?: continue
        val key = SharedGroupKey(cookie.name, cookie.value, cookie.path, root)
        hostCounts.getOrPut(key) { LinkedHashSet() }.add(cookie.domain)
    }

    val emittedPromoted = HashSet<SharedGroupKey>()
    val result = ArrayList<VVoStoredCookie>(items.size)
    for ((cookie, inferredFromSourceHost) in items) {
        if (inferredFromSourceHost && cookie.hostOnly) {
            val root = trustedRootDomainOf(cookie.domain)
            if (root != null) {
                val key = SharedGroupKey(cookie.name, cookie.value, cookie.path, root)
                if ((hostCounts[key]?.size ?: 0) >= 2) {
                    if (emittedPromoted.add(key)) {
                        result += cookie.copy(domain = root, hostOnly = false)
                    }
                    continue
                }
            }
        }
        result += cookie
    }
    return result
}

private fun trustedRootDomainOf(domain: String): String? {
    val normalized = domain.trim().trimStart('.').lowercase()
    return VVO_TRUSTED_ROOT_DOMAINS.firstOrNull { root ->
        normalized == root || normalized.endsWith(".$root")
    }
}

private fun LoginCookieValue.toStoredCookieOrNull(nowEpochMillis: Long): VVoStoredCookie? {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return null
    val attrs = attributes
    val expiresAt = attrs?.expiresAt
    if (expiresAt != null && expiresAt <= nowEpochMillis) return null

    val rawAttrDomain = attrs?.domain?.trim()?.takeIf { it.isNotEmpty() }
    val domain: String
    val hostOnly: Boolean
    if (rawAttrDomain != null) {
        val normalized = rawAttrDomain.trimStart('.').lowercase()
        if (!isTrustedVvoCookieDomain(normalized)) return null
        domain = normalized
        hostOnly =
            attrs.hostOnly
                ?: (!rawAttrDomain.startsWith('.') && normalized !in VVO_TRUSTED_ROOT_DOMAINS)
    } else {
        val sourceHost =
            runCatching { Url(sourceUrl) }
                .getOrNull()
                ?.takeIf { it.protocol == URLProtocol.HTTPS && it.port == URLProtocol.HTTPS.defaultPort && it.user == null && it.password == null }
                ?.host
                ?.lowercase()
                ?: return null
        if (!isTrustedVvoCookieDomain(sourceHost)) return null
        domain = sourceHost
        hostOnly = true
    }

    return VVoStoredCookie(
        name = cleanName,
        value = value,
        domain = domain,
        hostOnly = hostOnly,
        path = normalizeCookiePath(attrs?.path),
        expiresAtEpochMillis = expiresAt,
        secure = attrs?.secure ?: true,
        httpOnly = attrs?.httpOnly ?: false,
    )
}

private data class ParsedSetCookie(
    val cookie: VVoStoredCookie,
    val expired: Boolean,
)

private fun parseSetCookieHeader(
    rawHeader: String,
    requestUrl: Url,
    nowEpochMillis: Long,
): ParsedSetCookie? {
    val parts = rawHeader.split(';')
    val (name, value) = parts.firstOrNull()?.toCookieNameValueOrNull() ?: return null
    val requestHost = requestUrl.host.lowercase()

    var domainAttr: String? = null
    var pathAttr: String? = null
    var maxAgeSeconds: Long? = null
    var expiresEpochSeconds: Long? = null
    var secure = false
    var httpOnly = false

    for (rawAttr in parts.drop(1)) {
        val trimmed = rawAttr.trim()
        if (trimmed.isEmpty()) continue
        val eqIdx = trimmed.indexOf('=')
        val attrName = (if (eqIdx >= 0) trimmed.substring(0, eqIdx) else trimmed).trim().lowercase()
        val attrVal = if (eqIdx >= 0) trimmed.substring(eqIdx + 1).trim() else ""
        when (attrName) {
            "domain" -> if (attrVal.isNotEmpty()) domainAttr = attrVal
            "path" -> if (attrVal.isNotEmpty()) pathAttr = attrVal
            "max-age" -> maxAgeSeconds = attrVal.toLongOrNull()
            "expires" -> expiresEpochSeconds = parseVvoCookieExpiresEpochSeconds(attrVal)
            "secure" -> secure = true
            "httponly" -> httpOnly = true
        }
    }

    val domain: String
    val hostOnly: Boolean
    if (domainAttr != null) {
        val normalized = domainAttr.trimStart('.').lowercase()
        if (!isTrustedVvoCookieDomain(normalized)) return null
        if (!domainMatches(requestHost, normalized, hostOnly = false)) return null
        domain = normalized
        hostOnly = false
    } else {
        if (!isTrustedVvoCookieDomain(requestHost)) return null
        domain = requestHost
        hostOnly = true
    }

    val expiresAtEpochMillis: Long? =
        when {
            maxAgeSeconds != null -> {
                if (maxAgeSeconds <= 0L) {
                    return ParsedSetCookie(
                        cookie =
                            VVoStoredCookie(
                                name = name,
                                value = value,
                                domain = domain,
                                hostOnly = hostOnly,
                                path = normalizeCookiePath(pathAttr ?: defaultCookiePath(requestUrl.encodedPath)),
                                secure = secure,
                                httpOnly = httpOnly,
                            ),
                        expired = true,
                    )
                }
                val maxAllowedSeconds = (Long.MAX_VALUE - nowEpochMillis) / 1_000L
                if (maxAgeSeconds >= maxAllowedSeconds) Long.MAX_VALUE else nowEpochMillis + maxAgeSeconds * 1_000L
            }

            expiresEpochSeconds != null -> {
                val millis =
                    if (expiresEpochSeconds >= Long.MAX_VALUE / 1_000L) {
                        Long.MAX_VALUE
                    } else if (expiresEpochSeconds <= Long.MIN_VALUE / 1_000L) {
                        Long.MIN_VALUE
                    } else {
                        expiresEpochSeconds * 1_000L
                    }
                if (millis <= nowEpochMillis) {
                    return ParsedSetCookie(
                        cookie =
                            VVoStoredCookie(
                                name = name,
                                value = value,
                                domain = domain,
                                hostOnly = hostOnly,
                                path = normalizeCookiePath(pathAttr ?: defaultCookiePath(requestUrl.encodedPath)),
                                secure = secure,
                                httpOnly = httpOnly,
                            ),
                        expired = true,
                    )
                }
                millis
            }

            else -> null
        }

    return ParsedSetCookie(
        cookie =
            VVoStoredCookie(
                name = name,
                value = value,
                domain = domain,
                hostOnly = hostOnly,
                path = normalizeCookiePath(pathAttr ?: defaultCookiePath(requestUrl.encodedPath)),
                expiresAtEpochMillis = expiresAtEpochMillis,
                secure = secure,
                httpOnly = httpOnly,
            ),
        expired = false,
    )
}

internal fun String.toCookieNameValueOrNull(): Pair<String, String>? {
    val separator = indexOf('=')
    if (separator <= 0) return null
    val key = substring(0, separator).trim()
    val value = substring(separator + 1).trim()
    return if (key.isEmpty()) null else key to value
}

private fun domainMatches(
    requestHost: String,
    cookieDomain: String,
    hostOnly: Boolean,
): Boolean =
    if (hostOnly) {
        requestHost == cookieDomain
    } else {
        requestHost == cookieDomain || requestHost.endsWith(".$cookieDomain")
    }

private fun pathMatches(
    requestPath: String,
    cookiePath: String,
): Boolean {
    if (requestPath == cookiePath) return true
    if (!requestPath.startsWith(cookiePath)) return false
    return cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'
}

private fun normalizeCookiePath(path: String?): String {
    val trimmed = path?.trim().orEmpty()
    return if (trimmed.startsWith('/')) trimmed else "/"
}

private fun defaultCookiePath(requestPath: String): String {
    if (requestPath.isEmpty() || !requestPath.startsWith('/')) return "/"
    val lastSlash = requestPath.lastIndexOf('/')
    return if (lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
}

internal fun parseVvoCookieExpiresEpochSeconds(value: String): Long? {
    val match = VvoCookieExpiresRegex.find(value.trim()) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = vvoMonthNumber(match.groupValues[2]) ?: return null
    val year =
        match.groupValues[3]
            .toIntOrNull()
            ?.let { rawYear ->
                when (rawYear) {
                    in 0..69 -> 2000 + rawYear
                    in 70..99 -> 1900 + rawYear
                    else -> rawYear
                }
            } ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null

    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return null
    }

    return vvoDaysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
}

private val VvoCookieExpiresRegex =
    Regex(
        pattern = """(?i)(?:[a-z]{3},\s*)?(\d{1,2})[-\s]([a-z]{3})[-\s](\d{2,4})\s+(\d{1,2}):(\d{2}):(\d{2})\s*(?:gmt|utc)?""",
    )

private fun vvoMonthNumber(value: String): Int? =
    when (value.lowercase()) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> null
    }

private fun vvoDaysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = adjustedYear / 400
    val yearOfEra = adjustedYear - era * 400
    val monthPrime = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
