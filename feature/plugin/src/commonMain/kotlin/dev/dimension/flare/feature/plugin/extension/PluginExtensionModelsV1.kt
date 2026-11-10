package dev.dimension.flare.feature.plugin.extension

import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.render.UiDateTime
import dev.dimension.flare.ui.render.toUi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public enum class PluginExtensionResultStatusV1 {
    @SerialName("completed")
    Completed,

    @SerialName("already-completed")
    AlreadyCompleted,

    @SerialName("partial")
    Partial,

    @SerialName("failed")
    Failed,

    @SerialName("authentication-required")
    AuthenticationRequired,

    @SerialName("user-action-required")
    UserActionRequired,

    @SerialName("skipped")
    Skipped,

    @SerialName("interrupted")
    Interrupted,
}

@Serializable
public data class PluginExtensionResultV1(
    val status: PluginExtensionResultStatusV1,
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    internal fun requireValid() {
        require(status != PluginExtensionResultStatusV1.Interrupted) { "Interrupted is reserved for the Host" }
        require(listOf(succeeded, skipped, failed).all { it in 0..100_000 }) { "Invalid result counts" }
    }
}

@Serializable
public data class PluginExtensionRequestV1(
    val actionId: String,
    val businessDate: String,
    val trigger: String,
)

public data class PluginExtensionActionItemV1(
    val id: String,
    val title: UiText,
    val description: UiText?,
    val dailyTimeZone: String?,
    val platformIds: Set<String> = emptySet(),
)

public data class PluginExtensionAccountItemV1(
    val accountKey: String,
    val platformId: String,
    val authOrigins: List<String>,
    val authorizationToken: String,
    val accountId: String?,
    val automaticActions: Set<String>,
    val account: UiAccount? = null,
    val profile: UiProfile? = null,
)

@Serializable
public data class PluginExtensionRunV1(
    val id: String,
    val accountId: String,
    val actionId: String,
    val businessDate: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val result: PluginExtensionResultV1 = PluginExtensionResultV1(PluginExtensionResultStatusV1.Interrupted),
) {
    public val startedAt: UiDateTime get() = Instant.fromEpochMilliseconds(startedAtMillis).toUi()
}

public data class PluginExtensionStateV1(
    val name: UiText = UiText.Raw(""),
    val available: Boolean = false,
    val actions: List<PluginExtensionActionItemV1> = emptyList(),
    val accounts: List<PluginExtensionAccountItemV1> = emptyList(),
    val runs: List<PluginExtensionRunV1> = emptyList(),
    val runningAccounts: Set<String> = emptySet(),
    val error: Boolean = false,
    val loading: Boolean = false,
)

@Serializable
internal data class PluginExtensionGrantV1(
    val id: String,
    val accountKey: String,
    val platformId: String,
    val sessionId: String,
    val permissions: String,
    val automaticActions: Set<String> = emptySet(),
    val sourceAuthorizationRevision: String? = null,
)

@Serializable
internal data class StoredPluginExtensionsV1(
    val grants: List<PluginExtensionGrantV1> = emptyList(),
    val runs: List<PluginExtensionRunV1> = emptyList(),
    val attemptDates: Map<String, String> = emptyMap(),
)
