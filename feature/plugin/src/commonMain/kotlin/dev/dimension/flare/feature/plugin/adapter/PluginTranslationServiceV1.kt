package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.translation.PluginTranslationProvider
import dev.dimension.flare.data.translation.PluginTranslationService
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.lifecycle.derivedPluginState
import dev.dimension.flare.feature.plugin.manifest.toUiText
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PluginTranslationServiceV1(
    private val subsystem: PluginSubsystemV1,
) : PluginTranslationService {
    override val providers: StateFlow<List<PluginTranslationProvider>> =
        derivedPluginState(subsystem.running, subsystem.storage.changes) { running, _ ->
            running.plugins.values
                .filter { it.installed.manifest.translation != null }
                .map { plugin ->
                    PluginTranslationProvider(
                        plugin.installed.pluginId,
                        plugin.installed.manifest.name
                            .toUiText(plugin.installed.pluginId),
                        key(plugin),
                    )
                }.sortedBy { it.pluginId }
        }

    override suspend fun translate(
        pluginId: String,
        sourceTemplate: String?,
        sourceJson: String,
        targetLanguage: String,
        prompt: String,
        batch: Boolean,
    ): String? {
        val plugin = plugin(pluginId)
        val expected = key(plugin)
        val manifest = plugin.installed.manifest
        val request =
            buildJsonObject {
                if (sourceTemplate != null) put("sourceTemplate", sourceTemplate)
                put("sourceJson", sourceJson)
                if (batch) put("sourceDocument", PluginJsonV1.parseToJsonElement(sourceJson))
                put("targetLanguage", targetLanguage)
                put("prompt", prompt)
            }
        val response =
            subsystem.runtimePool.invokeJson(
                plugin = plugin,
                key = PluginRuntimeKeyV1.plugin(pluginId, plugin.installed.packageHash),
                context =
                    PluginInvocationContextV1.plugin(
                        pluginId,
                        plugin.installed.packageHash,
                        manifest.permissions.httpOrigins,
                        Locale.language,
                        manifest.platform?.id.orEmpty(),
                    ),
                method = if (batch) "translation.translateBatchDocumentJson" else "translation.translateDocumentJson",
                request = request,
                timeout = PluginCallTimeoutV1.Extended,
                validate = {
                    require(
                        it == JsonNull || (it is JsonPrimitive && it.isString),
                    ) { "Translation must return a JSON string or null" }
                },
            )
        return withCurrent(pluginId, expected) { (response as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content }
    }

    override suspend fun <T> withCurrent(
        pluginId: String,
        expectedKey: String,
        block: suspend () -> T,
    ): T {
        val plugin = plugin(pluginId)
        if (key(plugin) != expectedKey) throw CancellationException("Plugin translation changed")
        val namespaces = requireNotNull(plugin.installed.manifest.translation).cache.storageNamespaces
        val token = subsystem.storage.token(pluginId, namespaces)
        return plugin.session.invoke("translation.write") {
            subsystem.storage.ifCurrent(pluginId, namespaces, token) {
                if (subsystem.running.value.plugins[pluginId] !== plugin ||
                    key(plugin) != expectedKey
                ) {
                    throw CancellationException("Plugin translation changed")
                }
                block()
            }
        }
    }

    private fun plugin(pluginId: String): RunningPluginV1 =
        checkNotNull(
            subsystem.running.value.plugins[pluginId]?.takeIf {
                it.installed.manifest.translation != null
            },
        ) { "Translation plugin is unavailable: $pluginId" }

    private fun key(plugin: RunningPluginV1): String {
        val manifest = plugin.installed.manifest
        val token =
            runCatching {
                subsystem.storage.token(manifest.id, requireNotNull(manifest.translation).cache.storageNamespaces)
            }.getOrElse { "unavailable" }
        return "plugin:${manifest.id}:${plugin.installed.packageHash}:${plugin.generation}:$token"
    }
}
