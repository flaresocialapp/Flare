package dev.dimension.flare.data.translation

import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.ui.model.UiText
import kotlinx.coroutines.flow.StateFlow
import kotlin.native.HiddenFromObjC

public data class PluginTranslationProvider(
    val pluginId: String,
    val name: UiText,
    val cacheKey: String,
)

/** Implemented by the optional plugin module; shared translation keeps its existing execution policy. */
@HiddenFromObjC
public interface PluginTranslationService {
    public val providers: StateFlow<List<PluginTranslationProvider>>

    public suspend fun translate(
        pluginId: String,
        sourceTemplate: String?,
        sourceJson: String,
        targetLanguage: String,
        prompt: String,
        batch: Boolean,
    ): String?

    public suspend fun <T> withCurrent(
        pluginId: String,
        expectedKey: String,
        block: suspend () -> T,
    ): T
}

internal fun pluginTranslationServiceOrNull(): PluginTranslationService? = runCatching { koinGet<PluginTranslationService>() }.getOrNull()

internal fun pluginTranslationCacheKey(pluginId: String): String =
    pluginTranslationServiceOrNull()
        ?.providers
        ?.value
        ?.firstOrNull { it.pluginId == pluginId }
        ?.cacheKey ?: "plugin:$pluginId:unavailable"

internal suspend fun <T> AppSettings.withCurrentTranslationProvider(
    expectedKey: String,
    block: suspend () -> T,
): T =
    when (val provider = translateConfig.provider) {
        is AppSettings.TranslateConfig.Provider.Plugin -> {
            val service = checkNotNull(pluginTranslationServiceOrNull()) { "Plugin translation is unavailable" }
            service.withCurrent(provider.pluginId, expectedKey, block)
        }

        else -> {
            block()
        }
    }
