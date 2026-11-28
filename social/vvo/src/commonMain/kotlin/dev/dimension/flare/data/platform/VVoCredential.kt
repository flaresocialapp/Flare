package dev.dimension.flare.data.platform

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
internal data class VVoStoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean = false,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
)

@Immutable
@Serializable
@SerialName("VVoCredential")
internal data class VVoCredential(
    val chocolate: String,
    val lastCookieRefreshEpochMillis: Long? = null,
    val cookies: List<VVoStoredCookie> = emptyList(),
    val userAgent: String? = null,
)
