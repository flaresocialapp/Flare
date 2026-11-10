package dev.dimension.flare.data.datasource.microblog

import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

/** Authentication stays in the account's provider; callers inject the result only into approved requests. */
@HiddenFromObjC
public interface AccountHttpCapability {
    public val origins: Set<String>

    public val accountOrigin: String? get() = null

    /** Stable across restarts, changed when a plugin provider or its authorization contract is replaced. */
    public val authorizationRevision: String? get() = null

    public suspend fun ensureActive() {}

    public suspend fun <T> withAuthorization(block: suspend () -> T): T {
        ensureActive()
        return block().also { ensureActive() }
    }

    public suspend fun authenticate(request: AccountHttpRequest): Map<String, String>
}

/** bodyBase64 contains the exact bytes sent by the Host, including multipart boundaries. */
@Serializable
public data class AccountHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyBase64: String? = null,
)
