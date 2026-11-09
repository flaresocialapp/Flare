package dev.dimension.flare.feature.plugin.management

import dev.dimension.flare.common.Locale
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginHostException
import dev.dimension.flare.feature.plugin.host.PluginHostGateway
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.createKtorPluginHttpTransport
import dev.dimension.flare.feature.plugin.host.platformUuid
import dev.dimension.flare.feature.plugin.manifest.isPluginSettingsPath
import dev.dimension.flare.feature.plugin.runtime.PluginPackageScriptLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.openZip

public data class PluginSettingsResourceV1(
    val mimeType: String,
    val base64: String,
)

/** A page owns this session. Native containers additionally authenticate the message's main frame and origin. */
public class PluginSettingsFacadeV1 internal constructor(
    private val subsystem: PluginSubsystemV1,
    pluginId: String,
) {
    public companion object {
        @Throws(Exception::class)
        public fun open(pluginId: String): PluginSettingsFacadeV1 = PluginSettingsFacadeV1(koinGet(), pluginId)
    }

    private val plugin = checkNotNull(subsystem.running.value.plugins[pluginId]) { "Plugin is unavailable" }
    public val entry: String = checkNotNull(plugin.installed.manifest.settings) { "Plugin has no settings page" }.entry
    public val host: String = platformUuid().replace("-", "") + ".plugin.invalid"
    public val contentSecurityPolicy: String =
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'none'; worker-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; sandbox allow-scripts allow-forms"

    // WebRTC does not use the resource loader or connect-src; install this in every frame before page scripts.
    public val documentStartScript: String =
        """
        (() => {
          for (const name of ["RTCPeerConnection", "webkitRTCPeerConnection", "mozRTCPeerConnection"]) {
            Object.defineProperty(globalThis, name, { value: undefined, writable: false, configurable: false });
          }
        })();
        """.trimIndent()
    private val active = MutableStateFlow(true)
    private val ready = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Semaphore(4)
    private val pending = MutableStateFlow(0)
    private val transport = lazy { createKtorPluginHttpTransport() }
    private val gateway = lazy { PluginHostGateway(transport.value) }

    init {
        scope.coroutineContext[Job]!!.invokeOnCompletion { if (transport.isInitialized()) transport.value.close() }
    }

    private val storage = subsystem.storage.access(pluginId, ::ensureActive)
    private val context =
        PluginInvocationContextV1.plugin(
            pluginId,
            plugin.installed.packageHash,
            plugin.installed.manifest.permissions.httpOrigins,
            Locale.language,
            plugin.installed.manifest.platform
                ?.id
                .orEmpty(),
        )
    private val archive by lazy {
        PluginPackageScriptLoader(subsystem.appFiles.fileSystem).verify(plugin)
        subsystem.appFiles.fileSystem.openZip(plugin.packagePath.toPath())
    }

    @Throws(Exception::class)
    public fun resource(path: String): PluginSettingsResourceV1 {
        ensureActive()
        require(isPluginSettingsPath(path)) { "Invalid settings resource path" }
        val bytes =
            plugin.developmentResources?.get(path) ?: run {
                val resource = ("/" + path).toPath()
                val size = archive.metadata(resource).size ?: error("Settings resource is missing")
                require(size in 0..4L * 1_024 * 1_024) { "Settings resource exceeds its size limit" }
                archive.read(resource) {
                    val buffer = Buffer()
                    while (buffer.size <= size) {
                        if (read(buffer, size + 1 - buffer.size) == -1L) break
                    }
                    require(buffer.size == size) { "Settings resource changed" }
                    buffer.readByteArray()
                }
            }
        ensureActive()
        return PluginSettingsResourceV1(mimeType(path), bytes.toByteString().base64())
    }

    @Throws(Exception::class)
    public suspend fun call(message: String): String {
        ensureActive()
        require(message.encodeToByteArray().size <= 5 * 1_024 * 1_024) { "Bridge message is too large" }
        while (true) {
            val count = pending.value
            if (count >= 32) throw PluginHostException("bridge.limit", "Too many pending Host requests")
            if (pending.compareAndSet(count, count + 1)) break
        }
        val task =
            scope.async {
                withTimeout(30_000) {
                    requests.withPermit {
                        ensureActive()
                        val request = PluginJsonV1.decodeFromString<SettingsBridgeRequest>(message)
                        require(request.id.length in 1..64) { "Invalid bridge request ID" }
                        val result =
                            if (request.operation == "bridge.hello") {
                                require(
                                    (request.arguments.jsonObject["version"] as? JsonPrimitive)?.intOrNull == 1,
                                ) { "Unsupported settings SDK protocol" }
                                ready.value = true
                                buildJsonObject {
                                    put("ok", true)
                                    put("value", buildJsonObject { put("version", 1) })
                                }
                            } else {
                                check(ready.value) { "Initialize the settings SDK before calling the Host" }
                                require(
                                    request.operation in setOf("storage.read", "storage.write", "http.request"),
                                ) { "Settings operation is unavailable" }
                                plugin.session.invoke(request.operation) {
                                    ensureActive()
                                    val response =
                                        gateway.value.call(
                                            request.operation,
                                            request.arguments.toString(),
                                            context,
                                            30_000,
                                            apiVersion = 2,
                                            storage = storage,
                                        )
                                    ensureActive()
                                    PluginJsonV1.parseToJsonElement(response).jsonObject
                                }
                            }
                        ensureActive()
                        JsonObject(result + ("id" to JsonPrimitive(request.id))).toString()
                    }
                }
            }
        task.invokeOnCompletion { pending.update { it - 1 } }
        return task.await()
    }

    public fun close() {
        active.value = false
        scope.cancel()
    }

    private fun ensureActive() {
        if (!active.value || subsystem.running.value.plugins[plugin.installed.pluginId] !== plugin || !plugin.session.isActive) {
            throw PluginHostException("bridge.closed", "Settings page session is closed")
        }
    }

    private fun mimeType(path: String): String =
        when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> error("Unsupported settings resource")
        }
}

@Serializable
private data class SettingsBridgeRequest(
    val id: String,
    val operation: String,
    val arguments: JsonElement = JsonObject(emptyMap()),
)
